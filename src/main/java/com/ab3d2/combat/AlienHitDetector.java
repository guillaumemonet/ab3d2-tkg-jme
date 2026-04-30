package com.ab3d2.combat;

import com.ab3d2.core.ai.AlienAI;
import com.ab3d2.core.ai.AlienRuntimeState;
import com.ab3d2.world.AlienControlSystem;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

/**
 * Service de detection de collision tir-vs-alien.
 *
 * <p>Modelise chaque alien comme une <b>capsule verticale</b> (axe Y) :</p>
 * <ul>
 *   <li>Centre XZ = {@code (worldX/32, -worldZ/32)} en unites JME</li>
 *   <li>Centre Y  = {@code spriteNode.getLocalTranslation().y} (= spawnY)</li>
 *   <li>Rayon     = 0.5 unite JME (= ~16 unites Amiga)</li>
 *   <li>Demi-hauteur = 0.9 unite JME (= ~30 unites Amiga, soit ~1m80 ingame)</li>
 * </ul>
 *
 * <p>Note : on n'utilise pas {@code AlienDef.collisionRadius()} qui donne 80,
 * 160 ou 320 unites Amiga (= 2.5, 5, 10 unites JME). Cette valeur est utilisee
 * dans l'ASM pour la collision murs (pour empecher les aliens de se coincer
 * dans les coins), pas pour le hit testing. Les balles doivent toucher le
 * <em>corps</em> de l'alien, pas son rayon de "girth".</p>
 *
 * <h2>Equivalent ASM</h2>
 *
 * <p>Routines portees :</p>
 * <ul>
 *   <li>{@code newaliencontrol.s::HasItHitAnAlien} : iteration sur tous les
 *       aliens vivants pour tester la proximite avec une bullet.</li>
 *   <li>{@code newplayershoot.s::plr1_HitscanSucceded} : detection de cible
 *       hitscan (point-in-capsule sur la trajectoire raycast).</li>
 * </ul>
 *
 * <h2>Limitations 2.C</h2>
 *
 * <ul>
 *   <li>Pas de bias d'auto-aim vertical (l'ASM autorise un peu de tolerance
 *       Y pour faciliter le tir, on utilise la capsule rigide ici).</li>
 *   <li>Bullet de splash damage (rocket, grenade) : on detecte juste le hit
 *       direct, pas le rayon d'explosion. La 2.D ajoutera l'AOE.</li>
 *   <li>Friendly fire : on ne distingue pas les aliens "alliés" (PLAYER1 def
 *       est un alien controle par l'IA). Tous sont touchables.</li>
 * </ul>
 *
 * @since session 113 (phase 2.C)
 */
public final class AlienHitDetector {

    /** Conversion Amiga -&gt; JME. */
    private static final float SCALE = 32f;

    /** Rayon de la capsule alien en unites JME (= 16 unites Amiga). */
    private static final float ALIEN_HIT_RADIUS = 0.5f;

    /** Demi-hauteur de la capsule alien en unites JME. */
    private static final float ALIEN_HIT_HALF_HEIGHT = 0.9f;

    private final AlienControlSystem alienSystem;

    public AlienHitDetector(AlienControlSystem alienSystem) {
        this.alienSystem = alienSystem;
    }

