package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Diagnostic des textures de murs : dimensions, colonnes "visibles", UV layout.
 *
 * <p>Produit un rapport qui aide a diagnostiquer les problemes d'UV :</p>
 * <ul>
 *   <li>Largeur des PNGs vs ce qui est suppose par le builder (256 par defaut)</li>
 *   <li>Nombre de "colonnes" logiques (chaque colonne = 16 px dans l'AMOS ZWG)</li>
 *   <li>Hauteur reelle (doit etre 128 pour TEX_V)</li>
 *   <li>Alpha : est-ce que la texture est opaque ?</li>
 *   <li>Pixels noirs : indicateur visuel de mauvaise conversion</li>
 * </ul>
 *
 * <p>Usage : {@code ./gradlew diagWallTextures}</p>
 */
public class WallTextureDiagnostic {

    // Constantes utilisees dans LevelSceneBuilder (doivent matcher)
    private static final int   EXPECTED_COL_WIDTH = 16;   // fromTile * 16 = pixel offset
    private static final float EXPECTED_TEX_V     = 128f; // hauteur logique

    public static void main(String[] args) throws Exception {
        String wallDir = args.length > 0 ? args[0]
            : "assets/Textures/walls";

        Path dir = Path.of(wallDir);
        System.out.println("=== WallTextureDiagnostic ===");
        System.out.println("Dossier : " + dir.toAbsolutePath());
        System.out.println();

        if (!Files.isDirectory(dir)) {
            System.err.println("ERREUR : dossier introuvable");
            System.exit(1);
        }

        List<Path> pngs = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.toString().toLowerCase().endsWith(".png"))
             .sorted()
             .forEach(pngs::add);
        }

        if (pngs.isEmpty()) {
            System.err.println("ERREUR : aucun PNG trouve");
            System.exit(1);
        }

        System.out.printf("%-36s %5s %5s %5s %7s %9s %9s   %s%n",
            "FICHIER", "W", "H", "DEPTH", "COLS", "HVS128", "ALPHA?", "NOTES");
        System.out.println("─".repeat(110));

        int totalPngs = 0;
        int nonStandardH = 0;
        int nonMultiple16W = 0;
        int nonOpaque = 0;
        List<Integer> widths = new ArrayList<>();

        for (Path p : pngs) {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) {
                System.out.printf("  %-36s   ERR - image invalide%n", p.getFileName());
                continue;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int depth = img.getColorModel().getPixelSize();
            boolean hasAlpha = img.getColorModel().hasAlpha();
            boolean heightMatchesTexV = (h == (int) EXPECTED_TEX_V);
            boolean widthIsMult16 = (w % EXPECTED_COL_WIDTH == 0);

            // Compter les colonnes logiques (chaque col = 16 px)
            int cols = w / EXPECTED_COL_WIDTH;

            // Analyser pixels : combien sont transparents / noirs
            int pixTotal = 0, pixBlack = 0, pixFullAlpha = 0, pixTransparent = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int gB = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    pixTotal++;
                    if (a == 0) pixTransparent++;
                    else if (a == 255) pixFullAlpha++;
                    if (r == 0 && gB == 0 && b == 0) pixBlack++;
                }
            }

            List<String> notes = new ArrayList<>();
            if (!heightMatchesTexV) {
                notes.add(String.format("h!=128 (%d)", h));
                nonStandardH++;
            }
            if (!widthIsMult16) {
                notes.add(String.format("w!%%16 (%d)", w));
                nonMultiple16W++;
            }
            if (pixTransparent > 0) {
                notes.add(String.format("transp=%.1f%%", 100.0 * pixTransparent / pixTotal));
                nonOpaque++;
            }
            if (pixBlack > pixTotal / 3) {
                notes.add(String.format("noir=%.1f%%", 100.0 * pixBlack / pixTotal));
            }

            widths.add(w);

            System.out.printf("%-36s %5d %5d %5db  %5d %9s %9s   %s%n",
                p.getFileName().toString(),
                w, h, depth,
                cols,
                heightMatchesTexV ? "OK" : "NO",
                hasAlpha ? "yes" : "no",
                String.join(", ", notes));
            totalPngs++;
        }

        System.out.println();
        System.out.println("═".repeat(110));
        System.out.printf("TOTAL : %d PNGs analyses%n", totalPngs);
        System.out.printf("  - %d avec hauteur != %d%n", nonStandardH, (int) EXPECTED_TEX_V);
        System.out.printf("  - %d avec largeur non multiple de %d%n", nonMultiple16W, EXPECTED_COL_WIDTH);
        System.out.printf("  - %d avec canal alpha/transparence%n", nonOpaque);

        // Analyse des largeurs : groupes
        Map<Integer, Integer> widthCount = new TreeMap<>();
        for (int w : widths) widthCount.merge(w, 1, Integer::sum);
        System.out.println();
        System.out.println("DISTRIBUTION DES LARGEURS :");
        for (var e : widthCount.entrySet()) {
            System.out.printf("  %3d px : %d fichier(s)%n", e.getKey(), e.getValue());
        }

        System.out.println();
        System.out.println("IMPACT SUR LE MAPPING UV :");
        System.out.println("  - LevelSceneBuilder utilise texW = wallTexWidths[i] (lu du PNG)");
        System.out.println("  - uOffset = fromTile * 16 / texW  (pour decaler dans la texture)");
        System.out.println("  - uMax    = wallLen / texW        (repeat count horizontal)");
        System.out.println("  - vMax    = wallH / 128           (repeat count vertical)");
        System.out.println();
        System.out.println("  Si la hauteur reelle != 128, la texture est etiree ou compressee");
        System.out.println("  Si la largeur n'est pas multiple de 16, les decalages fromTile");
        System.out.println("  tombent entre 2 pixels -> blurring (mais Nearest filter evite ca)");
    }
}
