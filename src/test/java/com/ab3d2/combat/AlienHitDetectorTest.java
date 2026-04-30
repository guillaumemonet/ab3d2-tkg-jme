package com.ab3d2.combat;

import com.ab3d2.core.ai.AlienBehaviour;
import com.ab3d2.core.ai.AlienDef;
import com.ab3d2.core.ai.AlienRuntimeState;
import com.ab3d2.world.AlienControlSystem;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour {@link AlienHitDetector}.
 *
 * <p>Pour eviter de creer un vrai {@link AlienControlSystem} (qui necessite
 * JME), on instancie l'AlienControlSystem mais on injecte la liste d'aliens
 * et le nodeMap via reflection. C'est OK pour un test unitaire ; en production
 * c'est l'initialize() de l'AppState qui peuple ces collections.</p>
 *
 * @since session 113 (phase 2.C)
 */
class AlienHitDetectorTest {

    /** Conversion Amiga -&gt; JME (doit matcher AlienHitDetector.SCALE). */
    private static final float SCALE = 32f;

    private AlienControlSystem mockAcs;
    private List<AlienRuntimeState> aliens;
    private Map<AlienRuntimeState, Node> nodeMap;
    private AlienHitDetector det;

    @BeforeEach
    void setUp() throws Exception {
        // Cree un AlienControlSystem sans l'initialiser (= sans JME).
        mockAcs = new AlienControlSystem(null, null, null);
        aliens = new ArrayList<>();
        nodeMap = new IdentityHashMap<>();
        injectField(mockAcs, "aliens", aliens);
        injectField(mockAcs, "nodeMap", nodeMap);
        det = new AlienHitDetector(mockAcs);
    }

    @SuppressWarnings("unchecked")
    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        // Si c'est une List existante, on la vide et on remplit avec la nouvelle
        Object existing = f.get(target);
        if (existing instanceof List<?>) {
            ((List<Object>) existing).clear();
            ((List<Object>) existing).addAll((List<Object>) value);
            return;
        }
        if (existing instanceof Map<?, ?>) {
            ((Map<Object, Object>) existing).clear();
            ((Map<Object, Object>) existing).putAll((Map<Object, Object>) value);
            return;
        }
        f.set(target, value);
    }

    /** Helper : cree un alien place a (worldX, worldZ) Amiga (Y=0 JME). */
    private AlienRuntimeState placeAlien(int slot, float amigaX, float amigaZ) {
        AlienDef def = new AlienDef(0, "TestAlien",
            0, 0, 50, 16, 0, 32, 100, 255, 16, 0, 16, 50, 0, 32, 50,
            0, 5, 128, 1, 0, -1);
        AlienRuntimeState a = new AlienRuntimeState(slot, 0);
        a.worldX = amigaX;
        a.worldZ = amigaZ;
        a.initFromDef(def);

        Node node = new Node("alien_" + slot);
        // Position JME = Amiga / SCALE (avec Z flip)
        node.setLocalTranslation(amigaX / SCALE, 0f, -amigaZ / SCALE);

        aliens.add(a);
        nodeMap.put(a, node);
        return a;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findHitByPoint
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Point au centre de l'alien : hit")
    void pointAtAlienCenter() {
        AlienRuntimeState a = placeAlien(0, 320, 320); // Amiga (10, 10) JME (10, -10)

        AlienRuntimeState hit = det.findHitByPoint(10f, 0f, -10f);

        assertSame(a, hit);
    }

    @Test
    @DisplayName("Point a 0.4 unite JME du centre : hit (rayon 0.5)")
    void pointInsideRadius() {
        AlienRuntimeState a = placeAlien(0, 0, 0);

        AlienRuntimeState hit = det.findHitByPoint(0.4f, 0f, 0f);

        assertSame(a, hit);
    }

    @Test
    @DisplayName("Point a 0.6 unite JME du centre : miss (hors rayon)")
    void pointOutsideRadius() {
        placeAlien(0, 0, 0);

        AlienRuntimeState hit = det.findHitByPoint(0.6f, 0f, 0f);

        assertNull(hit);
    }

    @Test
    @DisplayName("Point trop haut : miss")
    void pointTooHigh() {
        placeAlien(0, 0, 0);

        AlienRuntimeState hit = det.findHitByPoint(0f, 5f, 0f);

        assertNull(hit, "Y=5 > halfHeight=0.9, hors capsule");
    }

    @Test
    @DisplayName("Alien mort : pas de hit")
    void deadAlienNotHit() {
        AlienRuntimeState a = placeAlien(0, 0, 0);
        a.mode = AlienBehaviour.DIE;
        a.hitPoints = 0;

        AlienRuntimeState hit = det.findHitByPoint(0f, 0f, 0f);

        assertNull(hit, "isAlive() = false, ne doit pas etre touchable");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // findHitByRay
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ray traverse le centre de l'alien : hit")
    void rayThroughCenter() {
        placeAlien(0, 320, 0);  // alien en (10, 0) JME

        AlienHitDetector.AlienHit hit = det.findHitByRay(
            new Vector3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            20f);

        assertTrue(hit.isHit(), "ray (0,0,0) +X devrait toucher alien a (10,0,0)");
        assertEquals(10f, hit.distance(), 0.5f, "impact a ~10 unites (capsule de rayon 0.5)");
    }

    @Test
    @DisplayName("Ray rate l'alien (passe a cote) : miss")
    void rayMisses() {
        placeAlien(0, 320, 0);

        AlienHitDetector.AlienHit hit = det.findHitByRay(
            new Vector3f(0, 0, 5),     // departe a Z=5
            new Vector3f(1, 0, 0),     // direction +X
            20f);

        assertFalse(hit.isHit(), "ray a Z=5 ne doit pas toucher alien a Z=0");
    }

    @Test
    @DisplayName("Ray plus court que la distance a l'alien : miss")
    void rayTooShort() {
        placeAlien(0, 320, 0);

        AlienHitDetector.AlienHit hit = det.findHitByRay(
            new Vector3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            5f);  // alien est a 10, ray ne va qu'a 5

        assertFalse(hit.isHit());
    }

    @Test
    @DisplayName("Plusieurs aliens : on touche le plus proche")
    void rayHitsClosestAlien() {
        AlienRuntimeState near = placeAlien(0, 160, 0);  // 5 JME
        placeAlien(1, 320, 0);                           // 10 JME

        AlienHitDetector.AlienHit hit = det.findHitByRay(
            new Vector3f(0, 0, 0),
            new Vector3f(1, 0, 0),
            20f);

        assertTrue(hit.isHit());
        assertSame(near, hit.alien(), "doit toucher l'alien le plus proche (5 JME)");
    }

    @Test
    @DisplayName("Ray vertical pur : miss (capsule verticale, pas de top)")
    void verticalRayMisses() {
        placeAlien(0, 0, 0);

        AlienHitDetector.AlienHit hit = det.findHitByRay(
            new Vector3f(0, -10, 0),
            new Vector3f(0, 1, 0),     // tout vertical
            20f);

        assertFalse(hit.isHit(), "ray purement vertical ne peut pas toucher cylindre vertical");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyDamage
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("applyDamage delegue vers AlienAI.inflictDamage()")
    void applyDamageAccumulates() {
        AlienRuntimeState a = placeAlien(0, 0, 0);
        int initial = a.damageTaken;

        det.applyDamage(a, 3);

        assertEquals(initial + 3, a.damageTaken, "damageTaken doit s'accumuler");
    }
}
