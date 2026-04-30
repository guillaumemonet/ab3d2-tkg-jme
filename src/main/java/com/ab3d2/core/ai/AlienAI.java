package com.ab3d2.core.ai;

/**
 * Machine a etats IA d'un alien. Port direct de
 * {@code modules/ai.s::AI_MainRoutine} et de ses sous-routines.
 *
 * <h2>Vue d'ensemble</h2>
 *
 * <pre>
 *                       +-------------+
 *      damage 25%      |  TAKE_DAMAGE |
 *      +---------------+              |  (frame WhichAnim=2)
 *      |               +------+-------+
 *      |                      |
 *      |                      | finished anim
 *      v                      v
 *  +----+--------+      +-----+------+   sees player &amp; reacted   +----+-------+
 *  |             |      |            +--------------------------&gt;|             |
 *  |   DEFAULT   +-----&gt;|  DEFAULT   |                            |  RESPONSE   |
 *  |   (start)   |      | (prowling) |&lt;---------------------------+ (attack)    |
 *  +-------------+      +------+-----+   timer1 timeout           +-+---+-------+
 *                              ^                                    |   |
 *                              |                                    |   | done attacking
 *                              | timer1 timeout                     |   |
 *                              |                                    v   |
 *                       +------+--------+   sees player &amp; ready  +-----+-------+
 *                       |               |&lt;-----------------------+              |
 *                       |   FOLLOWUP    +-----------------------&gt;|              |
 *                       |               |                        |              |
 *                       +---------------+                        +--------------+
 *
 *      hp &lt;= 0 ----&gt; DIE (then removal)
 *
 *      RETREAT : declared but not implemented in original ASM (rts).
 *                We dispatch to DEFAULT for ASM-fidelity.
 * </pre>
 *
 * <h2>Routines portees</h2>
 *
 * <ul>
 *   <li>{@link #update} = {@code AI_MainRoutine} (le dispatcher 6-cas)</li>
 *   <li>{@link #doDefault} = {@code ai_DoDefault} &rarr; {@code ai_ProwlRandom}</li>
 *   <li>{@link #doResponse} = {@code ai_DoResponse} &rarr; {@code ai_Charge / ai_AttackWithGun}</li>
 *   <li>{@link #doFollowup} = {@code ai_DoFollowup} &rarr; {@code ai_PauseBriefly / ai_Approach}</li>
 *   <li>{@link #doRetreat} = {@code ai_DoRetreat} (vide dans l'ASM, fallback DEFAULT ici)</li>
 *   <li>{@link #doTakeDamage} = {@code ai_DoTakeDamage}</li>
 *   <li>{@link #doDie} = {@code ai_DoDie}</li>
 *   <li>{@link #applyDamage} = {@code ai_TakeDamage} (decrement HP, transitions)</li>
 * </ul>
 *
 * <h2>Limitations / TODO</h2>
 *
 * <p>Phase 2.A (cette session) couvre les <em>transitions d'etat</em> et la
 * lecture de la table {@link AlienDef}. Les comportements moteur (deplacement
 * physique avec {@code MoveObject + Obj_DoCollision}, gestion des collisions
 * murs/sols, animations sprite 4-directionnelles) restent a faire en phase
 * 2.B. Pour cette etape, les deplacements sont simplifies en interpolation
 * lineaire sans collision et l'animation se contente de toggle whichAnim 0&harr;1
 * pour permettre les tests.</p>
 *
 * @since session 113
 */
public final class AlienAI {

    /**
     * Quand on meurt, l'animation joue pendant ce nombre de frames Amiga avant
     * que l'alien soit definitivement supprime du monde. Equivalent du
     * {@code move.w #25, EntT_Timer3_w(a0)} dans {@code ai_CheckDamage}.
     */
    private static final int DEATH_FADE_FRAMES = 25;

    /** Durée min avant qu'un alien puisse re-tirer apres un coup. */
    private static final int MIN_ATTACK_INTERVAL = 8;

    /**
     * Distance carree maximale (en unites Amiga^2) pour qu'un alien attaque
     * "in front of him". 160 unites = ~5 unites JME, soit la portee corps-a-corps.
     */
    private static final int CHARGE_RANGE_AMIGA = 160;

    private final AlienDef def;
    private final AiWorld world;

    public AlienAI(AlienDef def, AiWorld world) {
        this.def = def;
        this.world = world;
    }

    public AlienDef def() { return def; }

