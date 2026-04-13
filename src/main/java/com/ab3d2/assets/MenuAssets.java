package com.ab3d2.assets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;

import static org.lwjgl.opengl.GL33.*;

/**
 * Assets du menu AB3D2 — chargement et textures GL.
 *
 * Palette font : 8 entrées (fontpal[0..7])
 *   - fontpal[0] = fond (forcé transparent pour la texture atlas)
 *   - fontpal[1] = couleur principale du texte (utilisée dans le système feu
 *                  via buildMenuPalette : index 56 → fontpal[56>>5] = fontpal[1])
 *   - fontpal[7] = variation claire (utilisée directement dans les pixels du bitmap font)
 *
 * Couleur réelle de la police AB3D2 = #4F876B (teal-vert, analysé depuis font_green.png).
 */
public class MenuAssets {

    private static final Logger log = LoggerFactory.getLogger(MenuAssets.class);

    // ── Dimensions ────────────────────────────────────────────────────────────
    public static final int SCREEN_W       = 320;
    public static final int SCREEN_H       = 256;
    public static final int FONT_GLYPH_W   = 16;
    public static final int FONT_GLYPH_H   = 16;
    public static final int FONT_COLS      = 20;
    public static final int FONT_ROWS      = 11;
    public static final int FONT_W         = FONT_COLS * FONT_GLYPH_W;
    public static final int FONT_H         = FONT_ROWS * FONT_GLYPH_H;
    public static final int FONT_PLANES    = 3;
    public static final int FONT_FIRST_CHAR = 32;
    public static final int FONT_NUM_CHARS  = FONT_COLS * FONT_ROWS;

    // ── Données brutes ────────────────────────────────────────────────────────
    private byte[] back2Raw;
    private byte[] fontRaw;
    private byte[] creditsRaw;

    // ── Palettes ──────────────────────────────────────────────────────────────
    private int[] backpal;
    private int[] firepal;
    private int[] fontpal;
    private int[] menuPalette;

    // ── Textures GL ───────────────────────────────────────────────────────────
    private int backgroundTexture = -1;
    private int fontTexture       = -1;
    private int creditsTexture    = -1;

    private boolean loaded = false;

    // ── Chargement ────────────────────────────────────────────────────────────

    public void load(Path menuDir) {
        log.info("Loading menu assets from {}", menuDir.toAbsolutePath());

        backpal = loadPalette(menuDir.resolve("back.pal"),         4);
        firepal = loadPalette(menuDir.resolve("firepal.pal2"),     8);
        fontpal = loadPalette(menuDir.resolve("font16x16.pal2"),   8);

        if (backpal == null) backpal = defaultBackpal();
        if (firepal == null) firepal = defaultFirepal();
        if (fontpal == null) fontpal = defaultFontpal();

        menuPalette = AmigaBitplaneDecoder.buildMenuPalette(backpal, firepal, fontpal);

        back2Raw   = loadRaw(menuDir.resolve("back2.raw"));
        fontRaw    = loadRaw(menuDir.resolve("font16x16.raw2"));
        creditsRaw = loadRaw(menuDir.resolve("credits_only.raw"));

        // ── Textures GL ───────────────────────────────────────────────────────
        if (back2Raw != null) {
            int[] px = AmigaBitplaneDecoder.decode(back2Raw, SCREEN_W, SCREEN_H, 2, backpal);
            backgroundTexture = createTexture(px, SCREEN_W, SCREEN_H, false);
        }

        if (fontRaw != null) {
            // fontpal[0] forcé transparent — tous les pixels de fond = alpha 0
            // Les pixels de glyphe (colorIdx 1-7) gardent leur alpha opaque.
            // La texture sert d'alpha-mask pour Ab3dFont + drawTextureTinted.
            int[] fp = buildFontPaletteAlphaMask();
            int[] px = AmigaBitplaneDecoder.decode(fontRaw, FONT_W, FONT_H, FONT_PLANES, fp);
            fontTexture = createTexture(px, FONT_W, FONT_H, false);
            log.info("Font texture id={} ({}×{})", fontTexture, FONT_W, FONT_H);
        }

        if (creditsRaw != null) {
            int[] fp = buildFontPaletteAlphaMask();
            int[] px = AmigaBitplaneDecoder.decode(creditsRaw, SCREEN_W, 192, FONT_PLANES, fp);
            creditsTexture = createTexture(px, SCREEN_W, 192, false);
        }

        loaded = true;
        log.info("Menu assets loaded — back2={} font={} credits={}",
            back2Raw != null, fontRaw != null, creditsRaw != null);
    }

