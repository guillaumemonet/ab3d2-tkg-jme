package com.ab3d2.combat;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.math.Vector3f;

import java.util.List;

/**
 * Service de raycast contre la geometrie du niveau (murs, sols, plafonds).
 *
 * <p>Utilise Bullet Physics (Minie) via <code>PhysicsSpace.rayTest()</code> qui
 * est deja en place pour les collisions du joueur. Le resultat est la distance
 * de l'impact et la normale de la surface touchee.</p>
 *
 * <p>Equivalent des routines ASM :</p>
 * <ul>
 *   <li><code>HitAWall</code> (detection de collision murale pour les bullets)</li>
 *   <li><code>plr1_HitscanSucceded</code> / <code>plr1_HitscanFailed</code>
 *       (pour les hitscans qui testent ligne-de-visee + impact wall)</li>
 * </ul>
 *
 * <p>Simplifications vs ASM : on ne fait pas la distinction
 * <code>HitAWallUp</code>/<code>HitAWallDown</code> (collision sol vs plafond).
 * Bullet nous donne directement la normale, qu'on peut utiliser pour deduire
 * le type de surface si besoin (normal.y &gt; 0.7 = sol, &lt; -0.7 = plafond,
 * sinon mur).</p>
 */
public final class WorldRaycaster {

    /** Epsilon pour recule legerement l'impact par rapport a la surface. */
    public static final float IMPACT_BACKOFF = 0.02f;

    /**
     * Resultat d'un raycast : point d'impact + normale + distance.
     *
     * <p>Si <code>hit = false</code>, les autres champs sont indetermines.</p>
     */
    public static final class RayHit {
        public final boolean  hit;
        public final Vector3f impactPoint;
        public final Vector3f normal;
        public final float    distance;

        public RayHit(boolean hit, Vector3f impactPoint, Vector3f normal, float distance) {
            this.hit         = hit;
            this.impactPoint = impactPoint;
            this.normal      = normal;
            this.distance    = distance;
        }

        public static RayHit miss() {
            return new RayHit(false, null, null, Float.POSITIVE_INFINITY);
        }

        /**
         * Indique si la surface touchee est un sol (normale vers le haut).
         * Seuil 0.7 ~ 45 degres max d'inclinaison pour compter comme sol.
         */
        public boolean isFloor() {
            return hit && normal != null && normal.y > 0.7f;
        }

        /** Indique si la surface est un plafond (normale vers le bas). */
        public boolean isCeiling() {
            return hit && normal != null && normal.y < -0.7f;
        }

        /** Indique si la surface est un mur (normale quasi-horizontale). */
        public boolean isWall() {
            return hit && normal != null && Math.abs(normal.y) <= 0.7f;
        }
    }

    private final PhysicsSpace physicsSpace;

    public WorldRaycaster(PhysicsSpace physicsSpace) {
        this.physicsSpace = physicsSpace;
    }

    /**
     * Lance un rayon de <code>from</code> dans <code>direction</code> sur une
     * distance maximale <code>maxDistance</code>. Retourne le premier hit
     * rencontre (le plus proche de <code>from</code>).
     *
     * @param from        origine du rayon (unites JME)
     * @param direction   direction normalisee
     * @param maxDistance distance maximale du rayon
     * @return resultat du raycast (hit=false si rien touche)
     */
    public RayHit castRay(Vector3f from, Vector3f direction, float maxDistance) {
        if (physicsSpace == null) return RayHit.miss();

        Vector3f to = from.add(direction.mult(maxDistance));
        List<PhysicsRayTestResult> results = physicsSpace.rayTest(from, to);
        if (results.isEmpty()) return RayHit.miss();

        // Trouve le hit le plus proche (Minie ne trie pas toujours).
        PhysicsRayTestResult closest = null;
        float closestFraction = Float.POSITIVE_INFINITY;
        for (PhysicsRayTestResult r : results) {
            float f = r.getHitFraction();
            if (f < closestFraction) {
                closestFraction = f;
                closest = r;
            }
        }
        if (closest == null) return RayHit.miss();

        // Interpole le point d'impact le long du rayon
        float fraction = closest.getHitFraction();
        Vector3f impact = from.clone().interpolateLocal(to, fraction);

        // Recule legerement l'impact vers l'origine pour eviter que les
        // effets visuels (flash, sprite d'explosion) soient z-fightes dans
        // le mur.
        Vector3f normal = closest.getHitNormalLocal(new Vector3f());
        impact.addLocal(normal.mult(IMPACT_BACKOFF));

        float distance = from.distance(impact);
        return new RayHit(true, impact, normal, distance);
    }
}
