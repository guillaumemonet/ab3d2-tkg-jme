package com.ab3d2.world;

/**
 * Utilitaires geometriques 2D (plan XZ).
 *
 * <p>Code extrait de {@link LiftControl} session 110 pour partage avec
 * {@link ZoneTracker}. Methodes statiques pures, sans etat.</p>
 *
 * <p>Convention : tous les polygones sont des tableaux plats
 * {@code [x0, z0, x1, z1, ..., xN, zN]} de longueur paire, avec au moins
 * 6 floats (3 sommets) sinon resultats indefinis.</p>
 *
 * @since session 110
 */
public final class PolygonXZ {

    private PolygonXZ() {}  // utilitaire statique

    /**
     * Test point-in-polygon par ray casting (algorithme classique de Franklin).
     *
     * <p>Compte le nombre d'aretes que coupe un rayon horizontal partant du
     * point. Pair = exterieur, impair = interieur. Robuste pour les polygones
     * convexes et concaves, ne suppose rien sur le sens (CW ou CCW).</p>
     *
     * <p>Le {@code 1e-6f} ajoute au denominateur evite la division par zero
     * pour les aretes horizontales (zi == zj).</p>
     *
     * @param xz polygone XZ aplati (taille minimum 6, doit etre paire)
     * @param px coordonnee X du point a tester
     * @param pz coordonnee Z du point a tester
     * @return true si le point est strictement a l'interieur du polygone
     */
    public static boolean pointInPolygon(float[] xz, float px, float pz) {
        if (xz == null || xz.length < 6) return false;
        int n = xz.length / 2;
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = xz[i*2], zi = xz[i*2+1];
            float xj = xz[j*2], zj = xz[j*2+1];
            boolean intersect = ((zi > pz) != (zj > pz)) &&
                (px < (xj - xi) * (pz - zi) / (zj - zi + 1e-6f) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /**
     * Distance horizontale (XZ) entre un point et un polygone.
     *
     * <p>Si le point est dans le polygone -> 0. Sinon, distance au bord le
     * plus proche (= au segment le plus proche).</p>
     *
     * @param xz polygone XZ aplati
     * @param px coordonnee X du point
     * @param pz coordonnee Z du point
     * @return distance >= 0, ou {@code Float.MAX_VALUE} si polygone invalide
     */
    public static float distanceToPolygon(float[] xz, float px, float pz) {
        if (xz == null || xz.length < 6) return Float.MAX_VALUE;
        if (pointInPolygon(xz, px, pz)) return 0f;

        float minDist = Float.MAX_VALUE;
        int n = xz.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ax = xz[i*2],   az = xz[i*2+1];
            float bx = xz[j*2],   bz = xz[j*2+1];
            float d = distanceToSegment(px, pz, ax, az, bx, bz);
            if (d < minDist) minDist = d;
        }
        return minDist;
    }

    /**
     * Distance d'un point a un segment dans le plan XZ.
     *
     * <p>Projection orthogonale du point sur le segment, clampee a [a, b],
     * puis distance euclidienne au point projete.</p>
     */
    public static float distanceToSegment(float px, float pz,
                                          float ax, float az,
                                          float bx, float bz) {
        float wx = bx - ax, wz = bz - az;
        float l2 = wx * wx + wz * wz;
        if (l2 < 1e-6f) {
            // segment degenere
            float ex = px - ax, ez = pz - az;
            return (float) Math.sqrt(ex * ex + ez * ez);
        }
        float t = Math.max(0f, Math.min(1f, ((px - ax) * wx + (pz - az) * wz) / l2));
        float cx = ax + t * wx, cz = az + t * wz;
        float ex = px - cx,     ez = pz - cz;
        return (float) Math.sqrt(ex * ex + ez * ez);
    }
}
