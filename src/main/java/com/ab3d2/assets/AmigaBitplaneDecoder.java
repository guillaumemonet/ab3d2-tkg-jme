package com.ab3d2.assets;

import java.nio.ByteBuffer;

/**
 * Décodeur de bitplanes Amiga vers ARGB.
 */
public final class AmigaBitplaneDecoder {

    private AmigaBitplaneDecoder() {}

    public static int[] decode(byte[] data, int width, int height, int numPlanes, int[] palette) {
        int rowBytes  = (width + 7) / 8;
        int planeSize = rowBytes * height;
        int[] pixels  = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int byteIdx  = y * rowBytes + (x / 8);
                int bitIdx   = 7 - (x % 8);
                int colorIdx = 0;
                for (int p = 0; p < numPlanes; p++) {
                    int b = data[p * planeSize + byteIdx] & 0xFF;
                    colorIdx |= (((b >> bitIdx) & 1) << p);
                }
                pixels[y * width + x] = palette[colorIdx];
            }
        }
        return pixels;
    }

    public static int[] loadPalette(byte[] data, int count) {
        int[] pal = new int[count];
        for (int i = 0; i < count && i * 4 + 3 < data.length; i++) {
            int r = data[i*4+1]&0xFF, g = data[i*4+2]&0xFF, b = data[i*4+3]&0xFF;
            pal[i] = 0xFF000000 | (r<<16) | (g<<8) | b;
        }
        return pal;
    }

    public static int[] buildMenuPalette(int[] backpal, int[] firepal, int[] fontpal) {
        int[] palette = new int[256];
        for (int c = 0; c < 256; c++) {
            if ((c & 0xE0) != 0) {
                palette[c] = fontpal[(c >> 5) & 7];
            } else if ((c & 0x1C) != 0) {
                int fi1 = (c & 0x1C) >> 2, fi2 = (c & 0x03);
                int c1 = firepal[fi1], c2 = firepal[fi2];
                int r = Math.min(255, ((c1>>16)&0xFF) + ((c2>>16)&0xFF));
                int g = Math.min(255, (((c1>>8)&0xFF)*3)/4 + ((c2>>8)&0xFF));
                int b = Math.min(255, (c1&0xFF) + (c2&0xFF));
                palette[c] = 0xFF000000 | (r<<16) | (g<<8) | b;
            } else {
                palette[c] = backpal[c & 3];
            }
        }
        return palette;
    }
}
