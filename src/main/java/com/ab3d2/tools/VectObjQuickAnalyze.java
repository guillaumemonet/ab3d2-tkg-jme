package com.ab3d2.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Analyse rapide : affiche pour chaque vectobj le header, les parts,
 * et le onOff mask de chaque frame, pour voir si c'est une animation
 * par-frame ou une geometrie multi-parts statique.
 */
public class VectObjQuickAnalyze {

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/main/resources/vectobj");
        try (var stream = Files.list(dir)) {
            for (Path f : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                String name = f.getFileName().toString();
                if (name.startsWith("newtexture") || name.equals("SBDepack")) continue;
                try {
                    analyze(f);
                } catch (Exception e) {
                    System.out.printf("%-20s ERR: %s%n", name, e.getMessage());
                }
            }
        }
    }

    static void analyze(Path f) throws IOException {
        byte[] data = Files.readAllBytes(f);
        if (data.length < 10) return;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int sortIt    = b.getShort(0) & 0xFFFF;
        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;

        if (numPoints > 4096 || numFrames > 64 || numFrames == 0) {
            System.out.printf("%-20s INVALID (nP=%d nF=%d)%n", f.getFileName(), numPoints, numFrames);
            return;
        }

        // Count parts
        int linesPtr = 6 + numFrames * 4;
        int numParts = 0;
        int pos = linesPtr;
        while (pos + 4 <= data.length && numParts < 64) {
            short first = b.getShort(pos);
            if (first < 0) break;
            numParts++;
            pos += 4;
        }

        // Read onOff mask for each frame
        System.out.printf("%-20s nP=%3d nF=%2d nParts=%2d  ", f.getFileName(), numPoints, numFrames, numParts);
        int identicalMaskCount = 0;
        long firstMask = -1;
        boolean allSame = true;
        int activeBitsInFrame0 = 0;

        for (int fr = 0; fr < Math.min(numFrames, 6); fr++) {
            int tableOfs = 6 + fr * 4;
            int ptsOfs = 2 + (b.getShort(tableOfs) & 0xFFFF);
            if (ptsOfs + 4 > data.length) continue;
            long mask = b.getInt(ptsOfs) & 0xFFFFFFFFL;
            if (fr == 0) {
                firstMask = mask;
                // Count bits set in first frame
                long m = mask & ((1L << numParts) - 1);
                for (int i = 0; i < numParts; i++) if ((m & (1L << i)) != 0) activeBitsInFrame0++;
            } else if (mask != firstMask) {
                allSame = false;
            }
            System.out.printf("f%d=%08x ", fr, mask);
        }
        System.out.printf("  %s  activeF0=%d/%d",
            allSame ? "SAME" : "DIFF", activeBitsInFrame0, numParts);
        System.out.println();
    }
}
