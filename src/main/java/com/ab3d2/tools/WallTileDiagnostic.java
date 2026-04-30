package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Analyse les "tiles" logiques d'une texture de mur.
 *
 * <p>Hypothese confirmee par l'ASM (hiresgourwall.s) : le rendu Amiga utilise
 * <code>draw_WallTextureWidthMask_w</code> (= tileWidth - 1) pour wrapper
 * la colonne de sampling, PUIS ajoute <code>draw_FromTile_w</code> (fromTile*16)
 * comme offset pixel dans la texture.</p>
 *
 * <p>Une texture de 256x128 contient donc 2 tiles logiques de 128x128 :
 * <ul>
 *   <li>fromTile=0 (offset 0 px) selectionne la tile gauche</li>
 *   <li>fromTile=8 (offset 128 px) selectionne la tile droite</li>
 * </ul>
 *
 * <p>Cet outil :
 * <ul>
 *   <li>Deduit la tileWidth probable depuis la largeur totale</li>
 *   <li>Ecrit un PNG annote montrant les frontieres de tiles + les offsets
 *       (fromTile * 16) utilises dans le jeu</li>
 *   <li>Extrait chaque tile individuelle en PNG separe</li>
 * </ul>
 *
 * <p>Usage :</p>
 * <pre>
 *   ./gradlew diagWallTiles              # Tous les murs
 *   ./gradlew diagWallTiles -Pwall=02    # Un seul (hullmetal)
 * </pre>
 */
public class WallTileDiagnostic {

    public static void main(String[] args) throws Exception {
        String wallDir = args.length > 0 ? args[0] : "assets/Textures/walls";
        String outDir  = args.length > 1 ? args[1] : "build/wall-tiles";
        String specific = args.length > 2 ? args[2] : null;

        Path walls = Path.of(wallDir);
        Path out   = Path.of(outDir);
        Files.createDirectories(out);

        if (!Files.isDirectory(walls)) {
            System.err.println("Dossier introuvable : " + walls);
            System.exit(1);
        }

        System.out.println("=== WallTileDiagnostic ===");
        System.out.println("Hypothese : tileWidth = 128 si texW >= 128, sinon tileWidth = texW");
        System.out.println();

        List<Path> pngs = new ArrayList<>();
        try (var s = Files.list(walls)) {
            s.filter(p -> p.getFileName().toString().matches("wall_\\d{2}_.*\\.png"))
             .sorted()
             .forEach(pngs::add);
        }

        for (Path p : pngs) {
            String fn = p.getFileName().toString();
            String num = fn.substring(5, 7);
            if (specific != null && !specific.equals(num)) continue;

            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) continue;

            int w = img.getWidth();
            int h = img.getHeight();
            int tileWidth = deduceTileWidth(w);
            int numTiles  = (int) Math.ceil(w / (double) tileWidth);

            System.out.printf("%s : %dx%d -> tileWidth=%d, %d tile(s)%n",
                fn, w, h, tileWidth, numTiles);

            // 1. Generer une version annotee (rouge sur les frontieres de tiles)
            BufferedImage annotated = annotate(img, tileWidth);
            Path annotPath = out.resolve(fn.replace(".png", "_annotated.png"));
            ImageIO.write(annotated, "PNG", annotPath.toFile());
            System.out.printf("  -> %s%n", annotPath.getFileName());

            // 2. Extraire chaque tile individuelle
            for (int t = 0; t < numTiles; t++) {
                int tileX = t * tileWidth;
                int tileW = Math.min(tileWidth, w - tileX);
                if (tileW <= 0) break;
                BufferedImage tile = img.getSubimage(tileX, 0, tileW, h);
                BufferedImage copy = new BufferedImage(tileW, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = copy.createGraphics();
                g.drawImage(tile, 0, 0, null);
                g.dispose();
                Path tilePath = out.resolve(fn.replace(".png", "_tile" + t + ".png"));
                ImageIO.write(copy, "PNG", tilePath.toFile());
            }
        }
        System.out.println();
        System.out.println("Tiles extraites dans : " + out.toAbsolutePath());
    }

    /**
     * Deduit la largeur de tile logique depuis la largeur totale du PNG.
     *
     * <p>Regle empirique basee sur l'ASM : si la texture fait >= 128 px de large,
     * on decoupe en tiles de 128 (le widthMask ASM = 127). Sinon, la texture
     * est sa propre tile.</p>
     */
    static int deduceTileWidth(int texW) {
        if (texW >= 128) return 128;
        // Pour les petites textures (stonewall 192, steampunk 64, brownstonestep 128),
        // tileWidth = texW (elles sont elles-memes des tiles uniques).
        return texW;
    }

    /**
     * Ajoute des lignes rouges sur les frontieres de tiles.
     */
    private static BufferedImage annotate(BufferedImage src, int tileWidth) {
        int w = src.getWidth(), h = src.getHeight();
        // Agrandir l'image 4x pour mieux voir
        int scale = 2;
        BufferedImage annot = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = annot.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w * scale, h * scale, null);

        // Lignes rouges verticales sur les frontieres de tiles
        g.setColor(new Color(255, 0, 0, 200));
        g.setStroke(new BasicStroke(2f));
        for (int x = tileWidth; x < w; x += tileWidth) {
            int sx = x * scale;
            g.drawLine(sx, 0, sx, h * scale);
        }
        // Labels
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        for (int t = 0, x = 0; x < w; t++, x += tileWidth) {
            int sx = x * scale + 4;
            g.setColor(new Color(0, 0, 0, 200));
            g.fillRect(sx - 2, 2, 80, 18);
            g.setColor(Color.YELLOW);
            g.drawString("tile" + t + " (fromTile=" + (x/16) + ")", sx, 16);
        }
        g.dispose();
        return annot;
    }
}
