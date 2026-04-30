package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Cree une image zoomee x2 de l'atlas complet avec numerotation des tiles.
 * Permet de visualiser OU sont les differentes textures dans la grille 4x8.
 *
 * <p>Usage : {@code ./gradlew extractAtlasGrid}</p>
 */
public class AtlasGridExtractor {

    public static void main(String[] args) throws Exception {
        String atlasPath = args.length > 0 ? args[0]
            : "assets/Textures/vectobj/texturemaps_atlas.png";
        String outPath   = args.length > 1 ? args[1]
            : "assets/Textures/vectobj/atlas_numbered.png";

        BufferedImage atlas = ImageIO.read(Path.of(atlasPath).toFile());
        int TILE = 64;
        int COLS = 4;
        int ROWS = 8;
        int ZOOM = 3;

        int outW = atlas.getWidth() * ZOOM;
        int outH = atlas.getHeight() * ZOOM;
        BufferedImage out = new BufferedImage(outW, outH,
            BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = out.createGraphics();
        // Fond magenta pour voir les zones transparentes
        g.setColor(new Color(255, 0, 255));
        g.fillRect(0, 0, outW, outH);

        // Zoom sans interpolation
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(atlas, 0, 0, outW, outH, null);

        // Grille + numeros
        g.setColor(new Color(255, 255, 0));
        g.setStroke(new BasicStroke(2));
        g.setFont(new Font("Monospaced", Font.BOLD, 20));

        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int idx = r * COLS + c;
                int bank = idx / 16;        // 0 ou 1 (chaque bank = 4*4 = 16 tiles)
                int slot = (idx / 4) % 4;   // slot dans la bank
                int tileCol = idx % 4;      // colonne dans le slot

                int x = c * TILE * ZOOM;
                int y = r * TILE * ZOOM;

                // Bord jaune
                g.drawRect(x, y, TILE * ZOOM, TILE * ZOOM);

                // Fond semi-transparent pour le texte
                g.setColor(new Color(0, 0, 0, 180));
                g.fillRect(x + 2, y + 2, 92, 22);

                g.setColor(Color.YELLOW);
                g.drawString(String.format("%d b%ds%dc%d", idx, bank, slot, tileCol),
                    x + 4, y + 20);

                g.setColor(Color.YELLOW);
            }
        }

        g.dispose();
        ImageIO.write(out, "PNG", Path.of(outPath).toFile());
        System.out.println("Wrote: " + outPath);
        System.out.println("  Dimensions: " + outW + "x" + outH);
        System.out.println("  Grille: " + COLS + "x" + ROWS + " tiles de " + TILE + "x" + TILE);
    }
}
