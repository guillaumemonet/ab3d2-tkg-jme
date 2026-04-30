package com.ab3d2.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Outil de diagnostic pour l'UV mapping d'un vectobj.
 *
 * <p>Dump l'analyse detaillee de tous les polygones d'un modele :
 * ptIdx, bytes U/V bruts, tile de l'atlas, UV finale calculee.</p>
 *
 * <p>Usage :</p>
 * <pre>./gradlew dumpVectObjUVs -Pvectobj=rifle</pre>
 *
 * <p>Permet de comprendre pourquoi un polygone apparait avec la mauvaise
 * texture : verifier si le texOffset pointe bien dans la bonne tile et
 * si les deltas U/V restent dans des bornes raisonnables.</p>
 */
public class VectObjUVDump {

    public static void main(String[] args) throws IOException {
        String vectDir = args.length > 0 ? args[0] : "src/main/resources/vectobj";
        String target  = args.length > 1 ? args[1] : "rifle";

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

        // Parts
        int linesPtr = 6 + numFrames * 4;
        java.util.List<Integer> partBodyOfs = new java.util.ArrayList<>();
        int pos = linesPtr;
        while (pos + 2 <= data.length) {
            short firstWord = b.getShort(pos);
            if (firstWord < 0) break;
            if (pos + 4 > data.length) break;
            partBodyOfs.add(startOfs + (firstWord & 0xFFFF));
            pos += 4;
        }
        System.out.printf("%d parts%n%n", partBodyOfs.size());

        // onOff frame 0
        int ptsOfs0 = startOfs + (b.getShort(6) & 0xFFFF);
        long onOffMask = 0xFFFFFFFFL;
        if (ptsOfs0 + 4 <= data.length) {
            onOffMask = b.getInt(ptsOfs0) & 0xFFFFFFFFL;
        }

        // Dump polygones de toutes les parts visibles
        for (int pi = 0; pi < partBodyOfs.size(); pi++) {
            if ((onOffMask & (1L << pi)) == 0) continue;
            int bodyOfs = partBodyOfs.get(pi);
            System.out.printf("=== Part %d (bodyOfs=0x%04X) ===%n", pi, bodyOfs);
            dumpPart(data, b, bodyOfs, numPoints, pi);
            System.out.println();
        }
    }

    private static void dumpPart(byte[] data, ByteBuffer b, int bodyOfs, int numPoints, int partIdx) {
        int pos = bodyOfs;
        int polyIdx = 0;

        while (pos + 4 <= data.length) {
            short numLines = b.getShort(pos);
            if (numLines < 0 || numLines > 64) break;
            int polySize = 18 + numLines * 4;
            if (pos + polySize > data.length) break;

            int footerOfs = pos + numLines * 4 + 12;
            int rawTex = b.getShort(footerOfs) & 0xFFFF;
            int brightness = data[footerOfs + 2] & 0xFF;
            int flags = b.getShort(pos + 2) & 0xFFFF;
            int gouraud = b.getShort(footerOfs + 4) & 0xFFFF;

            int colStart  = TextureMapConverter.texOffsetToColumn(rawTex);
            int rowStart  = TextureMapConverter.texOffsetToRowStart(rawTex);
            int slot      = TextureMapConverter.texOffsetToSlot(rawTex);
            int bank      = TextureMapConverter.texOffsetToBank(rawTex);
            int atlasTile = TextureMapConverter.texOffsetToAtlasTile(rawTex);

            int numVerts = numLines + 1;

            System.out.printf("  Poly %d.%-3d numLines=%d numVerts=%d  texOfs=0x%04X (bank %d slot %d col %d row %d -> tile %d)  bright=%d  flags=0x%04X gouraud=0x%04X%n",
                partIdx, polyIdx, numLines, numVerts, rawTex, bank, slot, colStart, rowStart, atlasTile, brightness, flags, gouraud);

            int minU = 999, maxU = -1, minV = 999, maxV = -1;
            StringBuilder verts = new StringBuilder();
            for (int v = 0; v < numVerts; v++) {
                int vOfs = pos + 4 + v * 4;
                int ptIdx = b.getShort(vOfs) & 0xFFFF;
                int u = data[vOfs + 2] & 0xFF;
                int vb = data[vOfs + 3] & 0xFF;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u);
                minV = Math.min(minV, vb); maxV = Math.max(maxV, vb);

                int colFinal = colStart + vb;
                int rowFinal = rowStart + u;
                verts.append(String.format("    v[%d] pt=%d U=%d V=%d -> col=%d row=%d%n",
                    v, ptIdx, u, vb, colFinal, rowFinal));
            }
            System.out.print(verts);
            System.out.printf("    -> U range [%d..%d], V range [%d..%d]  (rowFinal [%d..%d], colFinal [%d..%d])%n",
                minU, maxU, minV, maxV,
                rowStart + minU, rowStart + maxU,
                colStart + minV, colStart + maxV);

            // Alertes
            if (rowStart + maxU > 63)
                System.out.printf("    WARN : rowFinal depasse 63 (max=%d) - texture va deborder sur tile suivante%n",
                    rowStart + maxU);
            if (colStart + maxV > 255)
                System.out.printf("    WARN : colFinal depasse 255 (max=%d) - texture va deborder en colonne%n",
                    colStart + maxV);

            pos += polySize;
            polyIdx++;
        }
    }
}
