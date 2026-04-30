package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Outil de diagnostic : scanne un vectobj et extrait TOUS les texOffsets
 * utilises par ses polygones.
 *
 * <p>Sorties :</p>
 * <ul>
 *   <li>Console : liste des (polyIdx, texOffset, bank, rowStart, colStart, brightness)</li>
 *   <li>PNG : atlas annote avec un rectangle par polygone, numerote.
 *            Permet de voir visuellement ou les polygones pointent.</li>
 * </ul>
 *
 * <p>Utile pour diagnostiquer les modeles qui n'ont pas la bonne texture
 * (ex: Mantis en session 65).</p>
 *
 * Usage Gradle :
 * <pre>./gradlew scanVectObjTex -Pvectobj=Mantis</pre>
 */
public class VectObjTextureScan {

    public static void main(String[] args) throws Exception {
        String vectDir = args.length > 0 ? args[0] : "src/main/resources/vectobj";
        String outDir  = args.length > 1 ? args[1] : "build/tex-scan";
        String palDir  = args.length > 2 ? args[2] : "src/main/resources";
        String target  = args.length > 3 ? args[3] : "Mantis";

        Path vect = Path.of(vectDir);
        Path out  = Path.of(outDir);
        Files.createDirectories(out);

        // --- Charger les donnees texture pour l'atlas annote ---
        byte[] texData   = Files.readAllBytes(vect.resolve("newtexturemaps"));
        byte[] shadeData = Files.readAllBytes(vect.resolve("newtexturemaps.pal"));
        int[]  palette   = TextureMapConverter.loadGlobalPalette(Path.of(palDir));

        // --- Lister les vectobj a scanner ---
        List<Path> files = new ArrayList<>();
        if (target.equals("ALL")) {
            try (var stream = Files.list(vect)) {
                files = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.equals("SBDepack")
                            && !n.endsWith(".pal")
                            && !n.equals("newtexturemaps");
                    })
                    .sorted()
                    .toList();
            }
        } else {
            Path target_file = vect.resolve(target);
            if (!Files.exists(target_file)) {
                System.err.println("ERREUR: fichier " + target + " introuvable dans " + vect);
                return;
            }
            files = List.of(target_file);
        }

        for (Path file : files) {
            scanOne(file, out, texData, shadeData, palette);
        }
    }

    private static void scanOne(Path file, Path outDir,
                                 byte[] texData, byte[] shadeData, int[] palette) throws IOException {
        String name = file.getFileName().toString();
        byte[] data = Files.readAllBytes(file);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        System.out.printf("%n========== %s (%d bytes) ==========%n", name, data.length);

        int sortIt    = b.getShort(0) & 0xFFFF;
        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;
        int startOfs  = 2;

        System.out.printf("sortIt=%d numPoints=%d numFrames=%d%n", sortIt, numPoints, numFrames);

        if (numPoints > 4096 || numFrames > 64 || numFrames == 0) {
            System.out.println("ABORT : valeurs incoherentes");
            return;
        }

        // Parts list
        int linesPtr = 6 + numFrames * 4;
        List<Integer> partBodyOfs = new ArrayList<>();
        int pos = linesPtr;
        while (pos + 2 <= data.length) {
            short firstWord = b.getShort(pos);
            if (firstWord < 0) break;
            if (pos + 4 > data.length) break;
            partBodyOfs.add(startOfs + (firstWord & 0xFFFF));
            pos += 4;
        }
        System.out.printf("%d parts trouvees%n", partBodyOfs.size());

        // onOffMask de frame 0
        int ptsOfs0 = startOfs + (b.getShort(6) & 0xFFFF);
        long onOffMask = 0xFFFFFFFFL;
        if (ptsOfs0 + 4 <= data.length) {
            onOffMask = b.getInt(ptsOfs0) & 0xFFFFFFFFL;
        }

        // Scan tous les polygones de toutes les parts visibles
        List<PolyInfo> polys = new ArrayList<>();
        for (int pi = 0; pi < partBodyOfs.size(); pi++) {
            if ((onOffMask & (1L << pi)) == 0) continue;  // part invisible frame 0

            int bodyOfs = partBodyOfs.get(pi);
            int curPos = bodyOfs;
            int polyIdx = 0;
            while (curPos + 4 <= data.length) {
                short numLines = b.getShort(curPos);
                if (numLines < 0 || numLines > 64) break;

                int polySize = 18 + numLines * 4;
                if (curPos + polySize > data.length) break;

                int footerOfs = curPos + numLines * 4 + 12;
                int rawTex     = b.getShort(footerOfs) & 0xFFFF;
                int brightness = data[footerOfs + 2] & 0xFF;

                polys.add(new PolyInfo(
                    pi, polyIdx, numLines,
                    rawTex,
                    TextureMapConverter.texOffsetToBank(rawTex),
                    TextureMapConverter.texOffsetToRowStart(rawTex),
                    TextureMapConverter.texOffsetToColumn(rawTex),
                    brightness
                ));

                curPos += polySize;
                polyIdx++;
            }
        }
        System.out.printf("%d polygones scannes au total%n%n", polys.size());

        // --- Tableau console ---
        System.out.println("part.poly | texOffset  | bank | rowStart | colStart | brightness");
        System.out.println("----------|------------|------|----------|----------|-----------");
        // Limite a 50 polys en console
        int shown = Math.min(polys.size(), 50);
        for (int i = 0; i < shown; i++) {
            PolyInfo pi = polys.get(i);
            System.out.printf("  %d.%-4d  | 0x%04X     |  %d   |    %2d    |   %3d    |    %3d%n",
                pi.partIdx, pi.polyIdxInPart, pi.rawTex, pi.bank,
                pi.rowStart, pi.colStart, pi.brightness);
        }
        if (polys.size() > shown) System.out.printf("... et %d autres%n", polys.size() - shown);

        // --- Statistiques par banque ---
        int[] bankCounts = new int[2];
        for (PolyInfo pi : polys) bankCounts[pi.bank]++;
        System.out.printf("%nStats par banque : bank0=%d  bank1=%d%n", bankCounts[0], bankCounts[1]);

        // --- Distribution des rowStart ---
        Map<Integer, Integer> rowHist = new TreeMap<>();
        for (PolyInfo pi : polys) {
            rowHist.merge(pi.rowStart, 1, Integer::sum);
        }
        System.out.println("Distribution rowStart :");
        for (var e : rowHist.entrySet()) {
            System.out.printf("  row %2d : %d polys%n", e.getKey(), e.getValue());
        }

        // --- Distribution des colStart ---
        Map<Integer, Integer> colHist = new TreeMap<>();
        for (PolyInfo pi : polys) {
            colHist.merge(pi.colStart, 1, Integer::sum);
        }
        System.out.printf("Distribution colStart : %d valeurs distinctes, range %d..%d%n",
            colHist.size(),
            colHist.keySet().stream().min(Integer::compare).orElse(0),
            colHist.keySet().stream().max(Integer::compare).orElse(0));

        // --- PNG annote : atlas + rectangles de polygones ---
        BufferedImage atlas = renderAnnotatedAtlas(texData, shadeData, palette, polys);
        Path outPng = outDir.resolve(name + "_texscan.png");
        ImageIO.write(atlas, "PNG", outPng.toFile());
        System.out.printf("%nAnnotation PNG : %s%n", outPng.toAbsolutePath());
    }

    /**
     * Rend l'atlas avec un rectangle par polygone :
     * - rouge = bank 0
     * - bleu = bank 1
     * - le rectangle part du (colStart, rowStart + bank*64) avec taille estimee
     */
    private static BufferedImage renderAnnotatedAtlas(byte[] texData, byte[] shadeData,
                                                       int[] palette, List<PolyInfo> polys) {
        int W = TextureMapConverter.BANK_COLS;          // 256
        int H = TextureMapConverter.BANK_ROWS * 2;      // 128
        int ZOOM = 4;

        BufferedImage img = new BufferedImage(W * ZOOM, H * ZOOM, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // Base atlas (fond)
        for (int bank = 0; bank < 2; bank++) {
            BufferedImage bankImg = TextureMapConverter.renderBank(texData, shadeData, palette, bank);
            g.drawImage(bankImg, 0, bank * TextureMapConverter.BANK_ROWS * ZOOM,
                W * ZOOM, TextureMapConverter.BANK_ROWS * ZOOM, null);
        }

        // Rectangles par polygone
        for (PolyInfo pi : polys) {
            int x = pi.colStart * ZOOM;
            int y = (pi.bank * TextureMapConverter.BANK_ROWS + pi.rowStart) * ZOOM;
            // Couleur : rouge vif pour bank0, bleu cyan pour bank1
            g.setColor(pi.bank == 0 ? new Color(255, 64, 64, 180) : new Color(64, 200, 255, 180));
            // Petite croix au point d'ancrage
            g.drawLine(x - ZOOM, y, x + ZOOM, y);
            g.drawLine(x, y - ZOOM, x, y + ZOOM);
            // Cercle de 8 pixels d'atlas = 8*ZOOM au rendu
            g.drawOval(x - ZOOM, y - ZOOM, 2 * ZOOM, 2 * ZOOM);
        }

        g.dispose();
        return img;
    }

    record PolyInfo(
        int partIdx,
        int polyIdxInPart,
        int numLines,
        int rawTex,
        int bank,
        int rowStart,
        int colStart,
        int brightness
    ) {}
}
