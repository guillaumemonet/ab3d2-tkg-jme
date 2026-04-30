package com.ab3d2.world;

import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Controle JME pour les portes animees.
 *
 * Basé sur la structure ZLiftable du jeu original (zone_liftable.h) :
 *   - La porte est une ZONE (doorZoneId) dont les murs montent/descendent ensemble
 *   - zl_Bottom / zl_Top : plage de hauteur editeur
 *   - zl_RaiseCondition : comment la porte s'ouvre (toucher joueur, toucher balle...)
 *   - zl_LowerCondition : comment la porte se referme (timeout, jamais...)
 *   - zl_OpenDuration : duree d'ouverture en ticks (0 = ne se referme pas)
 *
 * Tout les pans d'une meme porte sont enfants du meme Node et montent ensemble.
 *
 * Correspondance raiseCondition (DR_* dans defs.i) :
 *   0 = PLAYER_USE (bouton)   - on approche
 *   1 = PLAYER_TOUCH          - on touche
 *   2 = BULLET_TOUCH          - une balle touche
 *   3 = ALIEN_TOUCH           - un alien touche
 *   4 = TIMEOUT               - minuterie
 *   5 = NEVER                 - jamais
 *
 * Correspondance lowerCondition :
 *   0 = TIMEOUT (se referme apres openDuration)
 *   1 = NEVER   (reste ouverte une fois ouverte)
 */
public class DoorControl extends AbstractControl {

    /** Position du joueur — partagee, mise a jour par GameAppState chaque frame. */
    public static final Vector3f PLAYER_POS = new Vector3f();

    // Vitesses par defaut (en unites JME/seconde)
    private static final float DEFAULT_OPEN_SPEED  = 2.0f;
    private static final float DEFAULT_CLOSE_SPEED = 1.2f;
    // Distance de declenchement = max(TRIGGER_DIST_MIN, halfWidthDoor + TRIGGER_MARGIN)
    // Pour les grandes portes (zone 132 = 14 JME large), il faut plus de marge.
    private static final float TRIGGER_DIST_MIN = 3.0f;
    private static final float TRIGGER_MARGIN   = 1.5f;
    private static final float COLLISION_THRESHOLD = 0.5f;

    // Conditions (de defs.i)
    private static final int RAISE_PLAYER_USE   = 0;
    private static final int RAISE_PLAYER_TOUCH = 1;
    private static final int RAISE_BULLET       = 2;
    private static final int RAISE_ALIEN        = 3;
    private static final int RAISE_TIMEOUT      = 4;
    private static final int RAISE_NEVER        = 5;

    private static final int LOWER_TIMEOUT = 0;
    private static final int LOWER_NEVER   = 1;

    private float state = 0f;  // 0=ferme, 1=ouvert
    private float yTop, yBot;
    private float openSpeed  = DEFAULT_OPEN_SPEED;
    private float closeSpeed = DEFAULT_CLOSE_SPEED;
    private int   raiseCondition = RAISE_PLAYER_TOUCH;
    private int   lowerCondition = LOWER_TIMEOUT;
    private float openDuration   = 3.0f;   // secondes
    private float openTimer      = 0f;     // chrono depuis ouverture complete
    private float triggerDist    = TRIGGER_DIST_MIN; // calcule au setSpatial
    private float stateMaxCached  = 1f;     // calcule une seule fois au setSpatial

    private final WallCollision doorCollision;
    private DoorSound doorSound;          // initialise au premier update()
    private boolean soundInitialized = false;
    // Pour detecter les transitions d'etat (ferme->ouverture, ouvert->fermeture)
    private boolean wasOpening = false;
    private boolean wasClosing = false;

