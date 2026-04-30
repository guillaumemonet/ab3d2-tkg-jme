package com.ab3d2.combat;

/**
 * Configuration d'un type de projectile (bullet).
 *
 * <p>Correspondance stricte avec la structure <code>BulT</code> d'AB3D2
 * (defs.i, taille = <code>BulT_SizeOf_l = 300</code> bytes) :</p>
 *
 * <pre>
 *   STRUCTURE BulT,0
 *     ULONG BulT_IsHitScan_l          ;   0, 4  0=projectile, !=0=raycast instantane
 *     ULONG BulT_Gravity_l            ;   4, 4  gravity (0=pas de gravite)
 *     ULONG BulT_Lifetime_l           ;   8, 4  duree de vie (-1 = infini)
 *     ULONG BulT_AmmoInClip_l         ;  12, 4  ammo par clip pickup
 *     ULONG BulT_BounceHoriz_l        ;  16, 4  rebondit sur murs horizontaux
 *     ULONG BulT_BounceVert_l         ;  20, 4  rebondit sur sol/plafond
 *     ULONG BulT_HitDamage_l          ;  24, 4  degats par hit direct
 *     ULONG BulT_ExplosiveForce_l     ;  28, 4  rayon d'explosion (0=pas d'explo)
 *     ULONG BulT_Speed_l              ;  32, 4  vitesse (shift factor pour asl.l)
 *     ULONG BulT_AnimFrames_l         ;  36, 4  nb frames anim vol
 *     ULONG BulT_PopFrames_l          ;  40, 4  nb frames anim impact
 *     ULONG BulT_BounceSFX_l          ;  44, 4  SFX sur rebond
 *     ULONG BulT_ImpactSFX_l          ;  48, 4  SFX sur impact
 *     ULONG BulT_GraphicType_l        ;  52, 4  0=bitmap, 1=glare, 2=additive
 *     ULONG BulT_ImpactGraphicType_l  ;  56, 4  idem pour l'impact
 *     STRUCT BulT_AnimData_vb,120     ;  60,120 anim du projectile en vol
 *     STRUCT BulT_PopData_vb,120      ; 180,120 anim de l'impact/explosion
 *   LABEL BulT_SizeOf_l               ; 300
 * </pre>
 *
 * <p>Ces 20 BulletDefs sont stockes en sequence dans le GLF a l'offset
 * <code>GLFT_BulletDefs_l</code>.</p>
 *
 * <p>Les 20 bullet types du jeu, d'apres TEST.LNK (section
 * <code>GLFT_BulletNames_l</code>) :</p>
 * <ol start="0">
 *   <li>Plasma Bolt</li>
 *   <li>Machine Gun Bullet</li>
 *   <li>Rocket</li>
 *   <li>Splutch1</li>
 *   <li>Splutch2</li>
 *   <li>Splutch3</li>
 *   <li>Splutch4</li>
 *   <li>Shotgun Round</li>
 *   <li>Grenade</li>
 *   <li>Blaster Bolt</li>
 *   <li>Assault Lazer</li>
 *   <li>Explosion</li>
 *   <li>MindZap</li>
 *   <li>MegaPlasma</li>
 *   <li>Lazer</li>
 *   <li>Mine</li>
 *   <li>BULLET TYPE Q (placeholder)</li>
 *   <li>BULLET TYPE R (placeholder)</li>
 *   <li>BULLET TYPE S (placeholder)</li>
 *   <li>BULLET TYPE T (placeholder)</li>
 * </ol>
 *
 * @param isHitScan         Si non-zero, la bullet touche instantanement (raycast).
 *                           Sinon c'est un projectile physique qui vole.
 * @param gravity           Acceleration Y par frame. 0 = ligne droite.
 * @param lifetime          Duree de vie en frames. -1 = infini.
 * @param ammoInClip        Ammo donne par un clip pickup.
 * @param bounceHoriz       Non-zero = rebondit sur les murs (ex: grenades).
 * @param bounceVert        Non-zero = rebondit sur le sol/plafond.
 * @param hitDamage         Degats par impact direct.
 * @param explosiveForce    Rayon d'explosion (0 = pas d'explosion en AoE).
 * @param speed             Shift amount (asl.l d1, d0 dans l'ASM). La vitesse
 *                           reelle = direction * 2^speed.
 * @param animFrames        Nombre de frames de l'animation en vol.
 * @param popFrames         Nombre de frames de l'animation d'impact.
 * @param bounceSfx         Index SFX pour le rebond.
 * @param impactSfx         Index SFX pour l'impact.
 * @param graphicType       0 = bitmap sprite, 1 = glare, 2 = additive.
 * @param impactGraphicType Idem pour le sprite d'impact.
 * @param animData          120 bytes d'anim du projectile (format TBD).
 * @param popData           120 bytes d'anim de l'impact (format TBD).
 */
