package com.ab3d2.world;

import com.ab3d2.combat.PlayerHealthState;
import com.ab3d2.combat.WorldRaycaster;
import com.ab3d2.core.ai.AiWorld;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Implementation runtime de {@link AiWorld} qui s'appuie sur les systemes JME
 * existants pour repondre aux queries de l'IA.
 *
 * <h2>Couplages</h2>
 * <ul>
 *   <li><b>Joueur</b> : position lue directement depuis {@code Camera.getLocation()}.
 *       Convention : on convertit JME &rarr; Amiga en multipliant par {@code SCALE=32}
 *       pour que l'IA travaille dans son systeme de coords natif.</li>
 *   <li><b>Zone</b> : {@link ZoneTracker} maintient la zone du joueur a jour
 *       chaque frame (le {@link com.ab3d2.app.GameAppState} fait le call).</li>
 *   <li><b>LOS</b> : {@link WorldRaycaster} fait un raycast a travers le
 *       PhysicsSpace pour tester si un mur bloque le tir vers le joueur.</li>
 *   <li><b>Control points</b> : table chargee une fois depuis le JSON niveau,
 *       indexee par id 0..N-1. Pas de pathfinding pour l'instant (la 2.B
 *       initiale utilise les CP comme cibles directes en ligne droite).</li>
 *   <li><b>RNG</b> : {@link Random} seede a 42 pour la reproductibilite des
 *       parties (deterministe). On peut changer plus tard si besoin.</li>
 *   <li><b>Audio</b> : no-op pour l'instant. Sera branche quand l'AudioManager
 *       sera porte.</li>
 * </ul>
 *
 * <h2>Conversion d'unites</h2>
 *
 * <p>L'IA travaille en <em>unites Amiga</em> (signed 16-bit, ~32 unites = 1 m).
 * Le moteur JME travaille en unites JME (1 unite = 1 m). Le facteur de
 * conversion est {@code SCALE = 32} (defini partout dans le code).</p>
 *
 * <p>Convention des coords X/Z :
 * <ul>
 *   <li><b>Amiga &rarr; JME</b> : {@code jx = x_amiga / 32}, {@code jz = -z_amiga / 32}
 *       (Z flippe pour passer du repere Amiga vers le repere JME)</li>
 *   <li><b>JME &rarr; Amiga</b> : {@code x_amiga = jx * 32}, {@code z_amiga = -jz * 32}</li>
 * </ul></p>
 *
 * <p>Pour les hauteurs (Y), c'est l'inverse : valeurs editeur Amiga plus
 * petites = position plus haute dans le monde, donc {@code jy = -y_amiga / 32}.</p>
 *
 * @since session 113
 */
public final class AiWorldAdapter implements AiWorld {

    /** Facteur de conversion JME &lt;-&gt; Amiga (defini dans tout le code). */
    public static final float SCALE = 32f;

    /**
     * Distance max pour les raycast LOS (en unites JME).
     * 200 JME = 6400 unites Amiga, suffisant pour traverser n'importe quel niveau
     * sans limite artificielle. L'ASM original n'a aucune limite (pure LOS via PVS),
     * mais on cap pour eviter les raycasts gigantesques sur les niveaux ouverts.
     *
     * <p>Note : l'ASM {@code ai_AttackWithGun} a une limite implicite de tir a
     * ~2896 unites Amiga (~90 JME) car proba inversement proportionnelle a dist^2,
     * donc au-dela les hit deviennent impossibles. On peut donc voir un alien
     * de loin mais il ne nous touchera pas.</p>
     */
    private static final float LOS_MAX_DIST_JME = 200f;

    private final com.jme3.renderer.Camera camera;
    private final ZoneTracker zoneTracker;
    private final WorldRaycaster raycaster;

    /** Etat sante du joueur, pour appliquer les degats infliges par les aliens.
     *  Optionnel : si null, les degats alien sont logges mais pas appliques. */
    private PlayerHealthState playerHealth;

    /** Control points indexes par id (0..N-1). Position en unites Amiga. */
    private final List<int[]> controlPoints;

    /** RNG deterministe pour les decisions IA. */
    private final Random rng;

    /** Multiplicateur de frame skip (= Anim_TempFrames_w). Mis a jour par GameAppState. */
    private int tempFrames = 1;

    /**
     * Detecteur de collision murs pour les aliens (session 113 phase 2.F).
     * Initialise depuis {@link AlienWallCollider#fromLevelScene} dans le
     * constructeur. {@code null} si la scene ne contient pas de murs.
     */
    private final AlienWallCollider wallCollider;

    /** Cache des floor/roof Y par zone, en unites JME. */
    private final Map<Integer, Float> floorYCache = new HashMap<>();
    private final Map<Integer, Float> roofYCache = new HashMap<>();

    public AiWorldAdapter(com.jme3.renderer.Camera camera,
                          ZoneTracker zoneTracker,
                          WorldRaycaster raycaster,
                          Node levelScene) {
        this.camera = camera;
        this.zoneTracker = zoneTracker;
        this.raycaster = raycaster;
        this.controlPoints = loadControlPoints(levelScene);
        this.rng = new Random(42L);
        // Phase 2.F : extraction des segments murs pour la collision alien.
        this.wallCollider = AlienWallCollider.fromLevelScene(levelScene);
    }

