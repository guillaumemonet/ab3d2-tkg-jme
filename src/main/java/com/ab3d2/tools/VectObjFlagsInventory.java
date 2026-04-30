package com.ab3d2.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Diagnostic : affiche les flags corrects de tous les polygones d'un vectobj,
 * conforme a doapoly de objdrawhires.s :
 *
 * <pre>
 *   move.w (a1)+, draw_PreHoles_b             ; WORD @ poly+2
 *     -&gt; byte HAUT  = draw_PreHoles_b
 *     -&gt; byte BAS   = draw_Holes_b
 *   move.w 12(a1,d7*4), draw_PreGouraud_b     ; WORD @ poly+N*4+16
 *     -&gt; byte HAUT  = draw_PreGouraud_b
 *     -&gt; byte BAS   = draw_Gouraud_b
 * </pre>
 *
 * <p>Les tests effectifs sont :</p>
 * <pre>
 *   tst.b draw_Holes_b    -&gt; byte BAS du WORD poly+2   -&gt; mode HOLES (drawpolh)   skip idx0
 *   tst.b draw_Gouraud_b  -&gt; byte BAS du WORD gouraud  -&gt; mode GOURAUD (drawpolg) shading normal
 *   tst.b draw_PreGouraud_b -&gt; byte HAUT du WORD gouraud -&gt; mode GLARE (drawpolGL) skip idx0
 * </pre>
 *
 * <p>Usage : {@code ./gradlew dumpVectObjFlags -Pvectobj=crab}</p>
 */
public class VectObjFlagsInventory {

    public static void main(String[] args) throws IOException {
        String vectDir = args.length > 0 ? args[0] : "src/main/resources/vectobj";
        String target  = args.length > 1 ? args[1] : "crab";

        Path p = Path.of(vectDir).resolve(target);
        if (!Files.exists(p)) {
            System.err.println("ERREUR: fichier " + target + " introuvable dans " + vectDir);
            return;
        }

        byte[] data = Files.readAllBytes(p);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;
        int startOfs  = 2;
        int linesPtr  = 6 + numFrames * 4;

        System.out.printf("========== %s (%d bytes) ==========%n", target, data.length);
        System.out.printf("numPoints=%d numFrames=%d%n%n", numPoints, numFrames);

        List<Integer> partBodyOfs = new ArrayList<>();
        int pos = linesPtr;
        while (pos + 2 <= data.length) {
            short firstWord = b.getShort(pos);
            if (firstWord < 0) break;
            if (pos + 4 > data.length) break;
            partBodyOfs.add(startOfs + (firstWord & 0xFFFF));
            pos += 4;
        }

        // onOff frame 0
        int ptsOfs0 = startOfs + (b.getShort(6) & 0xFFFF);
        long onOffMask = (ptsOfs0 + 4 <= data.length) ? (b.getInt(ptsOfs0) & 0xFFFFFFFFL) : 0xFFFFFFFFL;

        int totalPolys = 0;
        int holesCount = 0;      // byte BAS flags @ poly+2 != 0 -> drawpolh (skip idx0)
        int gouraudCount = 0;    // byte BAS gouraud        != 0 -> drawpolg (normal shaded)
        int glareCount = 0;      // byte HAUT gouraud       != 0 -> drawpolGL (skip idx0)

        // Stats par texOffset
        Map<Integer, int[]> perTex = new TreeMap<>();  // [total, holes, gouraud, glare]

        for (int pi = 0; pi < partBodyOfs.size(); pi++) {
            if ((onOffMask & (1L << pi)) == 0) continue;
            int bodyOfs = partBodyOfs.get(pi);
            System.out.printf("=== Part %d (bodyOfs=0x%04X) ===%n", pi, bodyOfs);
            System.out.println("  Poly | nL | flagsW | tex    | bright | gouraudW | holes(BAS) | gouraud(BAS) | glare(HAUT) | mode");
            System.out.println("  -----+----+--------+--------+--------+----------+------------+--------------+-------------+-----");

            int ppos = bodyOfs;
            int polyIdx = 0;
            while (ppos + 4 <= data.length) {
                short numLines = b.getShort(ppos);
                if (numLines < 0 || numLines > 64) break;
                int polySize = 18 + numLines * 4;
                if (ppos + polySize > data.length) break;

                int flagsW    = b.getShort(ppos + 2) & 0xFFFF;
                int footerOfs = ppos + numLines * 4 + 12;
                int rawTex    = b.getShort(footerOfs) & 0xFFFF;
                int brightness = data[footerOfs + 2] & 0xFF;
                int gouraudW  = b.getShort(footerOfs + 4) & 0xFFFF;

                // Decodage ASM-strict : WORD = (byte_haut << 8) | byte_bas
                int holesB    = flagsW   & 0xFF;       // byte BAS du WORD flags
                int gouraudB  = gouraudW & 0xFF;       // byte BAS du WORD gouraud
                int glareB    = (gouraudW >> 8) & 0xFF;// byte HAUT du WORD gouraud

                List<String> modes = new ArrayList<>();
                if (holesB   != 0) modes.add("HOLES");
                if (glareB   != 0) modes.add("GLARE");
                if (gouraudB != 0) modes.add("gouraud");
                String mode = modes.isEmpty() ? "normal" : String.join("+", modes);

                System.out.printf("  %4d | %2d | 0x%04X | 0x%04X | %3d    | 0x%04X   | %3d        | %3d          | %3d         | %s%n",
                    polyIdx, numLines, flagsW, rawTex, brightness, gouraudW,
                    holesB, gouraudB, glareB, mode);

                int[] stats = perTex.computeIfAbsent(rawTex, k -> new int[4]);
                stats[0]++;
                if (holesB   != 0) { stats[1]++; holesCount++; }
                if (gouraudB != 0) { stats[2]++; gouraudCount++; }
                if (glareB   != 0) { stats[3]++; glareCount++; }
                totalPolys++;

                ppos += polySize;
                polyIdx++;
            }
            System.out.println();
        }

        System.out.println("========== RESUME ==========");
        System.out.printf("Total polys: %d%n", totalPolys);
        System.out.printf("  HOLES   (byte bas flags != 0,   drawpolh,  skip idx0) : %d (%.1f%%)%n",
            holesCount, 100.0*holesCount/totalPolys);
        System.out.printf("  gouraud (byte bas gouraud != 0, drawpolg,  normal   ) : %d (%.1f%%)%n",
            gouraudCount, 100.0*gouraudCount/totalPolys);
        System.out.printf("  GLARE   (byte haut gouraud != 0,drawpolGL, skip idx0) : %d (%.1f%%)%n",
            glareCount, 100.0*glareCount/totalPolys);
        System.out.println();
        System.out.println("Par texOffset :");
        System.out.println("  tex    | total | holes | gouraud | glare");
        System.out.println("  -------+-------+-------+---------+------");
        for (var e : perTex.entrySet()) {
            int[] s = e.getValue();
            System.out.printf("  0x%04X | %5d | %5d | %7d | %5d%n",
                e.getKey(), s[0], s[1], s[2], s[3]);
        }
    }
}
