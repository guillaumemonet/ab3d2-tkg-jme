package com.ab3d2.world;

import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;

/**
 * Controle JME pour les LIFTS animes.
 *
 * <p>Reference ASM : {@code LiftRoutine} dans {@code newanims.s}.</p>
 *
 * <p>Modele d'apres l'ASM :</p>
 * <ul>
 *   <li>Le lift a une <b>position courante</b> {@code currentY} qui evolue dans
 *       {@code [yBot..yTop]}.</li>
 *   <li>Une <b>vitesse</b> {@code speed} en unites/sec :
 *     <ul>
 *       <li>{@code speed < 0} : le lift monte (yBot vers yTop)</li>
 *       <li>{@code speed > 0} : le lift descend (yTop vers yBot)</li>
 *       <li>{@code speed == 0} : immobile</li>
 *     </ul>
 *   </li>
 *   <li>Quand le lift atteint un extreme :
 *     <ul>
 *       <li>Au top, on regarde {@code lowerCondition} pour decider de descendre</li>
 *       <li>Au bottom, on regarde {@code raiseCondition} pour decider de monter</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Pour {@code raiseCondition=PLAYER_TOUCH} et {@code lowerCondition=PLAYER_TOUCH} :
 * le lift TOGGLE selon la position du joueur. Si en bas et joueur dessus -> monte.
 * Si en haut et joueur n'est plus dessus (ou autre) -> descend.</p>
 *
 * @since session 92 fix 4 (lifts implementes), refonte session 99
 */
public class LiftControl extends AbstractControl {

    /** Position du joueur — partagee, mise a jour par GameAppState chaque frame. */
    public static final Vector3f PLAYER_POS = new Vector3f();

    // raiseCondition (zl_RaiseCondition - DR_*)
    private static final int RAISE_PLAYER_USE   = 0;
    private static final int RAISE_PLAYER_TOUCH = 1;
    private static final int RAISE_BULLET       = 2;
    private static final int RAISE_ALIEN        = 3;
    private static final int RAISE_TIMEOUT      = 4;
    private static final int RAISE_NEVER        = 5;

    // lowerCondition (zl_LowerCondition - DL_*)
    private static final int LOWER_PLAYER_USE   = 0;
    private static final int LOWER_PLAYER_TOUCH = 1;
    private static final int LOWER_BULLET       = 2;
    private static final int LOWER_ALIEN        = 3;
    private static final int LOWER_TIMEOUT      = 4;
    private static final int LOWER_NEVER        = 5;

    /** Distance horizontale (JME) a laquelle le lift se declenche meme sans etre dessus. */
    private static final float TRIGGER_DIST = 2.0f;

    // ─── Configuration (lue depuis UserData via setSpatial) ────────────────
    private float yTop, yBot;            // hauteurs JME extremes
    private float openSpeedJ;            // unites JME / sec quand monte
    private float closeSpeedJ;           // unites JME / sec quand descend
    private int   raiseCondition = RAISE_PLAYER_TOUCH;
    private int   lowerCondition = LOWER_TIMEOUT;
    private float openDuration   = 3.0f; // sec a rester en haut avant de redescendre

    // ─── Etat dynamique ────────────────────────────────────────────────────
    /** Position Y JME courante du sol du lift. */
    private float currentY;

    /** Vitesse JME en unites/sec. <0 = monte, >0 = descend, 0 = immobile. */
    private float speed = 0f;

    /** Temps ecoule depuis l'arrivee au top (timer pour LOWER_TIMEOUT). */
    private float topTimer = 0f;

    /** True si le joueur se tient sur ce lift. Mis a jour par {@link GameAppState}. */
    private boolean playerStoodOnLift = false;
    public  void setPlayerStoodOnLift(boolean stood) { this.playerStoodOnLift = stood; }

    public LiftControl() {}

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        if (spatial == null) return;

        Float yt = spatial.getUserData("yTop");
        Float yb = spatial.getUserData("yBot");
        yTop = yt != null ? yt : 4f;
        yBot = yb != null ? yb : 0f;

        // Position de depart : en bas (au repos).
        // Le mesh visuel a ete genere a yBot, et currentY tracke la position absolue.
        currentY = yBot;

        Integer rc  = spatial.getUserData("raiseCondition");
        Integer lc  = spatial.getUserData("lowerCondition");
        Integer dur = spatial.getUserData("openDuration");
        if (rc != null) raiseCondition = rc;
        if (lc != null) lowerCondition = lc;
        if (dur != null) {
            // Amiga 25Hz -> /25f
            openDuration = (dur == 0) ? Float.MAX_VALUE : dur / 25f;
        }

