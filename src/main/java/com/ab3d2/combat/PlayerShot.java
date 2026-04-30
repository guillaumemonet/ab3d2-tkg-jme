package com.ab3d2.combat;

import com.jme3.scene.Geometry;

/**
 * Instance d'un projectile (bullet) du joueur en vol.
 *
 * <p>Correspondance avec la structure <code>ShotT</code> d'AB3D2 (defs.i,
 * taille <code>ShotT_SizeOf_l = 64</code>) et son header <code>ObjT</code> :</p>
 *
 * <pre>
 *   STRUCTURE ObjT,0
 *     ULONG ObjT_XPos_l           ; 0 position X (unites Amiga)
 *     ULONG ObjT_ZPos_l           ; 4 position Z
 *     ULONG ObjT_YPos_l           ; 8 position Y
 *     UWORD ObjT_ZoneID_w         ; 12 zone courante (-1 = slot libre)
 *     ...
 *     UBYTE ObjT_TypeID_b         ; 16 = OBJ_TYPE_PROJECTILE (2)
 *
 *   STRUCTURE ShotT,ObjT_Header_SizeOf_l
 *     UWORD ShotT_VelocityX_w     ; 18
 *     UWORD ShotT_VelocityZ_w     ; 22
 *     UWORD ShotT_Power_w         ; 28 degats
 *     UBYTE ShotT_Status_b        ; 30 0=en vol, 1=explose/pop
 *     UBYTE ShotT_Size_b          ; 31 bullet type (0..19)
 *     UWORD ShotT_VelocityY_w     ; 42
 *     LABEL ShotT_AccYPos_w       ; 44 position Y accumulee
 *     UBYTE ShotT_Anim_b          ; 52 frame d'animation courante
 *     UWORD ShotT_Gravity_w       ; 54 gravity copie de BulT
 *     UWORD ShotT_Lifetime_w      ; 58 accumule tpf, compare a BulT_Lifetime_l
 *     UWORD ShotT_Flags_w         ; 60 bounceHoriz (LO) / bounceVert (HI)
 *     UBYTE ShotT_Worry_b         ; 62 0xFF = actif
 *     UBYTE ShotT_InUpperZone_b   ; 63 quelle zone (upper/lower)
 * </pre>
 *
 * <p>Unites : on reste dans les unites JME (metres) cote Java.
 * Conversion depuis les unites Amiga (1 JME unit = 32 px Amiga) selon les
 * conventions deja etablies (voir SCALE = 128 dans VectObjConverter).</p>
 */
public class PlayerShot {

    /** Bullet type (index dans BulletDefs 0..19), correspond a ShotT_Size_b. */
    public int bulletType;

    /** Position 3D (unites JME). */
    public float posX, posY, posZ;

    /** Velocite (unites JME par seconde). */
    public float velX, velY, velZ;

    /** Degats a appliquer en cas de hit (ShotT_Power_w). */
    public int power;

    /**
     * Lifetime accumule en frames Amiga (ShotT_Lifetime_w).
     * Quand il depasse BulT_Lifetime_l, la bullet meurt (timeout).
     * BulT_Lifetime_l = -1 signifie vie infinie.
     */
    public float lifetime;

    /** Gravity copie depuis BulT_Gravity_l. */
    public int gravity;

    /** Flags de rebond : bit 0 = bounceHoriz, bit 1 = bounceVert. */
    public int bounceFlags;

    /**
     * Status ShotT_Status_b : 0 = en vol, 1 = touche quelque chose / animation
     * d'impact en cours. Correspond au bit "popping" de l'ASM.
     */
    public int status;

    /** Frame d'animation courante (ShotT_Anim_b). */
    public int animFrame;

    /**
     * Zone ID courante. -1 = slot libre (equivalent ObjT_ZoneID_w &lt; 0).
     * Dans notre port JME, on utilise cette valeur comme marqueur "bullet
     * active ou non". Vraie zone calculee plus tard quand on aura le pathing.
     */
    public int zoneId = -1;

    /**
     * Geometry JME representant visuellement la bullet (placeholder sphere
     * coloree en phase 1.C, sprite billboard plus tard en phase 1.D).
     */
    public Geometry geometry;

    /**
     * Identifie quel systeme gere le mouvement de cette bullet :
     * {@link #HANDLER_SIMPLE} = BulletUpdateSystem (mouvement lineaire, rapide),
     * {@link #HANDLER_PHYSICS} = PhysicsBulletSystem (Bullet Physics, gravite+rebond).
     *
     * <p>Fixe au moment du spawn selon
     * {@link PhysicsBulletSystem#shouldUsePhysics(BulletDef)}.</p>
     */
    public int handler = HANDLER_SIMPLE;

    /** Handler : mouvement lineaire simple (plasma, rocket, lazer). */
    public static final int HANDLER_SIMPLE  = 0;
    /** Handler : Bullet Physics avec gravite + rebond (grenade, mine). */
    public static final int HANDLER_PHYSICS = 1;

    /** True = slot libre (non alloue), false = bullet active en vol. */
    public boolean isFree() { return zoneId < 0; }

    /** Libere le slot et nettoie les refs pour GC / re-use. */
    public void release() {
        zoneId = -1;
        geometry = null;
        status = 0;
        animFrame = 0;
        lifetime = 0f;
        handler = HANDLER_SIMPLE;
    }

    @Override
    public String toString() {
        return String.format("PlayerShot{type=%d pos=(%.2f,%.2f,%.2f) vel=(%.2f,%.2f,%.2f) life=%.1f}",
            bulletType, posX, posY, posZ, velX, velY, velZ, lifetime);
    }
}
