package com.ab3d2.combat;

import com.ab3d2.combat.KeyBindings.Action;
import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.input.InputManager;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.Trigger;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Systeme de tir du joueur. Implemente les routines <code>Plr1_Shot</code>
 * et <code>plr1_FireProjectile</code> de <code>newplayershoot.s</code>.
 *
 * <p>Phase 1.C (session 85) : ajoute le spawn de projectiles.</p>
 * <ul>
 *   <li>Logique Plr1_Shot : cooldown, check ammo, consommation (session 84)</li>
 *   <li>Spawn de ShootT_BulCount_w projectiles dans le pool (session 85)</li>
 *   <li>Calcul des velocites avec spread angulaire (session 85)</li>
 *   <li>Pas encore : auto-aim Y, hitscan, SFX, collisions (phases suivantes)</li>
 * </ul>
 *
 * <p>Correspondance ASM (newplayershoot.s) :</p>
 * <pre>
 *   plr1_FireProjectile:
 *     move.w ShootT_BulCount_w(a6), d5   ; count
 *     move.w d5, d6
 *     subq   #1, d6
 *     asl.w  #7, d6                       ; (count-1) * 128
 *     neg.w  d6
 *     add.w  tempangpos, d6                ; angle - spread/2
 *   firefive:
 *     ; allouer slot
 *     ; lire sin/cos(d6)
 *     ; vel = dir * 2^BulT_Speed_l
 *     ; ajouter 256 a d6 pour la prochaine bullet
 *     ; decrementer d5, boucler tant que d5 &gt; 0
 * </pre>
 */
public class PlayerShootSystem extends AbstractAppState implements ActionListener {

    private static final Logger log = LoggerFactory.getLogger(PlayerShootSystem.class);