    public DoorControl(WallCollision doorCollision) {
        this.doorCollision = doorCollision;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        if (spatial == null) return;

        Float yt = spatial.getUserData("yTop");
        Float yb = spatial.getUserData("yBot");
        yTop = yt != null ? yt : 4f;
        yBot = yb != null ? yb : 0f;

        // Conditions depuis ZLiftable (stockees par LevelSceneBuilder)
        Integer rc = spatial.getUserData("raiseCondition");
        Integer lc = spatial.getUserData("lowerCondition");
        Integer dur = spatial.getUserData("openDuration");
        if (rc  != null) raiseCondition = rc;
        if (lc  != null) lowerCondition = lc;
        if (dur != null) {
            // Amiga tourne a 25 Hz logique (pas 50). zl_OpenDuration est en ticks 25Hz.
            openDuration = (dur == 0) ? Float.MAX_VALUE : dur / 25f;
            if (lowerCondition == LOWER_NEVER) openDuration = Float.MAX_VALUE;
        }

        // Conversion vitesse Amiga -> JME (calcul base sur la course COMPLETE)
        Integer zlOpenSpd  = spatial.getUserData("openSpeed");
        Integer zlCloseSpd = spatial.getUserData("closeSpeed");
        Integer zlBot      = spatial.getUserData("zlBottom");
        Integer zlTop      = spatial.getUserData("zlTop");
        if (zlOpenSpd != null && zlBot != null && zlTop != null) {
            float hEditor = Math.abs(zlTop - zlBot);
            if (hEditor > 0.1f) {
                if (zlOpenSpd > 0) {
                    openSpeed = (zlOpenSpd * 25f) / hEditor;
                }
                if (zlCloseSpd != null && zlCloseSpd > 0) {
                    closeSpeed = (zlCloseSpd * 25f) / hEditor;
                } else if (zlCloseSpd != null && zlCloseSpd == 0) {
                    // zl_ClosingSpeed = 0 : la porte ne se referme jamais
                    closeSpeed = 0f;
                    lowerCondition = LOWER_NEVER;
                }
            }
        }

        // Session 98 : calcul du triggerDist proportionnel a la taille de la porte.
        // Pour les grandes portes (zone 132 = 14 JME large), TRIGGER_DIST=2.5 est
        // trop court : le joueur bute contre la porte avant qu'elle ne se declenche.
        // On prend la moitie de la largeur de la porte + une marge.
        float maxHalfWidth = 0f;
        if (spatial instanceof Node n) {
            for (Spatial child : n.getChildren()) {
                if (!(child instanceof Geometry geo)) continue;
                Float x0 = geo.getUserData("x0"), z0 = geo.getUserData("z0");
                Float x1 = geo.getUserData("x1"), z1 = geo.getUserData("z1");
                if (x0 == null) continue;
                float dx = x1 - x0, dz = z1 - z0;
                float halfLen = (float) Math.sqrt(dx*dx + dz*dz) / 2f;
                if (halfLen > maxHalfWidth) maxHalfWidth = halfLen;
            }
        }
        triggerDist = Math.max(TRIGGER_DIST_MIN, maxHalfWidth + TRIGGER_MARGIN);

        // Session 98 : calcul du stateMax effectif une seule fois ici
        // (la porte rouge zone 132 a animDist=22 JME mais panelHeight=6 JME)
        stateMaxCached = computeMaxState();
        // Note : DoorSound sera initialise au premier controlUpdate() quand
        // l'AssetManager est disponible via spatial.getUserData / SceneGraph
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (!(spatial instanceof Node doorNode)) return;
        if (raiseCondition == RAISE_NEVER) return;

        // Init son au premier frame (AssetManager accessible via le SceneGraph)
        if (!soundInitialized) {
            initSound(doorNode);
            soundInitialized = true;
        }

        float minDist = minDistToPlayer(doorNode);
        float prev    = state;
        boolean opening = false, closing = false;
        float stateMax  = stateMaxCached;

        if (state < stateMax * 0.999f) {
            boolean shouldOpen = switch (raiseCondition) {
                case RAISE_PLAYER_USE, RAISE_PLAYER_TOUCH -> minDist < triggerDist;
                default -> minDist < triggerDist;
            };
            if (shouldOpen) {
                state = Math.min(stateMax, state + openSpeed * tpf);
                opening = true;
                if (state >= stateMax * 0.999f) openTimer = 0f;
            } else if (state > 0f && lowerCondition == LOWER_TIMEOUT) {
                state = Math.max(0f, state - closeSpeed * tpf);
                closing = true;
            }
        } else {
            openTimer += tpf;
            if (lowerCondition == LOWER_TIMEOUT && openTimer >= openDuration) {
                if (minDist >= triggerDist) {
                    state = Math.max(0f, state - closeSpeed * tpf);
                    closing = true;
                } else {
                    openTimer = 0f;
                }
            }
        }

        // Declenchement des sons sur les transitions d'etat
        if (opening && !wasOpening && prev <= 0.01f && doorSound != null) {
            doorSound.playOpen();
        }
        if (closing && !wasClosing && prev >= stateMax * 0.999f && doorSound != null) {
            doorSound.playClose();
        }
        wasOpening = opening;
        wasClosing = closing;

        // SESSION 103 : seuil abaisse pour eviter l'effet "par a-coups" sur
        // les portes lentes. Exemple zone 132 (rouge) : openSpeed=4 Amiga,
        // hEditor=704, donc state grandit de 0.142/sec, soit ~0.0024 par frame
        // a 60 FPS - juste au-dessus de l'ancien seuil 0.002. Avec la moindre
        // variance de tpf, on saute des updates et l'animation parait saccadee.
        // 0.0001 garantit un update a chaque frame meme pour les portes les
        // plus lentes. Le cout du updateMeshes est negligeable (4 vertex/4 UV).
        if (Math.abs(state - prev) > 0.0001f) updateMeshes(doorNode);

        // Cull la porte quand elle est totalement ouverte (panneaux disparus dans le plafond)
        doorNode.setCullHint(state >= stateMax * 0.999f ? Spatial.CullHint.Always : Spatial.CullHint.Dynamic);
        if (state < COLLISION_THRESHOLD * stateMax) addCollisionSegments(doorNode);
    }

