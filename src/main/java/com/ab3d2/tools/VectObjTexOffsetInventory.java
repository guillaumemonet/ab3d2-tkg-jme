package com.ab3d2.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Diagnostic : liste tous les texOffsets utilises par un vectobj et
 * regroupe par "tile 64x64" selon deux hypotheses de layout.
 *
 * <p>Usage : {@code ./gradlew dumpTexOffsets -Pvectobj=crab}</p>
 */
public class VectObjTexOffsetInventory {

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

        int sortIt    = b.getShort(0) & 0xFFFF;
        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;
        int startOfs  = 2;

        System.out.printf("========== %s (%d bytes) ==========%n", target, data.length);
        System.out.printf("sortIt=%d numPoints=%d numFrames=%d%n%n", sortIt, numPoints, numFrames);

        int linesPtr = 6 + numFrames * 4;

        // Inventaire de tous les texOffsets + deltas UV
        // key = rawTexOffset, value = list des (min_col_delta, max_col_delta, min_row_delta, max_row_delta)
        TreeMap<Integer, int[]> texStats = new TreeMap<>();  // [count, minColD, maxColD, minRowD, maxRowD]

        // Parts
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

        for (int pi = 0; pi < partBodyOfs.size(); pi++) {
            if ((onOffMask & (1L << pi)) == 0) continue;
            int bodyOfs = partBodyOfs.get(pi);
            int ppos = bodyOfs;

            while (ppos + 4 <= data.length) {
                short numLines = b.getShort(ppos);
                if (numLines < 0 || numLines > 64) break;
                int polySize = 18 + numLines * 4;
                if (ppos + polySize > data.length) break;

                int footerOfs = ppos + numLines * 4 + 12;
                int rawTex = b.getShort(footerOfs) & 0xFFFF;

                int numVerts = numLines + 1;
                int minU = 999, maxU = -1, minV = 999, maxV = -1;
                for (int v = 0; v < numVerts; v++) {
                    int vOfs = ppos + 4 + v * 4;
                    int u = data[vOfs + 2] & 0xFF;
                    int vb = data[vOfs + 3] & 0xFF;
                    minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                    minV = Math.min(minV, vb); maxV = Math.max(maxV, vb);
                }

                int[] stats = texStats.computeIfAbsent(rawTex, k -> new int[]{0, 999, -1, 999, -1});
                stats[0]++;
                stats[1] = Math.min(stats[1], minU);
                stats[2] = Math.max(stats[2], maxU);
                stats[3] = Math.min(stats[3], minV);
                stats[4] = Math.max(stats[4], maxV);

                ppos += polySize;
            }
        }

        // Affichage trié
        System.out.printf("%d texOffsets distincts trouves dans %s%n%n", texStats.size(), target);
        System.out.println("texOfs  | bank | slot | rowBk | colBk | cnt | U range | V range | Interpretations");
        System.out.println("--------+------+------+-------+-------+-----+---------+---------+----------------");

        for (var e : texStats.entrySet()) {
            int tex = e.getKey();
            int[] s = e.getValue();

            int bank = (tex & 0x8000) != 0 ? 1 : 0;
            int relOfs = tex & 0x7FFF;
            int slot = relOfs & 3;
            int rowBk = relOfs / 1024;       // 0..63  si hypothese "256x64 par slot"
            int colBk = (relOfs % 1024) / 4; // 0..255 si hypothese "256x64 par slot"

            // HYPOTHESE A : layout "8 tiles de 256x64" (actuel)
            int tileA = bank * 4 + slot;  // 0..7

            // HYPOTHESE B : layout "32 tiles de 64x64" en grille 4x8
            // Si dans chaque slot-row (256 cols), 4 tiles de 64 cols sont cote a cote :
            // tileCol64 = colBk / 64  (0..3)
            // tileRow64 = bank*4 + slot  (0..7)
            // tile index = tileRow64 * 4 + tileCol64
            int tileCol64 = colBk / 64;
            int tileRow64 = bank * 4 + slot;
            int tileB = tileRow64 * 4 + tileCol64;
            int colIn64 = colBk % 64;
            int rowIn64 = rowBk;  // rowBk est deja 0..63

            System.out.printf("0x%04X | %4d | %4d | %5d | %5d | %3d | %3d-%-3d | %3d-%-3d | A=tile%d(256x64) B=tile%d(64x64 col=%d row=%d)%n",
                tex, bank, slot, rowBk, colBk, s[0],
                s[1], s[2], s[3], s[4],
                tileA, tileB, colIn64, rowIn64);
        }
    }
}
