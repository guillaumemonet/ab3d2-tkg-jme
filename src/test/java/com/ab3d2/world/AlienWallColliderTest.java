package com.ab3d2.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de {@link AlienWallCollider}.
 *
 * <p>Verifie le test cercle&nbsp;vs&nbsp;segment 2D et le slide-along-wall
 * pour differents rayons d'aliens (girth 0/1/2 = 80/160/320 unites Amiga,
 * soit 2.5/5.0/10.0 en JME).</p>
 *
 * @since session 113 (phase 2.F)
 */
class AlienWallColliderTest {

    /** Rayon JME pour un alien standard (girth=1) : 160/32 = 5.0 */
    private static final float RADIUS_NORMAL = 5.0f;

    /** Rayon JME pour un alien mince (girth=0) : 80/32 = 2.5 */
    private static final float RADIUS_THIN = 2.5f;

    @Test
    @DisplayName("Aucun mur : retourne directement la position cible")
    void noWallsPassThrough() {
        AlienWallCollider c = new AlienWallCollider(List.of());
        float[] r = c.move(0, 0, 10, 0, RADIUS_NORMAL);
        assertEquals(10f, r[0], 0.01f);
        assertEquals(0f, r[1], 0.01f);
    }

    @Test
    @DisplayName("Mur perpendiculaire au mouvement : alien bloque a distance radius")
    void wallBlocksMovement() {
        // Mur vertical en X=10, de Z=-10 a Z=10
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(10, -10, 10, 10)
        ));
        // Alien en (0,0) qui veut aller a (20,0) - traverserait le mur
        float[] r = c.move(0, 0, 20, 0, RADIUS_NORMAL);
        // Resultat attendu : pousse a (10 - radius, 0) = (5, 0)
        assertEquals(10f - RADIUS_NORMAL, r[0], 0.1f,
            "alien doit etre pousse a 'radius' du mur");
        assertEquals(0f, r[1], 0.1f);
    }

    @Test
    @DisplayName("Slide-along-wall : alien glisse le long quand il arrive en biais")
    void slideAlongWall() {
        // Mur vertical en X=10
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(10, -100, 10, 100)
        ));
        // Alien en (0,0) qui veut aller en (20, 5) -> en biais vers le mur
        float[] r = c.move(0, 0, 20, 5, RADIUS_NORMAL);
        // X est borne par radius, mais Z avance
        assertTrue(r[0] <= 10f - RADIUS_NORMAL + 0.5f, "X ne doit pas traverser le mur");
        assertTrue(r[1] > 4f, "Z doit avoir avance (slide-along-wall)");
    }

    @Test
    @DisplayName("Mouvement diagonal contre coin : pas de NaN, position finie")
    void diagonalIntoCornerProducesFiniteResult() {
        // Coin en L : mur a X=10 et mur a Z=10
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(10, 0, 10, 20),
            new AlienWallCollider.Segment(0, 10, 20, 10)
        ));
        float[] r = c.move(0, 0, 20, 20, RADIUS_NORMAL);
        // Resultat doit etre fini (pas NaN ni Infinity)
        assertTrue(Float.isFinite(r[0]), "X doit etre fini");
        assertTrue(Float.isFinite(r[1]), "Z doit etre fini");
        // L'alien doit etre pousse hors des deux murs
        assertTrue(r[0] <= 10f - RADIUS_NORMAL + 0.5f);
        assertTrue(r[1] <= 10f - RADIUS_NORMAL + 0.5f);
    }

    @Test
    @DisplayName("Mur degenere (longueur nulle) : ignore (pas de division par 0)")
    void degenerateSegmentIgnored() {
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(5, 5, 5, 5) // longueur 0
        ));
        // Doit passer comme si pas de mur
        float[] r = c.move(0, 0, 10, 0, RADIUS_NORMAL);
        assertEquals(10f, r[0], 0.01f);
    }

    @Test
    @DisplayName("Alien deja exactement sur un mur : se fait pousser le long de la normale")
    void alienOnWallIsPushedAlongNormal() {
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(0, -10, 0, 10)
        ));
        // Alien pile sur (0,0) qui ne bouge pas
        float[] r = c.move(0, 0, 0, 0, RADIUS_NORMAL);
        // Doit etre pousse en X (la normale au mur vertical = (1, 0) ou (-1, 0))
        assertTrue(Math.abs(r[0]) >= RADIUS_NORMAL - 0.5f,
            "alien sur mur doit etre pousse de radius en X (ici " + r[0] + ")");
        assertEquals(0f, r[1], 0.01f, "Z reste inchange");
    }

    @Test
    @DisplayName("Mouvement parallele au mur : pas de blocage")
    void parallelMovementNotBlocked() {
        // Mur en X=10
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(10, -100, 10, 100)
        ));
        // Alien en (5, 0) (a distance radius du mur), bouge en +Z
        float[] r = c.move(5, 0, 0, 5, RADIUS_NORMAL);
        // Pas de blocage Z
        assertEquals(5f, r[1], 0.1f);
        assertEquals(5f, r[0], 0.1f);
    }

    @Test
    @DisplayName("3 iterations de resolution : stabilite sur configuration multi-murs")
    void multiWallsStability() {
        // 4 murs formant une boite : X=0, X=10, Z=0, Z=10
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(0, 0, 0, 10),
            new AlienWallCollider.Segment(10, 0, 10, 10),
            new AlienWallCollider.Segment(0, 0, 10, 0),
            new AlienWallCollider.Segment(0, 10, 10, 10)
        ));
        // Alien dans la boite, voudrait sortir a -100,-100 mais reste coince
        float[] r = c.move(5, 5, -100, -100, RADIUS_NORMAL);
        // Doit etre pousse aux radius des 4 murs
        assertTrue(r[0] >= RADIUS_NORMAL - 0.5f, "X doit etre >= radius (mur X=0)");
        assertTrue(r[1] >= RADIUS_NORMAL - 0.5f, "Z doit etre >= radius (mur Z=0)");
        assertTrue(Float.isFinite(r[0]) && Float.isFinite(r[1]),
            "resultat fini meme apres iterations multiples");
    }

    @Test
    @DisplayName("Petit rayon (girth=0) : peut passer plus pres des murs")
    void thinAlienPushedLessFar() {
        AlienWallCollider c = new AlienWallCollider(List.of(
            new AlienWallCollider.Segment(10, -100, 10, 100)
        ));
        // Meme position de depart, meme delta, mais radius different
        float[] thin   = c.move(0, 0, 20, 0, RADIUS_THIN);
        float[] normal = c.move(0, 0, 20, 0, RADIUS_NORMAL);
        // L'alien mince est pousse a (10 - 2.5) = 7.5
        // L'alien normal est pousse a (10 - 5.0) = 5.0
        assertTrue(thin[0] > normal[0],
            "alien mince doit etre plus pres du mur que l'alien normal");
        assertEquals(10f - RADIUS_THIN, thin[0], 0.1f);
        assertEquals(10f - RADIUS_NORMAL, normal[0], 0.1f);
    }
}
