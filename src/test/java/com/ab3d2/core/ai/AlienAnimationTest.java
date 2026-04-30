package com.ab3d2.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour {@link AlienViewpoint} et {@link AlienAnimTable}
 * (Phase 2.E - animations sprite des aliens).
 *
 * <p>Couverture :</p>
 * <ul>
 *   <li>{@link AlienViewpoint#compute} dans les 4 quadrants principaux
 *       (alien face camera, dos, profil droit, profil gauche)</li>
 *   <li>{@link AlienAnimTable#pickFrame} pour chaque mode (DEFAULT, RESPONSE,
 *       TAKE_DAMAGE, DIE) et chaque viewpoint</li>
 *   <li>Cycle d'animation : la phase change quand timer2 augmente</li>
 *   <li>Clamp quand le WAD a moins de frames que demande</li>
 * </ul>
 *
 * @since session 113 (phase 2.E)
 */
class AlienAnimationTest {

    // -------------------------------------------------------------------------
    // AlienViewpoint.compute() : reproduit ASM ViewpointToDraw
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AlienViewpoint.compute - 4 quadrants")
    class ViewpointCompute {

        @Test
        @DisplayName("alien face a la camera (memes angles) -> AWAY")
        void sameAngleAway() {
            // Si alien et camera ont le meme angle, l'alien regarde dans le
            // meme sens que la camera = on voit son DOS.
            assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(0, 0));
            assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(1024, 1024));
        }

        @Test
        @DisplayName("alien angle oppose camera (180 deg) -> TOWARDS")
        void oppositeAngleTowards() {
            // Camera a 0 (regarde +Z), alien a 2048 (regarde -Z) :
            // l'alien regarde la camera = TOWARDS (de face).
            assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(2048, 0));
            assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(0, 2048));
        }

        @Test
        @DisplayName("alien angle 90 deg de la camera -> RIGHT (profil droit camera)")
        void perpendicularRight() {
            // Camera 0, alien 1024 (= +X) -> son cap est a droite de la camera.
            // L'alien va vers la droite de la camera = vue de profil RIGHT.
            assertEquals(AlienViewpoint.RIGHT, AlienViewpoint.compute(1024, 0));
        }

        @Test
        @DisplayName("alien angle -90 deg (3072) -> LEFT (profil gauche camera)")
        void perpendicularLeft() {
            // Camera 0, alien 3072 (= -X) -> son cap est a gauche de la camera.
            assertEquals(AlienViewpoint.LEFT, AlienViewpoint.compute(3072, 0));
        }

        @Test
        @DisplayName("ordinal() suit l'ordre ASM (TOWARDS=0, RIGHT=1, AWAY=2, LEFT=3)")
        void ordinalMatchesAsm() {
            assertEquals(0, AlienViewpoint.TOWARDS.ordinal());
            assertEquals(1, AlienViewpoint.RIGHT.ordinal());
            assertEquals(2, AlienViewpoint.AWAY.ordinal());
            assertEquals(3, AlienViewpoint.LEFT.ordinal());
        }
    }

    // -------------------------------------------------------------------------
    // AlienAnimTable.pickFrame() : selection de la frame PNG
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("AlienAnimTable.pickFrame - choix de la frame PNG")
    class PickFrame {

        @Test
        @DisplayName("DEFAULT + TOWARDS : frames 0..3 cycliques")
        void walkTowardsCycles() {
            // WALK_TICK_DIVISOR=6, donc une nouvelle frame toutes les 6 frames Amiga.
            // Phase 0 (timer2=0..5) -> frame 0
            // Phase 1 (timer2=6..11) -> frame 1
            // Phase 2 (timer2=12..17) -> frame 2
            // Phase 3 (timer2=18..23) -> frame 3
            // Cycle (timer2=24..29) -> frame 0
            assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.TOWARDS, 0, 19));
            assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.TOWARDS, 5, 19));
            assertEquals(1, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.TOWARDS, 6, 19));
            assertEquals(3, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.TOWARDS, 18, 19));
            // Cycle
            assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.TOWARDS, 24, 19));
        }

        @Test
        @DisplayName("DEFAULT + RIGHT : frames 4..7")
        void walkRightFrames4to7() {
            assertEquals(4, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.RIGHT, 0, 19));
            assertEquals(5, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.RIGHT, 6, 19));
            assertEquals(7, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.RIGHT, 18, 19));
        }

        @Test
        @DisplayName("DEFAULT + AWAY : frames 8..11")
        void walkAwayFrames8to11() {
            assertEquals(8, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.AWAY, 0, 19));
            assertEquals(11, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.AWAY, 18, 19));
        }

        @Test
        @DisplayName("DEFAULT + LEFT : frames 12..15")
        void walkLeftFrames12to15() {
            assertEquals(12, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.LEFT, 0, 19));
            assertEquals(15, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.LEFT, 18, 19));
        }

        @Test
        @DisplayName("TAKE_DAMAGE -> frame 17 (HIT_FRAME_BASE)")
        void hitFrame() {
            assertEquals(AlienAnimTable.HIT_FRAME_BASE,
                AlienAnimTable.pickFrame(AlienBehaviour.TAKE_DAMAGE,
                    AlienViewpoint.TOWARDS, 0, 19));
            assertEquals(AlienAnimTable.HIT_FRAME_BASE,
                AlienAnimTable.pickFrame(AlienBehaviour.TAKE_DAMAGE,
                    AlienViewpoint.LEFT, 100, 19));
        }

        @Test
        @DisplayName("DIE -> frame 18 (DIE_FRAME_BASE)")
        void dieFrame() {
            assertEquals(AlienAnimTable.DIE_FRAME_BASE,
                AlienAnimTable.pickFrame(AlienBehaviour.DIE,
                    AlienViewpoint.TOWARDS, 0, 19));
        }

        @Test
        @DisplayName("RESPONSE alterne entre ATTACK_FRAME (16) et walk frame")
        void responseAlternatesAttackAndWalk() {
            // ATTACK_TICK_DIVISOR=4. Phase 0,2,4,... = ATTACK_FRAME ;
            // phase 1,3,5,... = walk frame du viewpoint.
            // timer2=0..3 -> phase=0 -> ATTACK_FRAME=16
            assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
                AlienViewpoint.TOWARDS, 0, 19));
            assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
                AlienViewpoint.TOWARDS, 3, 19));
            // timer2=4..7 -> phase=1 -> walk frame du viewpoint = base
            assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
                AlienViewpoint.TOWARDS, 4, 19));
            // timer2=8..11 -> phase=2 -> ATTACK_FRAME
            assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
                AlienViewpoint.TOWARDS, 8, 19));
        }

        @Test
        @DisplayName("Clamp : si maxFrames < frame demande -> fallback viewpoint base")
        void clampToViewpointBase() {
            // WAD avec seulement 12 frames disponibles : LEFT (frames 12..15)\n
            // n'existe pas -> fallback sur viewpoint base = LEFT.ordinal*4 = 12,\n
            // mais 12 >= 12 aussi -> fallback ultime = 0.
            assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.LEFT, 0, 12));
        }

        @Test
        @DisplayName("Clamp : viewpoint AWAY (frames 8..11) avec 10 frames -> base 8")
        void clampPartialViewpoint() {
            // AWAY base = 8, max=10 -> 8 < 10 OK pour phase 0
            // mais phase 1 (frame 9) OK aussi (9 < 10)
            // phase 2 (frame 10) -> 10 >= 10 -> fallback base = 8
            assertEquals(8, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
                AlienViewpoint.AWAY, 12, 10));
        }
    }
}
