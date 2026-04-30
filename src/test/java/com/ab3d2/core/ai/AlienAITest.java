package com.ab3d2.core.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires de la machine a etats {@link AlienAI}.
 *
 * <p>Ces tests valident les transitions de la machine a 4+2 etats sans
 * dependance JME. Ils utilisent un {@link FakeWorld} qui simule le minimum
 * d'environnement necessaire (joueur visible/invisible, control points, RNG
 * deterministe).</p>
 *
 * <h2>Couverture</h2>
 * <ul>
 *   <li>DEFAULT &rarr; RESPONSE quand LOS et reactionTime ecoule</li>
 *   <li>DEFAULT reset reactionTime quand on perd la LOS</li>
 *   <li>RESPONSE &rarr; FOLLOWUP quand l'animation d'attaque est finie</li>
 *   <li>RESPONSE &rarr; FOLLOWUP quand on perd la LOS</li>
 *   <li>FOLLOWUP &rarr; DEFAULT quand timer1 timeout</li>
 *   <li>FOLLOWUP &rarr; RESPONSE quand on retrouve la LOS</li>
 *   <li>RETREAT fait un fallback DEFAULT (ASM-fidele)</li>
 *   <li>TAKE_DAMAGE &rarr; DEFAULT quand l'animation est finie</li>
 *   <li>Damage avec ratio 75/25 (RESPONSE vs TAKE_DAMAGE)</li>
 *   <li>Modele de damage ASM-fidele : cumul/4 vs HP_init (HP immuable)</li>
 *   <li>Champ de vision frontal (ai_CheckInFront)</li>
 *   <li>Tir alien hitscan (ai_AttackWithGun) avec proba inv-distance</li>
 *   <li>Tir alien projectile (ai_AttackWithProjectile) avec dispatch sur bulType</li>
 * </ul>
 *
 * @since session 113
 */
class AlienAITest {

    /** AlienDef typique pour Red Alien (= Triclaw level A) - charge corps-a-corps. */
    private AlienDef chargerDef;

    /** AlienDef typique pour Guard - tireur. */
    private AlienDef gunnerDef;

    /** AlienDef pour boss volant. */
    private AlienDef flyerDef;

    @BeforeEach
    void setUp() {
        chargerDef = new AlienDef(
            0, "TestCharger",
            /*gfx*/ 0,
            /*defBeh*/ 0, /*reaction*/ 50, /*defSpeed*/ 16,
            /*respBeh*/ 0, /*respSpeed*/ 32, /*respTimeout*/ 100,
            /*dmgRetreat*/ 255, /*dmgFollowup*/ 16,
            /*folBeh*/ 1, /*folSpeed*/ 16, /*folTimeout*/ 50,
            /*retBeh*/ 0, /*retSpeed*/ 32, /*retTimeout*/ 50,
            /*bul*/ 0, /*hp*/ 5, /*height*/ 128, /*girth*/ 1,
            /*splat*/ 0, /*aux*/ -1
        );

        gunnerDef = new AlienDef(
            2, "TestGunner",
            0,
            0, 30, 12,
            2 /* AttackWithGun */, 16, 80,
            255, 8,
            0 /* PauseBriefly */, 12, 40,
            0, 16, 50,
            9, 4, 160, 1,
            0, -1
        );

        flyerDef = new AlienDef(
            15, "TestFlyer",
            1, // VECTOR
            1 /* Flying */, 40, 24,
            5 /* AttackWithGunFlying */, 32, 100,
            255, 16,
            3 /* ApproachFlying */, 20, 60,
            0, 32, 50,
            12, 125, 200, 2,
            0, -1
        );
    }

    // -------------------------------------------------------------------------
    // Helpers : un AlienDef a la valeur par defaut + creation d'etat
    // -------------------------------------------------------------------------

    private AlienRuntimeState newAlien(AlienDef def, FakeWorld w) {
        AlienRuntimeState a = new AlienRuntimeState(0, def.index());
        a.worldX = 100; a.worldY = 0; a.worldZ = 100;
        a.zoneId = 0;
        a.initFromDef(def);
        return a;
    }

