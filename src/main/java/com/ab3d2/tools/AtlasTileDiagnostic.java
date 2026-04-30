package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Diagnostic des tiles d'un atlas PNG pour verifier quelles sont noires/vides.
 *
 * <p>Lit l'atlas 256x512 et pour chaque tile 64x64 (grille 4x8 = 32 tiles),
 * compte combien de pixels sont noirs (R=G=B=0) et affiche une couleur moyenne.</p>
 *
 * <p>Usage : {@code ./gradlew diagAtlas}</p>
 */
public class AtlasTileDiagnostic {

    public static void main(String[] args) throws Exception {
        String atlasPath = args.length > 0 ? args[0]
            : "assets/Textures/vectobj/texturemaps_atlas.png";

        BufferedImage atlas = ImageIO.read(Path.of(atlasPath).toFile());
        System.out.printf("Atlas: %s (%dx%d)%n%n",
            atlasPath, atlas.getWidth(), atlas.getHeight());

        if (atlas.getWidth() != 256 || atlas.getHeight() != 512) {
            System.out.println("WARN: dimensions inattendues");
        }

        int TILE = 64;
        int COLS = atlas.getWidth() / TILE;   // 4
        int ROWS = atlas.getHeight() / TILE;  // 8
        int tilePixels = TILE * TILE;

        System.out.printf("Grille: %d colonnes x %d lignes = %d tiles de %dx%d%n%n",
            COLS, ROWS, COLS * ROWS, TILE, TILE);

        System.out.println("Idx | Pos (col,row) | Non-noirs | R avg | G avg | B avg | Etat");
        System.out.println("----+---------------+-----------+-------+-------+-------+------");

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                int nonBlack = 0;
                long rSum = 0, gSum = 0, bSum = 0;

                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        int px = atlas.getRGB(c * TILE + x, r * TILE + y);
                        int rr = (px >> 16) & 0xFF;
                        int gg = (px >>  8) & 0xFF;
                        int bb =  px        & 0xFF;
                        rSum += rr; gSum += gg; bSum += bb;
                        if (rr + gg + bb > 0) nonBlack++;
                    }
                }

                String etat;
                double fill = (double) nonBlack / tilePixels;
                if (fill < 0.01) etat = "NOIRE";
                else if (fill < 0.3) etat = "sombre";
                else if (fill < 0.7) etat = "moy";
                else etat = "pleine";

                System.out.printf("%3d | (%d, %d)       | %4d/%4d | %5d | %5d | %5d | %s%n",
                    idx, c, r, nonBlack, tilePixels,
                    (int)(rSum / tilePixels), (int)(gSum / tilePixels), (int)(bSum / tilePixels),
                    etat);
            }
        }
    }
}
