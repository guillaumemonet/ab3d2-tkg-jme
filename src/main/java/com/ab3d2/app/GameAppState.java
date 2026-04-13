package com.ab3d2.app;

import com.ab3d2.world.*;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.*;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.*;
import com.jme3.texture.Texture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Etat de jeu 3D.
 *
 * LECON APPRISE : ne JAMAIS appeler rm.getRenderer().invalidateState()
 * ni aucun appel GL direct dans AppState.render() — cela brise le pipeline
 * de rendu JME completement (rien ne s'affiche, meme le GuiNode).
 *
 * La gestion du GL state apres Renderer2D se fait uniquement dans cleanup()
 * des AppStates menu avec glBindFramebuffer(0) + glViewport plein ecran.
 *
 * Ordre d'initialisation :
 *   initialize() : charge .j3o, lumieres, camera, input, attach(bullet)
 *   update() frame 1 : bullet pas encore init -> camera libre
 *   update() frame 2 : bullet init -> setupPhysics() + placePlayer()
 */
public class GameAppState extends AbstractAppState implements ActionListener, AnalogListener {

    private static final Logger log = LoggerFactory.getLogger(GameAppState.class);

    private static final float MOVE_SPEED        = 8.0f;   // unites JME/sec (1 unite = 32 px Amiga)
    private static final float TURN_SPEED        = 3.0f;
    private static final float MOUSE_SENSITIVITY = 0.005f;
    private static final float EYE_HEIGHT        = 1.5f;

    private static final float PLAYER_RADIUS = 0.4f;
    private static final float PLAYER_HEIGHT = 1.0f;
    private static final float STEP_HEIGHT   = 0.35f;
    private static final float GRAVITY       = 30f;
    private static final float FALL_SPEED    = 30f;

    private static final ColorRGBA HEADLIGHT_COLOR = new ColorRGBA(1.2f, 1.1f, 1.0f, 1f);
    private static final float     HEADLIGHT_RANGE = 256f;

    private static final String
        A_FWD="g_fwd", A_BACK="g_back", A_LEFT="g_left", A_RIGHT="g_right",
        A_TURNL="g_tl", A_TURNR="g_tr",
        A_MX="g_mx", A_MXN="g_mxn", A_MY="g_my", A_MYN="g_myn",
        A_JUMP="g_jump", A_EXIT="g_exit";

    private final Path assetsRoot, jmeAssets;
    private final int  levelIndex;
    private SimpleApplication sa;

    private Node             levelScene;
    private WallCollision    doorCollision;
    private BulletAppState   bullet;
    private CharacterControl player;
    private Node             playerNode;
    private boolean          physicsReady = false;

    private AmbientLight ambientLight;
    private PointLight   headLight;
    private float        yaw=0f, pitch=0f;
    private float        moveFwd=0f, moveRight=0f, keyTurnDelta=0f;

    public GameAppState(Path assetsRoot, Path jmeAssets, int levelIndex) {
        this.assetsRoot = assetsRoot;
        this.jmeAssets  = jmeAssets;
        this.levelIndex = levelIndex;
    }

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        sa = (SimpleApplication) app;
        String letter = String.valueOf((char)('A' + levelIndex));

        sa.getFlyByCamera().setEnabled(false);
        sa.getCamera().setFrustumPerspective(80f,
            (float)sa.getCamera().getWidth()/sa.getCamera().getHeight(), 0.05f, 2000f);

        // Charger le .j3o (convention JME : Scenes/ avec majuscule)
        String scenePath = "Scenes/scene_" + letter + ".j3o";
        try {
            levelScene = (Node) sa.getAssetManager().loadModel(scenePath);
            log.info("Scene chargee : {}", scenePath);
        } catch (Exception e) {
            log.error(".j3o absent — lancer ./gradlew buildScenes : {}", e.getMessage());
            sa.getStateManager().attach(new LevelSelectAppState(assetsRoot, jmeAssets));
            sa.getStateManager().detach(this);
            return;
        }

        sa.getRootNode().attachChild(levelScene);

        // Lumieres
        ambientLight = new AmbientLight(new ColorRGBA(0.55f, 0.52f, 0.58f, 1f));
        sa.getRootNode().addLight(ambientLight);

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.2f));
        sa.getRootNode().addLight(sun);

        headLight = new PointLight();
        headLight.setColor(HEADLIGHT_COLOR);
        headLight.setRadius(HEADLIGHT_RANGE);
        sa.getRootNode().addLight(headLight);

        // Fix materiaux : Lighting.j3md -> Unshaded avec texture preservee
        fixMaterials(levelScene);

        // Camera au spawn (avant physique, pour eviter frame noire)
        positionCameraAtSpawn();

        setupInput();
        sa.getInputManager().setCursorVisible(false);

        // Initialiser la reference audio statique pour DoorControl
        GameSoundRef.assetManager  = sa.getAssetManager();
        GameSoundRef.audioListener = sa.getListener();
        // Listener audio : suivra la camera dans update()

        // BulletAppState — physicsSpace disponible seulement au frame suivant
        bullet = new BulletAppState();
        sm.attach(bullet);

        log.info("GameAppState init OK — attente BulletAppState");
    }

    private void setupPhysics() {
        Node geoNode = (Node) levelScene.getChild("geometry");
        if (geoNode != null) {
            try {
                CollisionShape shape = CollisionShapeFactory.createMeshShape(geoNode);
                RigidBodyControl rbc = new RigidBodyControl(shape, 0f);
                geoNode.addControl(rbc);
                bullet.getPhysicsSpace().add(rbc);
                log.info("Collider statique OK ({} meshes)", geoNode.getQuantity());
            } catch (Exception e) {
                log.warn("Collider echec : {}", e.getMessage());
            }
        }

        CapsuleCollisionShape capsule = new CapsuleCollisionShape(PLAYER_RADIUS, PLAYER_HEIGHT);
        player = new CharacterControl(capsule, STEP_HEIGHT);
        player.setFallSpeed(FALL_SPEED);
        player.setGravity(GRAVITY);
        playerNode = new Node("player");
        playerNode.addControl(player);
        sa.getRootNode().attachChild(playerNode);
        bullet.getPhysicsSpace().add(player);

        // DoorControl
        doorCollision = new WallCollision();
        Node doorsNode = (Node) levelScene.getChild("doors");
        if (doorsNode != null) {
            for (Spatial child : doorsNode.getChildren())
                if (child instanceof Node dn)
                    dn.addControl(new DoorControl(doorCollision));
            log.info("DoorControl : {} portes", doorsNode.getQuantity());
        }

        placePlayer();
        physicsReady = true;
        log.info("Physique OK — joueur spawne");
    }

    private void positionCameraAtSpawn() {
        if (levelScene == null) return;
        Float px = levelScene.getUserData("p1X");
        Float pz = levelScene.getUserData("p1Z");
        float x = px != null ? px : 0f, z = pz != null ? pz : 0f;
        float y = getSpawnFloorY() + EYE_HEIGHT + 0.1f;  // 0.1 = pieds au sol
        sa.getCamera().setLocation(new Vector3f(x, y, z));
        sa.getCamera().lookAtDirection(
            new Vector3f(FastMath.cos(yaw), 0, FastMath.sin(yaw)), Vector3f.UNIT_Y);
        headLight.setPosition(new Vector3f(x, y, z));
        log.info("Camera spawn : ({}, {}, {})", x, y, z);
    }

    private float getSpawnFloorY() {
        Integer zoneId = levelScene != null ? levelScene.getUserData("p1Zone") : null;
        if (zoneId != null) {
            Node zns = (Node) levelScene.getChild("zones");
            if (zns != null) for (Spatial s : zns.getChildren()) {
                Integer id = s.getUserData("id");
                if (id != null && id.equals(zoneId)) {
                    Integer fh = s.getUserData("floorH");
                    if (fh != null) return -fh / 32f;
                    break;
                }
            }
        }
        return 0f;
    }

    /**
     * Convertit tous les materiaux Lighting.j3md en Unshaded + texture preservee.
     * Raison : Lighting.j3md sans UseMaterialColors=true utilise les vertex colors
     * non definies (noires) meme avec des lumieres. Unshaded est plus simple et fiable.
     */
    private void fixMaterials(Node root) {
        int[] n = {0};
        root.depthFirstTraversal(s -> {
            if (!(s instanceof Geometry g)) return;
            Material old = g.getMaterial();
            Material neu = new Material(sa.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
            if (old != null) {
                try {
                    var tp = old.getTextureParam("DiffuseMap");
                    if (tp != null && tp.getValue() != null)
                        neu.setTexture("ColorMap", (Texture) tp.getValue());
                    else
                        neu.setColor("Color", new ColorRGBA(0.6f, 0.6f, 0.6f, 1f));
                } catch (Exception e) {
                    neu.setColor("Color", ColorRGBA.Gray);
                }
            } else {
                neu.setColor("Color", ColorRGBA.Magenta);
            }
            neu.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            g.setMaterial(neu);
            n[0]++;
        });
        log.info("fixMaterials : {} geometries -> Unshaded", n[0]);
    }

    private void placePlayer() {
        if (player == null || levelScene == null) return;
        Float px = levelScene.getUserData("p1X");
        Float pz = levelScene.getUserData("p1Z");
        float x = px != null ? px : 0f, z = pz != null ? pz : 0f;
        // Placer le joueur 0.1 unite au-dessus du sol (juste les pieds sur le sol)
        float spawnY = getSpawnFloorY() + 0.1f;
        player.setPhysicsLocation(new Vector3f(x, spawnY, z));
        log.info("Spawn joueur : ({}, {}, {})", x, spawnY, z);
    }

    private boolean needsInvalidate = true; // invalider le cache GL JME au 1er frame

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        // Invalider le cache GL de JME UNE SEULE FOIS au premier update().
        // Cela force JME a rebinder tous ses shaders/VAOs/textures depuis zero.
        // NOTE : NE PAS faire ca dans render() car cela brise le pipeline JME.
        // Dans update() c'est safe car renderManager.render() n'a pas encore commence.
        if (needsInvalidate) {
            sa.getRenderManager().getRenderer().invalidateState();
            needsInvalidate = false;
        }

        if (!physicsReady) {
            if (bullet != null && bullet.isInitialized()) setupPhysics();
            updateCameraFree(tpf);
            headLight.setPosition(sa.getCamera().getLocation());
            moveFwd = moveRight = keyTurnDelta = 0f;
            return;
        }
        yaw   = (yaw + keyTurnDelta) % FastMath.TWO_PI;
        pitch = FastMath.clamp(pitch, -FastMath.HALF_PI*0.85f, FastMath.HALF_PI*0.85f);
        float cy=FastMath.cos(yaw), sy=FastMath.sin(yaw);
        // Normaliser la direction : onAnalog clavier donne value~=tpf, pas une direction unitaire.
        // Sans normalisation : setWalkDirection *= MOVE_SPEED * tpf * tpf = trop lent.
        float fwd   = moveFwd   > 0 ? 1f : moveFwd   < 0 ? -1f : 0f;
        float right = moveRight > 0 ? 1f : moveRight < 0 ? -1f : 0f;
        Vector3f walk = new Vector3f(cy*fwd - sy*right, 0, sy*fwd + cy*right);
        if (walk.lengthSquared() > 0) walk.normalizeLocal();
        player.setWalkDirection(walk.multLocal(MOVE_SPEED * tpf));
        if (doorCollision != null) { doorCollision.clear(); DoorControl.PLAYER_POS.set(player.getPhysicsLocation()); }
        moveFwd = moveRight = keyTurnDelta = 0f;
        updateCameraFromPlayer();
        headLight.setPosition(sa.getCamera().getLocation());
    }

    private void updateCameraFromPlayer() {
        if (player == null) return;
        float cy=FastMath.cos(yaw), sy=FastMath.sin(yaw), cp=FastMath.cos(pitch), sp=FastMath.sin(pitch);
        sa.getCamera().setLocation(player.getPhysicsLocation().add(0, EYE_HEIGHT, 0));
        sa.getCamera().lookAtDirection(new Vector3f(cy*cp, sp, sy*cp).normalizeLocal(), Vector3f.UNIT_Y);
    }

    private void updateCameraFree(float tpf) {
        yaw   = (yaw + keyTurnDelta) % FastMath.TWO_PI;
        pitch = FastMath.clamp(pitch, -FastMath.HALF_PI*0.85f, FastMath.HALF_PI*0.85f);
        float cy=FastMath.cos(yaw), sy=FastMath.sin(yaw), cp=FastMath.cos(pitch), sp=FastMath.sin(pitch);
        Vector3f pos  = sa.getCamera().getLocation().clone();
        Vector3f fwdV = new Vector3f(cy*cp, 0, sy*cp).normalizeLocal();
        Vector3f rightV = new Vector3f(sy, 0, -cy).normalizeLocal();
        float spd = MOVE_SPEED * tpf;
        // Normaliser direction (meme raison que update())
        float f = moveFwd   > 0 ? 1f : moveFwd   < 0 ? -1f : 0f;
        float r = moveRight > 0 ? 1f : moveRight < 0 ? -1f : 0f;
        pos.addLocal(fwdV.mult(f * spd)).addLocal(rightV.mult(r * spd));
        sa.getCamera().setLocation(pos);
        sa.getCamera().lookAtDirection(new Vector3f(cy*cp, sp, sy*cp).normalizeLocal(), Vector3f.UNIT_Y);
    }

    @Override public void render(RenderManager rm) { /* NE PAS modifier l'etat GL ici */ }

    @Override
    public void cleanup() {
        sa.getInputManager().setCursorVisible(true);
        var im = sa.getInputManager();
        for (var a : new String[]{A_FWD,A_BACK,A_LEFT,A_RIGHT,A_TURNL,A_TURNR,
                                   A_MX,A_MXN,A_MY,A_MYN,A_JUMP,A_EXIT})
            if (im.hasMapping(a)) im.deleteMapping(a);
        im.removeListener(this);
        im.removeListener((AnalogListener)this);
        if (levelScene  != null) { sa.getRootNode().detachChild(levelScene);   levelScene  = null; }
        if (playerNode  != null) { sa.getRootNode().detachChild(playerNode);   playerNode  = null; }
        if (ambientLight!= null) { sa.getRootNode().removeLight(ambientLight); ambientLight = null; }
        if (headLight   != null) { sa.getRootNode().removeLight(headLight);    headLight    = null; }
        if (bullet      != null) { sa.getStateManager().detach(bullet);        bullet       = null; }
        physicsReady = false;
        sa.getFlyByCamera().setEnabled(true);
    }

    private void setupInput() {
        var im = sa.getInputManager();
        im.addMapping(A_FWD,   new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        im.addMapping(A_BACK,  new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        im.addMapping(A_LEFT,  new KeyTrigger(KeyInput.KEY_A));
        im.addMapping(A_RIGHT, new KeyTrigger(KeyInput.KEY_D));
        im.addMapping(A_TURNL, new KeyTrigger(KeyInput.KEY_LEFT));
        im.addMapping(A_TURNR, new KeyTrigger(KeyInput.KEY_RIGHT));
        im.addMapping(A_JUMP,  new KeyTrigger(KeyInput.KEY_SPACE));
        im.addMapping(A_EXIT,  new KeyTrigger(KeyInput.KEY_ESCAPE));
        im.addMapping(A_MX,  new MouseAxisTrigger(MouseInput.AXIS_X, false));
        im.addMapping(A_MXN, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        im.addMapping(A_MY,  new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        im.addMapping(A_MYN, new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        im.addListener(this, A_EXIT, A_JUMP);
        im.addListener((AnalogListener)this,
            A_FWD,A_BACK,A_LEFT,A_RIGHT,A_TURNL,A_TURNR,A_MX,A_MXN,A_MY,A_MYN);
    }

    @Override
    public void onAction(String name, boolean p, float t) {
        if (!isEnabled()) return;
        switch (name) {
            case A_EXIT -> { if (p) {
                sa.getStateManager().attach(new LevelSelectAppState(assetsRoot, jmeAssets));
                sa.getStateManager().detach(this);
            }}
            case A_JUMP -> { if (p && player != null) player.jump(); }
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (!isEnabled()) return;
        switch (name) {
            case A_FWD   -> moveFwd      +=  value;
            case A_BACK  -> moveFwd      -=  value;
            case A_RIGHT -> moveRight    +=  value;
            case A_LEFT  -> moveRight    -=  value;
            case A_TURNR -> keyTurnDelta -= TURN_SPEED * value;
            case A_TURNL -> keyTurnDelta += TURN_SPEED * value;
            case A_MX    -> yaw   += MOUSE_SENSITIVITY * value * sa.getCamera().getWidth();
            case A_MXN   -> yaw   -= MOUSE_SENSITIVITY * value * sa.getCamera().getWidth();
            case A_MY    -> pitch += MOUSE_SENSITIVITY * value * sa.getCamera().getHeight();
            case A_MYN   -> pitch -= MOUSE_SENSITIVITY * value * sa.getCamera().getHeight();
        }
        pitch = FastMath.clamp(pitch, -FastMath.HALF_PI*0.85f, FastMath.HALF_PI*0.85f);
    }
}
