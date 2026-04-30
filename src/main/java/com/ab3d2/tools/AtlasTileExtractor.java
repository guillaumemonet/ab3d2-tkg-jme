package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Sauvegarde une tile 64x64 (ou plusieurs) en PNG zoome pour visualisation.
 *
 * <p>Permet de voir PRECISEMENT ce que le crab/wasp/etc. echantillonne dans
 * l'atlas. Si tu dis "texture 27", on peut comparer la tile 22-26 (0-based)
 * avec ce que tu attendais visuellement.</p>
 *
 * <p>Usage : {@code ./gradlew extractTile -Ptile=22,26,27}</p>
 */
public class AtlasTileExtractor {

    public static void main(String[] args) throws Exception {
        String atlasPath = args.length > 0 ? args[0]
            : "assets/Textures/vectobj/texturemaps_atlas.png";
        String tilesArg  = args.length > 1 ? args[1] : "22,26";
        String outDir    = args.length > 2 ? args[2]
            : "assets/Textures/vectobj/tiles";

        BufferedImage atlas = ImageIO.read(Path.of(atlasPath).toFile());
        Path.of(outDir).toFile().mkdirs();

        int TILE = 64;
        int COLS = 4;

        System.out.printf("Extraction depuis %s (%dx%d)%n%n",
            atlasPath, atlas.getWidth(), atlas.getHeight());

        // Zoom x8 pour bien voir
        int ZOOM = 8;
        int OUT_SIZE = TILE * ZOOM;

        for (String t : tilesArg.split(",")) {
            int idx = Integer.parseInt(t.trim());
            int col = idx % COLS;
            int row = idx / COLS;
            int srcX = col * TILE;
            int srcY = row * TILE;

            BufferedImage out = new BufferedImage(OUT_SIZE, OUT_SIZE,
                BufferedImage.TYPE_INT_ARGB);

            int alphaCount = 0, blackCount = 0;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    int px = atlas.getRGB(srcX + x, srcY + y);
                    int alpha = (px >>> 24) & 0xFF;
                    int r = (px >> 16) & 0xFF;
                    int g = (px >>  8) & 0xFF;
                    int bv = px & 0xFF;

                    if (alpha == 0) alphaCount++;
                    if (r + g + bv == 0 && alpha > 0) blackCount++;

                    // zoom sans interpolation
                    for (int dy = 0; dy < ZOOM; dy++)
                        for (int dx = 0; dx < ZOOM; dx++)
                            out.setRGB(x * ZOOM + dx, y * ZOOM + dy, px);
                }
            }

            Path outFile = Path.of(outDir, String.format("tile_%02d_x%d.png", idx, ZOOM));
            ImageIO.write(out, "PNG", outFile.toFile());

            System.out.printf("Tile %2d (col=%d, row=%d) -> %s%n", idx, col, row, outFile);
            System.out.printf("  alpha=0: %d/%d (%.0f%%), noirs opaques: %d/%d (%.0f%%)%n",
                alphaCount, TILE*TILE, 100.0*alphaCount/(TILE*TILE),
                blackCount, TILE*TILE, 100.0*blackCount/(TILE*TILE));
        }
    }
}
