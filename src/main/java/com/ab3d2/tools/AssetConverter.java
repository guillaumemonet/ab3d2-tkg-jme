package com.ab3d2.tools;

import com.ab3d2.assets.WadTextureData;
import com.ab3d2.assets.WallTextureExtractor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

/**
 * Convertit tous les assets Amiga en formats standards pour JME.
 *
 * Résultats :
 *   assets/Textures/walls/wall_00_stonewall.png ... wall_12_brownstonestep.png
 *   assets/Textures/floors/floor_01.png ... floor_16.png
 *
 * Usage :
 *   ./gradlew convertAssets       (walls + floors en une seule passe)
 *
 * Sources :
 *   arg[0] = répertoire resources Amiga (contient walls/, floors/, 256pal.bin)
 *   arg[1] = répertoire de sortie walls  (assets/Textures/walls)
 *   arg[2] = répertoire de sortie floors (assets/Textures/floors)
 */
public class AssetConverter {

    // ── Mapping texIndex → .256wad (depuis GLFT test.lnk) ────────────────────
    public static final String[] WALL_NAMES = {
        "stonewall",      //  0
        "brownpipes",     //  1
        "hullmetal",      //  2
        "technotritile",  //  3
        "brownspeakers",  //  4
        "chevrondoor",    //  5
        "technolights",   //  6
        "redhullmetal",   //  7
        "alienredwall",   //  8
        "gieger",         //  9
        "rocky",          // 10
        "steampunk",      // 11
        "brownstonestep", // 12
    };
    public static final int NUM_WALL_TEX  = WALL_NAMES.length;
    public static final int NUM_FLOOR_TEX = 16;  // floor.1 → floor.16

    // ── Point d'entrée ────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String srcRes    = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-java/src/main/resources";
        String wallOut   = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Textures/walls";
        String floorOut  = args.length > 2 ? args[2]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Textures/floors";

        Path root = Path.of(srcRes);
        System.out.println("=== AssetConverter ===");
        System.out.println("Source  : " + root);
        System.out.println("Walls   : " + wallOut);
        System.out.println("Floors  : " + floorOut);
        System.out.println();