    /**
     * Charge les control points depuis le levelScene. Les CP ne sont pas
     * actuellement exposes en tant que Nodes scene, donc on les lit depuis le
     * JSON via une convention : userData {@code "controlPoints"} sur le root.
     *
     * <p>Phase 2.B initiale : si l'info n'est pas dans le scene, on cree une
     * liste vide. L'IA utilisera des deplacements ligne-droite vers le joueur
     * sans patrouille active.</p>
     */
    private static List<int[]> loadControlPoints(Node levelScene) {
        List<int[]> result = new ArrayList<>();
        // TODO 2.C : exporter les control points depuis LevelSceneBuilder.
        // Pour l'instant on retourne une liste vide -> l'IA n'utilisera pas
        // les CP, les aliens vont en ligne droite vers le joueur quand ils le
        // voient (suffisant pour valider la machine a etats).
        return result;
    }

    /** Mise a jour du frame skip multiplier (appele par GameAppState). */
    public void setTempFrames(int frames) {
        this.tempFrames = Math.max(1, frames);
    }

    /**
     * Branche l'etat sante du joueur pour que les tirs alien fassent vraiment
     * des degats. Sans ce setter, les attaques sont des no-op (logges).
     */
    public void setPlayerHealth(PlayerHealthState ph) {
        this.playerHealth = ph;
    }

    @Override public int tempFrames() { return tempFrames; }

    // ── Joueur ────────────────────────────────────────────────────────────────

    @Override
    public float playerX() {
        return camera.getLocation().x * SCALE;
    }

    @Override
    public float playerY() {
        return -camera.getLocation().y * SCALE;
    }

    @Override
    public float playerZ() {
        // JME -> Amiga : flip Z
        return -camera.getLocation().z * SCALE;
    }

    @Override
    public int playerZone() {
        return zoneTracker != null ? zoneTracker.getCurrentZoneId() : -1;
    }

    @Override
    public boolean playerInUpperZone() {
        // Phase 2.B : pas encore de tracking upper/lower (zones a 2 niveaux
        // sont rares dans le jeu). Retourne false par defaut.
        return false;
    }

    @Override
    public int playerRoomBrightness() {
        if (zoneTracker == null) return 32;
        var zi = zoneTracker.getCurrentZone();
        return zi != null ? zi.brightness() : 32;
    }

    @Override
    public int playerNoiseVolume() {
        // Phase 2.B : on ne tracke pas le bruit du joueur (pas de tirs/pas
        // counted dans Plr1_NoiseVol_w). Retourne 0 = silence.
        return 0;
    }

    // ── Ligne de vue ──────────────────────────────────────────────────────────

    @Override
    public boolean canSeePlayer(float fromX, float fromY, float fromZ,
                                int fromZone, boolean fromUpper) {
        if (raycaster == null) return false;

        // Conversion Amiga -> JME pour le raycast
        float jx = fromX / SCALE;
        float jy = -fromY / SCALE;
        float jz = -fromZ / SCALE;

        Vector3f from = new Vector3f(jx, jy + 1.0f, jz); // +1 = "yeux" alien
        Vector3f playerPos = camera.getLocation();
        Vector3f delta = playerPos.subtract(from);
        float distToPlayer = delta.length();
        if (distToPlayer < 0.01f) return true;
        if (distToPlayer > LOS_MAX_DIST_JME) return false;

        Vector3f dir = delta.normalize();

        // Raycast vers le joueur. Si le hit est ferme avant la distance au
        // joueur, c'est qu'un mur bloque.
        WorldRaycaster.RayHit hit = raycaster.castRay(from, dir, distToPlayer);
        if (!hit.hit) return true;  // rien entre nous = on voit
        // Le hit est un mur (ou sol/plafond) avant le joueur -> bloque
        return hit.distance >= distToPlayer - 0.1f;
    }

    // ── Control points ────────────────────────────────────────────────────────

    @Override
    public int getNextControlPoint(int fromCP, int toCP) {
        // Phase 2.B : pas de pathfinding, on retourne directement la cible
        // (simulation : "il y a un chemin direct"). C'est le comportement
        // par defaut quand l'ASM ne trouve pas de CP intermediaire.
        return toCP;
    }

    @Override
    public int numControlPoints() {
        return controlPoints.size();
    }

    @Override
    public float controlPointX(int cpId) {
        if (cpId < 0 || cpId >= controlPoints.size()) return 0f;
        return controlPoints.get(cpId)[0];
    }

    @Override
    public float controlPointZ(int cpId) {
        if (cpId < 0 || cpId >= controlPoints.size()) return 0f;
        return controlPoints.get(cpId)[1];
    }

    @Override
    public float controlPointY(int cpId) {
        // Phase 2.B : tous les CP au sol Y=0 (les aliens volants utiliseront
        // leur propre logique de hauteur).
        return 0f;
    }