    /**
     * Dispatcher principal. Equivalent ASM :
     * <pre>
     *   AI_MainRoutine:
     *       cmp.b   #1, EntT_CurrentMode_b(a0)
     *       blt     ai_DoDefault     ; mode == 0
     *       beq     ai_DoResponse    ; mode == 1
     *       cmp.b   #3, EntT_CurrentMode_b(a0)
     *       blt     ai_DoFollowup    ; mode == 2
     *       beq     ai_DoRetreat     ; mode == 3
     *       cmp.b   #5, EntT_CurrentMode_b(a0)
     *       beq     ai_DoDie         ; mode == 5
     *       ; sinon                  ; mode == 4
     *   ai_DoTakeDamage:
     * </pre>
     *
     * @param a l'alien a mettre a jour (modifie en place)
     */
    public void update(AlienRuntimeState a) {
        if (a == null) return;
        // Alien deja supprime (mort + fade fini) : skip
        if (!a.isAlive() && a.mode != AlienBehaviour.DIE) return;

        // Pre-handler : si des degats ont ete encaisses cette frame, on les
        // applique AVANT de dispatcher. Ce tri ASM-fidele evite qu'un alien
        // mort continue a tirer une frame de plus.
        if (a.damageTaken > 0 && a.mode != AlienBehaviour.DIE) {
            applyDamage(a);
            // Si applyDamage a deja change le mode (TAKE_DAMAGE ou DIE), on
            // dispatchera dans le nouveau mode au prochain tick mais on
            // continue tout de meme a faire le dispatcher de cette frame
            // pour la fluidite (comportement ASM).
        }

        switch (a.mode) {
            case DEFAULT      -> doDefault(a);
            case RESPONSE     -> doResponse(a);
            case FOLLOWUP     -> doFollowup(a);
            case RETREAT      -> doRetreat(a);
            case TAKE_DAMAGE  -> doTakeDamage(a);
            case DIE          -> doDie(a);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Mode handlers ────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Patrouille (prowl). Port simplifie de {@code ai_ProwlRandom + ai_Widget}.
     * Les transitions d'etat sont fideles a l'ASM ; le deplacement physique est
     * un placeholder pour la 2.A.
     *
     * <p>L'ASM fait, dans l'ordre :</p>
     * <ol>
     *   <li>{@code AI_LookForPlayer1} : test LOS via raycast/PVS</li>
     *   <li>{@code ai_CheckInFront} : verifie que le joueur est devant l'alien</li>
     *   <li>{@code timer1 -= Anim_TempFrames_w} : decompte reaction</li>
     *   <li>{@code ai_CheckForDark} : test luminosite</li>
     *   <li>{@code ai_CheckAttackOnGround} (si pas volant) : verifie que CP voisin</li>
     *   <li>Si tout passe : transition RESPONSE</li>
     * </ol>
     */
    void doDefault(AlienRuntimeState a) {
        a.whichAnim = 0;  // walk anim

        // Decompte du timer1 (= reaction time avant de pouvoir attaquer)
        int frames = world.tempFrames();
        if (a.timer1 > 0) a.timer1 -= frames;

        // Phase 2.E (fix) : incrementer timer2 chaque frame pour faire avancer
        // l'animation de marche. Sans ca, l'alien reste fige sur la frame 0.
        // L'ASM fait pareil : ai_DoWalkAnim incremente EntT_Timer2_w a chaque
        // tick d'AI_MainRoutine.
        a.timer2 += frames;
        // Bornage pour eviter les debordements de int sur de longues sessions
        if (a.timer2 > 100000) a.timer2 = a.timer2 % 24;  // 24 = WALK_TICK_DIVISOR * WALK_FRAMES_PER_VIEW

        // Test LOS sur le joueur (raycast PVS, sans champ de vision frontal)
        a.seesPlayer = world.canSeePlayer(a.worldX, a.worldY, a.worldZ,
                                          a.zoneId, a.inUpperZone);

        if (a.seesPlayer && isPlayerInFront(a) && a.timer1 <= 0 && !isInDark(a)) {
            // Transition DEFAULT -> RESPONSE
            //   Equivalent ASM (ai_ProwlRandom .attack_player) :
            //     move.w #0,EntT_Timer2_w(a0)
            //     move.b #1,EntT_CurrentMode_b(a0)
            //     move.b #1,EntT_WhichAnim_b(a0)
            a.mode = AlienBehaviour.RESPONSE;
            a.whichAnim = 1;
            a.timer2 = 0;
            // Tourne face au joueur pour que la prochaine frame soit nette
            faceTowards(a, world.playerX(), world.playerZ());
            // On a transitionne : pas de prowl cette frame.
            return;
        } else if (!a.seesPlayer || !isPlayerInFront(a)) {
            // Reset reaction timer quand on perd la LOS ou que le joueur
            // sort du champ de vision frontal.
            //   Equivalent ASM (ai_ProwlRandom .cant_see_player) :
            //     move.w AI_ReactionTime_w, EntT_Timer1_w(a0)
            a.timer1 = def.reactionTime();
        }

        // Patrouille : avancer vers le control point cible. Si on n'en a pas
        // (= AiWorld n'expose pas encore les CP), on fait un prowl aleatoire
        // simple pour que l'alien ne soit pas fige sur place. Le but de cette
        // 2.E est d'ETRE VISUELLEMENT VIVANT - le vrai pathfinding via control
        // points viendra plus tard quand AiWorld exposera la table CP.
        if (world.numControlPoints() > 0) {
            moveTowardsControlPoint(a, def.defaultSpeed());
        } else {
            prowlRandom(a);
        }
    }

    /**
     * Patrouille aleatoire simple (= placeholder de {@code ai_ProwlRandom}
     * sans control points). L'alien marche dans une direction et change de
     * direction toutes les ~50 frames Amiga. Sert UNIQUEMENT a animer
     * visuellement les aliens sans CP - sera remplace quand le pathfinding
     * via control points sera fonctionnel.
     *
     * <p>L'ASM original ({@code ai_ProwlRandom + ai_Widget}) selectionne un
     * CP cible aleatoire et y va via le graphe de CP. Sans cette infrastructure,
     * on simule par : choisir un cap aleatoire toutes les N frames, avancer
     * dans cette direction.</p>
     *
     * <p>Phase 2.E (fix) : utilise {@code prowlTicker} (champ dedie de
     * {@link AlienRuntimeState}) au lieu de {@code timer3} qui est reserve a la
     * fade-out de la mort dans l'ASM.</p>
     */
    private void prowlRandom(AlienRuntimeState a) {
        // Toutes les ~50 frames Amiga, changer de direction (= 1 sec de
        // marche dans le meme sens, ASM-like).
        if (a.prowlTicker <= 0) {
            int rand = world.getRandom();
            // Angle aleatoire 0..4095
            a.currentAngle = rand & 0xFFF;
            // Recharge le timer prowl (50..100 frames Amiga)
            a.prowlTicker = 50 + ((rand >> 12) & 0x3F);
        } else {
            a.prowlTicker -= world.tempFrames();
        }

        // Avancer dans la direction du regard (utilise sin/cos comme l'ASM).
        double rad = a.currentAngle * (2.0 * Math.PI / 4096.0);
        float fwdX = (float) Math.sin(rad);
        float fwdZ = (float) Math.cos(rad);
        float step = def.defaultSpeed() * world.tempFrames();
        // Phase 2.F : passer le delta a resolveAlienMove pour gerer les murs.
        // Si l'alien rencontre un mur, il glissera le long au lieu de
        // traverser. Le rayon est lu depuis def.collisionRadius() (= 80, 160
        // ou 320 unites Amiga selon girth).
        float[] resolved = world.resolveAlienMove(
            a.worldX, a.worldZ, fwdX * step, fwdZ * step, def.collisionRadius());
        a.worldX = resolved[0];
        a.worldZ = resolved[1];
    }

    /**
     * Mode attaque (RESPONSE). L'alien charge ou tire selon
     * {@code def.responseBehaviour()}.
     *
     * <p>Comportements ASM :</p>
     * <ul>
     *   <li>0 = {@code ai_Charge} : charge directe corps-a-corps</li>
     *   <li>1 = {@code ai_ChargeToSide} : charge en spirale (utilise RunAround)</li>
     *   <li>2 = {@code ai_AttackWithGun} : tir hitscan (Shotgun-like)</li>
     *   <li>3 = {@code ai_ChargeFlying} : charge en vol</li>
     *   <li>4 = {@code ai_ChargeToSideFlying} : charge en spirale en vol</li>
     *   <li>5 = {@code ai_AttackWithGunFlying} : tir hitscan en vol</li>
     * </ul>
     *
     * <p>Pour les charges (0, 1, 3, 4), on s'avance vers le joueur. Pour les
     * tirs (2, 5), on reste sur place et on tire (avec proba basée sur dist²).</p>
     *
     * <p>L'ASM utilise {@code ai_DoAction_b} (pose par l'animation a des frames
     * specifiques) pour declencher les tirs. Sans porter les vraies tables
     * d'anims, on simule par : <b>tirer une fois quand timer2 == FIRE_FRAME</b>.</p>
     */
    void doResponse(AlienRuntimeState a) {
        a.whichAnim = 1;  // attack anim

        // Increment timer2 (frame d'animation courante)
        int prevTimer2 = a.timer2;
        a.timer2 += world.tempFrames();

        // Retest la LOS - si on perd le joueur on passe en FOLLOWUP
        a.seesPlayer = world.canSeePlayer(a.worldX, a.worldY, a.worldZ,
                                          a.zoneId, a.inUpperZone);

        // Tourner vers le joueur (l'ASM le fait via HeadTowardsAng a chaque tick)
        if (a.seesPlayer) {
            faceTowards(a, world.playerX(), world.playerZ());
        }

        // Trigger de tir : a la frame FIRE_FRAME (= simulation de ai_DoAction_b).
        // Une seule fois par cycle d'attaque, quand on traverse la frame 4.
        boolean fireTrigger = (prevTimer2 < FIRE_FRAME && a.timer2 >= FIRE_FRAME);
        if (fireTrigger && a.seesPlayer && isPlayerInFront(a)) {
            performAttack(a);
        } else if (!a.seesPlayer || !isPlayerInFront(a)) {
            // Joueur perdu ou hors champ : passe en FOLLOWUP immediatement
            transitionToFollowup(a);
            return;
        } else if (!def.attacksWithGun()) {
            // Pour les charges (0/1/3/4), avancer vers le joueur entre les hits
            moveTowardsPlayer(a, def.responseSpeed());
        }

        // L'animation d'attaque dure {@link #MIN_ATTACK_INTERVAL} frames
        // Apres quoi, on retombe en FOLLOWUP (= pause apres avoir tire)
        if (a.timer2 >= MIN_ATTACK_INTERVAL) {
            transitionToFollowup(a);
        }
    }

    /**
     * Frame d'animation a laquelle l'alien declenche un tir / coup.
     * Equivalent simplifie de l'octet "trigger" pose par les tables d'anims
     * AlienAnimPtr_l a une frame specifique de l'anim attaque.
     */
    private static final int FIRE_FRAME = 4;

    /**
     * Execute l'attaque selon le {@code responseBehaviour} de la def.
     *
     * <p>L'ASM fait la dispatch HITSCAN vs PROJECTILE au niveau du
     * {@code BulletDef.IsHitScan_l}, pas du behaviour. C'est la routine
     * {@code ai_AttackCommon} qui fait :</p>
     * <pre>
     *   tst.l BulT_IsHitScan_l(a1)
     *   beq ai_AttackWithProjectile
     *   ; sinon ai_AttackWithHitScan
     * </pre>
     *
     * <p>Comportements 0/1/3/4 (= Charge) : pas de tir, juste collision physique
     * quand l'alien atteint le joueur (ai_DoAction_b * 2 = damage). Pour notre
     * port, on applique des degats melee si on est tres proche.</p>
     */
    private void performAttack(AlienRuntimeState a) {
        if (!def.attacksWithGun()) {
            // responseBehaviour 0/1/3/4 : charge corps-a-corps
            attackMelee(a);
            return;
        }
        // responseBehaviour 2/5 : tir distant. Dispatch sur le bullet type.
        if (isHitscanBullet(def.bulType())) {
            attackWithGun(a);          // ai_AttackWithHitScan
        } else {
            attackWithProjectile(a);   // ai_AttackWithProjectile
        }
    }

    /**
     * Indique si un bullet type est marque <code>BulT_IsHitScan_l != 0</code>
     * dans le GLF. Hardcode les types connus du jeu :
     * <ul>
     *   <li>1 = Machine Gun Bullet (well ard guard)</li>
     *   <li>7 = Shotgun Round ('Ard Guard)</li>
     *   <li>12 = MindZap (AlienPriest)</li>
     * </ul>
     * Tous les autres sont des projectiles physiques.
     */
    private static boolean isHitscanBullet(int bulType) {
        return bulType == 1 || bulType == 7 || bulType == 12;
    }

    /**
     * Tir alien hitscan.
     * Equivalent ASM : {@code ai_AttackWithGun} (modules/ai.s) :
     * <pre>
     *   d0 = GetRand & 0x7FFF
     *   d1 = (a6).x * (a6).x + (a6).z * (a6).z   ; dist^2 dans repere alien
     *   d1 = d1 / 64
     *   d0 = d0 * 4
     *   if (d0 > d1) -> hit_player                ; rand*4 > dist^2/64
     *      add.b SHOTPOWER, EntT_DamageTaken_b(Plr1_ObjectPtr_l)
     *   else SHOOTPLAYER1 (effet visuel sans damage)
     * </pre>
     *
     * <p>Plus l'alien est loin, plus dist^2 est grand, moins on touche
     * (proba inversement proportionnelle a la distance). C'est ce qui fait
     * que les Guards sont dangereux a proximite mais ratables a distance.</p>
     */
    private void attackWithGun(AlienRuntimeState a) {
        float dx = world.playerX() - a.worldX;
        float dz = world.playerZ() - a.worldZ;
        float dist2 = dx * dx + dz * dz;

        // ASM : rand & 0x7FFF, en signed 16-bit max ~32k.
        int rand = world.getRandom() & 0x7FFF;
        // Hit si rand*4 > dist^2/64. En float : rand*256 > dist^2 (= 4*64).
        boolean hit = (rand * 256f) > dist2;

        if (hit) {
            // Lookup des degats du bullet type
            int damage = damageForBulletType(def.bulType());
            world.applyDamageToPlayer(damage, a.worldX, a.worldZ);
        }
        // Que ce soit hit ou miss, on joue le son du tir
        // TODO : world.playPositionalSound(shootSfx, 100, a.worldX, a.worldZ, 0);
    }

    /**
     * Tir projectile : spawn une bullet alien qui voyage vers le joueur.
     * Equivalent ASM : {@code ai_AttackWithProjectile} -&gt; {@code FireAtPlayer1}
     * (newaliencontrol.s).
     *
     * <p>Pour la phase 2.D initiale, on delegue a {@link AiWorld#spawnAlienProjectile}
     * qui peut soit spawner une vraie bullet alien (futur 2.E), soit appliquer
     * directement des degats avec proba de hit (placeholder).</p>
     */
    private void attackWithProjectile(AlienRuntimeState a) {
        int damage = damageForBulletType(def.bulType());
        world.spawnAlienProjectile(def.bulType(), damage,
            a.worldX, a.worldY, a.worldZ);
    }

    /**
     * Attaque corps-a-corps : applique des degats si tres proche.
     * Equivalent ASM : dans {@code ai_Charge}, quand l'alien hit le joueur
     * via {@code Obj_DoCollision} avec collide flag {@code %100000}, et
     * {@code GotThere = true} :
     * <pre>
     *   move.b ai_DoAction_b, d0
     *   asl.w #1, d0                                    ; *2
     *   add.b d0, EntT_DamageTaken_b(Plr1_ObjectPtr_l)
     * </pre>
     */
    private void attackMelee(AlienRuntimeState a) {
        float dx = world.playerX() - a.worldX;
        float dz = world.playerZ() - a.worldZ;
        float dist2 = dx * dx + dz * dz;
        // Portee de melee : 80 unites Amiga (~2.5 JME)
        if (dist2 < 80 * 80) {
            int meleeDamage = MELEE_DAMAGE;
            world.applyDamageToPlayer(meleeDamage, a.worldX, a.worldZ);
        }
    }

    /** Degats de melee (= ai_DoAction_b * 2 dans l'ASM, simulation simple). */
    private static final int MELEE_DAMAGE = 2;

    /**
     * Lookup des degats par bullet type. C'est en theorie {@code BulT_HitDamage_l}
     * du GLF, mais l'AiWorld n'a pas acces au GlfDatabase. On hardcode les
     * valeurs connues du jeu.
     *
     * <p>Source : {@code TEST.LNK::GLFT_BulletDefs_l} (extraits de la session 86) :</p>
     */
    private static int damageForBulletType(int bulType) {
        return switch (bulType) {
            case 0  -> 1;   // Plasma Bolt
            case 1  -> 1;   // Machine Gun Bullet
            case 2  -> 5;   // Rocket
            case 3, 4, 5, 6 -> 1;  // Splutch1..4 (debris)
            case 7  -> 1;   // Shotgun Round
            case 8  -> 5;   // Grenade
            case 9  -> 2;   // Blaster Bolt
            case 10 -> 2;   // Assault Lazer
            case 11 -> 5;   // Explosion
            case 12 -> 3;   // MindZap
            case 13 -> 3;   // MegaPlasma
            case 14 -> 4;   // Lazer
            case 15 -> 8;   // Mine
            default -> 1;
        };
    }

    /**
     * Transition RESPONSE -&gt; FOLLOWUP. Equivalent ASM (ai_AttackWithGun .not_finished_attacking) :
     * <pre>
     *   move.b #0,EntT_WhichAnim_b(a0)
     *   move.b #2,EntT_CurrentMode_b(a0)
     *   move.w AI_FollowupTimer_w,EntT_Timer1_w(a0)
     *   move.w #0,EntT_Timer2_w(a0)
     * </pre>
     */
    private void transitionToFollowup(AlienRuntimeState a) {
        a.mode = AlienBehaviour.FOLLOWUP;
        a.whichAnim = 0;
        a.timer1 = def.followupTimeout();
        a.timer2 = 0;
    }

    /**
     * Mode FOLLOWUP : pause apres une attaque, ou approche pour la suivante.
     * Equivalent ASM : {@code ai_DoFollowup} &rarr; {@code ai_PauseBriefly /
     * ai_Approach / ai_ApproachToSide / ai_ApproachFlying / ai_ApproachToSideFlying}.
     */
    void doFollowup(AlienRuntimeState a) {
        a.whichAnim = 0;

        int frames = world.tempFrames();
        if (a.timer1 > 0) a.timer1 -= frames;

        // Phase 2.E (fix) : incrementer timer2 pour faire avancer l'anim de
        // marche en mode FOLLOWUP aussi. Sans ca, le sprite reste fige
        // pendant la pause apres une attaque.
        a.timer2 += frames;
        if (a.timer2 > 100000) a.timer2 = a.timer2 % 24;

        a.seesPlayer = world.canSeePlayer(a.worldX, a.worldY, a.worldZ,
                                          a.zoneId, a.inUpperZone);

        if (a.timer1 <= 0) {
            // Timeout FOLLOWUP - on retourne en DEFAULT
            //   Equivalent ASM (ai_PauseBriefly .stillwaiting -> .cant_see_player ->
            //   move.b #0,EntT_CurrentMode_b)
            a.mode = AlienBehaviour.DEFAULT;
            a.timer1 = def.reactionTime();
        } else if (a.seesPlayer && isPlayerInFront(a) && !isInDark(a)) {
            // LOS retrouvee + joueur devant : on relance la sequence d'attaque
            //   Equivalent ASM (ai_PauseBriefly .attack_player) :
            //     move.b #1,EntT_WhichAnim_b(a0)
            //     move.b #1,EntT_CurrentMode_b(a0)
            a.mode = AlienBehaviour.RESPONSE;
            a.whichAnim = 1;
            a.timer2 = 0;
            faceTowards(a, world.playerX(), world.playerZ());
        } else if (def.followupBehaviour() >= 1) {
            // Approche le joueur en attendant qu'il revienne dans le champ de vision
            // (Approach / ApproachToSide / ApproachFlying / ApproachToSideFlying)
            moveTowardsPlayer(a, def.followupSpeed());
        }
    }

    /**
     * Mode RETREAT : declare dans la struct mais <b>non implemente dans l'ASM
     * original</b> ({@code ai_DoRetreat: rts}). Pour rester ASM-fidele, on
     * fait un fallback vers DEFAULT.
     *
     * <p>Si on voulait un jour implementer la fuite (ce que la struct AlienT
     * suggere etre l'intention initiale des devs Amiga), il faudrait :</p>
     * <ul>
     *   <li>S'eloigner du joueur a {@link AlienDef#retreatSpeed()}</li>
     *   <li>Decompter {@link AlienDef#retreatTimeout()} avant de retomber en DEFAULT</li>
     *   <li>Rester en RETREAT tant que les degats cumules sont superieurs a
     *       {@link AlienDef#damageToRetreat()}</li>
     * </ul>
     */
    void doRetreat(AlienRuntimeState a) {
        // ASM original : ai_DoRetreat est { rts }. On reproduit en passant en DEFAULT.
        a.mode = AlienBehaviour.DEFAULT;
        a.timer1 = def.reactionTime();
    }

    /**
     * Animation "hit" : l'alien encaisse, sa fuite/charge est interrompue.
     * Apres l'animation finie, retour en DEFAULT.
     * Equivalent ASM : {@code ai_DoTakeDamage}.
     */
    void doTakeDamage(AlienRuntimeState a) {
        a.whichAnim = 2;  // hit anim
        a.timer2 += world.tempFrames();

        // L'anim "hit" dure ~8 frames Amiga
        if (a.timer2 >= 8) {
            //   Equivalent ASM :
            //     move.b #0,EntT_CurrentMode_b(a0)
            //     move.b #0,EntT_WhichAnim_b(a0)
            //     move.w #0,EntT_Timer2_w(a0)
            a.mode = AlienBehaviour.DEFAULT;
            a.whichAnim = 0;
            a.timer2 = 0;
        }
    }

    /**
     * Animation de mort, puis suppression. L'alien continue d'etre rendu
     * pendant {@link #DEATH_FADE_FRAMES} pour permettre le splat / bits.
     * Equivalent ASM : {@code ai_DoDie}.
     */
    void doDie(AlienRuntimeState a) {
        a.whichAnim = 3;  // die anim
        a.hitPoints = 0;
        a.timer3 -= world.tempFrames();

        if (a.timer3 <= 0) {
            // Marque pour suppression. Le AlienControlSystem qui itere sur les
            // aliens devra check {@link AlienRuntimeState#isDeadAndGone()} et
            // les retirer de la simulation.
            a.timer3 = 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Damage / death ───────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applique les degats accumules ({@code damageTaken}) au cumul lifetime,
     * et decide des transitions :
     * <ul>
     *   <li>Si {@code totalDamageDone / 4 &ge; hitPoints} (HP_init) &rarr; DIE</li>
     *   <li>Sinon, 75% chance d'agro-er (passer en RESPONSE)</li>
     *   <li>Sinon, 25% chance d'animer le hit (TAKE_DAMAGE)</li>
     * </ul>
     *
     * <p>Equivalent ASM : {@code ai_TakeDamage} (lignes 100-160 de modules/ai.s) :</p>
     * <pre>
     *   add.w d0, AI_Damaged_vw[idx]    ; cumul += damageTaken
     *   d0 = AI_Damaged_vw[idx] / 4     ; ASR.w #2,d0
     *   d1 = EntT_HitPoints_b(a0)       ; HP_init (jamais modifie)
     *   move.b #0, EntT_DamageTaken_b(a0)
     *   cmp.w d0, d1
     *   ble ai_JustDied                 ; HP_init &le; cumul/4 -&gt; mort
     *   GetRand &amp; 3 == 0 -&gt; TAKE_DAMAGE (25%)
     *   sinon                  -&gt; RESPONSE (75%)
     * </pre>
     *
     * <p>La ratio 75/25 vient de {@code jsr GetRand ; and.w #3 ; beq.s .dodododo}.</p>
     *
     * <p><b>Important</b> : {@code hitPoints} (= HP_init) <b>n'est jamais
     * decremente</b>, conformement a l'ASM. Seul {@code totalDamageDone}
     * accumule les degats. C'est important : {@code hitPoints} est la "barre
     * de vie" affichee a l'IA pour decider de la mort, mais le compteur
     * lifetime s'accumule a part.</p>
     */
    void applyDamage(AlienRuntimeState a) {
        if (a.damageTaken <= 0) return;

        a.totalDamageDone += a.damageTaken;
        a.damageTaken = 0;

        // Test de mort : ASM-fidele, HP_init &le; cumul/4
        if ((a.totalDamageDone >> 2) >= a.hitPoints) {
            justDied(a);
            return;
        }

        // Effet sonore (scream, geree par world.playPositionalSound en 2.B)
        // TODO : world.playPositionalSound(screamSfx, 200, a.worldX, a.worldZ, ...);

        // Repartition aleatoire 75/25 (ASM : "GetRand and 3 == 0" -> 25%)
        int r = world.getRandom() & 3;
        if (r == 0) {
            // 25% : animation de hit avant de reprendre
            //   Equivalent ASM (ai_TakeDamage .dodododo) :
            //     move.b #4, EntT_CurrentMode_b(a0)
            //     move.b #2, EntT_WhichAnim_b(a0)
            a.mode = AlienBehaviour.TAKE_DAMAGE;
            a.whichAnim = 2;
            a.timer2 = 0;
        } else {
            // 75% : aggrer immediatement (passer en RESPONSE et viser le joueur)
            //   Equivalent ASM (ai_TakeDamage .no_stop) :
            //     move.b #1, EntT_CurrentMode_b(a0)
            //     move.b #1, EntT_WhichAnim_b(a0)
            a.mode = AlienBehaviour.RESPONSE;
            a.whichAnim = 1;
            a.timer1 = 0;  // reaction immediate
            a.timer2 = 0;
            // Tourne face au joueur
            faceTowards(a, world.playerX(), world.playerZ());
        }
    }

    /**
     * Transition vers la mort. Joue le scream final, programme la suppression
     * apres {@link #DEATH_FADE_FRAMES}.
     *
     * <p>Note : on ne touche PAS a {@code hitPoints} (= HP_init) qui reste
     * fixe, conformement a l'ASM.</p>
     */
    private void justDied(AlienRuntimeState a) {
        a.mode = AlienBehaviour.DIE;
        a.whichAnim = 3;
        a.timer2 = 0;
        a.timer3 = DEATH_FADE_FRAMES;

        // TODO 2.E : effets - splatter, son, message displayText
        // ASM : muls #AlienT_SizeOf_l, d0 ; lea GLFT_AlienDefs_l(a2), a2 ;
        //       move.b AlienT_SplatType_w+1(a2), Anim_SplatType_w
        // Si splatType >= NUM_BULLET_DEFS : spawn smaller aliens (boss only)
        // Sinon : Anim_ExplodeIntoBits (bullets de splash)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ── Helpers : mouvement et orientation (placeholders pour 2.B) ─────────
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Test "in front" : verifie que le joueur est dans le champ de vision
     * frontal de l'alien.
     *
     * <p>Equivalent ASM : {@code ai_CheckInFront} (modules/ai.s) :</p>
     * <pre>
     *   dx = playerX - alienX
     *   dz = playerZ - alienZ
     *   sin = SinCosTable[currentAngle]
     *   cos = SinCosTable[currentAngle + COSINE_OFS]
     *   dot = dx*sin + dz*cos
     *   sgt d0     ; D0 = -1 si dot &gt; 0 (joueur devant), 0 sinon
     * </pre>
     *
     * <p><b>Crucial</b> : sans ce test, les aliens detectent le joueur a 360°
     * (ils "voient derriere eux"). Avec, ils n'agressent que si le joueur est
     * dans leur cone de vision frontal (= demi-plan devant).</p>
     *
     * @return true si le joueur est devant l'alien
     */
    private boolean isPlayerInFront(AlienRuntimeState a) {
        float dx = world.playerX() - a.worldX;
        float dz = world.playerZ() - a.worldZ;
        // currentAngle 0..4095 -> radians (0 = +Z avant, sens CW vu du dessus)
        // L'ASM utilise (sin(angle), cos(angle)) comme direction "forward"
        double rad = a.currentAngle * (2.0 * Math.PI / 4096.0);
        float forwardX = (float) Math.sin(rad);
        float forwardZ = (float) Math.cos(rad);
        // Dot product : positif si meme demi-plan que la direction de regard
        return (dx * forwardX + dz * forwardZ) > 0f;
    }

    /**
     * Test "in the dark" : compare la luminosite de la zone du joueur a un
     * tirage aleatoire. Plus c'est sombre, plus l'alien risque de ne pas
     * detecter le joueur meme s'il a une LOS technique.
     *
     * <p>Equivalent ASM : {@code ai_CheckForDark} :</p>
     * <pre>
     *   jsr     GetRand
     *   and.w   #31, d0
     *   cmp.w   Plr1_RoomBright_w, d0
     *   bge.s   .not_in_dark
     * </pre>
     *
     * @return vrai si l'alien ne voit pas (assez sombre)
     */
    private boolean isInDark(AlienRuntimeState a) {
        // Si l'alien est dans la meme zone que le joueur, il voit toujours
        if (a.zoneId == world.playerZone()) return false;

        int r = world.getRandom() & 31;
        return r < world.playerRoomBrightness();
    }

    /**
     * Place l'angle de l'alien pour qu'il regarde vers (targetX, targetZ).
     * Calcul d'angle 0..4095 depuis le delta XZ.
     *
     * <p>Equivalent ASM : {@code HeadTowardsAng} (qui met le resultat dans
     * {@code AngRet}, lu ensuite par l'appelant pour ecrire dans
     * {@code EntT_CurrentAngle_w}).</p>
     */
    private void faceTowards(AlienRuntimeState a, float targetX, float targetZ) {
        float dx = targetX - a.worldX;
        float dz = targetZ - a.worldZ;
        // atan2 retourne -PI..PI, on convertit en 0..4095
        double rad = Math.atan2(dx, dz);
        if (rad < 0) rad += 2 * Math.PI;
        a.currentAngle = (int) (rad * 4096.0 / (2 * Math.PI)) & 0xFFF;
    }

    /**
     * Deplacement vers le joueur (pour modes RESPONSE charge / FOLLOWUP approach).
     * <b>Placeholder 2.A</b> : deplacement lineaire sans collision.
     */
    private void moveTowardsPlayer(AlienRuntimeState a, int speed) {
        moveTowards(a, world.playerX(), world.playerZ(), speed);
    }

    /**
     * Deplacement vers le control point cible. <b>Placeholder 2.A</b>.
     */
    private void moveTowardsControlPoint(AlienRuntimeState a, int speed) {
        if (a.targetControlPoint < 0) return;
        if (a.targetControlPoint >= world.numControlPoints()) return;
        float tx = world.controlPointX(a.targetControlPoint);
        float tz = world.controlPointZ(a.targetControlPoint);
        moveTowards(a, tx, tz, speed);
    }

    /**
     * Deplacement avec saturation a la position cible si on l'atteint dans la frame.
     *
     * <p>Phase 2.F : utilise {@link AiWorld#resolveAlienMove} pour gerer les
     * collisions avec les murs. Si l'alien rencontre un mur en allant vers
     * sa cible, il glissera le long au lieu de traverser.</p>
     */
    private void moveTowards(AlienRuntimeState a, float tx, float tz, int speed) {
        float dx = tx - a.worldX;
        float dz = tz - a.worldZ;
        float dist2 = dx * dx + dz * dz;
        if (dist2 < 0.01f) return;

        // speed est en unites Amiga / frame, on convertit avec tempFrames
        float step = speed * world.tempFrames();
        float dist = (float) Math.sqrt(dist2);

        float deltaX, deltaZ;
        if (dist <= step) {
            // On arrive a la cible cette frame -> delta = vecteur entier
            deltaX = dx;
            deltaZ = dz;
        } else {
            // Avance d'un "step" dans la direction de la cible
            deltaX = (dx / dist) * step;
            deltaZ = (dz / dist) * step;
        }

        // Phase 2.F : resolve contre les murs (slide-along-wall).
        float[] resolved = world.resolveAlienMove(
            a.worldX, a.worldZ, deltaX, deltaZ, def.collisionRadius());
        a.worldX = resolved[0];
        a.worldZ = resolved[1];

        // Update angle to face direction of movement
        faceTowards(a, tx, tz);
    }

    /**
     * Inflige des degats externes a l'alien (depuis un tir joueur, explosion, etc.).
     * Cette methode est l'equivalent de {@code add.b d0,EntT_DamageTaken_b(a0)}
     * dans l'ASM, sauf qu'on l'appelle depuis le code Java (PlayerShootSystem).
     *
     * <p>Le traitement effectif des degats (decrement HP + transitions) se
     * fait dans {@link #applyDamage} appelle au debut du prochain {@link #update}.</p>
     */
    public static void inflictDamage(AlienRuntimeState a, int amount) {
        if (a == null || a.mode == AlienBehaviour.DIE) return;
        a.damageTaken += amount;
    }
}
