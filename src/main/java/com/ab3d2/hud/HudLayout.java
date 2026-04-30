package com.ab3d2.hud;

/**
 * Geometrie du HUD d'Alien Breed 3D II en coordonnees natives Amiga 320x256.
 *
 * <p>Source de verite pour toutes les positions/dimensions du HUD. Toutes les valeurs
 * viennent directement de {@code screen.h} et {@code draw.h} du code original C.
 * Cette classe est <b>independante</b> de toute resolution d'ecran ou framework graphique :
 * elle decrit uniquement la <i>geometrie logique</i> du HUD.</p>
 *
 * <h2>Conversion vers coordonnees ecran</h2>
 * Utiliser {@link HudScaling} pour convertir ces coordonnees natives en coordonnees
 * pixel de la fenetre JME (quelle que soit la resolution cible).
 *
 * <h2>Structure visuelle (native 320x256)</h2>
 * <pre>
 * y=0    +----------------------------------+
 *        |        BORDURE HAUTE (16px)      |
 * y=16   +--+----------------------------+--+
 *        |  |                            |  |
 *        |B |      ZONE 3D (small)       |B |   SMALL_XPOS=64
 *        |O |      192 x 160             |O |   SMALL_YPOS (dynamique)
 *        |R |                            |R |
 *        |D |                            |D |
 * y=176  +--+----------------------------+--+
 *        |  ZONE MESSAGES (~48 px)          |
 *        |  y = SMALL_HEIGHT+SMALL_YPOS+4   |
 * y=232  +----------------------------------+
 *        |  PANNEAU BAS (24 px)             |
 *        |  1-0    AMMO 000   ENERGY 000    |
 * y=256  +----------------------------------+
 * </pre>
 */
public final class HudLayout {

    private HudLayout() {}  // utilitaire static

    // ---------- Dimensions ecran natives (from screen.h) ----------

    /** Largeur ecran native Amiga. */
    public static final int NATIVE_WIDTH  = 320;
    /** Hauteur ecran native Amiga. */
    public static final int NATIVE_HEIGHT = 256;

    /** Largeur des bordures laterales (zone decoree orange/rose). */
    public static final int HUD_BORDER_WIDTH = 16;

    // ---------- Zone 3D (small screen = HUD entier visible) ----------

    /** Largeur de la zone 3D en mode small-screen (HUD visible). */
    public static final int SMALL_WIDTH  = 192;
    /** Hauteur de la zone 3D en mode small-screen. */
    public static final int SMALL_HEIGHT = 160;
    /** X du bord gauche de la zone 3D : (320-192)/2 = 64. */
    public static final int SMALL_XPOS   = 64;

    /**
     * Y du bord haut de la zone 3D (variable dans le jeu : permet de centrer
     * verticalement selon les preferences utilisateur).
     *
     * <p>Valeur par defaut : 20 (comme {@code SMALL_YPOS = 20} dans screen.c).</p>
     */
    public static final int SMALL_YPOS_DEFAULT = 20;

    // ---------- Zone fullscreen (3D couvre presque tout) ----------

    /** Hauteur de la zone 3D en mode fullscreen. */
    public static final int FS_HEIGHT_DEFAULT = NATIVE_HEIGHT - 16;  // = 240

    // ---------- Panneau bas (compteurs AMMO/ENERGY, slots armes) ----------

    /**
     * Position X du compteur AMMO (coordonnees natives).
     * Valeur directe de {@code DRAW_HUD_AMMO_COUNT_X} dans draw.h.
     */
    public static final int HUD_AMMO_COUNT_X = 160;
    /**
     * Position Y du compteur AMMO.
     * draw.h utilise -18 (relatif au bas) ; Y absolu = 256 - 18 = 238.
     */
    public static final int HUD_AMMO_COUNT_Y = NATIVE_HEIGHT - 18;

    /** Position X du compteur ENERGY. */
    public static final int HUD_ENERGY_COUNT_X = 272;
    /** Position Y absolue du compteur ENERGY. */
    public static final int HUD_ENERGY_COUNT_Y = NATIVE_HEIGHT - 18;