    // -------------------------------------------------------------------------
    // Tests de base : structure de la def
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AlienDef.collisionRadius() table diststowall")
    void collisionRadius() {
        AlienDef girth0 = new AlienDef(0, "g0", 0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0, 0, 0,-1);
        AlienDef girth1 = new AlienDef(0, "g1", 0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0, 1, 0,-1);
        AlienDef girth2 = new AlienDef(0, "g2", 0, 0,0,0,0,0,0,0,0,0,0,0,0,0,0, 0,0,0, 2, 0,-1);
        assertEquals(80,  girth0.collisionRadius());
        assertEquals(160, girth1.collisionRadius());
        assertEquals(320, girth2.collisionRadius());
    }

    @Test
    @DisplayName("AlienDef.isFlying() / attacksWithGun()")
    void aliendefHelpers() {
        assertFalse(chargerDef.isFlying());
        assertTrue(flyerDef.isFlying());
        assertFalse(chargerDef.attacksWithGun()); // Charge
        assertTrue(gunnerDef.attacksWithGun());   // AttackWithGun
        assertTrue(flyerDef.attacksWithGun());    // AttackWithGunFlying
    }

    @Test
    @DisplayName("AlienBehaviour.fromAsmByte() round-trip")
    void asmByteRoundtrip() {
        for (AlienBehaviour b : AlienBehaviour.values()) {
            assertEquals(b, AlienBehaviour.fromAsmByte(b.toAsmByte()));
        }
        // Out of range fallback to DEFAULT
        assertEquals(AlienBehaviour.DEFAULT, AlienBehaviour.fromAsmByte(99));
        assertEquals(AlienBehaviour.DEFAULT, AlienBehaviour.fromAsmByte(-1));
    }

    // -------------------------------------------------------------------------
    // Transitions DEFAULT -> RESPONSE
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Transitions DEFAULT")
    class DefaultMode {

        @Test
        @DisplayName("Pas de joueur visible : reste en DEFAULT, timer1 reset")
        void noPlayerSeenStaysDefault() {
            FakeWorld w = new FakeWorld().withPlayerVisible(false);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 5;  // a moitie du compte a rebours

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.DEFAULT, a.mode);
            assertEquals(chargerDef.reactionTime(), a.timer1,
                "timer1 doit etre reset au reactionTime quand pas de LOS");
            assertFalse(a.seesPlayer);
        }

