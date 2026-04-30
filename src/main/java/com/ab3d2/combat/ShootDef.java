package com.ab3d2.combat;

/**
 * Configuration d'une arme (gun).
 *
 * <p>Correspondance stricte avec la structure <code>ShootT</code> d'AB3D2
 * (defs.i, taille = <code>ShootT_SizeOf_l = 8</code> bytes) :</p>
 *
 * <pre>
 *   STRUCTURE ShootT,0
 *     UWORD ShootT_BulType_w   ; 0, 2  index dans GLFT_BulletDefs_l (0..19)
 *     UWORD ShootT_Delay_w     ; 2, 2  cooldown apres tir (frames Amiga)
 *     UWORD ShootT_BulCount_w  ; 4, 2  nombre de bullets par tir (shotgun=8)
 *     UWORD ShootT_SFX_w       ; 6, 2  index sample sound (0..63)
 *   LABEL ShootT_SizeOf_l      ; 8
 * </pre>
 *
 * <p>Ces 10 ShootDefs sont stockes en sequence dans le GLF a l'offset
 * <code>GLFT_ShootDefs_l</code>, lus par le code ASM <code>Plr1_Shot</code> :</p>
 *
 * <pre>
 *   lea GLFT_ShootDefs_l(a6), a6
 *   lea (a6, d0.w*8), a6            ; d0 = gunSelected 0..9
 *   move.w ShootT_BulType_w(a6), d0 ; bullet type
 *   move.w ShootT_Delay_w(a6), Plr1_TimeToShoot_w
 *   move.w ShootT_BulCount_w(a6), d7
 *   move.w ShootT_SFX_w(a6), Aud_SampleNum_w
 * </pre>
 *
 * @param bulletType Index dans BulletDefs (0..19). Ex: shotgun -&gt; type 8
 *                   (Shotgun Round), rifle -&gt; 1 (Machine Gun Bullet).
 * @param delay      Cooldown apres tir, exprime en "frames Amiga" (1 frame =
 *                   ~1/25 seconde a 25 Hz). Ex: shotgun 60 = ~2.4s.
 * @param bulletCount Nombre de projectiles tires simultanement. shotgun=8
 *                   (spread), autres armes=1.
 * @param sfx        Index dans GLFT_SFXFilenames_l (0..63).
 */
public record ShootDef(
    int bulletType,
    int delay,
    int bulletCount,
    int sfx
) {
    /** Taille d'un ShootDef dans le GLF = ShootT_SizeOf_l ASM. */
    public static final int SIZE_BYTES = 8;

    /** Nombre total de ShootDefs dans le GLF = NUM_GUN_DEFS ASM. */
    public static final int COUNT = 10;

    /** Offsets des champs dans un ShootT_SizeOf_l de 8 bytes. */
    public static final int OFS_BUL_TYPE   = 0;
    public static final int OFS_DELAY      = 2;
    public static final int OFS_BUL_COUNT  = 4;
    public static final int OFS_SFX        = 6;
}