    /** X du premier slot d'arme (chiffre "1"). */
    public static final int HUD_ITEM_SLOTS_X   = 24;
    /** Y absolu des slots d'arme (= 240). */
    public static final int HUD_ITEM_SLOTS_Y   = NATIVE_HEIGHT - 16;
    /** Nombre de slots d'armes affiches (chiffres "1" a "0" = 10 slots). */
    public static final int NUM_WEAPON_SLOTS   = 10;

    // ---------- Dimensions des glyphes HUD ----------

    /** Largeur d'un chiffre AMMO/ENERGY en pixels natifs. */
    public static final int HUD_CHAR_W = 8;
    /** Hauteur d'un chiffre AMMO/ENERGY en pixels natifs. */
    public static final int HUD_CHAR_H = 7;

    /** Largeur d'un chiffre de slot d'arme. */
    public static final int HUD_CHAR_SMALL_W = 8;
    /** Hauteur d'un chiffre de slot d'arme. */
    public static final int HUD_CHAR_SMALL_H = 5;

    /** Largeur totale d'un compteur 3-chiffres (= 24 px). */
    public static final int COUNT_W = 3 * HUD_CHAR_W;

    // ---------- Font des messages runtime ----------

    /** Largeur d'un caractere de message runtime. */
    public static final int MSG_CHAR_W = 8;
    /** Hauteur d'un caractere de message runtime. */
    public static final int MSG_CHAR_H = 8;

    // ---------- Zone messages ----------

    /** Marge horizontale/verticale pour le rendu de texte. */
    public static final int TEXT_MARGIN   = 4;
    /** Espacement vertical entre 2 lignes de messages. */
    public static final int TEXT_Y_SPACING = 2;

    /** Nombre maximum de lignes de messages visibles en small-screen. */
    public static final int MSG_MAX_LINES_SMALL = 4;

    // ---------- Helpers de geometrie (coord natives) ----------

    /** X natif du premier caractere du compteur AMMO. */
    public static int getAmmoGlyphStartX()   { return HUD_AMMO_COUNT_X; }
    /** X natif du premier caractere du compteur ENERGY. */
    public static int getEnergyGlyphStartX() { return HUD_ENERGY_COUNT_X; }

    /** Y du haut du glyph AMMO/ENERGY. */
    public static int getCountGlyphTopY() { return HUD_AMMO_COUNT_Y; }

    /**
     * X natif du glyph d'un slot d'arme donne.
     * @param slotIndex 0..9 : correspond aux chiffres 1,2,...,9,0 affiches en bas-gauche
     */
    public static int getWeaponSlotX(int slotIndex) {
        return HUD_ITEM_SLOTS_X + slotIndex * HUD_CHAR_SMALL_W;
    }

    /** Y du haut des slots d'arme. */
    public static int getWeaponSlotTopY() { return HUD_ITEM_SLOTS_Y; }

    /**
     * Y natif du haut de la zone messages (small-screen).
     * @param smallYPos valeur courante de SMALL_YPOS (depend des preferences)
     */
    public static int getMessageTopY(int smallYPos) {
        return SMALL_HEIGHT + smallYPos + TEXT_MARGIN;
    }

    /** X natif du debut du texte de message. */
    public static int getMessageLeftX() {
        return HUD_BORDER_WIDTH + TEXT_MARGIN;  // = 20
    }

    /** Y natif du bas de la zone messages (limite avant le panneau AMMO/ENERGY). */
    public static int getMessageBottomY() {
        return NATIVE_HEIGHT - HUD_BORDER_WIDTH - 8;  // = 232
    }

    /**
     * Rectangle natif de la zone 3D en mode small-screen.
     * @return [x, y, width, height] en coord natives
     */
    public static int[] getSmall3DViewport(int smallYPos) {
        return new int[] { SMALL_XPOS, smallYPos, SMALL_WIDTH, SMALL_HEIGHT };
    }

    /**
     * Rectangle natif de la zone 3D en mode fullscreen.
     * @return [x, y, width, height] en coord natives
     */
    public static int[] getFullscreen3DViewport() {
        return new int[] { 0, 0, NATIVE_WIDTH, FS_HEIGHT_DEFAULT };
    }
}
