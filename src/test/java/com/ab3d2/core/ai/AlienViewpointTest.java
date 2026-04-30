package com.ab3d2.core.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests unitaires de {@link AlienViewpoint#compute}.
 *
 * <p>Verifie le mapping (alienAngle, cameraAngle) -&gt; vue (TOWARDS / RIGHT /
 * AWAY / LEFT) conforme a l'ASM {@code newaliencontrol.s::ViewpointToDraw}.</p>
 *
 * <p>Convention angles : 0..4095 (4096 = tour complet), 0 = +Z, sens horaire
 * vu de dessus. Donc :</p>
 * <ul>
 *   <li>angle 0    = regarde +Z</li>
 *   <li>angle 1024 = regarde +X (90deg)</li>
 *   <li>angle 2048 = regarde -Z (180deg)</li>
 *   <li>angle 3072 = regarde -X (270deg)</li>
 * </ul>
 *
 * @since session 113 (phase 2.E)
 */
class AlienViewpointTest {

    /**
     * Si l'alien et la camera regardent la meme direction (rel=0), l'alien
     * <em>tourne le dos</em> a la camera : on voit AWAY.
     *
     * <p>Pourquoi : si camera regarde +Z et alien regarde +Z aussi, la camera
     * voit le dos de l'alien (cos(0)=1 &gt; 0 -&gt; FAP / AWAY).</p>
     */
    @Test
    @DisplayName("Alien et camera dans le meme sens : AWAY")
    void sameDirectionGivesAway() {
        assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(0, 0));
        assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(1024, 1024));
        assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(2048, 2048));
    }

    /**
     * Si l'alien et la camera sont dos a dos (rel=2048 = 180deg), la camera
     * voit la face de l'alien : TOWARDS.
     */
    @Test
    @DisplayName("Alien face a la camera (oppose) : TOWARDS")
    void oppositeDirectionGivesTowards() {
        assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(2048, 0));
        assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(0, 2048));
        assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(3072, 1024));
    }

    /**
     * Si l'alien tourne a 90deg de la camera (rel=1024 ou rel=3072), on voit
     * son profil : RIGHT ou LEFT selon le sens.
     *
     * <p>Convention ASM : sin &gt; 0 -&gt; LEFT, sin &lt;= 0 -&gt; RIGHT
     * (avec rel = alienAngle - cameraAngle).</p>
     */
    @Test
    @DisplayName("Alien profil 90deg : RIGHT ou LEFT")
    void profileGivesRightOrLeft() {
        // rel=1024 (= +90deg) -> sin > 0 -> LEFT
        assertEquals(AlienViewpoint.LEFT, AlienViewpoint.compute(1024, 0));
        // rel=3072 (= -90deg = +270deg) -> sin < 0 -> RIGHT
        assertEquals(AlienViewpoint.RIGHT, AlienViewpoint.compute(3072, 0));
    }

    /**
     * Test wrap-around : un angle qui depasse 4095 doit etre traite modulo
     * (le AND 0xFFF dans la formule).
     */
    @Test
    @DisplayName("Wrap-around angle : conserve le viewpoint correct")
    void wrapAroundIsCorrect() {
        // alienAngle 4096 = 0 modulo 4096
        // cameraAngle 0 -> rel=0 -> AWAY
        assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(4096, 0));
        // alienAngle 6144 = 2048 modulo 4096
        // cameraAngle 0 -> rel=2048 -> TOWARDS
        assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(6144, 0));
    }

    /**
     * Test continuity : on tourne autour d'un alien fixe (alienAngle=0,
     * camera angle balaye 0..4095). On doit voir AWAY (0deg)
     * -&gt; LEFT (90deg) -&gt; TOWARDS (180deg) -&gt; RIGHT (270deg) -&gt; AWAY.
     *
     * <p>Note : l'ASM utilise rel = alienAngle - cameraAngle, donc quand la
     * camera passe de 0 a 1024 (90deg vers +X), rel passe de 0 a -1024 (= 3072).
     * Avec rel=3072, sin &lt; 0 -&gt; RIGHT (pas LEFT). On valide cette inversion :
     * le sens de "tour autour" perceptuel est inverse vs l'angle abs alien.</p>
     */
    @Test
    @DisplayName("Tour autour d'un alien fixe : sequence de viewpoints")
    void walkingAroundAlien() {
        int alien = 0;  // alien regarde +Z
        // cameraAngle 0 (camera regarde +Z aussi) -> rel=0 -> AWAY
        assertEquals(AlienViewpoint.AWAY, AlienViewpoint.compute(alien, 0));
        // cameraAngle 1024 (cam regarde +X) -> rel=-1024=3072 -> RIGHT
        assertEquals(AlienViewpoint.RIGHT, AlienViewpoint.compute(alien, 1024));
        // cameraAngle 2048 (cam regarde -Z) -> rel=2048 -> TOWARDS
        assertEquals(AlienViewpoint.TOWARDS, AlienViewpoint.compute(alien, 2048));
        // cameraAngle 3072 (cam regarde -X) -> rel=-3072=1024 -> LEFT
        assertEquals(AlienViewpoint.LEFT, AlienViewpoint.compute(alien, 3072));
    }

    /**
     * Test ordinaux : verifie que l'ordinal de l'enum suit l'ordre ASM
     * (TOWARDSFRAME=0, RIGHTFRAME=1, AWAYFRAME=2, LEFTFRAME=3) car ils sont
     * utilises comme index dans {@link AlienAnimTable#pickFrame}.
     */
    @Test
    @DisplayName("Ordinaux conformes a l'ordre ASM")
    void ordinalsMatchAsm() {
        assertEquals(0, AlienViewpoint.TOWARDS.ordinal());
        assertEquals(1, AlienViewpoint.RIGHT.ordinal());
        assertEquals(2, AlienViewpoint.AWAY.ordinal());
        assertEquals(3, AlienViewpoint.LEFT.ordinal());
    }
}