    @Override
    public int zoneControlPoint(int zoneId, boolean upperFloor) {
        if (zoneTracker == null) return 0;
        // ZoneTracker.ZoneInfo n'expose pas (encore) le controlPoint de la zone.
        // Phase 2.B : retourne 0 (= alien va vers le CP 0 par defaut).
        return 0;
    }

    // ── Zones ─────────────────────────────────────────────────────────────────

    @Override
    public int zoneBrightness(int zoneId) {
        if (zoneTracker == null) return 32;
        var zi = zoneTracker.getZone(zoneId);
        return zi != null ? zi.brightness() : 32;
    }

    @Override
    public float zoneFloorY(int zoneId, boolean upperFloor) {
        if (zoneTracker == null) return 0f;
        Float cached = floorYCache.get(zoneId);
        if (cached != null) return cached;
        var zi = zoneTracker.getZone(zoneId);
        float y = (zi != null) ? -zi.floorH() / SCALE : 0f;
        floorYCache.put(zoneId, y);
        return y;
    }

    @Override
    public float zoneRoofY(int zoneId, boolean upperFloor) {
        if (zoneTracker == null) return 100f;
        Float cached = roofYCache.get(zoneId);
        if (cached != null) return cached;
        var zi = zoneTracker.getZone(zoneId);
        float y = (zi != null) ? -zi.roofH() / SCALE : 100f;
        roofYCache.put(zoneId, y);
        return y;
    }

    // ── Aleatoire ─────────────────────────────────────────────────────────────

    @Override
    public int getRandom() {
        return rng.nextInt(65536);
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    @Override
    public void playPositionalSound(int sampleNum, int volume,
                                     float worldX, float worldZ, int echoLevel) {
        // TODO 2.E : brancher l'AudioNode JME quand le AudioManager sera porte.
        // Pour l'instant : no-op (les aliens tuent / crient en silence).
    }

    // ── Combat alien -> joueur (session 113 phase 2.D) ─────────────────────

    @Override
    public void applyDamageToPlayer(int damage, float fromX, float fromZ) {
        if (playerHealth == null) return; // pas wire, no-op
        if (damage <= 0) return;

        // Si le joueur a un shield, les degats vont au shield d'abord.
        // Pour l'instant on simplifie : pas de shield logic, degats direct a la sante.
        // TODO 2.E : implementer la consommation du shield avant la sante.
        playerHealth.takeDamage(damage);

        // TODO 2.E : effet visuel (red flash overlay) quand le joueur est touche.
        // L'ASM utilise EntT_ImpactX/Z pour appliquer un knockback visuel.
    }

    @Override
    public void spawnAlienProjectile(int bulletType, int damage,
                                      float fromX, float fromY, float fromZ) {
        // Phase 2.D : pas encore de vrai pool de bullets aliens. On simule le
        // hit en appliquant les degats avec une probabilite reduite (le joueur
        // pourrait esquiver un projectile qu'il voit venir).
        //
        // Plus precisement : le projectile alien voyage a vitesse fixe, le
        // joueur peut s'eloigner. On applique 50% de chance de hit pour les
        // aliens en mode RESPONSE qui tirent un projectile.
        //
        // TODO 2.E : creer un AlienShotPool symetrique a PlayerShotPool, et un
        // AlienBulletUpdateSystem qui fait voyager les bullets et teste
        // collision avec le joueur.
        if (playerHealth == null) return;
        if ((rng.nextInt(100)) < 50) {
            playerHealth.takeDamage(damage);
        }
    }

    // ── Collision murs alien (phase 2.F) ─────────────────────────────────

    /**
     * Resout le mouvement alien contre les murs en convertissant Amiga -&gt; JME,
     * delegue a {@link AlienWallCollider#move}, puis reconvertit JME -&gt; Amiga.
     *
     * <p>Convention : X reste, Z est negate, comme dans le reste du code
     * (cf. {@link com.ab3d2.tools.LevelSceneBuilder} et
     * {@link AlienControlSystem}).</p>
     */
    @Override
    public float[] resolveAlienMove(float fromX, float fromZ,
                                      float dx, float dz,
                                      int radiusAmiga) {
        // Pas de wallCollider (cas test ou scene vide) : retour direct.
        if (wallCollider == null || wallCollider.segmentCount() == 0) {
            return new float[]{ fromX + dx, fromZ + dz };
        }
        // Conversion Amiga -> JME (rappel : x identique, z negate, /SCALE)
        float jmePx =  fromX / SCALE;
        float jmePz = -fromZ / SCALE;
        float jmeDx =  dx / SCALE;
        float jmeDz = -dz / SCALE;
        float jmeRadius = radiusAmiga / SCALE;

        float[] resolved = wallCollider.move(jmePx, jmePz, jmeDx, jmeDz, jmeRadius);

        // JME -> Amiga (z re-negate)
        float newAmigaX =  resolved[0] * SCALE;
        float newAmigaZ = -resolved[1] * SCALE;
        return new float[]{ newAmigaX, newAmigaZ };
    }
}
