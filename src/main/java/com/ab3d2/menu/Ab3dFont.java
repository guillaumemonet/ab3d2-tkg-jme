package com.ab3d2.menu;

import com.ab3d2.assets.MenuAssets;
import com.ab3d2.render.Renderer2D;

/**
 * Rendu de la police AB3D2 16×16 depuis l'atlas font16x16.
 *
 * La texture fontTexture est déjà décodée avec les bonnes couleurs :
 *   - index 0 = alpha 0 (transparent — pixels noirs du fond)
 *   - index 1..7 = couleurs fontpal avec alpha 255
 *
 * On utilise simplement drawTexture — pas de teinte, pas de shader spécial.
 * Les pixels noirs sont transparents, les autres s'affichent tels quels.
 */
public class Ab3dFont {

    private static final int GLYPH_W   = MenuAssets.FONT_GLYPH_W;
    private static final int GLYPH_H   = MenuAssets.FONT_GLYPH_H;
    private static final int FONT_COLS = MenuAssets.FONT_COLS;
    private static final int ATLAS_W   = MenuAssets.FONT_W;
    private static final int ATLAS_H   = MenuAssets.FONT_H;
    private static final int FIRST     = MenuAssets.FONT_FIRST_CHAR;
    private static final int NUM_CHARS = MenuAssets.FONT_NUM_CHARS;

    private final int fontTexture;

    public Ab3dFont(int fontTexture) {
        this.fontTexture = fontTexture;
    }

    /** Dessine une chaîne à (x, y) en pixels. */
    public void drawString(Renderer2D r, String text, float x, float y) {
        if (fontTexture < 0) return;
        float curX = x, baseX = x, curY = y;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < FIRST) { curY += 20; curX = baseX; continue; }
            int idx = c - FIRST;
            if (idx >= NUM_CHARS) { curX += GLYPH_W; continue; }
            drawGlyph(r, idx, curX, curY);
            curX += GLYPH_W;
        }
    }

    /** XPos en bytes (1 byte = 8px), comme mnu_printxy. */
    public void drawStringBytes(Renderer2D r, String text, int xBytes, int yPx) {
        drawString(r, text, xBytes * 8f, yPx);
    }

    private void drawGlyph(Renderer2D r, int idx, float dstX, float dstY) {
        int atlasX = (idx % FONT_COLS) * GLYPH_W;
        int atlasY = (idx / FONT_COLS) * GLYPH_H;
        r.drawTexture(fontTexture,
                dstX, dstY, GLYPH_W, GLYPH_H,
                (float) atlasX / ATLAS_W,
                (float) atlasY / ATLAS_H,
                (float)(atlasX + GLYPH_W) / ATLAS_W,
                (float)(atlasY + GLYPH_H) / ATLAS_H);
    }

    public float stringWidth(String text) {
        int w = 0, maxW = 0;
        for (char c : text.toCharArray()) {
            if (c < FIRST) { maxW = Math.max(maxW, w); w = 0; }
            else w += GLYPH_W;
        }
        return Math.max(maxW, w);
    }

    public int getFontTexture() { return fontTexture; }
}