    /**
     * Resultat d'une detection de hit.
     *
     * @param alien    l'alien touche (ou {@code null} si miss)
     * @param impact   point d'impact en coords JME (peut etre null si miss)
     * @param distance distance de l'origine au point d'impact (en unites JME)
     */
    public record AlienHit(AlienRuntimeState alien, Vector3f impact, float distance) {
        public boolean isHit() { return alien != null; }
        public static AlienHit miss() { return new AlienHit(null, null, Float.POSITIVE_INFINITY); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detection par point (utilisee par les projectiles)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Teste si un point en coords JME est a l'interieur de la capsule d'un
     * alien vivant. Retourne le premier alien touche (pas de tri par distance,
     * car les bullets sont petites donc 1 alien max touche en pratique).
     *
     * @param jx position JME X de la bullet
     * @param jy position JME Y
     * @param jz position JME Z
     * @return l'alien touche, ou {@code null} si aucun
     */
    public AlienRuntimeState findHitByPoint(float jx, float jy, float jz) {
        if (alienSystem == null) return null;
        float r2 = ALIEN_HIT_RADIUS * ALIEN_HIT_RADIUS;

        for (AlienRuntimeState a : alienSystem.getAliens()) {
            if (!a.isAlive()) continue;

            // Position alien en JME
            float ax = a.worldX / SCALE;
            float az = -a.worldZ / SCALE;

            float dx = jx - ax;
            float dz = jz - az;
            if (dx * dx + dz * dz > r2) continue; // hors du cylindre XZ

            // Test Y : recupere la position Y du Node JME (= spawnY courant)
            Node node = alienSystem.getNodeFor(a);
            if (node != null) {
                float ay = node.getLocalTranslation().y;
                if (Math.abs(jy - ay) > ALIEN_HIT_HALF_HEIGHT) continue;
            }
            // hit !
            return a;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detection par rayon (utilisee par les hitscan)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lance un rayon de {@code from} dans {@code direction} (normalisee) sur
     * une distance maximale {@code maxDistance} et retourne le premier alien
     * vivant qu'il intersecte.
     *
     * <p>Approximation : on teste l'intersection ray-vs-cylinder (axe Y) en
     * resolvant le polynome quadratique de la projection XZ :</p>
     *
     * <pre>
     *   |O.xz + t*D.xz - C.xz|^2 = r^2
     *   t^2*(Dx^2+Dz^2) + 2t*((Ox-Cx)*Dx + (Oz-Cz)*Dz) + ((Ox-Cx)^2+(Oz-Cz)^2 - r^2) = 0
     * </pre>
     *
     * <p>On prend la racine positive la plus petite, puis on verifie que le
     * point d'impact est dans la plage Y de la capsule.</p>
     *
     * @return resultat de la detection (peut etre {@link AlienHit#miss()})
     */
    public AlienHit findHitByRay(Vector3f from, Vector3f direction, float maxDistance) {
        if (alienSystem == null) return AlienHit.miss();

        float bestT = maxDistance;
        AlienRuntimeState bestAlien = null;

        float ox = from.x, oy = from.y, oz = from.z;
        float dx = direction.x, dy = direction.y, dz = direction.z;

        // a = Dx^2 + Dz^2. Si nul (rayon vertical pur), pas d'intersection
        // possible car on n'a pas de "top" hit pour la capsule verticale.
        float a = dx * dx + dz * dz;
        if (a < 1e-6f) return AlienHit.miss();

        for (AlienRuntimeState alien : alienSystem.getAliens()) {
            if (!alien.isAlive()) continue;

            float ax = alien.worldX / SCALE;
            float az = -alien.worldZ / SCALE;

            // Resoudre at^2 + bt + c = 0 pour le cylindre XZ
            float ex = ox - ax;
            float ez = oz - az;
            float b = 2f * (ex * dx + ez * dz);
            float c = ex * ex + ez * ez
                    - ALIEN_HIT_RADIUS * ALIEN_HIT_RADIUS;
            float disc = b * b - 4f * a * c;
            if (disc < 0f) continue; // pas d'intersection

            float sqrtD = (float) Math.sqrt(disc);
            // Plus petite racine positive (= entree dans le cylindre)
            float t = (-b - sqrtD) / (2f * a);
            if (t < 0f) {
                // L'origine est dans le cylindre : pas un hit valide pour un
                // tir (eviter de se tirer dessus).
                t = (-b + sqrtD) / (2f * a);
                if (t < 0f) continue;
            }
            if (t >= bestT) continue; // un alien plus proche est deja trouve

            // Verifier que le point d'impact est dans la plage Y de la capsule
            float impactY = oy + t * dy;
            Node node = alienSystem.getNodeFor(alien);
            if (node != null) {
                float centerY = node.getLocalTranslation().y;
                if (Math.abs(impactY - centerY) > ALIEN_HIT_HALF_HEIGHT) continue;
            }

            bestT = t;
            bestAlien = alien;
        }

        if (bestAlien == null) return AlienHit.miss();
        Vector3f impact = new Vector3f(
            ox + bestT * dx,
            oy + bestT * dy,
            oz + bestT * dz);
        return new AlienHit(bestAlien, impact, bestT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Application des degats (delegate vers AlienAI)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inflige des degats a un alien. Equivaut a {@code add.b damage,
     * EntT_DamageTaken_b(a0)} dans l'ASM. Les degats seront traites au
     * prochain {@link AlienAI#update} (transition DIE / RESPONSE / TAKE_DAMAGE
     * selon la regle 75/25).
     *
     * @param alien  l'alien cible (doit etre vivant ; sinon no-op)
     * @param damage nombre de HP a infliger
     */
    public void applyDamage(AlienRuntimeState alien, int damage) {
        AlienAI.inflictDamage(alien, damage);
    }
}
