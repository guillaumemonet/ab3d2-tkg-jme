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
    private static final float TRIGGER_DIST        = 2.5f;
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
            openDuration = (dur == 0) ? Float.MAX_VALUE : dur / 50f;
            if (lowerCondition == LOWER_NEVER) openDuration = Float.MAX_VALUE;
        }
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

        if (state < 0.999f) {
            boolean shouldOpen = switch (raiseCondition) {
                case RAISE_PLAYER_USE, RAISE_PLAYER_TOUCH -> minDist < TRIGGER_DIST;
                default -> minDist < TRIGGER_DIST;
            };
            if (shouldOpen) {
                state = Math.min(1f, state + openSpeed * tpf);
                opening = true;
                if (state >= 0.999f) openTimer = 0f;
            } else if (state > 0f && lowerCondition == LOWER_TIMEOUT) {
                state = Math.max(0f, state - closeSpeed * tpf);
                closing = true;
            }
        } else {
            openTimer += tpf;
            if (lowerCondition == LOWER_TIMEOUT && openTimer >= openDuration) {
                if (minDist >= TRIGGER_DIST) {
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
        if (closing && !wasClosing && prev >= 0.999f && doorSound != null) {
            doorSound.playClose();
        }
        wasOpening = opening;
        wasClosing = closing;

        if (Math.abs(state - prev) > 0.002f) updateMeshes(doorNode);

        doorNode.setCullHint(state >= 0.999f ? Spatial.CullHint.Always : Spatial.CullHint.Dynamic);
        if (state < COLLISION_THRESHOLD) addCollisionSegments(doorNode);
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
        float fullH = yTop - yBot;
        if (fullH < 0.001f) return;

        // Le panneau entier translate vers le haut :
        // state=0 : porte en position fermee [yBot, yTop]
        // state=1 : porte au-dessus du plafond [yTop, yTop+fullH] = invisible
        float offset  = fullH * state;
        float curYBot = yBot + offset;
        float curYTop = yTop + offset;

        for (Spatial child : doorNode.getChildren()) {
            if (!(child instanceof Geometry geo)) continue;
            Float x0 = geo.getUserData("x0"), z0 = geo.getUserData("z0");
            Float x1 = geo.getUserData("x1"), z1 = geo.getUserData("z1");
            if (x0 == null) continue;

            FloatBuffer pb = geo.getMesh().getFloatBuffer(Type.Position);
            pb.rewind();
            pb.put(x0); pb.put(curYBot); pb.put(z0);
            pb.put(x1); pb.put(curYBot); pb.put(z1);
            pb.put(x1); pb.put(curYTop); pb.put(z1);
            pb.put(x0); pb.put(curYTop); pb.put(z0);
            pb.rewind();
            geo.getMesh().setBuffer(Type.Position, 3, pb);
            // Forcer le GPU a re-uploader le buffer (obligatoire pour setDynamic)
            geo.getMesh().getBuffer(Type.Position).setUpdateNeeded();
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