    /**
     * Conversion du "speed factor" Amiga en vitesse JME (unites/seconde).
     *
     * <p>Analyse detaillee de l'ASM (session 85 : bug identifie, fix session 86) :</p>
     *
     * <p>L'ASM fait :</p>
     * <pre>
     *   move.w (a1,d6.w), d0       ; sin(angle) WORD signed, max = 32768 (= 1.0)
     *   ext.l  d0                   ; sign extend LONG
     *   asl.l  d1, d0                ; d0 = sin &lt;&lt; speed  (ex: speed=5, sin=32768 -&gt; 1048576)
     *   move.l d0, ShotT_VelocityX_w ; stocke en LONG (champ etiquette _w mais 32-bit)
     * </pre>
     *
     * <p>Puis dans ItsABullet (newanims.s) le displacement par frame :</p>
     * <pre>
     *   move.l ShotT_VelocityX_w, d3  ; d3 = VelocityX_w (fixed-point 16.16)
     *   move.w d3, d4                  ; d4 = low 16 bits (fraction)
     *   swap   d3                      ; d3 = high 16 bits (integer part)
     *   muls   Anim_TempFrames, d3     ; d3 = int_part * frames
     *   mulu   Anim_TempFrames, d4     ; d4 = frac_part * frames
     *   ...
     *   add.l  d3, d2                  ; pos += displacement (16.16 fixed-point)
     * </pre>
     *
     * <p>Les positions ASM sont en <b>fixed-point 16.16</b> : le WORD haut est
     * la position entiere en "tuiles" de 128 unites, le WORD bas est la fraction.
     * Donc <code>VelocityX_w = sin &lt;&lt; speed</code> represente une vitesse en
     * <b>tuiles/frame</b> au format 16.16.</p>
     *
     * <p>Calcul pour sin=32768 (amplitude 1.0), speed=5 :</p>
     * <ul>
     *   <li>VelocityX_w = 32768 &lt;&lt; 5 = 1048576 = 0x00100000 (fixed-point)</li>
     *   <li>En decimal : 1048576 / 65536 = <b>16 tuiles/frame</b></li>
     *   <li>A 25 Hz : 16 * 25 = <b>400 tuiles/sec</b></li>
     *   <li>1 tuile Amiga = 128 unites Amiga = 1 unite JME (SCALE VectObj) =&gt;
     *       <b>400 unites JME/sec</b></li>
     * </ul>
     *
     * <p>Formule generique : <code>velJme = (2^speed) * sin_max * TICKS_PER_SECOND / (65536 * AMIGA_UNITS_PER_JME_UNIT)</code>.
     * Avec <code>sin_max=32768, AMIGA_UNITS_PER_JME_UNIT=128</code>, ca se
     * simplifie a <code>velJme = (2^speed) * 12.5</code> pour sin=1.0.</p>
     *
     * <p>Exemples calcules :</p>
     * <ul>
     *   <li>speed=4 (Blaster)      -&gt;  16 * 12.5 = 200 JME/s</li>
     *   <li>speed=5 (Plasma, Gren) -&gt;  32 * 12.5 = 400 JME/s</li>
     *   <li>speed=6 (Rocket, Lazer)-&gt;  64 * 12.5 = 800 JME/s</li>
     * </ul>
     *
     * <p><b>Bug session 85 :</b> j'avais ecrit <code>(2^speed) * 25 / 128 = 6.25</code>
     * pour Plasma (speed=5), oubliant le facteur 32768/65536 (= 0.5) du fixed-point.
     * Resultat : bullets ~64x plus lentes qu'elles ne devraient.</p>
     *
     * <p><b>Calibration session 86bis :</b> la formule ASM-stricte donne 400 JME/s
     * pour Plasma, ce qui est <b>injouable</b> sur les niveaux JME. L'echelle
     * theorique "1 tuile Amiga = 1 unite JME" n'est pas la vraie correspondance
     * des niveaux convertis (en pratique les niveaux JME sont plus petits).
     * On applique un <b>facteur de calibration 0.2</b> pour avoir :</p>
     * <ul>
     *   <li>speed=4 (Blaster)      -&gt;  40 JME/s  (2x le joueur)</li>
     *   <li>speed=5 (Plasma, Gren) -&gt;  80 JME/s  (4x le joueur)</li>
     *   <li>speed=6 (Rocket, Lazer)-&gt; 160 JME/s  (8x le joueur)</li>
     * </ul>
     * <p>Ces valeurs donnent un ressenti FPS classique (balle 5-10x plus
     * rapide que la marche). Si ajustement necessaire, modifier
     * <code>BULLET_SPEED_CALIBRATION</code>.</p>
     */
    private static final float AMIGA_UNITS_PER_JME_UNIT = 128f;
    private static final float SIN_MAX = 32768f;
    private static final float FIXED_POINT_SCALE = 65536f;
    /** Facteur empirique pour rendre les vitesses jouables (session 86bis). */
    private static final float BULLET_SPEED_CALIBRATION = 0.2f;
    /**
     * Calibration specifique aux grenades/mines : encore plus lent pour
     * qu'on les voie partir en arc. La grenade ASM part aussi vite qu'un
     * plasma (speed=5), mais visuellement on prefere une lancer plus lente.
     */
    private static final float GRENADE_SPEED_CALIBRATION = 0.1f;

    /**
     * Offset vertical du spawn au-dessus de la position du joueur (muzzle).
     * L'ASM fait <code>add.l #20*128, d0</code> ou d0 est
     * <code>playerYOff + 10*128</code> (les yeux). Donc total = +30*128 en
     * Amiga = 30 unites JME * 128 / 128 = 30 unites JME au-dessus des pieds.
     * Mais en JME on tire depuis la position camera qui est deja
     * <code>playerY + EYE_HEIGHT</code>. Donc on ajoute juste un petit offset
     * pour que le projectile sorte devant/sous le canon visuel.
     */
    private static final float MUZZLE_Y_OFFSET = -0.15f;

    /**
     * Spread en radians entre deux bullets consecutives d'un meme tir
     * (equivalent des 256 unites de table sin/cos ASM = 360deg/16 = 22.5deg).
     * Pour un shotgun 2 bullets, le spread total = SPREAD_PER_BULLET = 22.5deg.
     */
    private static final float SPREAD_PER_BULLET = FastMath.TWO_PI / 16f;

