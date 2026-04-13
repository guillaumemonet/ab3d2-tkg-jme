package com.ab3d2.menu;

import com.ab3d2.assets.MenuAssets;
import java.util.Arrays;

/**
 * Rendu des glyphes dans un buffer de pixels actifs (true/false).
 *
 * Ce buffer est injecté dans FireEffect.setTextLayer() sous forme d'int[]
 * où tout pixel non-nul = source de feu permanente.
 *
 * Reproduit mnu_printxy : XPos en bytes (1 byte=8px), YPos en pixels.
 * Police 16×16, 20 glyphes/ligne, ASCII 32 = premier glyphe.
 */
public class MenuRenderer {

    private static final int W          = MenuAssets.SCREEN_W;
    private static final int H          = MenuAssets.SCREEN_H;
    private static final int GLYPH_W    = MenuAssets.FONT_GLYPH_W;
    private static final int GLYPH_H    = MenuAssets.FONT_GLYPH_H;
    private static final int FONT_COLS  = MenuAssets.FONT_COLS;
    private static final int ATLAS_ROW  = W / 8;                         // 40 bytes/ligne
    private static final int FONT_PLANE = ATLAS_ROW * MenuAssets.FONT_H; // taille d'un plan

    // Pixels actifs (non-nul = source de feu)
    private final int[] textLayer = new int[W * H];

    private final byte[] fontRaw;

    public MenuRenderer(byte[] fontRaw) { this.fontRaw = fontRaw; }

    public void clear() { Arrays.fill(textLayer, 0); }

    /** XPos en bytes (1 byte = 8px), YPos en pixels. */
    public void drawString(String text, int xBytes, int yPx) {
        if (fontRaw == null) return;
        int curX = xBytes * 8, baseX = curX, curY = yPx;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32) { curY += 20; curX = baseX; continue; }
            int idx = c - 32;
            if (idx >= MenuAssets.FONT_NUM_CHARS) { curX += GLYPH_W; continue; }
            drawGlyph(idx, curX, curY);
            curX += GLYPH_W;
        }
    }

    private void drawGlyph(int idx, int dstX, int dstY) {
        int atlasX = (idx % FONT_COLS) * GLYPH_W;
        int atlasY = (idx / FONT_COLS) * GLYPH_H;

        for (int row = 0; row < GLYPH_H; row++) {
            int py = dstY + row;
            if (py < 0 || py >= H) continue;
            int byteOff = (atlasY + row) * ATLAS_ROW + atlasX / 8;

            // Combine les 3 plans : un pixel est actif si n'importe quel plan est à 1
            int bits0 = (safe(byteOff, fontRaw)              << 8) | safe(byteOff + 1, fontRaw);
            int bits1 = (safe(byteOff + FONT_PLANE, fontRaw) << 8) | safe(byteOff + 1 + FONT_PLANE, fontRaw);
            int bits2 = (safe(byteOff + FONT_PLANE*2, fontRaw) << 8) | safe(byteOff + 1 + FONT_PLANE*2, fontRaw);
            int active = bits0 | bits1 | bits2;  // pixel allumé si au moins un plan l'est

            for (int col = 0; col < GLYPH_W; col++) {
                int px = dstX + col;
                if (px < 0 || px >= W) continue;
                int bit = 15 - col;
                if (((active >> bit) & 1) != 0)
                    textLayer[py * W + px] = 1;
            }
        }
    }

    private int safe(int off, byte[] data) {
        return (off >= 0 && off < data.length) ? (data[off] & 0xFF) : 0;
    }

    public int[] getTextLayer() { return textLayer; }
}
