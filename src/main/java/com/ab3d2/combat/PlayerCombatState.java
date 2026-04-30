package com.ab3d2.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Etat combat du joueur (ammo, weapons, cooldown, arme selectionnee).
 *
 * <p>Correspondance stricte avec les champs de la structure <code>PlrT</code>
 * d'AB3D2 (defs.i) lies au combat :</p>
 *
 * <pre>
 *   UWORD PlrT_TimeToShoot_w       ; 134  cooldown restant avant prochain tir
 *   UWORD PlrT_AmmoCounts_vw       ; 140  UWORD[20] compteur par bullet type
 *   UWORD PlrT_Weapons_vb          ; 184  UWORD[10] flags possede par arme
 *   UWORD PlrT_GunFrame_w          ; 204  frame courante de l'anim d'arme
 *   UBYTE PlrT_GunSelected_b       ; 222  arme active (0..9)
 *   UBYTE PlrT_Fire_b              ; 227  input fire (pressed)
 *   UBYTE PlrT_TmpGunSelected_b    ; 232  snapshot au moment du shoot
 *   UBYTE PlrT_TmpFire_b           ; 233  snapshot au moment du shoot
 * </pre>
 *
 * <p>La logique ASM <code>Plr1_Shot</code> fait 2 passes :</p>
 * <ol>
 *   <li>Premiere partie du tick : copie <code>GunSelected</code>/<code>Fire</code>
 *       dans les champs <code>Tmp*</code>. Ca permet au reste du tick de lire
 *       des valeurs stables meme si l'input change.</li>
 *   <li>Plr1_Shot lit les <code>Tmp*</code> pour decider si on tire.</li>
 * </ol>
 *
 * <p>Cette classe expose les accesseurs. La logique de tir est dans
 * <code>PlayerShootSystem</code>.</p>
 */
public class PlayerCombatState {

    /** Nombre de types de munitions (= NUM_BULLET_DEFS). */
    public static final int NUM_AMMO_TYPES = BulletDef.COUNT;

    /** Nombre d'armes (= NUM_GUN_DEFS). */
    public static final int NUM_WEAPONS = ShootDef.COUNT;

    /** Sentinelle d'ammo infini (utilisee pour debug / cheats). */
    public static final int INFINITE_AMMO = -1;

    // ── Etat "persistant" du joueur ──────────────────────────────────────

    /** PlrT_AmmoCounts_vw : compteur d'ammo par type (index = bullet type 0..19). */
    private final int[] ammoCounts = new int[NUM_AMMO_TYPES];

    /** PlrT_Weapons_vb : true = arme possedee par le joueur (index = gun 0..9). */
    private final boolean[] weaponsOwned = new boolean[NUM_WEAPONS];

    /** PlrT_GunSelected_b : quelle arme est active (0..9). */
    private int gunSelected = 0;

    /** Listeners notifies quand gunSelected change (pour sync UI/weapon view). */
    private final List<IntConsumer> gunSelectedListeners = new ArrayList<>();

    /**
     * PlrT_TimeToShoot_w : frames restantes avant de pouvoir tirer a nouveau.
     *
     * <p>Decremente chaque frame de <code>Anim_TempFrames_w</code> (delta time
     * en frames Amiga). Quand elle atteint 0, le joueur peut tirer.</p>
     *
     * <p>En float pour compatibilite JME (tpf en secondes), convertie en
     * "frames Amiga" equivalent via <code>TICKS_PER_SECOND</code>.</p>
     */
    private float timeToShoot = 0f;

    /** Flag logique : le joueur a-t-il maintenu le bouton de tir ? */
    private boolean fireHeld = false;

    // ── Snapshot "Tmp*" : valeur au moment du Plr1_Shot courant ──────────

    private int     tmpGunSelected = 0;
    private boolean tmpFire         = false;

    // ── Configuration d'horloge ──────────────────────────────────────────

    /**
     * Nombre de "frames Amiga" par seconde. Le jeu original tourne a 25 Hz
     * (interruption VBlank PAL/2), donc un delay de 50 frames = 2 secondes.
     *
     * <p>Les valeurs de <code>ShootT_Delay_w</code> et <code>Anim_TempFrames_w</code>
     * sont interpretees dans cette unite.</p>
     */
    public static final float TICKS_PER_SECOND = 25f;

    // ── Construction ─────────────────────────────────────────────────────

    public PlayerCombatState() {
        // Etat initial : aucune arme, aucune ammo. A configurer avant spawn.
    }

    // ── Ammo ─────────────────────────────────────────────────────────────

    /** Lit l'ammo disponible pour un bullet type. <code>INFINITE_AMMO</code> = infini. */
    public int getAmmo(int bulletType) {
        if (bulletType < 0 || bulletType >= NUM_AMMO_TYPES) return 0;
        return ammoCounts[bulletType];
    }