    /**
     * Session 100 : avec l'animation par-segment (riseAmount = panelHeight * state),
     * chaque segment atteint son repli complet a state=1, donc on n'a plus besoin
     * de plafonner. Tous les segs d'un meme groupe de porte se replient ensemble
     * a state=1, ce qui donne un effet de "bloc de porte" coherent (vs l'effet
     * rideau quand on melangeait des segs de hauteurs differentes).
     */
    private float computeMaxState() {
        return 1f;
    }

    /** Initialise DoorSound avec l'AssetManager depuis le RootNode. */
    private void initSound(Node doorNode) {
        // Remonter jusqu'a trouver un noeud racine avec AssetManager
        // En pratique GameAppState attache les sons via une reference statique
        try {
            if (GameSoundRef.assetManager == null) return;
            Integer openSfx  = spatial.getUserData("openSfx");
            Integer closeSfx = spatial.getUserData("closeSfx");
            // Calculer le centre de la porte depuis le premier segment
            Vector3f center = getDoorCenter(doorNode);
            doorSound = new DoorSound(
                GameSoundRef.assetManager, doorNode, center,
                openSfx  != null ? openSfx  : 0,
                closeSfx != null ? closeSfx : 0
            );
        } catch (Exception e) {
            // Sons non critiques — on continue sans son
        }
    }

