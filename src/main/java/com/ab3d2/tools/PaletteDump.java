package com.ab3d2.tools;

import java.nio.file.*;

/**
 * Petit utilitaire de dump hexadecimal pour comprendre les formats de palette Amiga.
 *
 * Usage : ./gradlew dumpPalette -Ppal=panelcols
 *         ./gradlew dumpPalette -Ppal=borderpal
 */
public class PaletteDump {

    public static void main(String[] args) throws Exception {
        String srcDir = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-original/media/includes";
        String name = args.length > 1 ? args[1] : "panelcols";

        Path p = Path.of(srcDir, name);
        if (!Files.exists(p)) {
            System.err.println("Fichier absent : " + p);
            return;
        }
        byte[] data = Files.readAllBytes(p);
        System.out.printf("=== %s (%d bytes) ===%n%n", name, data.length);

        // Dump 16 octets par ligne (4 entries de 4 bytes) + interpretation
        System.out.println("Entry | HEX raw            | as 2xWord 0RGB 12b | as 32b RGBA         ");
        System.out.println("------+--------------------+--------------------+---------------------");
        int maxEntries = Math.min(data.length / 4, 64);
        for (int i = 0; i < maxEntries; i++) {
            int off = i * 4;
            int b0 = data[off] & 0xFF, b1 = data[off+1] & 0xFF;
            int b2 = data[off+2] & 0xFF, b3 = data[off+3] & 0xFF;

            // Word 1 = bytes 0-1, Word 2 = bytes 2-3
            int w1 = (b0 << 8) | b1;
            int w2 = (b2 << 8) | b3;

            // Interpretation 1 : chaque word est 0RGB 12-bit Amiga
            int r1 = (w1 >> 8) & 0xF, g1 = (w1 >> 4) & 0xF, b_1 = w1 & 0xF;
            int r2 = (w2 >> 8) & 0xF, g2 = (w2 >> 4) & 0xF, b_2 = w2 & 0xF;

            // Interpretation 2 : 4 bytes = R G B A
            String as32 = String.format("R=%3d G=%3d B=%3d A=%3d", b0, b1, b2, b3);

            System.out.printf("%4d  | %02X %02X %02X %02X          | W1=$%04X(%d,%d,%d) W2=$%04X(%d,%d,%d) | %s%n",
                i, b0, b1, b2, b3, w1, r1, g1, b_1, w2, r2, g2, b_2, as32);
        }

        // Test aussi : comme sequence de bytes
        System.out.println("\nPremiers 64 bytes en chunks de 2 (si format word-only) :");
        for (int i = 0; i < Math.min(data.length / 2, 32); i++) {
            int off = i * 2;
            int w = ((data[off] & 0xFF) << 8) | (data[off+1] & 0xFF);
            int r = (w >> 8) & 0xF, g = (w >> 4) & 0xF, b = w & 0xF;
            System.out.printf("  W%02d=$%04X -> RGB(%d,%d,%d)%n", i, w, r, g, b);
        }
    }
}
