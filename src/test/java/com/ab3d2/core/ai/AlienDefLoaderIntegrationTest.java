package com.ab3d2.core.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests d'integration pour {@link AlienDefLoader} qui verifient le parsing
 * du vrai fichier {@code assets/levels/definitions.json} regenere par
 * {@code ./gradlew convertLevels}.
 *
 * <p>Ces tests sont actives uniquement quand le fichier existe (= apres
 * un convertLevels). Sur CI ou apres clean, ils sont skippes.</p>
 *
 * @since session 113 (phase 2.B)
 */
class AlienDefLoaderIntegrationTest {

    /** Chemin attendu vers definitions.json (relatif au CWD du test = projet root). */
    private static final Path DEFS_PATH = Paths.get("assets/levels/definitions.json");

    /** Active les tests seulement si le fichier existe (regenere par convertLevels). */
    static boolean defsFileExists() {
        return Files.exists(DEFS_PATH);
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Charge les 20 alien defs depuis le vrai definitions.json")
    void loadsRealDefinitions() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);

        assertEquals(20, defs.length, "tableau de 20 entrees");
        long nonNull = java.util.Arrays.stream(defs).filter(d -> d != null).count();
        assertTrue(nonNull >= 19, "au moins 19 aliens definis (le 20eme = 'billy' a 0)");
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Red Alien (#0) : reactionTime=5, hitPoints=2 (charger corps-a-corps)")
    void redAlienHasExpectedValues() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        AlienDef red = defs[0];

        assertNotNull(red);
        assertEquals("Red Alien", red.name());
        assertEquals(5, red.reactionTime(), "reactionTime=5 (=0.1s : reaction tres rapide)");
        assertEquals(2, red.hitPoints(),    "fragile : 2 HP");
        assertEquals(0, red.gfxType(),      "BITMAP (= sprite ALIEN2.WAD)");
        assertFalse(red.isFlying(),         "marche au sol");
        assertFalse(red.attacksWithGun(),   "responseBeh=1 = ChargeToSide (corps-a-corps)");
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Snake Scanner (#1) : volant, gun, gfxType=VECTOR")
    void snakeScannerIsFlyingGunner() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        AlienDef snake = defs[1];

        assertNotNull(snake);
        assertTrue(snake.isFlying(),       "defaultBehaviour=1 ProwlRandomFlying");
        assertTrue(snake.attacksWithGun(), "responseBehaviour=5 AttackWithGunFlying");
        assertEquals(1, snake.gfxType(),   "VECTOR (= modele 3D snake.j3o)");
        assertEquals(7, snake.hitPoints());
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Mantis Boss (#16) : tres resistant, large, melee")
    void mantisBossIsTough() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        AlienDef mantis = defs[16];

        assertNotNull(mantis);
        assertEquals("Mantis Boss", mantis.name());
        assertEquals(125, mantis.hitPoints(), "boss costaud");
        assertEquals(2, mantis.girth(),        "large (collisionRadius=320)");
        assertEquals(320, mantis.collisionRadius());
        assertEquals(800, mantis.height(),     "tres grand");
        assertEquals(1, mantis.gfxType(),      "VECTOR (mantis.j3o)");
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Crab Boss (#18) : girth large, vector, gfxType=1")
    void crabBossIsLargeBoss() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        AlienDef crab = defs[18];

        assertNotNull(crab);
        assertEquals("Crab Boss", crab.name());
        assertEquals(125, crab.hitPoints());
        assertEquals(1, crab.gfxType(), "VECTOR (crab.j3o)");
        assertEquals(2, crab.girth());
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Tous les aliens ont reactionTime > 0 sauf billy (#19)")
    void allAliensHaveSensibleReactionTime() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        for (int i = 0; i < 19; i++) {  // 0..18, on skip 19 (billy = placeholder)
            AlienDef d = defs[i];
            if (d == null) continue;
            assertTrue(d.reactionTime() > 0,
                "alien " + i + " (" + d.name() + ") doit avoir reactionTime > 0, "
                + "actuel=" + d.reactionTime());
            assertTrue(d.hitPoints() > 0,
                "alien " + i + " (" + d.name() + ") doit avoir hitPoints > 0");
        }
    }

    @Test
    @EnabledIf("defsFileExists")
    @DisplayName("Au moins 4 aliens VECTOR (snake, wasp, mantis, crab)")
    void hasFourVectorBosses() throws Exception {
        AlienDef[] defs = AlienDefLoader.load(DEFS_PATH);
        long vectorCount = java.util.Arrays.stream(defs)
            .filter(d -> d != null && d.gfxType() == 1)
            .count();
        assertTrue(vectorCount >= 4, "Au moins 4 aliens VECTOR, trouve=" + vectorCount);
    }
}
