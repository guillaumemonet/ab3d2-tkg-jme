package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

/**
 * Diagnostic session 65 : teste si newtexturemaps contient
 * 4 textures INTERLEAVED par byte dans chaque banque.
 *
 * <p>Le scan des texOffsets du Mantis a montre que ses polygones
 * utilisent des texOffsets avec lane=2, lane=3 (ex: 0x8302, 0x8303).
 * C'est-a-dire que l'ASM `a0 += texOffset` deplace a0 sur un byte
 * non aligne-sur-4. Les samples subsequents `(a0, d0.w*4)` lisent
 * alors toujours le MEME slot dans les groupes de 4 bytes.</p>
 *
 * <p>Theorie : chaque banque de 64KB contient 4 textures distinctes
 * de 16KB chacune, interleaved par byte :</p>
 * <pre>
 *   Texture slot 0 : bytes 0, 4, 8, 12, ...
 *   Texture slot 1 : bytes 1, 5, 9, 13, ...
 *   Texture slot 2 : bytes 2, 6, 10, 14, ...
 *   Texture slot 3 : bytes 3, 7, 11, 15, ...
 * </pre>
 *
 * <p>Sorties : 2 banques * 4 slots = 8 PNG de 256x64 chacun.
 * Si plusieurs slots ont des textures reconnaissables, la theorie est validee.</p>
 */
public class TextureLayoutExplorer {

    public static void main(String[] args) throws Exception {
        String vectDir = args.length > 0 ? args[0] : "src/main/resources/vectobj";
        String outDir  = args.length > 1 ? args[1] : "build/texture-explore";
        String palDir  = args.length > 2 ? args[2] : "src/main/resources";

        Path vect = Path.of(vectDir);
        Path out  = Path.of(outDir);
        Files.createDirectories(out);

        byte[] tex   = Files.readAllBytes(vect.resolve("newtexturemaps"));
        byte[] shade = Files.readAllBytes(vect.resolve("newtexturemaps.pal"));
        int[]  pal   = TextureMapConverter.loadGlobalPalette(Path.of(palDir));

        System.out.printf("newtexturemaps: %d bytes = %d banques de 64KB%n",
            tex.length, tex.length / 65536);
        System.out.println();

        // Rendu des 4 slots de chaque banque
        int numBanks = tex.length / 65536;
        int cols = 256, rows = 64;

        for (int bank = 0; bank < numBanks; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                BufferedImage img = renderSlot(tex, shade, pal, bank, slot, cols, rows);
                String name = String.format("bank%d_slot%d.png", bank, slot);
                ImageIO.write(img, "PNG", out.resolve(name).toFile());
                System.out.printf("  %s : %dx%d%n", name, cols, rows);
            }
        }

        // Atlas combine : 8 zones 256x64 empilees verticalement
        // bank 0 slots 0-3 en haut, bank 1 slots 0-3 en bas
        BufferedImage combined = new BufferedImage(cols, rows * 4 * numBanks, BufferedImage.TYPE_INT_RGB);
        var g = combined.createGraphics();
        for (int bank = 0; bank < numBanks; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                BufferedImage s = renderSlot(tex, shade, pal, bank, slot, cols, rows);
                int y = bank * rows * 4 + slot * rows;
                g.drawImage(s, 0, y, null);
            }
        }
        g.dispose();
        Path combinedPath = out.resolve("all_slots.png");
        ImageIO.write(combined, "PNG", combinedPath.toFile());
        System.out.printf("%nAtlas combine (8 zones 256x64 empilees) : %s%n", combinedPath);
        System.out.println("  bank0 slot0-3 en haut, puis bank1 slot0-3");

        // Preview zoome 4x des 8 slots cote a cote, pour voir les details
        int zoom = 4;
        BufferedImage wide = new BufferedImage(cols * zoom, rows * 4 * numBanks * zoom,
            BufferedImage.TYPE_INT_RGB);
        var wg = wide.createGraphics();
        for (int bank = 0; bank < numBanks; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                BufferedImage s = renderSlot(tex, shade, pal, bank, slot, cols, rows);
                int y = (bank * 4 + slot) * rows * zoom;
                wg.drawImage(s, 0, y, cols * zoom, rows * zoom, null);
            }
        }
        wg.dispose();
        Path widePath = out.resolve("all_slots_zoom4.png");
        ImageIO.write(wide, "PNG", widePath.toFile());
        System.out.printf("Zoom 4x           : %s%n", widePath);
    }

    /**
     * Rend le slot donne d'une banque.
     * pixel(row, col) = tex[bankBase + row*1024 + col*4 + slot]
     */
    static BufferedImage renderSlot(byte[] tex, byte[] shade, int[] pal,
                                     int bank, int slot, int cols, int rows) {
        BufferedImage img = new BufferedImage(cols, rows, BufferedImage.TYPE_INT_RGB);
        int bankBase = bank * 65536;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int ofs = bankBase + r * 1024 + c * 4 + slot;
                if (ofs >= tex.length) continue;
                int rawIdx    = tex[ofs] & 0xFF;
                int mappedIdx = shade[32 * 256 + rawIdx] & 0xFF;
                img.setRGB(c, r, pal[mappedIdx] & 0xFFFFFF);
            }
        }
        return img;
    }
}