        convertWalls(root, Path.of(wallOut));
        convertFloors(root, Path.of(floorOut));
    }

    // ── Murs .256wad → PNG ────────────────────────────────────────────────────

    public static void convertWalls(Path root, Path outDir) throws Exception {
        Files.createDirectories(outDir);
        int[] palette  = loadPalette(root);
        WallTextureExtractor extractor = new WallTextureExtractor(palette);
        int ok = 0, miss = 0;
        System.out.println("--- Murs (.256wad → PNG) ---");
        for (int i = 0; i < WALL_NAMES.length; i++) {
            Path wad = root.resolve("walls/" + WALL_NAMES[i] + ".256wad");
            if (!Files.exists(wad)) wad = root.resolve("walls/" + WALL_NAMES[i].toUpperCase() + ".256wad");
            String outName = String.format("wall_%02d_%s.png", i, WALL_NAMES[i]);
            Path   outPath = outDir.resolve(outName);
            if (!Files.exists(wad)) {
                System.out.printf("  [%2d] MANQUANT : %s%n", i, WALL_NAMES[i]);
                saveFallbackPng(outPath, i); miss++; continue;
            }
            try {
                WadTextureData tex = extractor.load(wad);
                saveTexturePng(tex, outPath);
                System.out.printf("  [%2d] %-20s %4dx%-4d → %s%n", i, WALL_NAMES[i], tex.width(), tex.height(), outName);
                ok++;
            } catch (Exception e) {
                System.out.printf("  [%2d] ERREUR %-16s : %s%n", i, WALL_NAMES[i], e.getMessage());
                saveFallbackPng(outPath, i); miss++;
            }
        }
        System.out.printf("Murs : %d OK, %d manquants%n%n", ok, miss);
    }

    // ── Sols IFF PNG → copie/renommage standard ───────────────────────────────

    /**
     * Copie les PNG IFF (Floor.1.png … floor.16.png) vers floor_01.png … floor_16.png.
     * Les fichiers IFF ont déjà été exportés en PNG dans floors/iff/.
     * On les normalise au format floor_NN.png (01-16).
     */
    public static void convertFloors(Path root, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path iffDir = root.resolve("floors/iff");
        System.out.println("--- Sols (IFF PNG → floor_NN.png) ---");
        int ok = 0, miss = 0;

        for (int n = 1; n <= NUM_FLOOR_TEX; n++) {
            String outName = String.format("floor_%02d.png", n);
            Path   outPath = outDir.resolve(outName);

            // Chercher le fichier source (plusieurs variantes de casse)
            Path src = findFloorIff(iffDir, n);
            if (src == null) {
                System.out.printf("  [%2d] MANQUANT → damier%n", n);
                saveFloorFallback(outPath, n); miss++; continue;
            }

            // Charger, éventuellement remettre à 64×64, et sauver
            try {
                BufferedImage img = ImageIO.read(src.toFile());
                if (img == null) throw new IOException("read null");
                // Recadrer/répéter à 64×64 si nécessaire
                BufferedImage tile = toTile64(img);
                ImageIO.write(tile, "PNG", outPath.toFile());
                System.out.printf("  [%2d] %-30s %4dx%d → %s%n", n,
                    src.getFileName(), img.getWidth(), img.getHeight(), outName);
                ok++;
            } catch (IOException e) {
                System.out.printf("  [%2d] ERREUR : %s%n", n, e.getMessage());
                saveFloorFallback(outPath, n); miss++;
            }
        }
        System.out.printf("Sols : %d OK, %d manquants%n%n", ok, miss);
    }

    /** Cherche Floor.N.png, floor.N.png, floor.N.plain.png dans le dossier iff. */
    private static Path findFloorIff(Path iffDir, int n) {
        if (!Files.isDirectory(iffDir)) return null;
        String[] variants = {
            "Floor." + n + ".png",
            "floor." + n + ".png",
            "floor." + n + ".plain.png",
            "Floor." + n + ".plain.png",
        };
        for (String v : variants) {
            Path p = iffDir.resolve(v);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    /** Redimensionne/tile une image en carré 64×64. */
    private static BufferedImage toTile64(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == 64 && h == 64) return src;
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        for (int y = 0; y < 64; y += h)
            for (int x = 0; x < 64; x += w)
                g.drawImage(src, x, y, Math.min(w, 64-x), Math.min(h, 64-y),
                            0, 0, Math.min(w, 64-x), Math.min(h, 64-y), null);
        g.dispose();
        return out;
    }

    private static void saveFloorFallback(Path out, int n) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        int c1 = (n % 2 == 0) ? 0xFF303030 : 0xFF252520;
        int c2 = (n % 2 == 0) ? 0xFF202020 : 0xFF1A1A15;
        for (int y=0;y<64;y++) for(int x=0;x<64;x++)
            img.setRGB(x,y,((x/8+y/8)%2==0)?c1:c2);
        ImageIO.write(img,"PNG",out.toFile());
    }

    // ── Palette ───────────────────────────────────────────────────────────────

    public static int[] loadPalette(Path root) throws IOException {
        for (String name : new String[]{"256pal.bin","palette.bin"}) {
            Path p = root.resolve(name);
            if (!Files.exists(p)) continue;
            byte[] raw = Files.readAllBytes(p);
            int[]  pal = parsePaletteAmiga(raw);
            System.out.printf("Palette : %s (%d bytes)%n", name, raw.length);
            return pal;
        }
        System.out.println("WARN : 256pal.bin absent → gris");
        int[] pal = new int[256];
        for (int i=0;i<256;i++) pal[i]=0xFF000000|(i<<16)|(i<<8)|i;
        return pal;
    }

    /** Format confirmé : 1536 bytes = 256 × 3 WORDs big-endian [0x00,R, 0x00,G, 0x00,B]. */
    public static int[] parsePaletteAmiga(byte[] raw) {
        int[] pal = new int[256];
        if (raw.length >= 1536) {
            for (int i=0;i<256;i++) {
                int b=i*6;
                pal[i]=0xFF000000|((raw[b+1]&0xFF)<<16)|((raw[b+3]&0xFF)<<8)|(raw[b+5]&0xFF);
            }
            return pal;
        }
        if (raw.length >= 768) {
            for (int i=0;i<256;i++)
                pal[i]=0xFF000000|((raw[i*3]&0xFF)<<16)|((raw[i*3+1]&0xFF)<<8)|(raw[i*3+2]&0xFF);
            return pal;
        }
        for (int i=0;i<256;i++) pal[i]=0xFF000000|(i<<16)|(i<<8)|i;
        return pal;
    }

    // ── Export PNG ────────────────────────────────────────────────────────────

    public static void saveTexturePng(WadTextureData tex, Path out) throws IOException {
        int w=tex.width(), h=tex.height();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<h;y++) for(int x=0;x<w;x++)
            img.setRGB(x,y,tex.pixels()[y*w+x]|0xFF000000);
        ImageIO.write(img,"PNG",out.toFile());
    }

    public static void saveFallbackPng(Path out, int idx) throws IOException {
        BufferedImage img = new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++) for(int x=0;x<64;x++)
            img.setRGB(x,y,((x/8+y/8)%2==0)?0xFFFF00FF:0xFF202020);
        ImageIO.write(img,"PNG",out.toFile());
    }
}
