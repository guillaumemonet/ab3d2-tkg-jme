package com.ab3d2.tools;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.*;

/**
 * Petit utilitaire : affiche les dimensions et quelques infos d'un PNG.
 *
 * Usage : ./gradlew pngInfo -Ppng=newborder.png
 */
public class PngInfo {
    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Interface";
        String name = args.length > 1 ? args[1] : "newborder.png";

        Path p = Path.of(dir, name);
        if (!Files.exists(p)) { System.err.println("Absent : " + p); return; }

        BufferedImage img = ImageIO.read(p.toFile());
        int w = img.getWidth(), h = img.getHeight();
        System.out.printf("=== %s ===%n", name);
        System.out.printf("Dimensions : %d x %d pixels%n", w, h);
        System.out.printf("Type       : %d (TYPE_INT_ARGB=%d)%n", img.getType(), BufferedImage.TYPE_INT_ARGB);
        System.out.printf("hasAlpha   : %b%n", img.getColorModel().hasAlpha());

        // Echantillons : 9 points (coins, centres des cotes, centre) + 4 positions dans la bande basse
        System.out.println("\nEchantillons de pixels :");
        int[][] samples = {
            {0, 0, 0},                    // coin haut-gauche
            {w-1, 0, 0},                  // coin haut-droit
            {0, h-1, 0},                  // coin bas-gauche
            {w-1, h-1, 0},                // coin bas-droit
            {w/2, 0, 0},                  // milieu haut
            {w/2, h/2, 0},                // centre exact
            {w/2, h-1, 0},                // milieu bas
            {10, h/2, 0},                 // milieu gauche (bordure)
            {w-11, h/2, 0},               // milieu droit (bordure)
        };
        for (int[] s : samples) {
            int argb = img.getRGB(s[0], s[1]);
            int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF,  b = argb & 0xFF;
            System.out.printf("  (%3d, %3d) : #%08X  A=%3d R=%3d G=%3d B=%3d%n",
                s[0], s[1], argb, a, r, g, b);
        }

        // Analyse par lignes : trouver la zone noire centrale et les bandes
        System.out.println("\nAnalyse ligne par ligne (detecte zones noires/colorees) :");
        String lastCategory = "";
        int regionStart = 0;
        for (int y = 0; y < h; y++) {
            String cat = categorizeRow(img, y);
            if (!cat.equals(lastCategory)) {
                if (!lastCategory.isEmpty())
                    System.out.printf("  y=%3d..%3d : %s%n", regionStart, y-1, lastCategory);
                regionStart = y;
                lastCategory = cat;
            }
        }
        System.out.printf("  y=%3d..%3d : %s%n", regionStart, h-1, lastCategory);

        // Analyse par colonnes : delimiter les bords gauche/droite
        System.out.println("\nAnalyse colonne par colonne (premiere ligne non-top) :");
        int sampleY = h / 2;  // milieu vertical
        String lastColCat = "";
        int colStart = 0;
        for (int x = 0; x < w; x++) {
            int argb = img.getRGB(x, sampleY);
            int rr = (argb >> 16) & 0xFF, gg = (argb >> 8) & 0xFF, bb = argb & 0xFF;
            String cat = (rr + gg + bb) < 30 ? "NOIR" : "COLORE";
            if (!cat.equals(lastColCat)) {
                if (!lastColCat.isEmpty())
                    System.out.printf("  x=%3d..%3d : %s%n", colStart, x-1, lastColCat);
                colStart = x;
                lastColCat = cat;
            }
        }
        System.out.printf("  x=%3d..%3d : %s%n", colStart, w-1, lastColCat);
    }

    /** Categorie d'une ligne : 100% noir, bord texture, ou mixed. */
    private static String categorizeRow(BufferedImage img, int y) {
        int w = img.getWidth();
        int blackCount = 0, coloredCount = 0;
        for (int x = 0; x < w; x++) {
            int argb = img.getRGB(x, y);
            int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
            if (r + g + b < 30) blackCount++;
            else coloredCount++;
        }
        if (coloredCount == 0) return "100% NOIR (zone vide)";
        if (blackCount == 0) return "100% COLORE (texture pleine)";
        double ratio = (double) blackCount / w;
        if (ratio > 0.85) return "bordures uniquement (>85% noir)";
        return String.format("mixte (%.0f%% noir)", ratio * 100);
    }
}
