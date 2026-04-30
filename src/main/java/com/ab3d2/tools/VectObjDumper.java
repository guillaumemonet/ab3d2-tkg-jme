package com.ab3d2.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Outil de diagnostic pour verifier la lecture du format vectobj.
 *
 * Usage : java com.ab3d2.tools.VectObjDumper [fichier_vectobj]
 *
 * Affiche en detail chaque champ decode, permet de verifier manuellement
 * que le parser interprete correctement le binaire.
 *
 * Reference stricte : objdrawhires.s / draw_PolygonModel
 */
public class VectObjDumper {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            // Defaut : dump passkey (petit, facile a analyser)
            String base = "src/main/resources/vectobj/";
            for (String name : new String[]{"passkey", "switch", "plink"}) {
                Path p = Path.of(base + name);
                if (Files.exists(p)) {
                    dump(p);
                    System.out.println("\n" + "=".repeat(80) + "\n");
                }
            }
        } else {
            for (String arg : args) {
                dump(Path.of(arg));
                System.out.println("\n" + "=".repeat(80) + "\n");
            }
        }
    }

    public static void dump(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        System.out.println("========== " + file.getFileName() + " (" + data.length + " bytes) ==========");

        // HEX dump des 64 premiers bytes
        System.out.println("\nHex dump header :");
        hexDump(data, 0, Math.min(128, data.length));

        // Header
        int sortIt    = b.getShort(0) & 0xFFFF;
        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;
        int startOfs  = 2;

        System.out.printf("%nHEADER (+0..+5):%n");
        System.out.printf("  [+0] sortIt    = %d (0x%04X)%n", sortIt, sortIt);
        System.out.printf("  [+2] numPoints = %d (0x%04X)%n", numPoints, numPoints);
        System.out.printf("  [+4] numFrames = %d (0x%04X)%n", numFrames, numFrames);

        if (numPoints > 4096 || numFrames > 64 || numFrames == 0) {
            System.out.println("  ABORT: valeurs incoherentes");
            return;
        }

        // Pointer table
        int frameTableOfs = 6;
        System.out.printf("%nPOINTER TABLE (@ +%d, %d entrees de 4 bytes) :%n", frameTableOfs, numFrames);
        int[] ptsOfs = new int[numFrames];
        int[] angOfs = new int[numFrames];
        for (int i = 0; i < numFrames; i++) {
            int ofs = frameTableOfs + i * 4;
            int rawPts = b.getShort(ofs) & 0xFFFF;
            int rawAng = b.getShort(ofs + 2) & 0xFFFF;
            ptsOfs[i] = startOfs + rawPts;
            angOfs[i] = startOfs + rawAng;
            System.out.printf("  frame[%d] : ptsOfs = 0x%04X -> file+0x%04X   angOfs = 0x%04X -> file+0x%04X%n",
                i, rawPts, ptsOfs[i], rawAng, angOfs[i]);
        }

        // Parts list
        // Format : chaque entree = 4 bytes
        //   WORD bodyOfs     (premier WORD, -1 = fin de la liste)
        //   WORD refPointOfs (deuxieme WORD, index dans draw_3DPointsRotated_vl pour tri)
        int linesPtr = frameTableOfs + numFrames * 4;
        System.out.printf("%nPART LIST (LinesPtr @ file+0x%X) :%n", linesPtr);
        List<int[]> parts = new ArrayList<>();
        int pos = linesPtr;
        while (pos + 2 <= data.length) {
            short firstWord = b.getShort(pos);
            if (firstWord < 0) {
                System.out.printf("  @ 0x%04X : bodyOfs=-1 -> FIN%n", pos);
                break;
            }
            if (pos + 4 > data.length) break;
            int rawBody  = firstWord & 0xFFFF;
            int bodyOfs  = startOfs + rawBody;
            int refPoint = b.getShort(pos + 2) & 0xFFFF;
            parts.add(new int[]{bodyOfs, refPoint});
            System.out.printf("  part[%d] @ 0x%04X : bodyOfs=0x%04X -> file+0x%04X   refPt=0x%04X%n",
                parts.size() - 1, pos, rawBody, bodyOfs, refPoint);
            pos += 4;
        }

        // Point data frame 0
        if (numPoints > 0 && ptsOfs.length > 0) {
            int pOfs = ptsOfs[0];
            System.out.printf("%nPOINT DATA frame 0 (@ file+0x%X) :%n", pOfs);

            if (pOfs + 4 > data.length) {
                System.out.println("  ABORT: pOfs hors limites");
                return;
            }

            long onOff = b.getInt(pOfs) & 0xFFFFFFFFL;
            System.out.printf("  [+0..3] onOff_l = 0x%08X%n", onOff);
            // Binaire
            StringBuilder bin = new StringBuilder();
            for (int i = 31; i >= 0; i--) {
                bin.append((onOff >> i) & 1);
                if (i % 8 == 0 && i > 0) bin.append(' ');
            }
            System.out.printf("            binaire = %s%n", bin);
            System.out.printf("            parts actives : ");
            for (int i = 0; i < parts.size(); i++) {
                if ((onOff & (1L << i)) != 0) System.out.print(i + " ");
            }
            System.out.println();

            int angleBytes = ((numPoints + 1) / 2) * 2;
            int ptsStart = pOfs + 4 + angleBytes;
            System.out.printf("  [+4..+%d] angleBytes = %d bytes (pointAngles)%n",
                4 + angleBytes - 1, angleBytes);
            System.out.printf("  [+%d..] points coords (%d points * 6 bytes = %d bytes)%n",
                4 + angleBytes, numPoints, numPoints * 6);

            // Dump les 8 premiers points
            System.out.println("\n  Coordinates (premiers 10 points) :");
            int toShow = Math.min(10, numPoints);
            for (int i = 0; i < toShow; i++) {
                int o = ptsStart + i * 6;
                if (o + 6 > data.length) break;
                short x = b.getShort(o);
                short y = b.getShort(o + 2);
                short z = b.getShort(o + 4);
                System.out.printf("    pt[%3d] @ 0x%04X : x=%6d  y=%6d  z=%6d%n", i, o, x, y, z);
            }
        }

        // Dump du premier polygone de la premiere part visible
        System.out.println("\n========== PREMIER POLYGONE ==========");
        long onOffMask = (numPoints > 0 && ptsOfs[0] + 4 <= data.length)
            ? (b.getInt(ptsOfs[0]) & 0xFFFFFFFFL) : 0xFFFFFFFFL;
        for (int pi = 0; pi < parts.size(); pi++) {
            if ((onOffMask & (1L << pi)) == 0) continue;
            int bodyOfs = parts.get(pi)[0];  // SESSION 59 : premier WORD = bodyOfs
            System.out.printf("%nPART %d body @ file+0x%X :%n", pi, bodyOfs);

            if (bodyOfs + 2 > data.length) break;
            short numLines = b.getShort(bodyOfs);

            System.out.printf("  [+0..1] numLines = %d%n", numLines);
            if (numLines < 0 || numLines > 64) {
                System.out.println("  ABORT: numLines invalide");
                break;
            }

            int polySize = 18 + numLines * 4;
            System.out.printf("  polySize    = 18 + N*4 = %d bytes%n", polySize);

            if (bodyOfs + polySize > data.length) {
                System.out.println("  ABORT: depasse fin de fichier");
                break;
            }

            System.out.printf("  [+2..3] flags    = 0x%04X%n", b.getShort(bodyOfs + 2) & 0xFFFF);

            // Dump hex du polygone complet
            System.out.println("\n  Hex dump du polygone complet (" + polySize + " bytes) :");
            hexDump(data, bodyOfs, polySize);

            // Decodage des vertex
            // SESSION 61 : numVerts = numLines + 1 (car draw_PutInLines tourne numLines+1
            // fois, chacune dessinant un edge v[i]->v[i+1] pour un polygone ferme)
            int numVerts = numLines + 1;
            System.out.printf("%n  Vertex list (%d vertex = numLines+1) :%n", numVerts);
            for (int v = 0; v < numVerts; v++) {
                int vOfs = bodyOfs + 4 + v * 4;
                int ptIdxWord  = b.getShort(vOfs) & 0xFFFF;
                int oldLowByte = data[vOfs + 1] & 0xFF;
                int oldHiByte  = data[vOfs]     & 0xFF;
                int u = data[vOfs + 2] & 0xFF;
                int v_byte = data[vOfs + 3] & 0xFF;
                String ok = (ptIdxWord < numPoints) ? "OK" : "HORS LIMITES (numPoints=" + numPoints + ")";
                System.out.printf("    v[%d] @ 0x%04X : WORD ptIdx=%5d  [old: hi=%3d lo=%3d]  u=%3d v=%3d  %s%n",
                    v, vOfs, ptIdxWord, oldHiByte, oldLowByte, u, v_byte, ok);
            }

            // 1 byte phantom (1 vertex de 4 bytes lu par PutInLines mais ignore)
            System.out.println("\n  Phantom 4 bytes (apres vertex list, lus par PutInLines) :");
            int phantomOfs = bodyOfs + 4 + numVerts * 4;
            hexDump(data, phantomOfs, 4);

            // Footer
            int footerOfs = bodyOfs + numLines * 4 + 12;
            System.out.printf("%n  Footer @ +0x%X (file+0x%X):%n", numLines * 4 + 12, footerOfs);
            int rawTex = b.getShort(footerOfs) & 0xFFFF;
            int br = data[footerOfs + 2] & 0xFF;
            int pa = data[footerOfs + 3] & 0xFF;
            int go = b.getShort(footerOfs + 4) & 0xFFFF;
            System.out.printf("    [+%d..%d] texOffset = 0x%04X (bank=%d, off=0x%04X)%n",
                numLines*4+12, numLines*4+13, rawTex, (rawTex & 0x8000) != 0 ? 1 : 0, rawTex & 0x7FFF);
            System.out.printf("    [+%d]    brightness = %d%n", numLines*4+14, br);
            System.out.printf("    [+%d]    polyAngle  = %d%n", numLines*4+15, pa);
            System.out.printf("    [+%d..%d] gouraud   = 0x%04X%n", numLines*4+16, numLines*4+17, go);

            // Decoder le polygone suivant aussi
            int nextPos = bodyOfs + polySize;
            if (nextPos + 2 <= data.length) {
                short nextNL = b.getShort(nextPos);
                System.out.printf("%n  POLY SUIVANT @ 0x%X : numLines = %d%n", nextPos, nextNL);
                if (nextNL >= 0 && nextNL <= 64) {
                    int nSize = 18 + nextNL * 4;
                    System.out.printf("    polySize = %d bytes%n", nSize);
                    System.out.println("    Hex :");
                    hexDump(data, nextPos, nSize);
                }
            }

            break; // dump juste la premiere part visible
        }
    }

    private static void hexDump(byte[] data, int ofs, int len) {
        for (int i = 0; i < len; i += 16) {
            System.out.printf("    %04X: ", ofs + i);
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16 && i + j < len; j++) {
                if (ofs + i + j >= data.length) break;
                int by = data[ofs + i + j] & 0xFF;
                hex.append(String.format("%02X ", by));
                ascii.append((by >= 32 && by < 127) ? (char) by : '.');
            }
            // Pad hex
            while (hex.length() < 48) hex.append(' ');
            System.out.println(hex + "  " + ascii);
        }
    }
}
