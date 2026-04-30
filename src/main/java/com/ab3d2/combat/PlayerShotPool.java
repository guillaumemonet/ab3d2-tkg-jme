package com.ab3d2.combat;

/**
 * Pool fixe de {@value #NUM_PLR_SHOT_DATA} slots de projectiles joueur.
 *
 * <p>Correspondance avec <code>Plr_ShotDataPtr_l</code> dans l'ASM :
 * <code>NUM_PLR_SHOT_DATA EQU 20</code> (defs.i).</p>
 *
 * <p>L'allocation consiste a trouver le premier slot avec
 * <code>ObjT_ZoneID_w &lt; 0</code> (libre), exactement comme le fait l'ASM :</p>
 *
 * <pre>
 *   move.l  Plr_ShotDataPtr_l, a0
 *   move.w  #NUM_PLR_SHOT_DATA-1, d1
 * .findonefree:
 *   move.w  ObjT_ZoneID_w(a0), d0
 *   blt.s   .foundonefree        ; trouve si &lt; 0
 *   NEXT_OBJ a0
 *   dbra    d1, .findonefree
 *   rts                           ; aucun slot : abandonne
 * </pre>
 *
 * <p>Si aucun slot libre, l'ASM <b>abandonne silencieusement le tir</b> (rts).
 * On fait pareil ici : <code>allocate()</code> retourne <code>null</code>
 * et le <code>PlayerShootSystem</code> doit gerer le cas.</p>
 */
public class PlayerShotPool {

    /** Taille du pool, = <code>NUM_PLR_SHOT_DATA</code> ASM. */
    public static final int NUM_PLR_SHOT_DATA = 20;

    private final PlayerShot[] slots;

    public PlayerShotPool() {
        this.slots = new PlayerShot[NUM_PLR_SHOT_DATA];
        for (int i = 0; i < NUM_PLR_SHOT_DATA; i++) {
            slots[i] = new PlayerShot();  // tous libres (zoneId = -1 par defaut)
        }
    }

    /**
     * Cherche un slot libre (zoneId &lt; 0) et le retourne, ou null.
     *
     * <p>Le slot retourne est encore "libre" : c'est a l'appelant d'initialiser
     * les champs (position, velocite, bulletType, zoneId non-negatif, etc.)
     * pour marquer la bullet active.</p>
     */
    public PlayerShot allocate() {
        for (PlayerShot s : slots) {
            if (s.isFree()) return s;
        }
        return null;
    }

    /** Tableau sous-jacent (pour iteration par le BulletUpdateSystem). */
    public PlayerShot[] getSlots() {
        return slots;
    }

    /** Nombre de slots actuellement actifs (non libres). */
    public int activeCount() {
        int n = 0;
        for (PlayerShot s : slots) if (!s.isFree()) n++;
        return n;
    }

    /** Libere tous les slots (changement de niveau, mort joueur). */
    public void releaseAll() {
        for (PlayerShot s : slots) s.release();
    }
}