    /** Ecrit l'ammo pour un bullet type (clampe a 0 minimum, sauf si INFINITE_AMMO). */
    public void setAmmo(int bulletType, int count) {
        if (bulletType < 0 || bulletType >= NUM_AMMO_TYPES) return;
        ammoCounts[bulletType] = (count < 0 && count != INFINITE_AMMO) ? 0 : count;
    }

    /** Consomme n ammo (aucun effet si l'ammo est infinie). */
    public void consumeAmmo(int bulletType, int n) {
        if (bulletType < 0 || bulletType >= NUM_AMMO_TYPES) return;
        if (ammoCounts[bulletType] == INFINITE_AMMO) return;
        ammoCounts[bulletType] = Math.max(0, ammoCounts[bulletType] - n);
    }

    /** Ajoute de l'ammo (ignore si infinie). */
    public void addAmmo(int bulletType, int n) {
        if (bulletType < 0 || bulletType >= NUM_AMMO_TYPES) return;
        if (ammoCounts[bulletType] == INFINITE_AMMO) return;
        ammoCounts[bulletType] += n;
    }

    // ── Weapons ──────────────────────────────────────────────────────────

    public boolean hasWeapon(int gunIdx) {
        if (gunIdx < 0 || gunIdx >= NUM_WEAPONS) return false;
        return weaponsOwned[gunIdx];
    }

    public void giveWeapon(int gunIdx) {
        if (gunIdx < 0 || gunIdx >= NUM_WEAPONS) return;
        weaponsOwned[gunIdx] = true;
    }

    public void takeWeapon(int gunIdx) {
        if (gunIdx < 0 || gunIdx >= NUM_WEAPONS) return;
        weaponsOwned[gunIdx] = false;
    }

    public int getGunSelected() { return gunSelected; }

    /** Selectionne une arme (doit etre possedee pour etre utilisable). */
    public void setGunSelected(int gunIdx) {
        if (gunIdx < 0 || gunIdx >= NUM_WEAPONS) return;
        if (this.gunSelected == gunIdx) return;
        this.gunSelected = gunIdx;
        for (IntConsumer l : gunSelectedListeners) l.accept(gunIdx);
    }

    /** Enregistre un listener appele sur chaque changement d'arme. */
    public void addGunSelectedListener(IntConsumer listener) {
        gunSelectedListeners.add(listener);
    }

    // ── Cooldown ─────────────────────────────────────────────────────────

    /** Frames restantes avant de pouvoir tirer. */
    public float getTimeToShoot() { return timeToShoot; }

    /**
     * Set le cooldown en "frames Amiga" apres un tir reussi.
     * Correspond a <code>move.w ShootT_Delay_w(a6), Plr1_TimeToShoot_w</code>
     * dans l'ASM (newplayershoot.s, .okcanshoot).
     */
    public void setTimeToShoot(float framesAmiga) {
        this.timeToShoot = framesAmiga;
    }

    /**
     * Decremente le cooldown, conformement a l'ASM :
     * <pre>
     *   move.w Anim_TempFrames_w, d0
     *   sub.w  d0, Plr1_TimeToShoot_w
     *   bge    .no_fire
     *   move.w #0, Plr1_TimeToShoot_w
     * </pre>
     *
     * @param framesAmigaDelta delta time en "frames Amiga" (ex: tpf * 25)
     */
    public void tickCooldown(float framesAmigaDelta) {
        if (timeToShoot <= 0f) return;
        timeToShoot -= framesAmigaDelta;
        if (timeToShoot < 0f) timeToShoot = 0f;
    }

    /** True si le cooldown est termine et que le tir est possible. */
    public boolean canFire() {
        return timeToShoot <= 0f;
    }

    // ── Input snapshot ───────────────────────────────────────────────────

    /** Input : l'utilisateur tient-il le bouton de tir ? */
    public boolean isFireHeld() { return fireHeld; }
    public void setFireHeld(boolean held) { this.fireHeld = held; }

    /**
     * Snapshot au debut du tick : copie GunSelected/Fire dans les Tmp*.
     * Correspond au code ASM qui fait ca avant d'appeler Plr1_Shot.
     */
    public void snapshotInput() {
        this.tmpGunSelected = gunSelected;
        this.tmpFire        = fireHeld;
    }

    public int     getTmpGunSelected() { return tmpGunSelected; }
    public boolean getTmpFire()         { return tmpFire; }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Reset complet (mort du joueur, nouveau niveau, etc.). */
    public void reset() {
        Arrays.fill(ammoCounts, 0);
        Arrays.fill(weaponsOwned, false);
        gunSelected = 0;
        timeToShoot = 0f;
        fireHeld = false;
        tmpFire = false;
        tmpGunSelected = 0;
    }

    /** Dump lisible pour logs. */
    @Override
    public String toString() {
        return String.format(
            "PlayerCombat{gun=%d cooldown=%.2f fire=%s ammo=%s}",
            gunSelected, timeToShoot, fireHeld, Arrays.toString(ammoCounts));
    }
}