    /**
     * Palette spéciale pour le décodage de la font texture :
     * - index 0 → transparent (alpha=0)
     * - index 1-7 → blanc opaque (alpha=0xFF, RGB=blanc)
     *
     * La couleur RGB réelle n'a PAS d'importance ici, car Ab3dFont utilise
     * drawTextureTinted (shader mode 3) qui remplace le RGB par uTint=fontpal[1].
     * Seul l'ALPHA de la texture compte pour masquer les pixels de fond.
     *
     * On utilise du blanc (#FFFFFF) pour s'assurer que le shader de teinte
     * fonctionne avec n'importe quelle couleur de tint sans perte de luminosité.
     */
    private int[] buildFontPaletteAlphaMask() {
        int[] fp = new int[8];
        fp[0] = 0x00000000; // transparent
        for (int i = 1; i < 8; i++) fp[i] = 0xFFFFFFFF; // blanc opaque
        return fp;
    }

    // ── Création texture GL ───────────────────────────────────────────────────

    public static int createTexture(int[] argb, int w, int h, boolean repeat) {
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        for (int px : argb) {
            buf.put((byte)((px >> 16) & 0xFF)); // R
            buf.put((byte)((px >>  8) & 0xFF)); // G
            buf.put((byte)( px        & 0xFF)); // B
            buf.put((byte)((px >> 24) & 0xFF)); // A
        }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    // ── Destroy ───────────────────────────────────────────────────────────────

    public void destroy() {
        if (backgroundTexture >= 0) { glDeleteTextures(backgroundTexture); backgroundTexture = -1; }
        if (fontTexture       >= 0) { glDeleteTextures(fontTexture);       fontTexture       = -1; }
        if (creditsTexture    >= 0) { glDeleteTextures(creditsTexture);    creditsTexture    = -1; }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int[] loadPalette(Path p, int count) {
        if (!Files.exists(p)) { log.debug("Palette absent: {}", p.getFileName()); return null; }
        try {
            byte[] data = Files.readAllBytes(p);
            int[] pal   = AmigaBitplaneDecoder.loadPalette(data, count);
            for (int i = 0; i < pal.length; i++) pal[i] |= 0xFF000000;
            return pal;
        } catch (IOException e) { log.warn("Palette {}: {}", p.getFileName(), e.getMessage()); return null; }
    }

    private byte[] loadRaw(Path p) {
        if (!Files.exists(p)) { log.debug("Raw absent: {}", p.getFileName()); return null; }
        try { return Files.readAllBytes(p); }
        catch (IOException e) { log.warn("Raw {}: {}", p.getFileName(), e.getMessage()); return null; }
    }

    // ── Palettes par défaut ───────────────────────────────────────────────────
    // Couleurs mesurées sur font_green.png (analyse pixel) : font ≈ #4F876B

    private static int[] defaultBackpal() {
        return new int[]{0xFF131317, 0xFF232B27, 0xFF1F231B, 0xFF131B1B};
    }

    private static int[] defaultFirepal() {
        // Dégradé feu : rouge foncé → orange → jaune
        return new int[]{
            0xFF170B00, 0xFF321200, 0xFF4D1500, 0xFF681300,
            0xFF820C00, 0xFF933F05, 0xFFA1710B, 0xFFB2AC12
        };
    }

    private static int[] defaultFontpal() {
        // Couleur réelle du menu AB3D2 mesurée : #4F876B (teal-vert)
        // Toutes les variantes restent proches de ce teal-vert.
        return new int[]{
            0xFF000000,  // 0 : fond (transparent dans la texture)
            0xFF4F876B,  // 1 : couleur principale (utilisée dans le feu via fontpal[c>>5])
            0xFF3A6B52,  // 2 : légèrement plus sombre
            0xFF2F5A44,  // 3 : plus sombre
            0xFF4F876B,  // 4 : identique à 1 (font mono)
            0xFF5A9678,  // 5 : légèrement plus clair
            0xFF3D7A5A,  // 6 : intermédiaire
            0xFF4F876B,  // 7 : même que 1 (pixels du bitmap)
        };
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int    getBackgroundTexture() { return backgroundTexture; }
    public int    getFontTexture()       { return fontTexture; }
    public int    getCreditsTexture()    { return creditsTexture; }
    public int[]  getMenuPalette()       { return menuPalette; }
    public int[]  getFirepal()           { return firepal; }
    public int[]  getBackpal()           { return backpal; }
    public int[]  getFontpal()           { return fontpal; }
    public byte[] getFontRaw()           { return fontRaw; }
    public byte[] getBack2Raw()          { return back2Raw; }
    public boolean isLoaded()            { return loaded; }
}
