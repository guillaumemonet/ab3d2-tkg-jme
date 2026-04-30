package com.ab3d2.hud;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Etat affichable du HUD : munitions, energie, arme selectionnee, messages recents.
 *
 * <p>Cette classe est un simple modele de donnees, decouple du rendu JME.
 * Le {@code HudAppState} la lit chaque frame pour mettre a jour l'affichage.</p>
 *
 * <p>Correspond aux variables du code original :
 * {@code draw_DisplayAmmoCount_w}, {@code draw_DisplayEnergyCount_w},
 * {@code Plr_GunSelected_b}, et la ring buffer {@code msg_Buffer} de message.c.</p>
 */
public final class HudState {

    /** Limite d'affichage superieure (chiffres a 3 positions). */
    public static final int DISPLAY_COUNT_LIMIT   = 999;
    /** Seuil bas de AMMO au-dela duquel on colore en rouge. */
    public static final int LOW_AMMO_WARN_LIMIT   = 9;
    /** Seuil bas de ENERGY au-dela duquel on colore en rouge. */
    public static final int LOW_ENERGY_WARN_LIMIT = 9;

    /**
     * Representation d'un message affichable.
     * @param text texte du message
     * @param tag categorie de message (0..3, determine la couleur)
     */
    public record Message(String text, int tag) {
        public static final int TAG_NARRATIVE = 0;  // vert intense
        public static final int TAG_DEFAULT   = 1;  // vert moyen
        public static final int TAG_OPTIONS   = 2;  // gris-bleu
        public static final int TAG_OTHER     = 3;  // gris moyen
    }

    // ---------- Compteurs (valeurs dynamiques) ----------

    private int ammoCount   = 0;
    private int energyCount = 100;

    // ---------- Selection d'arme ----------

    /**
     * Arme selectionnee (0..9, correspond aux touches 1-9,0 du clavier).
     * -1 = aucune arme selectionnee (ne devrait pas arriver en jeu normal).
     */
    private int selectedWeapon = 0;

    /**
     * Pour chaque slot 0..9, indique si l'arme est disponible (possedee par le joueur).
     * Les armes possedees mais non selectionnees s'affichent en rose/jaune.
     */
    private final boolean[] availableWeapons = new boolean[HudLayout.NUM_WEAPON_SLOTS];

    // ---------- Messages ----------

    /**
     * File des messages recents. Les plus anciens sont en tete, les plus recents en queue.
     * Capacite limitee a {@link HudLayout#MSG_MAX_LINES_SMALL} (les anciens sont evinces).
     */
    private final Deque<Message> messages = new ArrayDeque<>();

    // ---------- AMMO ----------

    public int  getAmmoCount() { return ammoCount; }
    public void setAmmoCount(int v) {
        this.ammoCount = clamp(v, 0, DISPLAY_COUNT_LIMIT);
    }
    public boolean isAmmoLow() { return ammoCount <= LOW_AMMO_WARN_LIMIT; }

    // ---------- ENERGY ----------

    public int  getEnergyCount() { return energyCount; }
    public void setEnergyCount(int v) {
        this.energyCount = clamp(v, 0, DISPLAY_COUNT_LIMIT);
    }
    public boolean isEnergyLow() { return energyCount <= LOW_ENERGY_WARN_LIMIT; }

    // ---------- Armes ----------

    public int  getSelectedWeapon() { return selectedWeapon; }
    public void setSelectedWeapon(int slot) {
        if (slot >= 0 && slot < HudLayout.NUM_WEAPON_SLOTS)
            this.selectedWeapon = slot;
    }

    public boolean isWeaponAvailable(int slot) {
        return slot >= 0 && slot < availableWeapons.length && availableWeapons[slot];
    }
    public void setWeaponAvailable(int slot, boolean available) {
        if (slot >= 0 && slot < availableWeapons.length) availableWeapons[slot] = available;
    }

    // ---------- Messages ----------

    public void pushMessage(String text, int tag) {
        if (text == null || text.isBlank()) return;
        // Eviter duplicats consecutifs (comme Msg_PushLineDedupLast dans message.c)
        Message last = messages.peekLast();
        if (last != null && last.text.equals(text)) return;

        messages.addLast(new Message(text, tag));
        while (messages.size() > HudLayout.MSG_MAX_LINES_SMALL) {
            messages.removeFirst();
        }
    }

    public void pushMessage(String text) {
        pushMessage(text, Message.TAG_DEFAULT);
    }

    public void clearMessages() { messages.clear(); }

    /** Retourne les messages, du plus ancien au plus recent. */
    public Iterable<Message> getMessages() { return messages; }

    /** Nombre de messages actuellement dans la file. */
    public int getMessageCount() { return messages.size(); }

    // ---------- Helpers ----------

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
