package com.ab3d2.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de {@link AlienAnimTable#pickFrame}.
 *
 * <p>Verifie le mapping (mode, viewpoint, amigaFrame, maxFrames) -&gt; index
 * de frame PNG. Les tests valident :</p>
 *
 * <ul>
 *   <li>Le cycle 4-frames de marche par viewpoint</li>
 *   <li>L'alternance attack/walk en mode RESPONSE</li>
 *   <li>Les frames fixes pour TAKE_DAMAGE / DIE</li>
 *   <li>Le clamp quand maxFrames est insuffisant</li>
 * </ul>
 *
 * @since session 113 (phase 2.E)
 */
class AlienAnimTableTest {

    /** maxFrames typique pour un alien standard (alien2 a 19 frames). */
    private static final int MAX_FRAMES = 19;

    @Test
    @DisplayName("DEFAULT + TOWARDS : cycle 0..3 puis loop")
    void walkTowardsCycles() {
        // WALK_TICK_DIVISOR=12 : la phase change toutes les 12 frames Amiga
        // amigaFrame 0..11  -> phase 0 -> frame 0
        // amigaFrame 12..23 -> phase 1 -> frame 1
        // amigaFrame 24..35 -> phase 2 -> frame 2
        // amigaFrame 36..47 -> phase 3 -> frame 3
        // amigaFrame 48..59 -> phase 4 % 4 = 0 -> frame 0 (loop)
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 0, MAX_FRAMES));
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 11, MAX_FRAMES));
        assertEquals(1, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 12, MAX_FRAMES));
        assertEquals(2, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 24, MAX_FRAMES));
        assertEquals(3, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 36, MAX_FRAMES));
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 48, MAX_FRAMES));
    }

    @Test
    @DisplayName("DEFAULT + RIGHT : frames 4..7")
    void walkRightUsesFrames4To7() {
        assertEquals(4, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.RIGHT, 0, MAX_FRAMES));
        assertEquals(5, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.RIGHT, 12, MAX_FRAMES));
        assertEquals(7, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.RIGHT, 36, MAX_FRAMES));
    }

    @Test
    @DisplayName("DEFAULT + AWAY : frames 8..11")
    void walkAwayUsesFrames8To11() {
        assertEquals(8, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.AWAY, 0, MAX_FRAMES));
        assertEquals(9, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.AWAY, 12, MAX_FRAMES));
    }

    @Test
    @DisplayName("DEFAULT + LEFT : frames 12..15")
    void walkLeftUsesFrames12To15() {
        assertEquals(12, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.LEFT, 0, MAX_FRAMES));
        assertEquals(15, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.LEFT, 36, MAX_FRAMES));
    }

    @Test
    @DisplayName("FOLLOWUP + RETREAT : meme cycle que DEFAULT (walk anim)")
    void followupAndRetreatUseWalkAnim() {
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.FOLLOWUP,
            AlienViewpoint.TOWARDS, 0, MAX_FRAMES));
        assertEquals(1, AlienAnimTable.pickFrame(AlienBehaviour.FOLLOWUP,
            AlienViewpoint.TOWARDS, 12, MAX_FRAMES));
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.RETREAT,
            AlienViewpoint.TOWARDS, 0, MAX_FRAMES));
    }

    @Test
    @DisplayName("RESPONSE : alterne entre ATTACK_FRAME (16) et walk frame 0")
    void responseAlternatesAttackAndWalk() {
        // ATTACK_TICK_DIVISOR=8 : phase change toutes les 8 frames Amiga
        // Pair = ATTACK_FRAME_BASE (16), impair = walkBase (= vp.ordinal()*4)
        // amigaFrame 0..7 -> phase 0 (pair) -> ATTACK = 16
        assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.TOWARDS, 0, MAX_FRAMES));
        // amigaFrame 8..15 -> phase 1 (impair) -> TOWARDS walk frame 0 = 0
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.TOWARDS, 8, MAX_FRAMES));
        // amigaFrame 16..23 -> phase 2 (pair) -> ATTACK
        assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.TOWARDS, 16, MAX_FRAMES));
    }

    @Test
    @DisplayName("RESPONSE + RIGHT : alterne entre 16 et walk RIGHT base (= 4)")
    void responseRightAlternatesAttackAndRightWalk() {
        assertEquals(16, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.RIGHT, 0, MAX_FRAMES));
        assertEquals(4, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.RIGHT, 8, MAX_FRAMES));
    }

    @Test
    @DisplayName("TAKE_DAMAGE : toujours frame 17 (HIT)")
    void takeDamageAlwaysHitFrame() {
        for (AlienViewpoint vp : AlienViewpoint.values()) {
            assertEquals(17, AlienAnimTable.pickFrame(AlienBehaviour.TAKE_DAMAGE,
                vp, 0, MAX_FRAMES));
            assertEquals(17, AlienAnimTable.pickFrame(AlienBehaviour.TAKE_DAMAGE,
                vp, 100, MAX_FRAMES));
        }
    }

    @Test
    @DisplayName("DIE : toujours frame 18 (DIE)")
    void dieAlwaysDieFrame() {
        for (AlienViewpoint vp : AlienViewpoint.values()) {
            assertEquals(18, AlienAnimTable.pickFrame(AlienBehaviour.DIE,
                vp, 0, MAX_FRAMES));
            assertEquals(18, AlienAnimTable.pickFrame(AlienBehaviour.DIE,
                vp, 50, MAX_FRAMES));
        }
    }

    @Test
    @DisplayName("Clamp : maxFrames=10, demande de frame 16 -> retombe sur 0")
    void clampWhenMaxFramesInsufficient() {
        // WAD avec seulement 10 frames : pas de frame 16 dispo
        // RESPONSE + TOWARDS demande 16 -> clamp : viewBase=0 (TOWARDS*4)
        // mais 0 < 10 OK -> frame 0
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.TOWARDS, 0, 10));

        // RESPONSE + LEFT demande 16 -> viewBase = LEFT*4 = 12, mais 12 > 10
        // -> retombe sur 0
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.RESPONSE,
            AlienViewpoint.LEFT, 0, 10));

        // DIE demande 18, maxFrames=10 -> viewBase=0 -> frame 0
        assertEquals(0, AlienAnimTable.pickFrame(AlienBehaviour.DIE,
            AlienViewpoint.TOWARDS, 0, 10));
    }

    @Test
    @DisplayName("Clamp partiel : maxFrames=14, LEFT walk OK, mais ATTACK clamp")
    void clampLeftWalkOkAttackFails() {
        // maxFrames=14 : LEFT walk demande 12..15. 12 et 13 OK, 14 et 15 hors-borne.
        // Pour amigaFrame=0 -> phase 0 -> frame 12, OK (< 14)
        assertEquals(12, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.LEFT, 0, 14));
        // amigaFrame 36 -> phase 3 -> frame 15, hors-borne (>= 14)
        // viewBase = 12, < 14 OK -> retombe sur 12
        assertEquals(12, AlienAnimTable.pickFrame(AlienBehaviour.DEFAULT,
            AlienViewpoint.LEFT, 36, 14));
    }

    @Test
    @DisplayName("pickFrameRaw : sans clamp, valeurs brutes")
    void pickFrameRawValues() {
        // Verifie le calcul brut sans clamp (utile en interne)
        assertEquals(0, AlienAnimTable.pickFrameRaw(AlienBehaviour.DEFAULT,
            AlienViewpoint.TOWARDS, 0));
        // amigaFrame 36 -> phase 3 (LEFT walk frame 3 = 12+3=15)
        assertEquals(15, AlienAnimTable.pickFrameRaw(AlienBehaviour.DEFAULT,
            AlienViewpoint.LEFT, 36));
        assertEquals(17, AlienAnimTable.pickFrameRaw(AlienBehaviour.TAKE_DAMAGE,
            AlienViewpoint.TOWARDS, 0));
        assertEquals(18, AlienAnimTable.pickFrameRaw(AlienBehaviour.DIE,
            AlienViewpoint.TOWARDS, 0));
    }
}
