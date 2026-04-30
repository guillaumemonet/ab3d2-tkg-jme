package com.ab3d2.combat;

/**
 * Etat sante / consommables / items du joueur (parallele a {@link PlayerCombatState}).
 *
 * <p>Correspond aux champs de la structure <code>PlrT</code> (defs.i) lies aux
 * consommables et items :</p>
 *
 * <pre>
 *   UWORD PlrT_Health_w        ; 136  sante (0..maxHealth, 0 = mort)
 *   UWORD PlrT_JetpackFuel_w   ; 138  carburant jetpack (0..maxFuel)
 *   UWORD PlrT_Shield_w        ; 180  shield actif (0/1, ou duree)
 *   UWORD PlrT_Jetpack_w       ; 182  jetpack possede (0/1)
 *   UBYTE PlrT_Keys_b          ; 218  bitmask des cles obtenues
 * </pre>
 *
 * <p>L'ASM <code>Game_AddToInventory</code> (game.c en C) merge ces valeurs depuis
 * les tables {@code GLFT_AmmoGive_l} et {@code GLFT_GunGive_l} d'un objet collecte
 * dans l'inventaire du joueur. Les compteurs sont clampes aux maximums definis
 * dans <code>GModT_MaxInv</code> (game properties).</p>
 *
 * <p>Cette classe expose les accesseurs et la logique d'ajout. La detection
 * collision joueur-objet et le declenchement de la collecte sont dans
 * {@code PickupSystem}.</p>
 *
 * @since session 112
 */
public class PlayerHealthState {

    /** Max sante par defaut (Plr1_StartHealth dans le jeu original = 200). */
    public static final int DEFAULT_MAX_HEALTH = 200;

    /** Max jetpack fuel par defaut (=200). */
    public static final int DEFAULT_MAX_FUEL = 200;

    /** Sante du joueur (PlrT_Health_w). 0 = mort. */
    private int health = DEFAULT_MAX_HEALTH;
    private int maxHealth = DEFAULT_MAX_HEALTH;

    /** Jetpack fuel (PlrT_JetpackFuel_w). */
    private int jetpackFuel = 0;
    private int maxJetpackFuel = DEFAULT_MAX_FUEL;

    /** Shield actif (PlrT_Shield_w). > 0 = actif. */
    private int shield = 0;

    /** Jetpack possede (PlrT_Jetpack_w). */
    private boolean jetpackOwned = false;

    /**
     * Bitmask des cles obtenues (PlrT_Keys_b).
     *
     * <p>Le jeu utilise un bitmask 8-bit, chaque bit representant une cle :
     * une fois ramassee, le bit reste a 1 pour toute la partie. Ce sont les
     * "Passkey" qui ouvrent certaines portes/lifts (cf. raiseCondition/lowerCondition
     * d'un ZLiftable).</p>
     */
    private int keysMask = 0;

    // ── Sante ────────────────────────────────────────────────────────────

    public int getHealth()    { return health; }
    public int getMaxHealth() { return maxHealth; }
    public boolean isDead()   { return health <= 0; }

    /** Set health, clampe a [0, maxHealth]. */
    public void setHealth(int v) {
        this.health = clamp(v, 0, maxHealth);
    }

    /** Set max health (utilise au spawn / configurer la difficulte). */
    public void setMaxHealth(int max) {
        this.maxHealth = Math.max(1, max);
        if (this.health > this.maxHealth) this.health = this.maxHealth;
    }

    /** Ajoute n a la sante (clampe a maxHealth). Retourne la quantite reellement ajoutee. */
    public int addHealth(int n) {
        if (n <= 0) return 0;
        int before = health;
        health = Math.min(maxHealth, health + n);
        return health - before;
    }

    /** Inflige n degats (clampe a 0). Retourne la quantite reellement enlevee. */
    public int takeDamage(int n) {
        if (n <= 0) return 0;
        int before = health;
        health = Math.max(0, health - n);
        return before - health;
    }

    // ── Jetpack ──────────────────────────────────────────────────────────

    public int  getJetpackFuel()    { return jetpackFuel; }
    public int  getMaxJetpackFuel() { return maxJetpackFuel; }
    public void setJetpackFuel(int v)  { this.jetpackFuel = clamp(v, 0, maxJetpackFuel); }
    public void setMaxJetpackFuel(int max) {
        this.maxJetpackFuel = Math.max(0, max);
        if (this.jetpackFuel > this.maxJetpackFuel) this.jetpackFuel = this.maxJetpackFuel;
    }
    /** Ajoute du carburant. Retourne la quantite reellement ajoutee. */
    public int addJetpackFuel(int n) {
        if (n <= 0) return 0;
        int before = jetpackFuel;
        jetpackFuel = Math.min(maxJetpackFuel, jetpackFuel + n);
        return jetpackFuel - before;
    }

    public boolean hasJetpack()      { return jetpackOwned; }
    public void    giveJetpack()     { this.jetpackOwned = true; }
    public void    takeJetpack()     { this.jetpackOwned = false; }

    // ── Shield ───────────────────────────────────────────────────────────

    public int  getShield()       { return shield; }
    public void setShield(int v)  { this.shield = Math.max(0, v); }
    public boolean hasShield()    { return shield > 0; }

    // ── Keys ─────────────────────────────────────────────────────────────

    /** Bitmask brut (8 bits, 1 bit par cle). */
    public int getKeysMask() { return keysMask; }
    /** Set le bitmask brut. */
    public void setKeysMask(int mask) { this.keysMask = mask & 0xFF; }
    /** Ajoute une cle (OR du bit correspondant). */
    public void giveKey(int keyBit) {
        if (keyBit < 0 || keyBit >= 8) return;
        this.keysMask |= (1 << keyBit);
    }
    /** Test si le joueur a une cle particuliere. */
    public boolean hasKey(int keyBit) {
        if (keyBit < 0 || keyBit >= 8) return false;
        return (this.keysMask & (1 << keyBit)) != 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Reset (mort, nouveau niveau, etc.). */
    public void reset() {
        this.health         = maxHealth;
        this.jetpackFuel    = 0;
        this.shield         = 0;
        this.jetpackOwned   = false;
        this.keysMask       = 0;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public String toString() {
        return String.format(
            "PlayerHealth{HP=%d/%d fuel=%d/%d shield=%d jetpack=%s keys=0x%02X}",
            health, maxHealth, jetpackFuel, maxJetpackFuel, shield,
            jetpackOwned, keysMask);
    }
}
