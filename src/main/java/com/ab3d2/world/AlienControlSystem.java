package com.ab3d2.world;

import com.ab3d2.core.ai.AlienAI;
import com.ab3d2.core.ai.AlienDef;
import com.ab3d2.core.ai.AlienDefLoader;
import com.ab3d2.core.ai.AlienRuntimeState;
import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.asset.AssetManager;
import com.jme3.math.FastMath;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppState qui anime tous les aliens du niveau.
 *
 * <h2>Cycle de vie</h2>
 *
 * <ol>
 *   <li>{@code initialize} : charge les 20 {@link AlienDef} depuis
 *       {@code definitions.json}, parcourt {@code levelScene/items} pour trouver
 *       les sprites avec {@code typeId=0} et cree un {@link AlienRuntimeState}
 *       par instance. Lie chaque etat au Node JME via une {@code Map}.</li>
 *   <li>{@code update(tpf)} chaque frame :
 *     <ul>
 *       <li>Calcule {@code tempFrames} (entier &ge;1) selon le tpf</li>
 *       <li>Pour chaque alien vivant : appelle {@link AlienAI#update}</li>
 *       <li>Synchronise la position du Node JME depuis {@code state.worldX/Y/Z}</li>
 *       <li>Si {@code state.isDeadAndGone()} : detache le Node et retire l'alien
 *           de la simulation</li>
 *     </ul>
 *   </li>
 *   <li>{@code cleanup} : reset, retire tous les aliens.</li>
 * </ol>
 *
 * <h2>Conversion d'unites</h2>
 *
 * <p>L'{@code AlienRuntimeState} stocke les coords en <em>unites Amiga</em>
 * (signed 16-bit, ~32 unites = 1 m). Quand on synchronise vers le Node JME, on
 * convertit avec :</p>
 *
 * <pre>
 *   nodeX =  state.worldX / SCALE
 *   nodeZ = -state.worldZ / SCALE   (Z flippe)
 *   nodeY = ... (le Y du sprite est laisse a la hauteur initiale du spawn,
 *               la machine a etats actuelle ne gere pas la hauteur sauf
 *               pour les aliens volants)
 * </pre>
 *
 * <h2>Statut phase 2.B</h2>
 *
 * <p>Cette version <b>n'implemente pas encore</b> :</p>
 * <ul>
 *   <li>L'animation 4-directionnelle des sprites ({@code ViewpointToDraw})</li>
 *   <li>La collision joueur-alien (le joueur peut traverser les aliens)</li>
 *   <li>La collision tirs joueur -&gt; aliens</li>
 *   <li>Le tir alien -&gt; joueur (pour les aliens {@code attacksWithGun})</li>
 *   <li>Le splat / spawn de petits aliens a la mort des boss</li>
 *   <li>Les sons positionnels (cris d'alien)</li>
 * </ul>
 *
 * <p>Mais elle valide le fait que :</p>
 * <ul>
 *   <li>La machine a etats demarre au spawn ({@code mode=DEFAULT})</li>
 *   <li>Les aliens detectent le joueur via LOS et passent en {@code RESPONSE}</li>
 *   <li>Les aliens en charge se rapprochent du joueur (ligne droite)</li>
 *   <li>Les transitions {@code DEFAULT -&gt; RESPONSE -&gt; FOLLOWUP -&gt; DEFAULT}
 *       fonctionnent en runtime</li>
 * </ul>
 *
 * @since session 113 (phase 2.B)
 */
public class AlienControlSystem extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(AlienControlSystem.class);

    /** Facteur de conversion Amiga -&gt; JME. */
    private static final float SCALE = AiWorldAdapter.SCALE;

    /**
     * 50 Hz Amiga vs ~60 Hz JME : on accumule le tpf et on appelle l'IA chaque
     * fois qu'on a accumule 1 frame Amiga (= 1/50 sec = 0.02 sec).
     */
    private static final float AMIGA_FRAME_DURATION = 0.02f;

    private final Path definitionsJson;
    private final Node itemsNode;
    private final AiWorldAdapter world;

    private AlienDef[] defs;
    private final List<AlienRuntimeState> aliens = new ArrayList<>();
    private final Map<AlienRuntimeState, Node> nodeMap = new IdentityHashMap<>();
    private final Map<AlienRuntimeState, AlienAI> aiMap = new IdentityHashMap<>();
    /** Y JME initial du sprite, capturee a l'init pour preserver la hauteur visuelle. */
    private final Map<AlienRuntimeState, Float> spawnYMap = new IdentityHashMap<>();
    /**
     * Animation sprite : un controller par alien anime, swap les frames PNG
     *  selon mode + viewpoint + phase. Phase 2.E.
     *  Cle = AlienRuntimeState (identite), valeur = controller dedie au sprite.
     */
    private final Map<AlienRuntimeState, AlienSpriteController> spriteCtrlMap = new IdentityHashMap<>();

    /** Accumulateur de temps pour produire des "frames Amiga" deterministes. */
    private float frameAccumulator = 0f;

    /** Camera JME : utilisee pour calculer le viewpoint de chaque sprite. Phase 2.E. */
    private Camera camera;
    /** AssetManager : utilise par les AlienSpriteController pour charger les PNG. */
    private AssetManager assetManager;

    /**
     * @param definitionsJson chemin vers {@code assets/levels/definitions.json}
     * @param itemsNode       Node {@code "items"} du levelScene (cree par
     *                        {@code LevelSceneBuilder.addItems})
     * @param world           adaptateur de monde pour les queries IA
     */
    public AlienControlSystem(Path definitionsJson, Node itemsNode, AiWorldAdapter world) {
        this.definitionsJson = definitionsJson;
        this.itemsNode = itemsNode;
        this.world = world;
    }

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        if (itemsNode == null) {
            log.warn("AlienControlSystem : itemsNode est null, aucun alien ne sera anime");
            return;
        }

        // Phase 2.E : on capture la camera et l'assetManager pour piloter
        // les AlienSpriteController.
        this.camera = app.getCamera();
        this.assetManager = app.getAssetManager();

        // 1. Charger les 20 definitions
        try {
            if (Files.exists(definitionsJson)) {
                defs = AlienDefLoader.load(definitionsJson);
                int loaded = 0;
                for (AlienDef d : defs) if (d != null) loaded++;
                log.info("AlienControlSystem : {} alien defs charges depuis {}", loaded, definitionsJson);
            } else {
                log.warn("AlienControlSystem : {} introuvable, comportements par defaut", definitionsJson);
                defs = new AlienDef[20];
            }
        } catch (Exception e) {
            log.error("Echec chargement definitions.json : {}", e.getMessage());
            defs = new AlienDef[20];
        }

        // 2. Parcourir les sprites alien (typeId=0) et creer un AlienRuntimeState par instance
        int count = 0;
        for (Spatial s : itemsNode.getChildren()) {
            if (!(s instanceof Node spriteNode)) continue;
            Integer typeId = s.getUserData("typeId");
            if (typeId == null || typeId != 0) continue;  // pas un alien

            Integer defIndex   = s.getUserData("defIndex");
            Integer worldX     = s.getUserData("worldX");
            Integer worldZ     = s.getUserData("worldZ");
            Integer worldY     = s.getUserData("worldY");
            Integer zoneId     = s.getUserData("zoneId");
            Integer angle      = s.getUserData("angle");
            Integer hitPoints  = s.getUserData("hitPoints");
            Integer teamNumber = s.getUserData("teamNumber");
            Integer doorLocks  = s.getUserData("doorLocks");
            Integer liftLocks  = s.getUserData("liftLocks");

            if (defIndex == null || worldX == null || worldZ == null) continue;

            int idx = defIndex;
            AlienDef def = (idx >= 0 && idx < defs.length && defs[idx] != null)
                ? defs[idx]
                : fallbackDef(idx);

            AlienRuntimeState state = new AlienRuntimeState(count, idx);
            state.worldX = worldX;
            state.worldY = worldY != null ? worldY : 0;
            state.worldZ = worldZ;
            state.zoneId = zoneId != null ? zoneId : 0;
            state.teamNumber = teamNumber != null ? teamNumber : -1;
            state.currentAngle = angle != null ? angle : 0;
            state.doorsAndLiftsHeld = combineLocks(doorLocks, liftLocks);
            state.initFromDef(def);

            // Si le Node a un hitPoints custom (depuis le binaire de niveau), l'utiliser
            if (hitPoints != null && hitPoints > 0) {
                state.hitPoints = hitPoints;
            }

            AlienAI ai = new AlienAI(def, world);

            aliens.add(state);
            nodeMap.put(state, spriteNode);
            aiMap.put(state, ai);
            // Capturer le Y JME initial du sprite pour preserver la hauteur
            spawnYMap.put(state, spriteNode.getLocalTranslation().y);

            // Phase 2.E : creer le sprite controller pour animer le PNG
            // affiche selon mode + viewpoint. Pour les aliens vector
            // (gfxType=1), le controller renvoie isAnimatable()=false donc
            // c'est un no-op (le modele 3D n'est pas anime, sera fait plus tard).
            String wadName = spriteNode.getUserData("wadName");
            if (wadName != null && !wadName.isEmpty()) {
                int maxFrames = countSpriteFrames(wadName);
                AlienSpriteController ctrl = new AlienSpriteController(
                    spriteNode, wadName, maxFrames, assetManager);
                if (ctrl.isAnimatable()) {
                    spriteCtrlMap.put(state, ctrl);
                }
            }
            count++;
        }

        log.info("AlienControlSystem : {} aliens animables, {} sprite controllers",
            count, spriteCtrlMap.size());
    }

    /**
     * Compte les frames disponibles pour un WAD donne (= max N tel que
     * {@code Textures/objects/{wad}/{wad}_fN.png} existe).
     *
     * <p>Le resultat est utilise comme borne max par {@link AlienAnimTable#pickFrame}
     * pour ne pas demander une frame qui n'existe pas.</p>
     *
     * <p>Phase 2.E (fix) : on teste l'existence directement via
     * {@link Files#exists} sur le filesystem au lieu de
     * {@code assetManager.locateAsset()}. Cette derniere methode emet un
     * warning JME a chaque fichier non trouve, polluant les logs avec des
     * messages comme "Cannot locate resource: alien2_f19.png (Flipped)". En
     * lisant directement le FS via le path {@code assets/Textures/objects/...},
     * on peut tester l'existence silencieusement.</p>
     *
     * <p>Convention du jeu : les frames sont sequentielles 0..N-1, donc on
     * s'arrete au premier trou. La cap a 32 est large : aucun WAD du jeu
     * original n'a plus de 22 frames.</p>
     */
    private int countSpriteFrames(String wadName) {
        // Tester via le filesystem (le contenu est dans assets/Textures/...)
        // pour eviter les warnings JME quand un fichier n'existe pas.
        java.nio.file.Path baseDir = java.nio.file.Paths.get(
            "assets", "Textures", "objects", wadName);
        if (!Files.isDirectory(baseDir)) {
            // Si le dossier n'existe pas, fallback : 1 frame minimum (la 0)
            return 1;
        }
        int max = 0;
        for (int i = 0; i < 32; i++) {
            java.nio.file.Path png = baseDir.resolve(wadName + "_f" + i + ".png");
            if (!Files.exists(png)) break;
            max = i + 1;
        }
        return Math.max(1, max);
    }

    /** Combine les bitmasks doorLocks (low byte) + liftLocks (high byte) en un long unique. */
    private static long combineLocks(Integer doorLocks, Integer liftLocks) {
        long d = doorLocks != null ? (doorLocks & 0xFFFFFFFFL) : 0L;
        long l = liftLocks != null ? (liftLocks & 0xFFFFFFFFL) : 0L;
        return (l << 32) | d;
    }

    /** Fallback def si {@code definitions.json} ne contient pas l'index demande. */
    private static AlienDef fallbackDef(int idx) {
        return new AlienDef(
            idx, "fallback_" + idx,
            0,         // gfxType
            0, 50, 16, // defaultBeh, reactionTime, defaultSpeed
            0, 32, 100,// responseBeh, responseSpeed, responseTimeout
            255, 16,   // damageToRetreat, damageToFollowup
            0, 16, 50, // followupBeh, followupSpeed, followupTimeout
            0, 32, 50, // retreatBeh, retreatSpeed, retreatTimeout
            0, 2, 128, 1, 0, -1
        );
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled() || aliens.isEmpty()) return;

        // Accumule le temps reel et execute autant de "frames Amiga" que necessaire
        frameAccumulator += tpf;
        int amigaFrames = 0;
        while (frameAccumulator >= AMIGA_FRAME_DURATION) {
            frameAccumulator -= AMIGA_FRAME_DURATION;
            amigaFrames++;
            // Cap : si on a accumule trop (lag spike), max 4 frames pour eviter
            // de geler le jeu en rattrapant
            if (amigaFrames >= 4) {
                frameAccumulator = 0f;
                break;
            }
        }
        if (amigaFrames == 0) return;

        // Donne le multiplicateur a l'adapter pour que l'IA en tienne compte
        // dans speeds/timeouts. Cas typique : amigaFrames=1 (60Hz JME -> 50Hz Amiga
        // approx, parfois 2 a 60 fps stable).
        world.setTempFrames(amigaFrames);

        // Update toutes les IA
        // Iteration en sens inverse pour pouvoir retirer les aliens morts en
        // cours de boucle sans index shift.
        // Phase 2.E : on calcule l'angle camera UNE fois pour toute la boucle
        // (= equivalent ASM Vis_AngPos_w lu par ai_DoWalkAnim via ViewpointToDraw).
        int cameraAngle = computeCameraAngle();

        for (int i = aliens.size() - 1; i >= 0; i--) {
            AlienRuntimeState state = aliens.get(i);
            AlienAI ai = aiMap.get(state);
            if (ai == null) continue;

            ai.update(state);

            Node node = nodeMap.get(state);
            if (node == null) continue;

            // Synchroniser la position du Node JME
            float jx = state.worldX / SCALE;
            float jz = -state.worldZ / SCALE;
            float jy = spawnYMap.getOrDefault(state, 0f);
            // TODO 2.F : si l'alien vole, ajuster jy via state.worldY
            node.setLocalTranslation(jx, jy, jz);

            // Phase 2.E : animer le sprite (swap _fN.png) selon mode + viewpoint.
            // Skipped pour les aliens DIE-puis-GONE (ils ont ete deja retires)
            // et pour les aliens vector (controller.isAnimatable()=false).
            AlienSpriteController ctrl = spriteCtrlMap.get(state);
            if (ctrl != null) {
                ctrl.update(state, cameraAngle);
            }

            // Retirer les aliens morts
            if (state.isDeadAndGone()) {
                node.removeFromParent();
                aliens.remove(i);
                nodeMap.remove(state);
                aiMap.remove(state);
                spawnYMap.remove(state);
                spriteCtrlMap.remove(state);
                log.debug("Alien #{} (def={}) supprime", state.slot, state.defIndex);
            }
        }
    }

    /**
     * Calcule l'angle camera 0..4095 (= {@code Vis_AngPos_w} ASM) depuis la
     * direction de regard JME. Le yaw camera 0 = +X, on convertit pour
     * matcher la convention ASM (0 = +Z avant, sens horaire vu du dessus).
     */
    private int computeCameraAngle() {
        if (camera == null) return 0;
        com.jme3.math.Vector3f dir = camera.getDirection();
        // Yaw JME : atan2(dz, dx) (= angle dans le plan XZ)
        // Convention alien : currentAngle 0 = +Z avant. atan2(dx, dz) donne
        // le bon angle (0 quand on regarde +Z).
        float rad = (float) Math.atan2(dir.x, dir.z);
        // JME camera regarde -Z par defaut, alors que les aliens utilisent +Z.
        // Inversion necessaire : on ajoute PI pour aligner les referenciels.
        rad += FastMath.PI;
        if (rad < 0) rad += FastMath.TWO_PI;
        // Normaliser dans [0, 2*PI]
        rad = rad % FastMath.TWO_PI;
        return (int) (rad * 4096.0 / (2 * Math.PI)) & 0xFFF;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        aliens.clear();
        nodeMap.clear();
        aiMap.clear();
        spawnYMap.clear();
        spriteCtrlMap.clear();
    }

    // ── API publique pour les autres systemes ────────────────────────────────

    /** Retourne la liste des aliens actuellement vivants (lecture seule). */
    public List<AlienRuntimeState> getAliens() {
        return aliens;
    }

    /** Retourne le Node JME associe a un alien. */
    public Node getNodeFor(AlienRuntimeState state) {
        return nodeMap.get(state);
    }

    /**
     * Inflige des degats a un alien depuis du code externe (ex. tir joueur).
     * Equivaut a {@code AlienAI.inflictDamage(state, amount)}.
     *
     * @param state alien cible
     * @param amount degats en HP
     */
    public void damageAlien(AlienRuntimeState state, int amount) {
        AlienAI.inflictDamage(state, amount);
    }

    /** Retourne l'alien le plus proche d'une position monde JME (pour ciblage des tirs). */
    public AlienRuntimeState findNearestAlien(float jmeX, float jmeY, float jmeZ, float maxDistJme) {
        AlienRuntimeState best = null;
        float bestDist2 = maxDistJme * maxDistJme;
        for (AlienRuntimeState a : aliens) {
            if (!a.isAlive()) continue;
            float ax = a.worldX / SCALE;
            float az = -a.worldZ / SCALE;
            float dx = ax - jmeX, dz = az - jmeZ;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = a;
            }
        }
        return best;
    }
}