    private Vector3f getDoorCenter(Node doorNode) {
        float cx = 0, cy = 0, cz = 0; int n = 0;
        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry)) continue;
            Float x0 = child.getUserData("x0"), z0 = child.getUserData("z0");
            Float x1 = child.getUserData("x1"), z1 = child.getUserData("z1");
            if (x0 == null) continue;
            cx += (x0 + x1) / 2f; cz += (z0 + z1) / 2f;
            cy += (yTop + yBot) / 2f; n++;
        }
        return n > 0 ? new Vector3f(cx/n, cy/n, cz/n) : new Vector3f(0, yTop/2f, 0);
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {}

    // ── Mise a jour des meshes ────────────────────────────────────────────────

    private void updateMeshes(Node doorNode) {
        // Session 98 : approche ASM-fidele.
        //
        // Dans newanims.s::DoorRoutine, l'animation modifie ZoneT_Roof_l de la zone-porte
        // et patche le `topWallH`/`botWallH` des ZDoorWalls voisins. Concretement :
        //   - Quand fermee (state=0) : panneau plein du sol au plafond du couloir
        //   - Quand ouverte (state=1) : panneau de hauteur 0 (s'est replie dans le plafond)
        //
        // Donc seul le BAS du panneau monte vers le haut. Le SOMMET reste fixe au
        // plafond du couloir. C'est l'illusion d'une porte qui rentre dans le plafond.
        //
        // La texture doit rester immobile dans le monde -> on ajuste le V du bas pour
        // que ce soit toujours la meme portion de texture qui soit visible a la meme
        // hauteur monde.

        // SESSION 104 : pre-pass pour calculer le panelHeight COMMUN (max parmi
        // tous les segs de la porte). Tous les segs utilisent ce max comme
        // distance de reference dans le calcul de riseAmount -> meme vitesse JME
        // et meme Y final pour tous les pans, même si leurs hauteurs propres
        // diffèrent (cas porte entre 2 couloirs de floorH/roofH distincts).
        float maxPanelHeight = 0f;
        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry geo)) continue;
            Float yBotSeg = geo.getUserData("yBotSeg");
            Float yTopSeg = geo.getUserData("yTopSeg");
            if (yBotSeg == null || yTopSeg == null) continue;
            float h = yTopSeg - yBotSeg;
            if (h > maxPanelHeight) maxPanelHeight = h;
        }

        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry geo)) continue;

            // SESSION 105 : branchement par faceType pour gerer les caps du cube.
            //   "top"    : pas d'animation, fixe au plafond -> skip
            //   "bottom" : translate Y pour suivre curYBot -> setLocalTranslation
            //   "front"/null : face verticale, animation des vertex (logique existante)
            String faceType = geo.getUserData("faceType");
            if ("top".equals(faceType)) continue;
            if ("bottom".equals(faceType)) {
                Float yBotB = geo.getUserData("yBotSeg");
                Float yTopB = geo.getUserData("yTopSeg");
                if (yBotB == null || yTopB == null) continue;
                float pH = yTopB - yBotB;
                if (pH < 0.001f) continue;
                float rA = maxPanelHeight * state;
                float eR = Math.min(rA, pH);
                geo.setLocalTranslation(0f, eR, 0f);
                continue;
            }

            Float x0 = geo.getUserData("x0"), z0 = geo.getUserData("z0");
            Float x1 = geo.getUserData("x1"), z1 = geo.getUserData("z1");
            if (x0 == null) continue;

            Float yBotSeg  = geo.getUserData("yBotSeg");
            Float yTopSeg  = geo.getUserData("yTopSeg");
            Float animDist = geo.getUserData("animDist");
            float segBot   = (yBotSeg != null)  ? yBotSeg  : yBot;
            float segTop   = (yTopSeg != null)  ? yTopSeg  : yTop;
            float dist     = (animDist != null) ? animDist : (segTop - segBot);
            if (dist < 0.001f) continue;

            // SESSION 104 : evolution du modele d'animation.
            //
            // Sess 98 : riseAmount = min(dist*state, panelHeight). animDist commun
            //   mais panelHeight variable -> certains segs collapsaient avant d'autres
            //   pendant la transition (effet "rideau qui se froisse").
            //
            // Sess 100 : riseAmount = panelHeight * state (par-seg). Mais les segs
            //   de hauteurs differentes montaient a des VITESSES differentes en JME,
            //   et atteignaient des Y finals differents.
            //
            // Sess 104 (maintenant) : riseAmount = maxPanelHeight * state (commun).
            //   Tous les segs montent a la meme vitesse JME et visent le meme Y final.
            //   effectiveRise est clampe a la panelHeight propre pour ne pas inverser
            //   le quad : un seg de hauteur < maxPanelHeight collapse a son segTop
            //   plus tot que les segs longs, mais visuellement c'est coherent (le
            //   pan disparait dans le plafond).
            //
            // La duree d'ouverture (state 0 -> 1) reste fixee par openSpeed. Le seg
            // le plus haut atteint son sommet exactement a state=1.
            float panelHeight = segTop - segBot;
            float riseAmount  = maxPanelHeight * state;
            float effectiveRise = Math.min(riseAmount, panelHeight);
            float curYBot    = segBot + effectiveRise;
            float curYTop    = segTop;  // SOMMET FIXE

            FloatBuffer pb = geo.getMesh().getFloatBuffer(Type.Position);
            pb.rewind();
            pb.put(x0); pb.put(curYBot); pb.put(z0);
            pb.put(x1); pb.put(curYBot); pb.put(z1);
            pb.put(x1); pb.put(curYTop); pb.put(z1);
            pb.put(x0); pb.put(curYTop); pb.put(z0);
            pb.rewind();
            geo.getMesh().setBuffer(Type.Position, 3, pb);
            geo.getMesh().getBuffer(Type.Position).setUpdateNeeded();

            // Animation UV :
            // Le sommet du quad montre TOUJOURS la portion haute de texture (uvBase[5], uvBase[7]).
            // Le bas du quad, qui est maintenant a curYBot (au lieu de segBot), doit montrer
            // la portion de texture correspondant a cette nouvelle hauteur monde.
            //
            // riseAmount JME = riseAmount * SCALE pixels Amiga
            // -> shift en V = riseAmount_pixels / tileH
            //
            // Le sommet (TL/TR = uvBase[5..7]) garde son V (vBaseTop).
            // Le bas (BL/BR = uvBase[1..3]) prend V = vBaseBot + vShift.
            // (le bas "glisse" vers une portion plus haute dans la texture).
            Float tileH = geo.getUserData("tileH");
            String uvCsv = geo.getUserData("uvBase");
            float[] uvBase = (uvCsv != null)
                ? com.ab3d2.tools.LevelSceneBuilder.csvToFloatArray(uvCsv)
                : null;
            if (uvBase != null && uvBase.length == 8 && tileH != null && tileH > 0) {
                // SESSION 107 : la TEXTURE accompagne le quad qui monte (= la
                // porte monte visuellement, sa partie haute disparait dans le
                // plafond). C'est l'inverse de la session 104.
                //
                // Sess 104 : BL.v += vShift -> la texture reste FIXE dans le
                //   monde, et la partie BASSE (V=0..vShift) sort du quad par
                //   le bas. Effet visuel = la texture s'efface a partir du bas.
                //
                // Sess 107 : TL.v -= vShift -> la texture SUIT le quad qui
                //   monte. BL.v reste a 0 (le bas du quad voit toujours la
                //   base du chevron). TL.v decroit -> la partie HAUTE de la
                //   texture (V=vM-vShift..vM) sort par le haut du quad =
                //   "glisse dans le mur du haut".
                //
                // Verification : a state=1, vShift = panelHeight*32/tileH = vM,
                // donc TL.v = 0 et le quad collapse en V (texture invisible).
                // CullHint.Always (state>=0.999) cache le tout.
                float vShift = (effectiveRise * 32f) / tileH;
                FloatBuffer ub = geo.getMesh().getFloatBuffer(Type.TexCoord);
                ub.rewind();
                // BL et BR : V inchange (= 0, on voit toujours la base du chevron)
                ub.put(uvBase[0]); ub.put(uvBase[1]);
                ub.put(uvBase[2]); ub.put(uvBase[3]);
                // TL et TR : V -= vShift (la partie haute de la texture sort par le haut du quad)
                ub.put(uvBase[4]); ub.put(uvBase[5] - vShift);
                ub.put(uvBase[6]); ub.put(uvBase[7] - vShift);
                ub.rewind();
                geo.getMesh().setBuffer(Type.TexCoord, 2, ub);
                geo.getMesh().getBuffer(Type.TexCoord).setUpdateNeeded();
            }
            geo.getMesh().updateBound();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private float minDistToPlayer(Node doorNode) {
        float minDist = Float.MAX_VALUE;
        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry seg)) continue;
            Float x0 = seg.getUserData("x0"), z0 = seg.getUserData("z0");
            Float x1 = seg.getUserData("x1"), z1 = seg.getUserData("z1");
            if (x0 == null) continue;
            float d = distSeg(PLAYER_POS.x, PLAYER_POS.z, x0, z0, x1, z1);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    private void addCollisionSegments(Node doorNode) {
        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry seg)) continue;
            Float x0 = seg.getUserData("x0"), z0 = seg.getUserData("z0");
            Float x1 = seg.getUserData("x1"), z1 = seg.getUserData("z1");
            if (x0 != null) doorCollision.addSegment(x0, z0, x1, z1);
        }
    }

    private static float distSeg(float px, float pz, float ax, float az, float bx, float bz) {
        float wx = bx - ax, wz = bz - az, l2 = wx * wx + wz * wz;
        if (l2 < 1e-6f) {
            float ex = px - ax, ez = pz - az;
            return (float) Math.sqrt(ex * ex + ez * ez);
        }
        float t  = Math.max(0f, Math.min(1f, ((px - ax) * wx + (pz - az) * wz) / l2));
        float cx = ax + t * wx, cz = az + t * wz;
        float ex = px - cx, ez = pz - cz;
        return (float) Math.sqrt(ex * ex + ez * ez);
    }

    public float getState() { return state; }
}
