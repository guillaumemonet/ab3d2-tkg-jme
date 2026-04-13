package com.ab3d2.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Collision 2D joueur ↔ murs (cercle vs segment de droite).
 *
 * Inspiré de objectmove.s (MoveObject) :
 *   - Test de côté : cross product (newPos - wallStart) × wallDir
 *   - Si négatif → collision → slide le long du mur
 *
 * Le joueur est un cercle de rayon PLAYER_RADIUS en coordonnées JME.
 * Toutes les coordonnées sont en JME (x, z) – Y est ignoré (2D).
 */
public class WallCollision {

    /** Rayon du joueur en unités JME (16 unités monde / 32 = 0.5) */
    public static final float PLAYER_RADIUS = 0.5f;

    /** Un segment de mur solide en coordonnées JME (x0,z0)→(x1,z1) */
    public record Segment(float x0, float z0, float x1, float z1) {}

    private final List<Segment> walls = new ArrayList<>();

    // ── Initialisation ────────────────────────────────────────────────────────

    public void clear() { walls.clear(); }

    public void addSegment(float x0, float z0, float x1, float z1) {
        float dx = x1-x0, dz = z1-z0;
        if (dx*dx + dz*dz > 0.0001f) walls.add(new Segment(x0, z0, x1, z1));
    }

    public int segmentCount() { return walls.size(); }

    // ── Mouvement avec collision ───────────────────────────────────────────────

    /**
     * Déplace le joueur de (px,pz) vers (px+dx, pz+dz).
     * Résout les collisions avec les murs et retourne la nouvelle position.
     *
     * @return float[]{newX, newZ}
     */
    public float[] move(float px, float pz, float dx, float dz) {
        float nx = px + dx;
        float nz = pz + dz;

        // 3 itérations pour stabilité (cas de coins / multiples murs)
        for (int iter = 0; iter < 3; iter++) {
            for (Segment seg : walls) {
                float[] r = resolve(nx, nz, seg);
                if (r != null) { nx = r[0]; nz = r[1]; }
            }
        }

        return new float[]{nx, nz};
    }

    // ── Résolution d'un mur ───────────────────────────────────────────────────

    /**
     * Si le joueur (cercle PLAYER_RADIUS en (px,pz)) touche le segment,
     * retourne la nouvelle position poussée hors du mur.
     * Retourne null si pas de collision.
     */
    private static float[] resolve(float px, float pz, Segment seg) {
        float wx = seg.x1 - seg.x0;
        float wz = seg.z1 - seg.z0;
        float len2 = wx*wx + wz*wz;
        if (len2 < 1e-6f) return null;

        // Projection de (px,pz) sur le segment → paramètre t ∈ [0,1]
        float t = ((px - seg.x0)*wx + (pz - seg.z0)*wz) / len2;
        t = Math.max(0f, Math.min(1f, t));

        // Point le plus proche sur le segment
        float cx = seg.x0 + t * wx;
        float cz = seg.z0 + t * wz;

        // Vecteur joueur → point le plus proche
        float ex = px - cx;
        float ez = pz - cz;
        float dist = (float) Math.sqrt(ex*ex + ez*ez);

        if (dist >= PLAYER_RADIUS) return null;  // pas de collision
        if (dist < 1e-5f) {
            // Joueur exactement sur le segment : pousser le long de la normale
            ex = -wz; ez = wx;  // normale perpendiculaire
            dist = (float) Math.sqrt(ex*ex + ez*ez);
        }

        // Pousser le joueur hors du mur
        float push = PLAYER_RADIUS - dist;
        float nx = px + (ex / dist) * push;
        float nz = pz + (ez / dist) * push;
        return new float[]{nx, nz};
    }
}