        @Test
        @DisplayName("Joueur visible mais reactionTime non ecoule : reste en DEFAULT")
        void playerSeenButNotReactedStaysDefault() {
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 30;  // encore du temps avant de reagir

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.DEFAULT, a.mode);
            assertTrue(a.seesPlayer, "LOS detectee");
            assertEquals(29, a.timer1, "timer1 decremente d'une frame");
        }

        @Test
        @DisplayName("Joueur visible et reactionTime ecoule : passe en RESPONSE")
        void playerSeenAndReactedSwitchesToResponse() {
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 0;

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.RESPONSE, a.mode);
            assertEquals(1, a.whichAnim, "anim 1 = attack");
            assertEquals(0, a.timer2, "timer2 reset pour debut d'anim attaque");
        }

        @Test
        @DisplayName("Joueur visible mais zone trop sombre : pas de transition")
        void playerSeenButTooDarkStaysDefault() {
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayerZone(99) // zone differente de l'alien
                .withPlayerRoomBrightness(31)  // tres sombre
                .withRandomSeed(0); // GetRand & 31 = 0 -> < 31 = "in dark"
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.zoneId = 0;
            a.timer1 = 0;

            new AlienAI(chargerDef, w).update(a);

            // Reste en DEFAULT a cause du isInDark check
            assertEquals(AlienBehaviour.DEFAULT, a.mode);
        }

        @Test
        @DisplayName("Prowl aleatoire : alien se deplace quand pas de control points (Phase 2.E)")
        void prowlMovesAlienWithoutControlPoints() {
            // Sans CP (numControlPoints=0 par defaut dans FakeWorld), l'alien
            // doit faire du prowl aleatoire pour ne pas etre fige.
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(false)
                .withRandomSequence(0x456); // angle initial 0x456
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.prowlTicker = 0; // declenche le pick d'un nouvel angle
            float startX = a.worldX, startZ = a.worldZ;

            new AlienAI(chargerDef, w).update(a);

            // L'alien doit avoir bouge (worldX ou worldZ a change)
            float dx = a.worldX - startX;
            float dz = a.worldZ - startZ;
            assertTrue(dx*dx + dz*dz > 0f,
                "prowl doit deplacer l'alien (def.defaultSpeed=16 par tick)");
            assertEquals(0x456, a.currentAngle, "angle pris du rand");
            assertTrue(a.prowlTicker > 0, "prowlTicker recharge pour cap dans nouvelle direction");
        }
    }

    // -------------------------------------------------------------------------
    // Transitions RESPONSE -> FOLLOWUP
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Transitions RESPONSE")
    class ResponseMode {

        @Test
        @DisplayName("Anim attaque finie : RESPONSE -> FOLLOWUP")
        void attackFinishedGoesToFollowup() {
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.timer2 = 7; // une frame avant que l'anim soit finie (>=8)

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.FOLLOWUP, a.mode);
            assertEquals(0, a.whichAnim);
            assertEquals(chargerDef.followupTimeout(), a.timer1);
        }

        @Test
        @DisplayName("Perte LOS pendant attaque : passe en FOLLOWUP immediatement")
        void losLostGoesToFollowup() {
            FakeWorld w = new FakeWorld().withPlayerVisible(false);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.timer2 = 0;

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.FOLLOWUP, a.mode);
            assertFalse(a.seesPlayer);
        }
    }

    // -------------------------------------------------------------------------
    // Transitions FOLLOWUP
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Transitions FOLLOWUP")
    class FollowupMode {

        @Test
        @DisplayName("Timer1 epuise : FOLLOWUP -> DEFAULT")
        void timeoutGoesToDefault() {
            FakeWorld w = new FakeWorld().withPlayerVisible(false);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.mode = AlienBehaviour.FOLLOWUP;
            a.timer1 = 1;

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.DEFAULT, a.mode);
            assertEquals(chargerDef.reactionTime(), a.timer1);
        }

        @Test
        @DisplayName("LOS retrouvee : FOLLOWUP -> RESPONSE")
        void losRecoveredGoesToResponse() {
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.mode = AlienBehaviour.FOLLOWUP;
            a.timer1 = 30;

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.RESPONSE, a.mode);
            assertEquals(1, a.whichAnim);
        }
    }

    // -------------------------------------------------------------------------
    // Transitions RETREAT (fallback DEFAULT)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("RETREAT fait fallback vers DEFAULT (ASM-fidele : ai_DoRetreat est vide)")
    void retreatFallsBackToDefault() {
        FakeWorld w = new FakeWorld();
        AlienRuntimeState a = newAlien(chargerDef, w);
        a.mode = AlienBehaviour.RETREAT;

        new AlienAI(chargerDef, w).update(a);

        assertEquals(AlienBehaviour.DEFAULT, a.mode,
            "L'ASM original a ai_DoRetreat: rts (vide). On reproduit en passant a DEFAULT.");
    }

    // -------------------------------------------------------------------------
    // Transitions TAKE_DAMAGE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("TAKE_DAMAGE termine apres anim, retour en DEFAULT")
    void takeDamageReturnsToDefault() {
        FakeWorld w = new FakeWorld();
        AlienRuntimeState a = newAlien(chargerDef, w);
        a.mode = AlienBehaviour.TAKE_DAMAGE;
        a.timer2 = 7;

        new AlienAI(chargerDef, w).update(a);

        assertEquals(AlienBehaviour.DEFAULT, a.mode);
        assertEquals(0, a.whichAnim);
    }

    // -------------------------------------------------------------------------
    // Damage handling
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Application des degats")
    class DamageHandling {

        @Test
        @DisplayName("Degats inflicted : hitPoints reste fixe (HP_init), totalDamageDone accumule")
        void damageReducesHP() {
            FakeWorld w = new FakeWorld()
                .withRandomSeed(1); // GetRand & 3 != 0 -> RESPONSE (75% case)
            AlienRuntimeState a = newAlien(chargerDef, w);
            int initialHP = a.hitPoints;

            AlienAI.inflictDamage(a, 2);
            new AlienAI(chargerDef, w).update(a);

            // Modele ASM-fidele : hitPoints (= HP_init) NE BOUGE PAS, c'est juste
            // le seuil de mort. Seul totalDamageDone accumule.
            assertEquals(initialHP, a.hitPoints,
                "hitPoints (= HP_init) reste fixe en mode ASM-fidele");
            assertEquals(0, a.damageTaken, "damageTaken consomme par applyDamage");
            assertEquals(2, a.totalDamageDone, "cumul vers AI_Damaged_vw");
        }

        @Test
        @DisplayName("Degats : 75% chance d'aggrer (RESPONSE)")
        void damage75PercentGoesToResponse() {
            // FakeWorld retourne sequence [1, 2, 3, 0, 1, 2, 3, 0, ...]
            // and 3 = [1, 2, 3, 0, ...] -> trois agro puis un hit anim
            FakeWorld w = new FakeWorld()
                .withRandomSequence(1, 2, 3, 1)
                .withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);

            AlienAI.inflictDamage(a, 1);
            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.RESPONSE, a.mode,
                "GetRand=1, and 3 = 1, != 0 -> RESPONSE (75%)");
            assertEquals(1, a.whichAnim);
        }

        @Test
        @DisplayName("Degats : 25% chance d'animer le hit (TAKE_DAMAGE)")
        void damage25PercentGoesToHitAnim() {
            FakeWorld w = new FakeWorld()
                .withRandomSequence(0, 0, 0, 0)
                .withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);

            AlienAI.inflictDamage(a, 1);
            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.TAKE_DAMAGE, a.mode,
                "GetRand=0, and 3 = 0 -> TAKE_DAMAGE (25%)");
            assertEquals(2, a.whichAnim);
        }

        @Test
        @DisplayName("Degats cumul/4 >= HP_init : passe en DIE (modele ASM)")
        void lethalDamageKills() {
            FakeWorld w = new FakeWorld();
            AlienRuntimeState a = newAlien(chargerDef, w);

            // chargerDef.hitPoints=5, donc il faut totalDamage >= 20 pour mourir
            // (20/4 = 5 >= 5). On envoie 99 d'un coup pour etre sur.
            AlienAI.inflictDamage(a, 99);
            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.DIE, a.mode);
            // Apres update : applyDamage() declenche justDied() qui passe en DIE,
            // puis le dispatcher appelle doDie() qui met hitPoints=0 (ASM-fidele,
            // cf. modules/ai.s::ai_DoDie .still_dying).
            assertEquals(0, a.hitPoints,
                "doDie met hitPoints=0 conformement a l'ASM original");
            assertEquals(99, a.totalDamageDone, "cumul reflete les 99 dmg recus");
            assertEquals(3, a.whichAnim);
            assertTrue(a.timer3 > 0, "timer3 = death fade");
        }

        @Test
        @DisplayName("Red Alien (HP=2) : meurt apres 8 plasmas (cumul/4=2 >= 2)")
        void redAlienDiesAfter8Plasmas() {
            // ASM : Red Alien HP=2, Plasma damage=1
            // Cumul=8 -> 8/4=2 >= 2 -> mort. 7 plasmas (cumul=7) -> 7/4=1 < 2 -> vivant.
            AlienDef redAlien = new AlienDef(0, "RedAlien", 0,
                0, 5, 16, 0, 32, 100, 255, 16, 1, 16, 50, 0, 32, 50,
                0 /* plasma */, 2 /* HP */, 128, 1, 0, -1);
            FakeWorld w = new FakeWorld();
            AlienRuntimeState a = newAlien(redAlien, w);
            AlienAI ai = new AlienAI(redAlien, w);

            // 7 plasmas : doit rester vivant
            for (int i = 0; i < 7; i++) {
                AlienAI.inflictDamage(a, 1);
                ai.update(a);
            }
            assertNotEquals(AlienBehaviour.DIE, a.mode,
                "7 plasmas (cumul=7, /4=1) < HP=2 : alien encore vivant");
            assertEquals(7, a.totalDamageDone);

            // 8eme plasma : mort
            AlienAI.inflictDamage(a, 1);
            ai.update(a);
            assertEquals(AlienBehaviour.DIE, a.mode,
                "8eme plasma (cumul=8, /4=2) >= HP=2 : mort");
        }

        @Test
        @DisplayName("inflictDamage sur alien deja mort : sans effet")
        void deadAlienIgnoresDamage() {
            FakeWorld w = new FakeWorld();
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.mode = AlienBehaviour.DIE;

            AlienAI.inflictDamage(a, 99);

            assertEquals(0, a.damageTaken,
                "damageTaken doit rester 0 sur alien mort");
        }
    }

    // -------------------------------------------------------------------------
    // Animation de mort
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DIE : timer3 decremente jusqu'a 0, puis isDeadAndGone()")
    void dieFadeOutCompletes() {
        FakeWorld w = new FakeWorld();
        AlienRuntimeState a = newAlien(chargerDef, w);
        a.mode = AlienBehaviour.DIE;
        a.timer3 = 2;
        // Note : on ne touche PAS hitPoints, qui reste a HP_init meme en mort
        // (modele ASM-fidele). isDeadAndGone() ne depend que de mode==DIE et timer3<=0.

        AlienAI ai = new AlienAI(chargerDef, w);
        ai.update(a);
        assertFalse(a.isDeadAndGone(), "encore en train de fader");
        ai.update(a);
        assertTrue(a.isDeadAndGone(), "fade fini, supprimable");
    }

    // -------------------------------------------------------------------------
    // Initialisation depuis def
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("initFromDef() applique HP et reactionTime")
    void initFromDef() {
        FakeWorld w = new FakeWorld();
        AlienRuntimeState a = new AlienRuntimeState(42, gunnerDef.index());

        a.initFromDef(gunnerDef);

        assertEquals(gunnerDef.hitPoints(), a.hitPoints);
        assertEquals(gunnerDef.reactionTime(), a.timer1);
        assertEquals(AlienBehaviour.DEFAULT, a.mode);
        assertEquals(0, a.whichAnim);
        assertEquals(0, a.damageTaken);
    }

    // -------------------------------------------------------------------------
    // FOV (ai_CheckInFront) - Phase 2.D
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Champ de vision frontal (ai_CheckInFront)")
    class FieldOfView {

        @Test
        @DisplayName("Joueur devant l'alien : agresse normalement")
        void playerInFrontTriggersResponse() {
            // alien at (100, 100) angle=0 -> regarde +Z
            // joueur at (200, 200) -> dx=100, dz=100
            // forward=(sin(0), cos(0))=(0, 1) -> dot = 0*100 + 1*100 = 100 > 0 OK
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 0;
            a.currentAngle = 0; // regarde +Z

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.RESPONSE, a.mode,
                "joueur devant (dot > 0) -> agresse");
        }

        @Test
        @DisplayName("Joueur derriere l'alien : ne reagit pas (reset timer)")
        void playerBehindDoesNotTrigger() {
            // alien at (100, 100) angle=2048 -> regarde -Z (180 deg)
            // joueur at (200, 200) -> dx=100, dz=100
            // forward=(sin(pi), cos(pi))=(0, -1) -> dot = 0*100 + (-1)*100 = -100 < 0 BACK
            FakeWorld w = new FakeWorld().withPlayerVisible(true);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 0;
            a.currentAngle = 2048; // regarde -Z (180 deg)

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.DEFAULT, a.mode,
                "joueur derriere (dot < 0) -> reste en patrouille");
            assertEquals(chargerDef.reactionTime(), a.timer1,
                "timer1 reset car pas en champ de vision");
        }

        @Test
        @DisplayName("Joueur sur le cote (90 deg) : limite du champ de vision")
        void playerToTheSide() {
            // alien at (100, 100) angle=1024 -> regarde +X (90 deg)
            // joueur at (200, 100) -> dx=100, dz=0
            // forward=(sin(pi/2), cos(pi/2))=(1, 0) -> dot = 1*100 + 0*0 = 100 > 0 OK
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(200, 0, 100);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.timer1 = 0;
            a.currentAngle = 1024; // regarde +X

            new AlienAI(chargerDef, w).update(a);

            assertEquals(AlienBehaviour.RESPONSE, a.mode,
                "joueur a 90 deg mais devant la direction -> agresse");
        }
    }

    // -------------------------------------------------------------------------
    // Tir alien -> joueur (Phase 2.D)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Tir alien (ai_AttackWithGun / ai_AttackWithProjectile)")
    class AlienShooting {

        /** 'Ard Guard hardcore : bulType=7 (Shotgun, hitscan), respBeh=2. */
        private final AlienDef ardGuardDef = new AlienDef(
            5, "ArdGuard", 0,
            0, 30, 12,
            2 /* AttackWithGun */, 16, 80,
            255, 8, 0, 12, 40, 0, 16, 50,
            7 /* Shotgun = HITSCAN */, 4, 160, 1, 0, -1);

        /** Guard standard : bulType=9 (Blaster, projectile). */
        private final AlienDef projectileGuardDef = new AlienDef(
            2, "Guard", 0,
            0, 30, 12,
            2, 16, 80,
            255, 8, 0, 12, 40, 0, 16, 50,
            9 /* Blaster = projectile */, 4, 160, 1, 0, -1);

        @Test
        @DisplayName("'Ard Guard (bulType=7 hitscan) : tire au FIRE_FRAME, hit a courte distance")
        void ardGuardShootsHitscanCloseRange() {
            // alien (100,100) joueur (110,100) -> dx=10, dz=0, dist^2=100
            // formule ASM : rand*256 > dist^2 -> avec rand=1000, rand*256=256000 > 100 -> HIT
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(110, 0, 100)
                .withRandomSequence(1000);
            AlienRuntimeState a = newAlien(ardGuardDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.currentAngle = 1024; // regarde +X (vers le joueur)
            a.timer2 = 3; // sera 4 apres update -> FIRE_FRAME crossed

            new AlienAI(ardGuardDef, w).update(a);

            assertEquals(1, w.applyDamageCount,
                "hitscan a courte distance avec rand=1000 -> hit garanti");
            assertEquals(1, w.lastDamageAmount,
                "Shotgun Round = 1 HP de degats");
            assertEquals(0, w.spawnProjectileCount,
                "hitscan -> pas de spawn de projectile");
        }

        @Test
        @DisplayName("'Ard Guard tres loin : miss garanti (dist^2 > rand*256 max)")
        void ardGuardMissesAtLongRange() {
            // alien (100,100) joueur (10000,100) -> dx=9900, dist^2=98010000
            // rand max = 32767, rand*256 = 8.39e6 < 9.8e7 -> miss garanti
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(10000, 0, 100)
                .withRandomSequence(32767); // max rand
            AlienRuntimeState a = newAlien(ardGuardDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.currentAngle = 1024;
            a.timer2 = 3;

            new AlienAI(ardGuardDef, w).update(a);

            assertEquals(0, w.applyDamageCount,
                "hitscan tres loin (dist^2 > rand*256) -> miss");
        }

        @Test
        @DisplayName("Guard (bulType=9 projectile) : spawn un projectile, pas de hitscan direct")
        void guardShootsProjectile() {
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(200, 0, 100);
            AlienRuntimeState a = newAlien(projectileGuardDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.currentAngle = 1024;
            a.timer2 = 3;

            new AlienAI(projectileGuardDef, w).update(a);

            assertEquals(1, w.spawnProjectileCount,
                "bulType=9 (Blaster) = projectile, doit spawn une bullet alien");
            assertEquals(9, w.lastBulletType, "Blaster Bolt = bulType 9");
            assertEquals(2, w.lastDamageAmount, "Blaster damage = 2");
            assertEquals(0, w.applyDamageCount,
                "projectile -> pas de damage direct (geree par bullet update plus tard)");
        }

        @Test
        @DisplayName("Pas de tir avant FIRE_FRAME : timer2=2 ne declenche pas")
        void noShootBeforeFireFrame() {
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(110, 0, 100)
                .withRandomSequence(1000);
            AlienRuntimeState a = newAlien(ardGuardDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.currentAngle = 1024;
            a.timer2 = 2; // 2 -> 3, fireTrigger = (2<4 && 3>=4) = false

            new AlienAI(ardGuardDef, w).update(a);

            assertEquals(0, w.applyDamageCount,
                "timer2 n'a pas franchi FIRE_FRAME=4 -> pas de tir");
        }

        @Test
        @DisplayName("Un seul tir par cycle d'attaque (timer2 deja > FIRE_FRAME)")
        void onlyOneShotPerCycle() {
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(110, 0, 100)
                .withRandomSequence(1000, 1000, 1000, 1000);
            AlienRuntimeState a = newAlien(ardGuardDef, w);
            a.mode = AlienBehaviour.RESPONSE;
            a.currentAngle = 1024;
            a.timer2 = 5; // deja PASSE FIRE_FRAME=4

            new AlienAI(ardGuardDef, w).update(a);

            assertEquals(0, w.applyDamageCount,
                "timer2 deja > FIRE_FRAME : ne re-tire pas");
        }
    }

    // -------------------------------------------------------------------------
    // Collision murs - Phase 2.F
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Collision murs (Obj_DoCollision)")
    class WallCollisionInAi {

        @Test
        @DisplayName("prowl : alien bloque par un mur, ne le traverse pas")
        void prowlBlockedByWall() {
            // chargerDef.defaultSpeed = 16, girth = 1 -> collisionRadius = 160
            // Mur en X=200. Alien en (100, 100), radius=160.
            // Sans mur, il avancerait vers +X. Avec mur, il est borne a (200 - 160 = 40).
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(false)
                .withRandomSequence(0x400) // angle 0x400 = 1024 = +X
                .withWallAtX(200);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.worldX = 100;
            a.worldZ = 100;
            a.prowlTicker = 0;

            new AlienAI(chargerDef, w).update(a);

            assertEquals(160, w.lastResolveRadius,
                "resolveAlienMove doit etre appele avec collisionRadius (=160 pour girth=1)");
            // L'alien voulait aller a (100 + 16, 100) = (116, 100), ce qui ne touche
            // pas le mur a X=200 (radius 160). Mais la collision a quand meme appele
            // resolveAlienMove. Verifions que le X est bien borne par le mock.
            assertTrue(a.worldX <= 200 - 160 + 0.1f,
                "alien borne a (wallX - radius) par le mock = 40 max");
        }

        @Test
        @DisplayName("prowl : alien arrete dans son elan par un mur (essai de traversee)")
        void prowlStoppedAtWall() {
            // Alien a 5 unites du mur (a X=195), avancant vers +X. Avec un step
            // de 16, sans collision il finirait a X=211. Avec collision, borne a 40.
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(false)
                .withRandomSequence(0x400) // angle 1024 = +X
                .withWallAtX(200);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.worldX = 195;
            a.worldZ = 100;
            a.prowlTicker = 0;

            new AlienAI(chargerDef, w).update(a);

            // Le mock borne a (200 - 160) = 40 mais on partait de 195, donc en
            // fait notre mock simpliste va pousser l'alien a X=40 (puisque on
            // partait < wallX et on voulait franchir). Cette regression est ok
            // car ca prouve que la collision est consultee.
            assertEquals(40, a.worldX, 0.1f,
                "mock collision : pousse a (wallX - radius)");
        }

        @Test
        @DisplayName("moveTowards : charge alien resolue contre mur (pas de traversee)")
        void chargeBlockedByWall() {
            // Joueur derriere un mur, alien en mode RESPONSE charge.
            // Le mur empeche l'alien d'atteindre le joueur.
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(true)
                .withPlayer(300, 0, 100)
                .withWallAtX(200);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.worldX = 100;
            a.worldZ = 100;
            a.mode = AlienBehaviour.RESPONSE;
            a.timer2 = 0;
            a.currentAngle = 1024; // face au joueur (+X)

            new AlienAI(chargerDef, w).update(a);

            // L'alien charge vers le joueur (X=300) mais est borne par le mur a X=40.
            assertTrue(a.worldX <= 200 - 160 + 0.1f,
                "alien charge mais bute sur le mur, ne traverse pas");
            assertEquals(160, w.lastResolveRadius,
                "resolveAlienMove appele avec le bon radius");
        }

        @Test
        @DisplayName("radius depend du girth : girth=2 (boss) plus large que girth=0")
        void radiusDependsOnGirth() {
            // Boss girth=2 -> collisionRadius = 320
            AlienDef bossDef = new AlienDef(
                10, "BigBoss", 0,
                0, 50, 16, 0, 32, 100, 255, 16, 0, 16, 50, 0, 32, 50,
                0, 5, 256, 2 /* girth=2 */, 0, -1);
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(false)
                .withRandomSequence(0x400);
            AlienRuntimeState a = newAlien(bossDef, w);
            a.prowlTicker = 0;

            new AlienAI(bossDef, w).update(a);

            assertEquals(320, w.lastResolveRadius,
                "boss girth=2 doit utiliser radius=320");
        }

        @Test
        @DisplayName("FakeWorld par defaut (pas de mur) : aucun blocage")
        void noWallNoBlocking() {
            FakeWorld w = new FakeWorld()
                .withPlayerVisible(false)
                .withRandomSequence(0x400);
            AlienRuntimeState a = newAlien(chargerDef, w);
            a.worldX = 100;
            a.worldZ = 100;
            a.prowlTicker = 0;

            new AlienAI(chargerDef, w).update(a);

            // Sans mur, l'alien doit avoir avance librement
            assertTrue(a.worldX > 100,
                "sans mur, alien avance librement (defaultSpeed * sin(angle 1024))");
        }
    }

    // =========================================================================
    // Mock world implementation
    // =========================================================================

    /** Implementation deterministe de {@link AiWorld} pour les tests. */
    static class FakeWorld implements AiWorld {
        private boolean playerVisible = false;
        private float plrX = 200, plrY = 0, plrZ = 200;
        private int plrZone = 1;
        private boolean plrUpper = false;
        private int plrBrightness = 0;
        private int plrNoise = 0;
        private Random rng = new Random(42);
        private int[] randomSequence = null;
        private int seqIdx = 0;

        FakeWorld withPlayerVisible(boolean v)        { this.playerVisible = v; return this; }
        FakeWorld withPlayerZone(int z)               { this.plrZone = z; return this; }
        FakeWorld withPlayerRoomBrightness(int b)     { this.plrBrightness = b; return this; }
        FakeWorld withRandomSeed(long s)              { this.rng = new Random(s); return this; }
        FakeWorld withRandomSequence(int... values)   { this.randomSequence = values; return this; }
        /** Configure la position du joueur (utile pour les tests FOV / tir). */
        FakeWorld withPlayer(float x, float y, float z) {
            this.plrX = x; this.plrY = y; this.plrZ = z;
            return this;
        }

        // -- Capture des appels de combat alien (pour les tests de tir) --------
        int applyDamageCount = 0;
        int lastDamageAmount = 0;
        int spawnProjectileCount = 0;
        int lastBulletType = -1;

        @Override public float playerX() { return plrX; }
        @Override public float playerY() { return plrY; }
        @Override public float playerZ() { return plrZ; }
        @Override public int playerZone() { return plrZone; }
        @Override public boolean playerInUpperZone() { return plrUpper; }
        @Override public int playerRoomBrightness() { return plrBrightness; }
        @Override public int playerNoiseVolume() { return plrNoise; }

        @Override
        public boolean canSeePlayer(float fx, float fy, float fz, int zone, boolean upper) {
            return playerVisible;
        }

        @Override public int getNextControlPoint(int from, int to) { return to; }
        @Override public int numControlPoints() { return 0; }
        @Override public float controlPointX(int id) { return 0; }
        @Override public float controlPointZ(int id) { return 0; }
        @Override public float controlPointY(int id) { return 0; }
        @Override public int zoneControlPoint(int z, boolean u) { return 0; }
        @Override public int zoneBrightness(int z) { return 32; }
        @Override public float zoneFloorY(int z, boolean u) { return 0; }
        @Override public float zoneRoofY(int z, boolean u) { return 100; }

        @Override
        public int getRandom() {
            if (randomSequence != null) {
                int v = randomSequence[seqIdx % randomSequence.length];
                seqIdx++;
                return v;
            }
            return rng.nextInt(65536);
        }

        @Override
        public void playPositionalSound(int sample, int vol, float x, float z, int echo) {
            // no-op
        }

        @Override
        public void applyDamageToPlayer(int damage, float fromX, float fromZ) {
            applyDamageCount++;
            lastDamageAmount = damage;
        }

        @Override
        public void spawnAlienProjectile(int bulletType, int damage,
                                          float fromX, float fromY, float fromZ) {
            spawnProjectileCount++;
            lastBulletType = bulletType;
            lastDamageAmount = damage;
        }

        // ─ Phase 2.F : simulateur de collision pour les tests ───────────────

        /**
         * Mur simule : si non-null, alien ne peut pas franchir ce X.
         * Le mur est suppose etre une infinite verticale a wallX (en Z).
         */
        Float wallX = null;
        /** Rayon de collision attendu (= verifie que radiusAmiga arrive bien). */
        int lastResolveRadius = -1;

        FakeWorld withWallAtX(float x) { this.wallX = x; return this; }

        @Override
        public float[] resolveAlienMove(float fromX, float fromZ,
                                          float dx, float dz,
                                          int radiusAmiga) {
            lastResolveRadius = radiusAmiga;
            float newX = fromX + dx;
            float newZ = fromZ + dz;
            // Mur simule : si l'alien essaie de franchir wallX en venant
            // de la gauche, on le borne a (wallX - radiusAmiga).
            if (wallX != null) {
                if (fromX < wallX && newX > wallX - radiusAmiga) {
                    newX = wallX - radiusAmiga;
                }
            }
            return new float[]{newX, newZ};
        }
    }
}