public record BulletDef(
    int isHitScan,
    int gravity,
    int lifetime,
    int ammoInClip,
    int bounceHoriz,
    int bounceVert,
    int hitDamage,
    int explosiveForce,
    int speed,
    int animFrames,
    int popFrames,
    int bounceSfx,
    int impactSfx,
    int graphicType,
    int impactGraphicType,
    byte[] animData,
    byte[] popData
) {
    /** Taille d'un BulletDef dans le GLF = BulT_SizeOf_l ASM. */
    public static final int SIZE_BYTES = 300;

    /** Nombre total de BulletDefs dans le GLF = NUM_BULLET_DEFS ASM. */
    public static final int COUNT = 20;

    /** Offsets des champs (tous 4 bytes/LONG sauf animData/popData). */
    public static final int OFS_IS_HITSCAN      =   0;
    public static final int OFS_GRAVITY         =   4;
    public static final int OFS_LIFETIME        =   8;
    public static final int OFS_AMMO_IN_CLIP    =  12;
    public static final int OFS_BOUNCE_HORIZ    =  16;
    public static final int OFS_BOUNCE_VERT     =  20;
    public static final int OFS_HIT_DAMAGE      =  24;
    public static final int OFS_EXPLOSIVE_FORCE =  28;
    public static final int OFS_SPEED           =  32;
    public static final int OFS_ANIM_FRAMES     =  36;
    public static final int OFS_POP_FRAMES      =  40;
    public static final int OFS_BOUNCE_SFX      =  44;
    public static final int OFS_IMPACT_SFX      =  48;
    public static final int OFS_GRAPHIC_TYPE    =  52;
    public static final int OFS_IMPACT_GFX_TYPE =  56;
    public static final int OFS_ANIM_DATA       =  60;
    public static final int ANIM_DATA_SIZE      = 120;
    public static final int OFS_POP_DATA        = 180;
    public static final int POP_DATA_SIZE       = 120;

    /**
     * Noms des 20 bullet types extraits de TEST.LNK (section
     * GLFT_BulletNames_l). Utilise pour les logs/debug.
     */
    public static final String[] NAMES = {
        "Plasma Bolt",          //  0
        "Machine Gun Bullet",   //  1
        "Rocket",               //  2
        "Splutch1",             //  3
        "Splutch2",             //  4
        "Splutch3",             //  5
        "Splutch4",             //  6
        "Shotgun Round",        //  7
        "Grenade",              //  8
        "Blaster Bolt",         //  9
        "Assault Lazer",        // 10
        "Explosion",            // 11
        "MindZap",              // 12
        "MegaPlasma",           // 13
        "Lazer",                // 14
        "Mine",                 // 15
        "BULLET TYPE Q",        // 16
        "BULLET TYPE R",        // 17
        "BULLET TYPE S",        // 18
        "BULLET TYPE T"         // 19
    };

    /** Retourne le nom lisible du type (fallback "Unknown" si hors bornes). */
    public static String nameOf(int bulletType) {
        if (bulletType < 0 || bulletType >= NAMES.length) return "Unknown";
        return NAMES[bulletType];
    }

    /**
     * Indique si cette bullet est un "hitscan" (raycast instantane) ou un
     * projectile physique. Base sur <code>BulT_IsHitScan_l</code>.
     */
    public boolean isHitScanBullet() { return isHitScan != 0; }

    /** Indique si cette bullet explose a l'impact. */
    public boolean isExplosive() { return explosiveForce != 0; }

    /** Indique si cette bullet est soumise a la gravite. */
    public boolean hasGravity() { return gravity != 0; }
}