    private final GlfDatabase        glf;
    private final PlayerCombatState  combat;
    private final KeyBindings        bindings;
    private final PlayerShotPool     shotPool;
    private final PlayerAimProvider  aim;
    /** Optionnel : systeme de traceurs hitscan pour feedback visuel. */
    private HitscanTracerSystem      tracerSystem;
    /** Optionnel : systeme physique pour les bullets a gravite/rebond. */
    private PhysicsBulletSystem      physicsSystem;
    /** Optionnel : systeme d'effets d'impact (flash au point d'impact). */
    private ImpactEffectSystem       impactSystem;

    private Application app;

    public PlayerShootSystem(GlfDatabase glf,
                             PlayerCombatState combat,
                             KeyBindings bindings,
                             PlayerShotPool shotPool,
                             PlayerAimProvider aim) {
        this.glf      = glf;
        this.combat   = combat;
        this.bindings = bindings;
        this.shotPool = shotPool;
        this.aim      = aim;
    }

    /**
     * Installe le systeme de traceurs hitscan. Optionnel : si non defini,
     * les hitscans logent seulement et pas de rendu visuel.
     */
    public void setTracerSystem(HitscanTracerSystem tracerSystem) {
        this.tracerSystem = tracerSystem;
    }

    /**
     * Installe le systeme de physique pour les bullets a gravite/rebond.
     * Si non defini, grenades/mines sont handlees par BulletUpdateSystem
     * avec gravite simple (moins realiste mais fonctionne).
     */
    public void setPhysicsSystem(PhysicsBulletSystem physicsSystem) {
        this.physicsSystem = physicsSystem;
    }

    /**
     * Installe le systeme d'effets d'impact. Optionnel : si non defini, les
     * impacts hitscan n'affichent pas de flash.
     */
    public void setImpactSystem(ImpactEffectSystem impactSystem) {
        this.impactSystem = impactSystem;
    }