        // Conversion vitesse Amiga -> JME.
        // ASM : anim_FloorMoveSpeed = zl_OpeningSpeed (unites editeur / frame@25Hz).
        // Convertir : unites JME / sec = (zl_OpeningSpeed / SCALE) * 25
        // (SCALE = 32 unites editeur par unite JME)
        Integer zlOpenSpd  = spatial.getUserData("openSpeed");
        Integer zlCloseSpd = spatial.getUserData("closeSpeed");
        // Vitesse par defaut raisonnable : 4 unites JME / sec
        openSpeedJ  = 4f;
        closeSpeedJ = 4f;
        if (zlOpenSpd  != null && zlOpenSpd  > 0) openSpeedJ  = (zlOpenSpd  / 32f) * 25f;
        if (zlCloseSpd != null && zlCloseSpd > 0) closeSpeedJ = (zlCloseSpd / 32f) * 25f;
        if (zlCloseSpd != null && zlCloseSpd == 0) {
            closeSpeedJ = 0f;  // ne descend jamais
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (!(spatial instanceof Node liftNode)) return;
        if (raiseCondition == RAISE_NEVER) return;

        boolean atBottom = currentY <= yBot + 0.01f;
        boolean atTop    = currentY >= yTop - 0.01f;

        // Detection joueur proche (player touch + player use)
        float minDist = minDistToPlayer(liftNode);
        boolean playerNear = (minDist < TRIGGER_DIST) || playerStoodOnLift;

        // ─── Decisions de mouvement ───────────────────────────────────────
        // ASM : tstliftraise quand atBottom, tstliftlower quand atTop.
        // En cours de mouvement, on continue jusqu'a l'extremite suivante.
        if (atBottom && speed >= 0f) {
            // En bas, immobile. Doit-on monter ?
            if (shouldRaise(playerNear)) {
                // En JME, monter = Y AUGMENTE (Y vers le haut). Donc speed > 0.
                speed = openSpeedJ;
            }
        } else if (atTop && speed <= 0f) {
            // En haut, immobile. Doit-on descendre ?
            topTimer += tpf;
            if (shouldLower(playerNear)) {
                speed = -closeSpeedJ; // descend = Y diminue en JME
            }
        }

        // ─── Application du mouvement ─────────────────────────────────────
        if (speed != 0f) {
            currentY += speed * tpf;

            // Clamp aux extremes et arret
            if (currentY >= yTop) {
                currentY = yTop;
                speed = 0f;
                topTimer = 0f;  // vient juste d'arriver en haut
            } else if (currentY <= yBot) {
                currentY = yBot;
                speed = 0f;
                topTimer = 0f;
            }

            updateFloorMesh(liftNode);
        }
    }

    /**
     * Doit-on commencer a monter ? (lift en bas, conditions de raise).
     */
    private boolean shouldRaise(boolean playerNear) {
        return switch (raiseCondition) {
            // PLAYER_USE/TOUCH : monter quand joueur dessus ou proche
            case RAISE_PLAYER_USE, RAISE_PLAYER_TOUCH -> playerNear;
            // TIMEOUT : monter apres un delai (rare, mais supporte)
            case RAISE_TIMEOUT -> topTimer >= openDuration;
            default -> playerNear;
        };
    }

    /**
     * Doit-on commencer a descendre ? (lift en haut, conditions de lower).
     */
    private boolean shouldLower(boolean playerNear) {
        if (closeSpeedJ <= 0f) return false;  // ne descend jamais
        return switch (lowerCondition) {
            // PLAYER_TOUCH : descendre quand joueur n'est PLUS dessus
            // (usage typique : ascenseur retour automatique)
            case LOWER_PLAYER_TOUCH -> !playerNear;
            // TIMEOUT : descendre apres delai si pas de joueur
            case LOWER_TIMEOUT -> topTimer >= openDuration && !playerNear;
            // NEVER : reste en haut definitivement
            case LOWER_NEVER -> false;
            default -> false;
        };
    }

    @Override protected void controlRender(RenderManager rm, ViewPort vp) {}

    /**
     * Met a jour la position visuelle du sol du lift.
     *
     * Session 100 : on translate le NODE parent (liftNode), pas le seul
     * Geometry du sol. Ainsi tous les enfants (le sol ET les 4 cotes
     * lift_side_*) montent ensemble, ce qui forme une cabine d'ascenseur
     * coherente.
     *
     * Avant : geo.setLocalTranslation(...) sur le seul "lift_floor_*". Mais
     * setLocalTranslation sur un enfant ne propage PAS aux freres. Resultat :
     * le sol montait tout seul, les cotes restaient en bas -> effet "sol
     * flottant" pas du tout "bloc de cabine".
     */
    private void updateFloorMesh(Node liftNode) {
        float deltaY = currentY - yBot;
        liftNode.setLocalTranslation(0f, deltaY, 0f);
    }

    /** Hauteur Y JME a laquelle le sol du lift se trouve actuellement. */
    public float getCurrentFloorY() {
        return currentY;
    }

    /** @return true si le joueur (PLAYER_POS) est au-dessus du polygone XZ du lift. */
    public boolean isPlayerOver(Node liftNode) {
        String floorXZCsv = liftNode.getUserData("floorXZ");
        if (floorXZCsv == null) return false;
        float[] floorXZ = com.ab3d2.tools.LevelSceneBuilder.csvToFloatArray(floorXZCsv);
        if (floorXZ.length < 6) return false;
        return PolygonXZ.pointInPolygon(floorXZ, PLAYER_POS.x, PLAYER_POS.z);
    }

    /**
     * Distance horizontale du joueur au polygone du lift (vue de dessus).
     * Si joueur au-dessus -> 0.
     *
     * <p>Session 110 : delegue a {@link PolygonXZ#distanceToPolygon}.</p>
     */
    private float minDistToPlayer(Node liftNode) {
        String floorXZCsv = liftNode.getUserData("floorXZ");
        if (floorXZCsv == null) return Float.MAX_VALUE;
        float[] floorXZ = com.ab3d2.tools.LevelSceneBuilder.csvToFloatArray(floorXZCsv);
        return PolygonXZ.distanceToPolygon(floorXZ, PLAYER_POS.x, PLAYER_POS.z);
    }

    // SESSION 110 : pointInPolygonXZ et distSeg ont ete deplaces dans PolygonXZ.
    // Ils etaient dupliques avec ce qu'allait avoir besoin ZoneTracker.

    /** Compatibilite : le state normalise [0..1] ou 0=bas, 1=haut. */
    public float getState() {
        if (yTop == yBot) return 0f;
        return (currentY - yBot) / (yTop - yBot);
    }
}
