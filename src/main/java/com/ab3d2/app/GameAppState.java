package com.ab3d2.app;

import com.ab3d2.combat.BulletUpdateSystem;
import com.ab3d2.combat.CombatBootstrap;
import com.ab3d2.combat.GlfDatabase;
import com.ab3d2.combat.HitscanTracerSystem;
import com.ab3d2.combat.ImpactEffectSystem;
import com.ab3d2.combat.KeyBindings;
import com.ab3d2.combat.PhysicsBulletSystem;
import com.ab3d2.combat.PlayerAimProvider;
import com.ab3d2.combat.PlayerCombatState;
import com.ab3d2.combat.PlayerHealthState;
import com.ab3d2.combat.PlayerShootSystem;
import com.ab3d2.combat.PlayerShotPool;
import com.ab3d2.combat.WorldRaycaster;
import com.ab3d2.core.BobController;
import com.ab3d2.hud.HudAppState;
import com.ab3d2.hud.HudMode;
import com.ab3d2.hud.HudState;
import com.ab3d2.weapon.WeaponType;
import com.ab3d2.weapon.WeaponViewAppState;
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
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
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

    // Session 88 : calibration confort joueur apres retour de test.
    // Avant (sess 86) : MOVE_SPEED=20, EYE_HEIGHT=1.5, PLAYER_HEIGHT=1.0 -> trop rapide et trop grand.
    // Maintenant : vitesse raisonnable et hauteur cyborg-like plus adaptee a l'echelle des niveaux.
    private static final float MOVE_SPEED        = 10.0f;  // unites JME/sec (etait 20)
    private static final float TURN_SPEED        = 3.0f;
    private static final float MOUSE_SENSITIVITY = 0.005f;
    private static final float EYE_HEIGHT        = 1.1f;   // yeux au-dessus du physics pos (etait 1.5)

    private static final float PLAYER_RADIUS = 0.35f;      // un peu plus mince (etait 0.4)
    private static final float PLAYER_HEIGHT = 0.8f;       // hauteur capsule (etait 1.0)
    private static final float STEP_HEIGHT   = 0.35f;
    private static final float GRAVITY       = 30f;
    private static final float FALL_SPEED    = 30f;

    /** Champ de vision horizontal (degres). Session 88 : 80 -> 75 (plus serre, moins de distorsion). */
    private static final float FOV_DEGREES = 75f;

    // ─── Lighting (session 92 : moderne JME) ─────────────────────────────────
    // Session 92 : passage a un lighting JME complet.
    // - AmbientLight : lumiere ambiante de fond (partout, meme dans les recoins)
    // - DirectionalLight : "lune" qui donne un sens de direction a l'eclairage
    // - PointLight headlight : torche du joueur, suit la camera
    // - PointLights par zone : serialises dans le .j3o (LevelSceneBuilder, ~40 par niveau)
    private static final ColorRGBA AMBIENT_COLOR   = new ColorRGBA(0.35f, 0.33f, 0.40f, 1f);
    private static final ColorRGBA SUN_COLOR       = new ColorRGBA(0.55f, 0.52f, 0.50f, 1f);
    private static final Vector3f  SUN_DIRECTION   = new Vector3f(-0.4f, -1f, -0.3f).normalizeLocal();

    /** Couleur legerement chaude/blanche de la torche/headlight du joueur. */
    private static final ColorRGBA HEADLIGHT_COLOR = new ColorRGBA(1.0f, 0.92f, 0.80f, 1f);
    /** Portee de la torche : assez grande pour eclairer une grande salle. */
    private static final float     HEADLIGHT_RANGE = 30f;

    private static final String
        A_FWD="g_fwd", A_BACK="g_back", A_LEFT="g_left", A_RIGHT="g_right",
        A_TURNL="g_tl", A_TURNR="g_tr",
        A_MX="g_mx", A_MXN="g_mxn", A_MY="g_my", A_MYN="g_myn",
        A_JUMP="g_jump", A_EXIT="g_exit",
        A_DEBUG_HUD="g_debug_hud",   // F3 : toggle HUD debug overlay
        A_TOGGLE_WEAPON="g_tog_wpn"; // H : toggle visibilite arme
    // Note session 84 : A_NEXT_WEAPON (Y) / A_PREV_WEAPON (T) retires d'ici,
    // la selection d'arme est maintenant geree par PlayerShootSystem via
    // KeyBindings (touches 1..8 + next/prev). La synchro entre combat state
    // et WeaponViewAppState passe par un listener.

    private final Path assetsRoot, jmeAssets;
    private final int  levelIndex;
    private SimpleApplication sa;

    private Node             levelScene;
    private WallCollision    doorCollision;
    private BulletAppState   bullet;
    private CharacterControl player;
    private Node             playerNode;
    private boolean          physicsReady = false;

    private AmbientLight     ambientLight;
    private DirectionalLight sunLight;
    private PointLight       headLight;

    /** HUD 2D (bordure + AMMO + ENERGY + armes + messages). Desactive session 75. */
    private HudAppState  hud;

    /** Affichage de l'arme en main (vue FPS). */
    private WeaponViewAppState weaponView;

    /** Systeme de tir du joueur (session 84). */
    private PlayerShootSystem shootSystem;
    /** Etat combat du joueur (ammo, weapons, cooldown) (session 84). */
    private PlayerCombatState combatState;
    /** Pool des 20 slots de projectiles joueur (session 85). */
    private PlayerShotPool shotPool;
    /** Mise a jour des projectiles en vol (session 85). */
    private BulletUpdateSystem bulletUpdateSystem;
    /** Traceurs visuels des tirs hitscan (session 86). */
    private HitscanTracerSystem tracerSystem;
    /** Systeme physique pour grenade/mine/splutch (session 86, Bullet Physics). */
    private PhysicsBulletSystem physicsBulletSystem;
    /** Effets d'impact (flash au point de collision) (session 87). */
    private ImpactEffectSystem impactSystem;

    /** Etat sante / consommables / items du joueur (sante, fuel, shield, jetpack, keys).
     *  Pendant de PlayerCombatState pour les pickups consommables. Session 112. */
    private PlayerHealthState healthState;

    /** Systeme de detection et application du ramassage des items au sol.
     *  Reproduit la logique ASM Plr1_CheckObjectCollide / Plr1_CollectItem.
     *  Session 112. */
    private PickupSystem pickupSystem;

    /** State HUD partage (messages, energie, ammo). Cree mais HudAppState pas
     *  encore attache (cf. session 75). Pour l'instant les messages sont juste
     *  loggues, l'overlay HUD viendra plus tard. */
    private HudState hudState;

    /** Bob de marche (head bob) — phase, amplitude, applique a camera + arme.
     *  Reproduit la mecanique ASM hires.s 2842 (Plr1_Control). Session 109. */
    private final BobController bobController = new BobController();

    /** Tracker de la zone courante du joueur. Initialise apres chargement du
     *  levelScene. Utilise pour gerer teleports et fin de niveau. Session 110. */
    private ZoneTracker zoneTracker;

    /** Systeme d'IA des aliens : creation des AlienRuntimeState depuis les sprites
     *  du levelScene, dispatch de la machine a etats {@code AlienAI} chaque frame.
     *  Session 113 (phase 2.B). Initialise dans setupPhysics() une fois que
     *  WorldRaycaster + PhysicsSpace sont disponibles. */
    private AlienControlSystem alienControlSystem;

    /** Garde-fou : true des qu'on a declenche endlevel (pour eviter les rebonds). */
    private boolean endLevelTriggered = false;

    /** Cooldown de teleport (sec) : empeche de retomber dans une zone-telep
     *  immediatement apres un teleport (eviterait des boucles si la destination
     *  est elle-meme une zone-telep). Session 110. */
    private float teleportCooldown = 0f;

    /** Overlay debug zone (haut-gauche de l'ecran) - session 110.
     *  Affiche la zone courante + position monde Amiga + info tel/exit.
     *  Toggle via F3. Visible par defaut. */
    private BitmapText debugZoneText;
    private boolean    debugZoneVisible = true;

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
        sa.getCamera().setFrustumPerspective(FOV_DEGREES,
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

        // Session 110 : tracker de la zone courante du joueur (teleport + exit).
        // Doit etre initialise APRES attachChild(levelScene) car il lit les
        // userData des Nodes zone_NN.
        zoneTracker = new ZoneTracker(levelScene);

        // Session 110 : overlay debug zone (toggle F3).
        // Affiche la zone courante + position monde Amiga, pratique pour
        // se reperer pendant le test. La position est affichee en coords
        // ORIGINALES Amiga (pas JME) pour pouvoir mapper sur l'editeur de
        // niveaux et le JSON.
        BitmapFont guiFont = sa.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        debugZoneText = new BitmapText(guiFont, false);
        debugZoneText.setSize(guiFont.getCharSet().getRenderedSize() * 1.2f);
        debugZoneText.setColor(ColorRGBA.Yellow);
        debugZoneText.setText("Zone: ?");
        // Position : haut-gauche, marge 10px du bord. On utilise la hauteur
        // courante de la cam pour partir du haut, le BitmapText s'inscrit
        // vers le bas a partir de son LocalTranslation.
        debugZoneText.setLocalTranslation(
            10f,
            sa.getCamera().getHeight() - 10f,
            0f);
        sa.getGuiNode().attachChild(debugZoneText);

        // ─── Lumieres (session 92 : lighting moderne JME) ───────────────────
        // AmbientLight : eclairage de base partout (empeche les zones sombres
        // d'etre totalement noires)
        ambientLight = new AmbientLight(AMBIENT_COLOR);
        sa.getRootNode().addLight(ambientLight);

        // DirectionalLight : "lune" qui donne un sens directionnel. Intensite
        // moderee pour ne pas ecraser les PointLights des zones.
        sunLight = new DirectionalLight();
        sunLight.setDirection(SUN_DIRECTION);
        sunLight.setColor(SUN_COLOR);
        sa.getRootNode().addLight(sunLight);

        // PointLight headlight : torche portee par le joueur, suit la camera.
        // Essentiel pour eclairer devant soi dans les couloirs obscurs.
        headLight = new PointLight();
        headLight.setColor(HEADLIGHT_COLOR);
        headLight.setRadius(HEADLIGHT_RANGE);
        sa.getRootNode().addLight(headLight);

        // Session 92 : garder les materiaux Lighting.j3md au lieu de les
        // convertir en Unshaded. Les PointLights par zone serialises dans
        // le .j3o prennent effet.
        upgradeMaterialsForLighting(levelScene);

        // Session 92 : configurer le mode de rendu lighting pour gerer
        // les nombreuses PointLights par zone (~40 par niveau).
        // SinglePass : cumule jusqu'a N lumieres en 1 passe de shader (rapide).
        // Le batch_size doit etre >= au nombre max de lumieres affectant un
        // MEME objet dans le frustum (pas le nombre total dans la scene).
        // Pour un FPS avec murs separes par zone, 8 est suffisant car peu
        // d'objets sont dans le champ de plusieurs PointLights a la fois.
        sa.getRenderManager().setPreferredLightMode(
            com.jme3.material.TechniqueDef.LightMode.SinglePass);
        sa.getRenderManager().setSinglePassLightBatchSize(8);

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

        // HUD 2D : desactive temporairement (session 75, priorite arme+tir)
        // A reactiver plus tard pour afficher AMMO/ENERGY/messages/bordures
        //
        // HudState hudState = new HudState();
        // hudState.setEnergyCount(200);
        // hudState.setSelectedWeapon(0);
        // hudState.setWeaponAvailable(0, true);
        // hud = new HudAppState(hudState, HudMode.SMALL_SCREEN);
        // sm.attach(hud);
        // hudState.pushMessage("Level " + (char)('A' + levelIndex) + " loaded", HudState.Message.TAG_NARRATIVE);

        // Arme en main (vue FPS)
        // Session 89bis : arme par defaut = SHOTGUN (conforme au jeu original)
        weaponView = new WeaponViewAppState(WeaponType.SHOTGUN);
        // Session 109 : connecter le BobController pour que l'arme oscille
        // avec la marche (en plus du bob camera).
        weaponView.setBobController(bobController);
        sm.attach(weaponView);

        // ── Systeme de combat (session 84 : Phase 1.B, session 85 : Phase 1.C) ──────
        // Charge le GLF (TEST.LNK) et cree le state de combat initial.
        try {
            GlfDatabase glf = GlfDatabase.loadFromResource("TEST.LNK");
            combatState = CombatBootstrap.newLevelStart(glf);

            // Synchroniser la selection d'arme : quand PlayerShootSystem change
            // d'arme via les touches 1..8 / next / prev, le WeaponViewAppState
            // doit charger le bon modele en main.
            combatState.addGunSelectedListener(gunIdx -> {
                WeaponType wt = WeaponType.fromSlot(gunIdx);
                if (wt != null) {
                    weaponView.loadWeapon(wt);
                    log.info("WeaponView -> {} (slot {})", wt, gunIdx);
                }
            });
            // Forcer l'affichage initial conforme au combat state
            WeaponType initialWt = WeaponType.fromSlot(combatState.getGunSelected());
            if (initialWt != null) weaponView.loadWeapon(initialWt);

            // Pool de projectiles partage entre le PlayerShootSystem (qui spawn)
            // et le BulletUpdateSystem (qui les deplace chaque frame).
            shotPool = new PlayerShotPool();

            // Aim provider : expose la position/direction camera.
            // Implemente comme anonymous class (pas lambda) pour pouvoir
            // retourner la camera courante a chaque appel (pas snapshot).
            PlayerAimProvider aim = new PlayerAimProvider() {
                @Override
                public Vector3f getMuzzlePosition() {
                    return sa.getCamera().getLocation();
                }
                @Override
                public Vector3f getAimDirection() {
                    return sa.getCamera().getDirection();
                }
                @Override
                public float getYaw() {
                    return yaw;
                }
            };

            KeyBindings bindings = new KeyBindings();  // defaults
            shootSystem = new PlayerShootSystem(glf, combatState, bindings, shotPool, aim);
            sm.attach(shootSystem);

            // Systeme de mise a jour des bullets simples en vol (session 85)
            bulletUpdateSystem = new BulletUpdateSystem(shotPool, glf);
            sm.attach(bulletUpdateSystem);

            // Traceurs visuels pour bullets hitscan (Shotgun, Rifle, MindZap) (session 86)
            tracerSystem = new HitscanTracerSystem();
            sm.attach(tracerSystem);
            shootSystem.setTracerSystem(tracerSystem);

            // Effets d'impact (flash au point de collision) (session 87)
            impactSystem = new ImpactEffectSystem();
            sm.attach(impactSystem);
            shootSystem.setImpactSystem(impactSystem);
            bulletUpdateSystem.setImpactSystem(impactSystem);

            // Systeme physique Bullet pour grenade/mine/splutch (session 86)
            // Necessite que BulletAppState soit attache avant (fait plus haut).
            physicsBulletSystem = new PhysicsBulletSystem(shotPool, glf, bullet);
            sm.attach(physicsBulletSystem);
            shootSystem.setPhysicsSystem(physicsBulletSystem);

            log.info("Systemes de combat attaches (shoot + bullet update + tracer + physics)");

            // ── Session 112 : etat sante + systeme pickup ─────────────────
            // PlayerHealthState : pendant de PlayerCombatState pour les pickups
            // de sante/jetpack/shield/keys. Initialise avec sante max (200).
            healthState = new PlayerHealthState();
            // HudState : pour l'instant juste un container de messages, sans
            // rendu (HudAppState pas attache, cf. session 75). Quand on
            // reactivera le HUD, les messages pickup s'afficheront automatiquement.
            hudState = new HudState();
            hudState.setEnergyCount(healthState.getHealth());

            // PickupSystem : detecte la collision joueur<->item dans la meme zone
            // chaque frame, applique ammoGive/gunGive et retire le sprite.
            // Recupere itemsNode depuis le levelScene (cree par LevelSceneBuilder).
            Node itemsNode = (Node) levelScene.getChild("items");
            if (itemsNode != null) {
                pickupSystem = new PickupSystem(itemsNode, zoneTracker,
                    combatState, healthState, hudState, assetsRoot);
                log.info("PickupSystem attache : {} items dans la scene", itemsNode.getQuantity());
            } else {
                log.warn("PickupSystem : pas de Node 'items' dans le levelScene");
            }
        } catch (Exception e) {
            log.error("Impossible de charger TEST.LNK : {}", e.getMessage());
            log.error("Copier TEST.LNK dans src/main/resources/ et relancer");
        }

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

        // LiftControl (refonte session 99) : approche "pousser le joueur Y".
        //
        // Le sol statique de la zone-lift est maintenant present dans `geometry/`
        // (= collision Bullet globale OK au repos). Le sol dynamique dans `lifts/`
        // est purement visuel.
        //
        // Quand le lift bouge, LiftControl translate localement le Geometry visuel,
        // et GameAppState.update() calcule manuellement la position Y du joueur en
        // fonction de currentFloorYDelta quand le joueur est au-dessus du polygone
        // du lift.
        //
        // Pas de RigidBodyControl kinematic - approche plus simple et fidele a
        // l'ASM (LiftRoutine modifie ZoneT_Floor_l, et le joueur est ensuite
        // re-positionne par le code de gestion physique du jeu original).
        Node liftsNode = (Node) levelScene.getChild("lifts");
        if (liftsNode != null) {
            for (Spatial child : liftsNode.getChildren())
                if (child instanceof Node ln) {
                    ln.addControl(new LiftControl());
                }
            log.info("LiftControl : {} lifts (refonte sess 99 - sol statique + push Y)",
                liftsNode.getQuantity());
        }

        placePlayer();
        physicsReady = true;

        // Installer le raycaster combat maintenant que le PhysicsSpace existe.
        // Il est utilise par BulletUpdateSystem (collision bullets vs murs) et
        // HitscanTracerSystem (tracer qui s'arrete au mur).
        WorldRaycaster raycaster = null;
        if (bulletUpdateSystem != null && tracerSystem != null) {
            raycaster = new WorldRaycaster(bullet.getPhysicsSpace());
            bulletUpdateSystem.setRaycaster(raycaster);
            tracerSystem.setRaycaster(raycaster);
            log.info("WorldRaycaster installe (collisions bullets/hitscan actives)");
        }

        // Session 113 (phase 2.B) : systeme d'IA des aliens.
        // Doit etre cree APRES :
        //   - levelScene charge (pour acceder a items/)
        //   - zoneTracker cree (pour AiWorldAdapter)
        //   - WorldRaycaster cree (pour le LOS via raycast)
        //
        // L'AlienControlSystem :
        //   1. Charge definitions.json pour avoir les 20 AlienDef
        //   2. Parcourt levelScene/items pour trouver les sprites typeId=0
        //   3. Cree un AlienRuntimeState par instance + l'attache au Node JME
        //   4. Chaque frame : update IA + sync position sprite
        Node itemsNode = (Node) levelScene.getChild("items");
        if (itemsNode != null && raycaster != null) {
            AiWorldAdapter aiWorld = new AiWorldAdapter(
                sa.getCamera(), zoneTracker, raycaster, levelScene);
            // Session 113 phase 2.D : brancher PlayerHealthState pour que les
            // tirs alien infligent vraiment des degats au joueur.
            if (healthState != null) {
                aiWorld.setPlayerHealth(healthState);
                log.info("AiWorldAdapter : PlayerHealthState branche (degats alien actifs)");
            }
            java.nio.file.Path defsPath = jmeAssets.resolve("levels/definitions.json");
            alienControlSystem = new AlienControlSystem(defsPath, itemsNode, aiWorld);
            sa.getStateManager().attach(alienControlSystem);
            log.info("AlienControlSystem attache (defs={})", defsPath);

            // Session 113 (phase 2.C) : detecteur de hit alien pour les tirs.
            // Branche sur BulletUpdateSystem (projectiles) + HitscanTracerSystem
            // (Shotgun/Rifle/MindZap) pour que les tirs joueur infligent des
            // degats. Sans ce detecteur, les bullets traversent les aliens.
            com.ab3d2.combat.AlienHitDetector hitDetector =
                new com.ab3d2.combat.AlienHitDetector(alienControlSystem);
            if (bulletUpdateSystem != null) {
                bulletUpdateSystem.setAlienHitDetector(hitDetector);
            }
            if (tracerSystem != null) {
                tracerSystem.setAlienHitDetector(hitDetector);
            }
            log.info("AlienHitDetector branche (bullets + hitscan font des degats)");
        } else {
            log.warn("AlienControlSystem non attache (itemsNode={}, raycaster={})",
                itemsNode, raycaster);
        }

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
     * Session 92 : upgrade de tous les materiaux pour utiliser le lighting JME.
     *
     * <p>Strategy :</p>
     * <ul>
     *   <li><b>Lighting.j3md</b> (murs/sols/plafonds/portes) : garde tel quel avec
     *       UseMaterialColors=true et force Ambient=0.5 si absent.</li>
     *   <li><b>Unshaded.j3md avec ColorMap + Alpha</b> (sprites billboards
     *       bitmap) : converti en Lighting.j3md en preservant la texture et
     *       l'alpha. Le sprite devient sensible a l'eclairage.</li>
     *   <li><b>Unshaded.j3md avec ColorMap + VertexColor</b> (vectobj atlas) :
     *       converti en Lighting.j3md. Le VertexColor devient un modulateur
     *       diffuse (couleur * texture), et le lighting JME vient par-dessus.</li>
     * </ul>
     *
     * <p>Sessions 75-90 faisaient l'inverse (tout en Unshaded) car on n'avait
     * pas confiance dans le lighting JME. Maintenant on assume pleinement :
     * les lumieres AmbientLight + DirectionalLight + headlight + PointLights
     * par zone doivent teinter toute la scene.</p>
     *
     * <p>Side effect visuel : les anciens effets de shading "baked-in" via
     * VertexColor sur les vectobj sont toujours la, combines au lighting
     * dynamique (double shade). Si ca fait trop, on peut forcer la
     * VertexColor a blanc dans un 2e passage.</p>
     */
    private void upgradeMaterialsForLighting(Node root) {
        int[] keptLighting = {0};
        int[] upgradedAlpha = {0};
        int[] upgradedVertexColor = {0};
        int[] untouched = {0};

        root.depthFirstTraversal(s -> {
            if (!(s instanceof Geometry g)) return;
            Material old = g.getMaterial();
            if (old == null) return;

            String defName = old.getMaterialDef().getName();

            // ──── Cas 1 : deja Lighting.j3md (murs/sols/plafonds/portes) ────
            if (defName.contains("Lighting")) {
                ensureLightingParams(old);
                keptLighting[0]++;
                return;
            }

            // ──── Cas 2 : Unshaded.j3md - a convertir ──────────────────────
            if (!defName.contains("Unshaded")) {
                untouched[0]++;
                return;
            }

            // Recuperer la texture
            Texture colorMap = null;
            try {
                var tp = old.getTextureParam("ColorMap");
                if (tp != null && tp.getValue() != null) colorMap = (Texture) tp.getValue();
            } catch (Exception ignored) {}

            // Detecter si Unshaded avec VertexColor (typique des vectobj)
            boolean hasVertexColor = false;
            try {
                var vc = old.getParam("VertexColor");
                if (vc != null && Boolean.TRUE.equals(vc.getValue())) hasVertexColor = true;
            } catch (Exception ignored) {}

            // Detecter si Unshaded en mode alpha (typique des sprites billboards)
            boolean hasAlpha = old.getAdditionalRenderState().getBlendMode()
                == RenderState.BlendMode.Alpha;

            // Creer un nouveau Lighting.j3md
            Material neu = new Material(sa.getAssetManager(), "Common/MatDefs/Light/Lighting.j3md");
            if (colorMap != null) {
                neu.setTexture("DiffuseMap", colorMap);
            }
            neu.setBoolean("UseMaterialColors", true);
            neu.setColor("Ambient", new ColorRGBA(0.45f, 0.45f, 0.45f, 1f));
            neu.setColor("Diffuse", ColorRGBA.White);

            if (hasVertexColor) {
                neu.setBoolean("UseVertexColor", true);
                upgradedVertexColor[0]++;
            }

            // Preserver la transparence alpha (sprites, glare)
            if (hasAlpha) {
                neu.setFloat("AlphaDiscardThreshold", 0.5f);
                neu.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                // Conserver le bucket Transparent pour le tri par distance
                g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
                upgradedAlpha[0]++;
            }

            // Preserver le face culling
            neu.getAdditionalRenderState().setFaceCullMode(
                old.getAdditionalRenderState().getFaceCullMode());

            g.setMaterial(neu);
        });

        log.info("upgradeMaterialsForLighting : {} Lighting (gardes) + {} Unshaded->Lighting (alpha) + {} Unshaded->Lighting (vertex color) + {} inchanges",
            keptLighting[0], upgradedAlpha[0], upgradedVertexColor[0], untouched[0]);
    }

    /**
     * S'assure qu'un Lighting.j3md a les parametres Ambient/Diffuse+UseMaterialColors
     * requis pour reagir correctement a l'AmbientLight de la scene. Certains
     * .j3o peuvent avoir ete sauves sans ces params.
     */
    private void ensureLightingParams(Material mat) {
        try {
            if (mat.getParam("UseMaterialColors") == null ||
                !Boolean.TRUE.equals(mat.getParam("UseMaterialColors").getValue())) {
                mat.setBoolean("UseMaterialColors", true);
            }
            if (mat.getParam("Ambient") == null) {
                mat.setColor("Ambient", new ColorRGBA(0.45f, 0.45f, 0.45f, 1f));
            }
            if (mat.getParam("Diffuse") == null) {
                mat.setColor("Diffuse", ColorRGBA.White);
            }
        } catch (Exception ignored) {}
    }

    private void placePlayer() {
        if (player == null || levelScene == null) return;
        Float px = levelScene.getUserData("p1X");
        Float pz = levelScene.getUserData("p1Z");
        float x = px != null ? px : 0f, z = pz != null ? pz : 0f;
        // Placer le joueur 0.1 unite au-dessus du sol (juste les pieds sur le sol)
        float spawnY = getSpawnFloorY() + 0.1f;
        player.setPhysicsLocation(new Vector3f(x, spawnY, z));
        // Session 110 : initialiser la zone courante du tracker au spawn pour
        // eviter une iteration brute-force au premier frame.
        Integer p1Zone = levelScene.getUserData("p1Zone");
        if (zoneTracker != null && p1Zone != null) {
            zoneTracker.setCurrentZoneId(p1Zone);
        }
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
        // Session 109 : intensite de marche AVANT normalisation. Sert a piloter
        // le bob (= 0 a l'arret, 1 quand on bouge dans une seule direction).
        // En diagonale walk.length() ~ 1.41, on clamp a 1 dans BobController.
        float walkIntensity = walk.length();
        if (walk.lengthSquared() > 0) walk.normalizeLocal();
        player.setWalkDirection(walk.multLocal(MOVE_SPEED * tpf));

        // Session 109 : avancer le bob (avant updateCameraFromPlayer qui le lit).
        // Note : on utilise le drapeau ducked = false en attendant un vrai systeme
        // d'accroupissement. A relier a PlayerState.ducked plus tard.
        bobController.update(walkIntensity, false, tpf);

        if (doorCollision != null) { doorCollision.clear(); DoorControl.PLAYER_POS.set(player.getPhysicsLocation()); }
        // Session 92 fix 4 : tenir le LiftControl au courant de la position du
        // joueur pour qu'il puisse savoir si on est dessus.
        LiftControl.PLAYER_POS.set(player.getPhysicsLocation());

        // Session 99 (refonte lift) : pousser le joueur Y quand il est sur un lift
        // en mouvement. Le sol statique reste a yBot, mais visuellement le sol
        // remonte avec currentFloorYDelta. Si le joueur est dans le polygone du
        // lift et que le lift est plus haut que sa position actuelle, on le pousse.
        applyLiftPushY();

        // Session 110 : detection zone courante + teleport + fin de niveau.
        // Apres applyLiftPushY pour utiliser la position physique definitive.
        applyZoneLogic(tpf);

        // Session 112 : detection des pickups au sol. Apres applyZoneLogic
        // pour avoir la zone courante a jour (le PickupSystem en a besoin).
        if (pickupSystem != null) {
            pickupSystem.update(player.getPhysicsLocation());
        }

        moveFwd = moveRight = keyTurnDelta = 0f;
        updateCameraFromPlayer();
        headLight.setPosition(sa.getCamera().getLocation());
    }

    /**
     * Logique liee a la zone courante du joueur (session 110).
     *
     * <ol>
     *   <li>Met a jour {@code zoneTracker.currentZoneId} via point-in-polygon.</li>
     *   <li>Si la zone est la zone exit du niveau -> {@link #endLevel()}.</li>
     *   <li>Sinon si la zone a {@code telZone &gt;= 0} -> {@link #applyTeleport}.</li>
     *   <li>Met a jour l'overlay debug avec la zone + position.</li>
     * </ol>
     *
     * <p>Reference ASM : {@code hires.s:2086-2107} (test exit zone) +
     * {@code hires.s:2902-2945} (logique teleport dans Plr1_Control).</p>
     */
    private void applyZoneLogic(float tpf) {
        if (zoneTracker == null || endLevelTriggered) return;

        // Cooldown teleport : empeche un teleport en chaine si la destination
        // est elle-meme une zone-telep (peu probable mais on se protege).
        if (teleportCooldown > 0f) teleportCooldown -= tpf;

        Vector3f playerPos = player.getPhysicsLocation();
        int currentZoneId = zoneTracker.update(playerPos);

        // Mettre a jour l'overlay debug (meme si zone < 0, pour signaler).
        updateDebugZoneOverlay(currentZoneId, playerPos);

        if (currentZoneId < 0) return;  // hors zone connue

        // 1. Fin de niveau ?
        if (zoneTracker.isExitZone(currentZoneId)) {
            log.info("=== Joueur dans zone EXIT {} -> fin de niveau ===", currentZoneId);
            endLevel();
            return;
        }

        // 2. Teleport ?
        if (teleportCooldown <= 0f) {
            ZoneTracker.ZoneInfo zi = zoneTracker.getCurrentZone();
            if (zi != null && zi.hasTeleport()) {
                applyTeleport(zi);
            }
        }
    }

    /**
     * Met a jour le texte de l'overlay debug zone (session 110).
     *
     * <p>Affiche : zone courante, position monde Amiga (= position editeur),
     * presence de teleport, marquage exit zone. Position monde calculee
     * en inversant la conversion JME -&gt; Amiga (= JME * 32, Z negate).</p>
     *
     * <p>Session 112 : ajout HP + collected count pour valider le ramassage.</p>
     *
     * <p>Pratique pour rapporter un bug : "je suis en zone 42, position
     * (1234, -567), il y a un trou dans le mur a ma droite".</p>
     */
    private void updateDebugZoneOverlay(int currentZoneId, Vector3f playerPos) {
        if (debugZoneText == null || !debugZoneVisible) return;

        // Position monde Amiga = JME * 32, Z negate (inverse de la conversion build).
        int worldX = (int) Math.round(playerPos.x * 32f);
        int worldZ = (int) Math.round(-playerPos.z * 32f);

        StringBuilder sb = new StringBuilder(96);
        if (currentZoneId < 0) {
            sb.append("Zone: ?? (hors polygone)");
        } else {
            sb.append("Zone: ").append(currentZoneId);
            ZoneTracker.ZoneInfo zi = zoneTracker.getCurrentZone();
            if (zi != null) {
                sb.append("  floor=").append(zi.floorH())
                  .append(" roof=").append(zi.roofH());
                if (zi.hasTeleport()) {
                    sb.append("  TEL->").append(zi.telZone());
                }
                if (zoneTracker.isExitZone(currentZoneId)) {
                    sb.append("  [EXIT]");
                }
            }
        }
        sb.append('\n');
        sb.append("Pos: (").append(worldX).append(", ").append(worldZ).append(")");
        if (zoneTracker.getExitZoneId() >= 0) {
            sb.append("  exitZone=").append(zoneTracker.getExitZoneId());
        }
        // Session 112 : ligne sante + items collectes pour debug pickup.
        if (healthState != null) {
            sb.append('\n');
            sb.append("HP:").append(healthState.getHealth())
              .append("/").append(healthState.getMaxHealth());
            if (healthState.getJetpackFuel() > 0) {
                sb.append("  Fuel:").append(healthState.getJetpackFuel());
            }
            if (healthState.hasJetpack()) sb.append("  [Jetpack]");
            if (healthState.hasShield())  sb.append("  [Shield]");
            if (healthState.getKeysMask() != 0) {
                sb.append("  Keys:0x").append(Integer.toHexString(healthState.getKeysMask()));
            }
            if (pickupSystem != null && pickupSystem.getCollectedCount() > 0) {
                sb.append("  Picked:").append(pickupSystem.getCollectedCount());
            }
        }
        debugZoneText.setText(sb.toString());
    }

    /**
     * Teleporte le joueur vers la zone destination (session 110).
     *
     * <p>Reference ASM ({@code hires.s:2918-2945}) :</p>
     * <pre>
     * Plr1_XOff_l = TelX
     * Plr1_ZOff_l = TelZ
     * Plr1_YOff_l = (Y - oldFloor) + newFloor   ; preserve hauteur relative
     * Plr1_ZonePtr_l = nouvelle zone
     * jouer son #26
     * </pre>
     *
     * <p>En JME : on convertit (telX, telZ) en coords JME (= /SCALE et negate Z),
     * et on place le joueur Y au sol de la nouvelle zone + EYE_HEIGHT pour
     * preserver la sensation. La hauteur relative au sol n'est pas preservee
     * exactement (l'ASM le fait via Plr1_YOff_l - oldFloor) car ca peut placer
     * le joueur dans le plafond de la zone destination si elle est plus basse.</p>
     */
    private void applyTeleport(ZoneTracker.ZoneInfo from) {
        int destZoneId = from.telZone();
        ZoneTracker.ZoneInfo dest = zoneTracker.getZone(destZoneId);
        if (dest == null) {
            log.warn("Teleport vers zone {} introuvable", destZoneId);
            return;
        }

        // Conversion coords Amiga -> JME : x/SCALE, z negate.
        float SCALE = 32f;
        float jx = from.telX() / SCALE;
        float jz = -from.telZ() / SCALE;
        float floorY = -dest.floorH() / SCALE;
        // Joueur depose au sol de la zone destination + petit offset.
        float jy = floorY + PLAYER_HEIGHT * 0.5f + 0.1f;

        log.info("=== Teleport zone {} -> zone {} ({}/{}) -> ({}/{}/{}) ===",
            from.id(), destZoneId, from.telX(), from.telZ(), jx, jy, jz);

        player.setPhysicsLocation(new Vector3f(jx, jy, jz));
        // Forcer la zone du tracker a la destination pour eviter une iteration
        // au prochain frame (et au cas ou le polygone destination soit decale).
        zoneTracker.setCurrentZoneId(destZoneId);
        // Cooldown 0.5s : le joueur doit "sortir" de la zone-telep avant de
        // pouvoir re-teleporter. Empeche les ping-pongs.
        teleportCooldown = 0.5f;

        // TODO : son de teleport (sample 26 dans l'ASM, MakeSomeNoise).
        // TODO : effet visuel flash blanc/shimmer.
    }

    /**
     * Declenche la fin de niveau : retour au menu de selection (session 110).
     *
     * <p>Pour l'instant on retourne au LevelSelectAppState. Plus tard, on
     * passera au niveau suivant automatiquement (incrementer levelIndex et
     * relancer GameAppState).</p>
     */
    private void endLevel() {
        endLevelTriggered = true;
        sa.getStateManager().attach(new LevelSelectAppState(assetsRoot, jmeAssets));
        sa.getStateManager().detach(this);
    }

    /**
     * Pousse le joueur en Y quand il se tient sur un lift en mouvement.
     *
     * <p>Approche fidele a l'ASM Amiga : le sol ne pousse pas physiquement le
     * joueur via Bullet (impossible avec MeshCollisionShape qui ne suit pas
     * setPhysicsLocation). A la place, on calcule la hauteur cible (yBot +
     * currentFloorYDelta) et on teleporte le joueur s'il est en-dessous.</p>
     *
     * <p>Le calcul est aussi tolerant : on pousse quand player.y &lt; targetY +
     * marge, sinon on laisse Bullet gerer. Comme ca on ne casse pas le saut
     * et on ne traverse pas les plafonds.</p>
     */
    private void applyLiftPushY() {
        Node liftsNode = (Node) levelScene.getChild("lifts");
        if (liftsNode == null) return;

        Vector3f playerPos = player.getPhysicsLocation();
        for (Spatial child : liftsNode.getChildren()) {
            if (!(child instanceof Node liftNode)) continue;
            LiftControl lc = liftNode.getControl(LiftControl.class);
            if (lc == null) continue;
            if (!lc.isPlayerOver(liftNode)) continue;

            // Indiquer au LiftControl que le joueur est dessus (active raise)
            lc.setPlayerStoodOnLift(true);

            // Sol cible (en JME) = position courante du sol visuel du lift
            float targetY = lc.getCurrentFloorY();

            // Si le joueur est en-dessous de targetY (a une marge pres), on le
            // pousse vers le haut. Marge = -0.05 pour eviter le jitter quand on
            // est juste a la limite.
            float playerY = playerPos.y;
            // Le joueur est sur ses pieds, mais physicsLocation = centre de la
            // capsule. EYE_HEIGHT compte deja les yeux par rapport au pieds.
            // Pour le sol, le bas de la capsule est a (playerY - PLAYER_HEIGHT*0.5).
            float playerFeetY = playerY - PLAYER_HEIGHT * 0.5f;

            if (playerFeetY < targetY - 0.05f) {
                // Pousser le joueur vers le haut.
                // Important : on translate la capsule pour que ses pieds soient a targetY
                Vector3f newPos = new Vector3f(
                    playerPos.x,
                    targetY + PLAYER_HEIGHT * 0.5f + 0.01f,
                    playerPos.z);
                player.setPhysicsLocation(newPos);
            }
            return; // un seul lift a la fois
        }

        // Aucun lift sous le joueur - signaler aux LiftControls
        for (Spatial child : liftsNode.getChildren()) {
            if (child instanceof Node liftNode) {
                LiftControl lc = liftNode.getControl(LiftControl.class);
                if (lc != null) lc.setPlayerStoodOnLift(false);
            }
        }
    }

    private void updateCameraFromPlayer() {
        if (player == null) return;
        float cy=FastMath.cos(yaw), sy=FastMath.sin(yaw), cp=FastMath.cos(pitch), sp=FastMath.sin(pitch);

        // Session 109 : bob de marche applique a la camera.
        //  - bobYCam : pulse vers le haut a chaque pas (toujours >= 0, comme ASM)
        //  - bobXCam : oscillation laterale signee, projetee sur l'axe "right"
        //              (= perpendiculaire a la direction de regard dans le plan XZ)
        float bobYCam = bobController.getBobYCamera();
        float bobXCam = bobController.getBobX();
        // Direction "right" en repere monde quand le joueur regarde a yaw :
        // forward = (cos(yaw), 0, sin(yaw)) dans le plan, donc right = (sin(yaw), 0, -cos(yaw))
        // Mais ici on veut juste un decalage lateral avant orientation : on l'ajoute a la position camera.

        Vector3f camPos = player.getPhysicsLocation().add(
            sy * bobXCam,
            EYE_HEIGHT + bobYCam,
            -cy * bobXCam);
        sa.getCamera().setLocation(camPos);
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
                                   A_MX,A_MXN,A_MY,A_MYN,A_JUMP,A_EXIT,A_DEBUG_HUD,
                                   A_TOGGLE_WEAPON})
            if (im.hasMapping(a)) im.deleteMapping(a);
        im.removeListener(this);
        im.removeListener((AnalogListener)this);
        if (debugZoneText != null) { sa.getGuiNode().detachChild(debugZoneText); debugZoneText = null; }
        if (levelScene  != null) { sa.getRootNode().detachChild(levelScene);   levelScene  = null; }
        if (playerNode  != null) { sa.getRootNode().detachChild(playerNode);   playerNode  = null; }
        if (ambientLight!= null) { sa.getRootNode().removeLight(ambientLight); ambientLight = null; }
        if (sunLight    != null) { sa.getRootNode().removeLight(sunLight);     sunLight     = null; }
        if (headLight   != null) { sa.getRootNode().removeLight(headLight);    headLight    = null; }
        if (bullet      != null) { sa.getStateManager().detach(bullet);        bullet       = null; }
        if (hud         != null) { sa.getStateManager().detach(hud);           hud          = null; }
        if (weaponView  != null) { sa.getStateManager().detach(weaponView);    weaponView   = null; }
        if (shootSystem != null) { sa.getStateManager().detach(shootSystem);   shootSystem  = null; }
        if (bulletUpdateSystem != null) { sa.getStateManager().detach(bulletUpdateSystem); bulletUpdateSystem = null; }
        if (tracerSystem != null) { sa.getStateManager().detach(tracerSystem); tracerSystem = null; }
        if (impactSystem != null) { sa.getStateManager().detach(impactSystem); impactSystem = null; }
        if (physicsBulletSystem != null) { sa.getStateManager().detach(physicsBulletSystem); physicsBulletSystem = null; }
        if (alienControlSystem != null) { sa.getStateManager().detach(alienControlSystem); alienControlSystem = null; }
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
        im.addMapping(A_DEBUG_HUD, new KeyTrigger(KeyInput.KEY_F3));
        im.addMapping(A_TOGGLE_WEAPON, new KeyTrigger(KeyInput.KEY_H));
        im.addMapping(A_MX,  new MouseAxisTrigger(MouseInput.AXIS_X, false));
        im.addMapping(A_MXN, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        im.addMapping(A_MY,  new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        im.addMapping(A_MYN, new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        im.addListener(this, A_EXIT, A_JUMP, A_DEBUG_HUD,
                       A_TOGGLE_WEAPON);
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
            case A_DEBUG_HUD -> { if (p) toggleDebugZoneOverlay(); }
            case A_TOGGLE_WEAPON -> { if (p && weaponView != null)
                weaponView.setWeaponVisible(!weaponView.isWeaponVisible()); }
        }
    }

    /**
     * Toggle l'overlay debug zone (touche F3, session 110).
     *
     * <p>Quand active : affiche la zone courante + position monde Amiga
     * en haut-gauche de l'ecran. Quand desactive : retire le texte du
     * guiNode pour ne pas surcharger.</p>
     */
    private void toggleDebugZoneOverlay() {
        if (debugZoneText == null) return;
        debugZoneVisible = !debugZoneVisible;
        if (debugZoneVisible) {
            sa.getGuiNode().attachChild(debugZoneText);
        } else {
            sa.getGuiNode().detachChild(debugZoneText);
        }
    }

    /** Change d'arme (debug : ancienne methode Y/T, remplace par PlayerShootSystem). */
    @SuppressWarnings("unused")
    private void cycleWeapon(int delta) {
        if (weaponView == null) return;
        WeaponType[] all = WeaponType.values();
        int idx = weaponView.getCurrentWeapon() != null
            ? weaponView.getCurrentWeapon().ordinal()
            : 0;
        idx = (idx + delta + all.length) % all.length;
        weaponView.loadWeapon(all[idx]);
        log.info("Arme : {}", all[idx]);
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