    // ── AppState lifecycle ───────────────────────────────────────────────

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.app = app;
        registerMappings();
        log.info("PlayerShootSystem init (arme active = {} - {})",
            combat.getGunSelected(), safeGunName(combat.getGunSelected()));
    }

    private void registerMappings() {
        InputManager im = app.getInputManager();
        Action[] toHandle = {
            Action.FIRE,
            Action.NEXT_WEAPON, Action.PREV_WEAPON,
            Action.WEAPON_1, Action.WEAPON_2, Action.WEAPON_3, Action.WEAPON_4,
            Action.WEAPON_5, Action.WEAPON_6, Action.WEAPON_7, Action.WEAPON_8,
            // Session 91 : slots 8 et 9 (touches 9 et 0)
            Action.WEAPON_9, Action.WEAPON_10
        };
        for (Action a : toHandle) {
            String name = KeyBindings.actionName(a);
            Trigger[] triggers = bindings.getTriggers(a);
            if (triggers.length == 0) continue;
            if (!im.hasMapping(name)) {
                im.addMapping(name, triggers);
            }
            im.addListener(this, name);
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (app == null) return;
        InputManager im = app.getInputManager();
        im.removeListener(this);
    }

    // ── Input ────────────────────────────────────────────────────────────

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (!isEnabled()) return;

        if (name.equals(KeyBindings.actionName(Action.FIRE))) {
            combat.setFireHeld(isPressed);
            return;
        }

        if (!isPressed) return;

        if (name.equals(KeyBindings.actionName(Action.NEXT_WEAPON))) cycleWeapon(+1);
        else if (name.equals(KeyBindings.actionName(Action.PREV_WEAPON))) cycleWeapon(-1);
        else {
            // Session 91 : 10 armes (slots 0..9 = touches 1..8, 9, 0)
            for (int i = 0; i < PlayerCombatState.NUM_WEAPONS; i++) {
                Action a = switch (i) {
                    case 0 -> Action.WEAPON_1;
                    case 1 -> Action.WEAPON_2;
                    case 2 -> Action.WEAPON_3;
                    case 3 -> Action.WEAPON_4;
                    case 4 -> Action.WEAPON_5;
                    case 5 -> Action.WEAPON_6;
                    case 6 -> Action.WEAPON_7;
                    case 7 -> Action.WEAPON_8;
                    case 8 -> Action.WEAPON_9;   // touche 9 -> slot 8
                    case 9 -> Action.WEAPON_10;  // touche 0 -> slot 9
                    default -> null;
                };
                if (a != null && name.equals(KeyBindings.actionName(a))) {
                    trySelectWeapon(i);
                    return;
                }
            }
        }
    }

    private void cycleWeapon(int delta) {
        int start = combat.getGunSelected();
        int idx = start;
        for (int i = 0; i < PlayerCombatState.NUM_WEAPONS; i++) {
            idx = Math.floorMod(idx + delta, PlayerCombatState.NUM_WEAPONS);
            if (combat.hasWeapon(idx)) {
                if (idx != start) {
                    combat.setGunSelected(idx);
                    log.info("Arme -> {} ({})", idx, safeGunName(idx));
                }
                return;
            }
        }
    }

    private void trySelectWeapon(int gunIdx) {
        if (!combat.hasWeapon(gunIdx)) {
            log.info("Arme {} non possedee", gunIdx);
            return;
        }
        combat.setGunSelected(gunIdx);
        log.info("Arme -> {} ({})", gunIdx, safeGunName(gunIdx));
    }

    // ── Update (= Plr1_Shot) ─────────────────────────────────────────────

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        float framesAmigaDelta = tpf * PlayerCombatState.TICKS_PER_SECOND;
        combat.snapshotInput();

        // ASM: cooldown check
        if (!combat.canFire()) {
            combat.tickCooldown(framesAmigaDelta);
            return;
        }

        // ASM: tst.b Plr1_TmpFire_b ; beq .no_fire
        if (!combat.getTmpFire()) return;

        int gunIdx = combat.getTmpGunSelected();
        if (gunIdx < 0 || gunIdx >= ShootDef.COUNT) return;

        // Session 91 : getEffectiveShootDef applique les overrides des slots
        // 8 et 9 (Plasma Multi-Shot et MegaLaser absents dans TEST.LNK)
        ShootDef shoot = CombatBootstrap.getEffectiveShootDef(glf, gunIdx);
        int bulletType = shoot.bulletType();
        if (bulletType < 0 || bulletType >= BulletDef.COUNT) return;
        BulletDef bullet = glf.getBulletDef(bulletType);

        // ASM: check ammo
        int ammoRequired  = shoot.bulletCount();
        if (ammoRequired == 0) return;  // GUN I/GUN J placeholders
        int ammoAvailable = combat.getAmmo(bulletType);
        boolean hasAmmo = (ammoAvailable == PlayerCombatState.INFINITE_AMMO)
                          || (ammoAvailable >= ammoRequired);
        if (!hasAmmo) {
            combat.setTimeToShoot(10f);
            log.info("Click! Plus d'ammo pour {} (type {} - {})",
                safeGunName(gunIdx), bulletType, BulletDef.nameOf(bulletType));
            return;
        }

        // .okcanshoot: consomme ammo + applique cooldown
        combat.setTimeToShoot(shoot.delay());
        combat.consumeAmmo(bulletType, ammoRequired);

        // Spawn bullets (projectile ou hitscan)
        if (bullet.isHitScanBullet()) {
            int spawned = fireHitscan(shoot, bullet, bulletType);
            log.info("FIRE! {} -> HITSCAN {}x {} (ammo: {}) [cooldown {}f]",
                safeGunName(gunIdx), spawned, BulletDef.nameOf(bulletType),
                ammoAfter(bulletType), (int) shoot.delay());
        } else {
            int spawned = fireProjectile(shoot, bullet, bulletType);
            log.info("FIRE! {} -> {}/{} projectiles {} spawned (ammo: {}) [cooldown {}f]",
                safeGunName(gunIdx), spawned, ammoRequired,
                BulletDef.nameOf(bulletType),
                ammoAfter(bulletType), (int) shoot.delay());
        }
    }

    // ── plr1_FireProjectile ──────────────────────────────────────────────
    /**
     * Spawn de projectiles dans le pool, avec spread angulaire.
     *
     * @return nombre de projectiles effectivement alloues (peut etre inferieur
     *         au bulletCount demande si le pool est plein)
     */
    private int fireProjectile(ShootDef shoot, BulletDef bullet, int bulletType) {
        int count = shoot.bulletCount();
        Vector3f muzzle = aim.getMuzzlePosition();
        Vector3f fwd    = aim.getAimDirection();
        float    yaw    = aim.getYaw();

        // ASM: d6 = tempangpos - (count-1)*128 (angle de depart du spread)
        // En JME equivalent : startYaw = yaw - (count-1)*SPREAD_PER_BULLET/2
        // Ca centre le spread autour de la direction de visee.
        float startYaw = yaw - (count - 1) * SPREAD_PER_BULLET * 0.5f;

        // Conversion speed ASM -> vitesse JME en unites/seconde
        // velAmigaPerFrame = (sin_max * 2^speed) / 65536 tuiles/frame (fixed-point 16.16)
        // velJmePerSecond  = velAmigaPerFrame * TICKS_PER_SECOND (car 1 tuile Amiga = 1 unite JME)
        // Calibration : on applique un facteur empirique pour avoir un ressenti
        // FPS jouable (sinon 400 JME/s -> injouable sur nos niveaux).
        float calibration = bullet.hasGravity() || bullet.bounceHoriz() != 0
            ? GRENADE_SPEED_CALIBRATION   // grenade/mine : tres lent pour voir l'arc
            : BULLET_SPEED_CALIBRATION;   // autres bullets : moderement rapide
        float speedJme = (1 << bullet.speed()) * SIN_MAX
                        * PlayerCombatState.TICKS_PER_SECOND
                        / FIXED_POINT_SCALE
                        * calibration;

        // Position de spawn : devant le joueur, au niveau du canon
        float spawnX = muzzle.x + fwd.x * 0.5f;
        float spawnY = muzzle.y + MUZZLE_Y_OFFSET;
        float spawnZ = muzzle.z + fwd.z * 0.5f;

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            PlayerShot shot = shotPool.allocate();
            if (shot == null) {
                // Pool plein : abandon silencieux comme l'ASM
                break;
            }

            // Direction de cette bullet (spread autour du yaw central)
            float thisYaw = startYaw + i * SPREAD_PER_BULLET;
            // En JME, yaw 0 = +X, yaw PI/2 = +Z (convention deja etablie)
            float dirX = FastMath.cos(thisYaw);
            float dirZ = FastMath.sin(thisYaw);
            // Pour le pitch : on utilise le Y de fwd (inclinaison verticale
            // de la camera) pour que les bullets montent/descendent comme la
            // visee. Pas de spread vertical (ASM non plus).
            float dirY = fwd.y;

            // Normaliser (dirX^2 + dirZ^2 + dirY^2 != 1 avec pitch != 0)
            float len = FastMath.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (len > 0.0001f) {
                dirX /= len;
                dirY /= len;
                dirZ /= len;
            }

            // Initialise les champs de la bullet
            shot.bulletType  = bulletType;
            shot.posX        = spawnX;
            shot.posY        = spawnY;
            shot.posZ        = spawnZ;
            shot.velX        = dirX * speedJme;
            shot.velY        = dirY * speedJme;
            shot.velZ        = dirZ * speedJme;
            shot.power       = bullet.hitDamage();
            shot.lifetime    = 0f;
            shot.gravity     = bullet.gravity();
            shot.bounceFlags = (bullet.bounceHoriz() != 0 ? 1 : 0)
                             | (bullet.bounceVert()  != 0 ? 2 : 0);
            shot.status      = 0;
            shot.animFrame   = 0;
            // IMPORTANT : zoneId >= 0 marque la bullet active.
            // On met 0 temporairement (le vrai zone sera calcule en phase
            // suivante quand on aura un ZoneResolver).
            shot.zoneId      = 0;
            shot.geometry    = null;

            // Routing : bullets a gravite/rebond -> Bullet Physics (Minie)
            // bullets rapides simples -> BulletUpdateSystem custom
            if (physicsSystem != null && PhysicsBulletSystem.shouldUsePhysics(bullet)) {
                shot.handler = PlayerShot.HANDLER_PHYSICS;
                physicsSystem.spawnPhysicsBullet(shot);
            } else {
                shot.handler = PlayerShot.HANDLER_SIMPLE;
                // Geometry sera creee par BulletUpdateSystem au premier update
            }

            spawned++;
        }
        return spawned;
    }

    // ── plr1_HitscanSucceded / plr1_HitscanFailed (session 86) ─────────

    /**
     * Spawn les traceurs visuels pour un tir hitscan. Pas encore de raycast
     * reel sur les ennemis (phase 1.D), mais le joueur voit son tir.
     *
     * <p>Correspondance ASM (newplayershoot.s, .fire_hitscanned_bullets) :</p>
     * <pre>
     *   move.w ShootT_BulCount_w(a6), d7    ; count de bullets
     * .fire_hitscanned_bullets:
     *   jsr    GetRand                        ; accuracy aleatoire
     *   ; test si le random tombe dans la zone de hit
     *   ; si oui : bsr plr1_HitscanSucceded
     *   ; sinon  : bsr plr1_HitscanFailed (bullet part dans la direction)
     *   subq   #1, d7
     *   bgt    .fire_hitscanned_bullets
     * </pre>
     *
     * <p>En phase 1.C on simplifie : tous les tirs hitscan spawnent juste un
     * traceur visuel dans la direction de visee, avec un petit spread comme
     * les projectiles.</p>
     *
     * @return nombre de traceurs effectivement spawns
     */
    private int fireHitscan(ShootDef shoot, BulletDef bullet, int bulletType) {
        int count = shoot.bulletCount();
        Vector3f muzzle = aim.getMuzzlePosition();
        Vector3f fwd    = aim.getAimDirection();
        float    yaw    = aim.getYaw();

        float startYaw = yaw - (count - 1) * SPREAD_PER_BULLET * 0.5f;
        int spawned = 0;

        // Position de spawn : un peu devant le joueur (sortie du canon)
        Vector3f origin = muzzle.add(fwd.mult(0.5f));
        origin.y += MUZZLE_Y_OFFSET;

        // Session 113 phase 2.C : on passe le hitDamage du BulletDef au tracer
        // pour que les hits sur aliens infligent les degats. Avant, le tracer
        // etait purement visuel.
        int damage = bullet.hitDamage();

        for (int i = 0; i < count; i++) {
            float thisYaw = startYaw + i * SPREAD_PER_BULLET;
            float dirX = FastMath.cos(thisYaw);
            float dirZ = FastMath.sin(thisYaw);
            float dirY = fwd.y;
            // Normaliser
            float len = FastMath.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (len > 0.0001f) {
                dirX /= len;
                dirY /= len;
                dirZ /= len;
            }
            Vector3f dir = new Vector3f(dirX, dirY, dirZ);

            if (tracerSystem != null) {
                Vector3f impact = tracerSystem.spawnTracer(origin, dir, bulletType, damage);
                // Spawn un flash d'impact a la fin du traceur (sur le mur
                // si on a raycast, ou sur l'alien si on a touche, ou au bout sinon).
                if (impactSystem != null) {
                    impactSystem.spawnImpact(impact, bulletType);
                }
            }
            spawned++;
        }
        return spawned;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private int ammoAfter(int bulletType) {
        return combat.getAmmo(bulletType);
    }

    private String safeGunName(int gunIdx) {
        if (gunIdx < 0 || gunIdx >= ShootDef.COUNT) return "???";
        String name = glf.getGunName(gunIdx);
        return (name != null && !name.isEmpty()) ? name : "Gun#" + gunIdx;
    }
}
