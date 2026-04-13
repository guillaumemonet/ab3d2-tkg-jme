package com.ab3d2.assets;

/**
 * Données d'une texture de mur décodée depuis .256wad.
 */
public record WadTextureData(
        String name,
        int    width,
        int    height,
        int[]  pixels,
        int[]  shadeTable
) {
    public static final int SHADE_ROWS          = 32;
    public static final int ENTRIES_PER_ROW     = 32;
    public static final int SHADE_TABLE_ENTRIES = SHADE_ROWS * ENTRIES_PER_ROW;

    public int shadeColor(int row, int texel5bit) {
        return shadeTable[(row & 31) * ENTRIES_PER_ROW + (texel5bit & 31)];
    }
    public int widthMask()  { return width  - 1; }
    public int heightMask() { return height - 1; }
}
