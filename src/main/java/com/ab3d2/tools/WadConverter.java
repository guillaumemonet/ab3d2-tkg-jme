package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Convertit les sprites bitmap AB3D2 (format WAD/PTR/256PAL) en PNG.
 *
 * Format WAD/PTR (extrait de leved303.amos, proc WIDTHSHOW et _TL_FRAME2IFF) :
 *
 * -- Fichier .PTR (pointer table) --
 *   4 bytes par colonne de strip (WOF colonnes au total).
 *   Chaque entrée = LONG:
 *     bits 0-23 : offset dans le WAD
 *     bits 24-25 : 0=first_third, 1=second_third, 2=third_third
 *   Les 8 derniers bytes du PTR sont un header :
 *     word[-4] = $FFFF (marqueur)
 *     word[-3] = NOFF (nombre de frames)
 *     word[-2] = WOFF (largeur d'une frame en colonnes)
 *     word[-1] = HOFF (hauteur d'une frame en lignes)
 *
 * -- Fichier .WAD (données colonnes) --
 *   Pour chaque colonne de strip x :
 *     PTR[x] donne l'offset + le "tiers" (third)
 *     A partir de WAD[offset + LY*2], pour chaque ligne y (HOF lignes) :
 *       word = données 3×5-bit (15 bits utilisés) :
 *         first_third  : index_couleur = word & 31         (bits 0-4)
 *         second_third : index_couleur = (word >> 5) & 31  (bits 5-9)
 *         third_third  : index_couleur = (word >> 10) & 31 (bits 10-14)
 *   Index 0 = transparent (couleur de fond)
 *
 * -- Fichier .256PAL (palette) --
 *   32 WORDs = 32 entrées × 2 bytes
 *   Chaque WORD = index dans la palette globale 256 couleurs.
 *   Lecture : B = Peek(start + A*2)  -- A = 0..31
 *
 * -- Palette globale (256pal.bin / PALC dans l'éditeur) --
 *   256 entrées × 6 bytes = [R:word, G:word, B:word] (0-255 chacun)
 *
 * Rendu d'un pixel (x,y) frame FRN, objet OG :
 *   1. frame desc: LX,LY,LW,LH depuis GLFT_FrameData (LINK+$39B0+OG*256+FRN*8)
 *   2. col_strip = PTR[LX + x]
 *   3. ofs = col_strip & $FFFFFF, ss = col_strip >> 24
 *   4. word = WAD[ofs + (LY+y)*2]
 *   5. idx32 = (word >> (ss*5)) & 31
 *   6. globalPalIdx = PAL256[idx32]  (lu depuis .256pal)
 *   7. (R,G,B) = palette globale[globalPalIdx]
 *
 * Usage Gradle : ./gradlew convertWads
 */
public class WadConverter {

    private final LnkParser lnk;
    private final int[] globalPalette;  // 256 entrées ARGB

    public WadConverter(LnkParser lnk, int[] globalPalette) {
        this.lnk = lnk;
        this.globalPalette = globalPalette;
    }

    public static void main(String[] args) throws Exception {
        String srcRes  = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-java/src/main/resources";
        String destDir = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Textures/objects";
        Path src  = Path.of(srcRes);
        Path dest = Path.of(destDir);
        Files.createDirectories(dest);

        // Chercher TEST.LNK
        Path lnkFile = findLnkFile(src);
        if (lnkFile == null) {
            System.out.println("ERREUR: TEST.LNK introuvable dans " + src);
            System.out.println("Cherche dans sounds/raw/TEST.LNK...");
            return;
        }
        System.out.println("TEST.LNK: " + lnkFile);

        LnkParser lnk = LnkParser.load(lnkFile);
        lnk.dump();

        // Charger palette globale
        int[] globalPal = loadGlobalPalette(src.resolve("256pal.bin"));

        WadConverter conv = new WadConverter(lnk, globalPal);

        // Chercher les WAD dans les dossiers resources
        List<Path> wadSearchPaths = gatherWadSearchPaths(src);

        // Convertir chaque sprite objet
        List<String> gfxNames = lnk.getObjGfxNames();
        int totalSaved = 0;
        for (int i = 0; i < gfxNames.size(); i++) {
            String name = gfxNames.get(i);
            if (name.isEmpty()) continue;

            String fileBase = LnkParser.extractFileName(name);
            System.out.printf("\n[%2d] %s -> %s%n", i, name, fileBase);

            // Chercher les fichiers WAD/PTR/256PAL
            byte[] wadData    = findAndLoad(fileBase + ".wad",    wadSearchPaths);
            byte[] ptrData    = findAndLoad(fileBase + ".ptr",    wadSearchPaths);
            byte[] palData    = findAndLoad(fileBase + ".256pal", wadSearchPaths);

            if (wadData == null || ptrData == null) {
                System.out.println("  MANQUANT: " + fileBase + ".wad ou .ptr");
                continue;
            }

            int[] objPal = palData != null
                ? parseObjPalette(palData, globalPal)
                : globalPal;

            int nFrames = lnk.countFrames(i);
            System.out.printf("  %d frames, WAD=%d bytes, PTR=%d bytes%n",
                nFrames, wadData.length, ptrData.length);

            Path objDir = dest.resolve(fileBase);
            Files.createDirectories(objDir);

            int saved = conv.convertObject(i, wadData, ptrData, objPal, objDir, fileBase);
            System.out.printf("  -> %d sprites PNG%n", saved);
            totalSaved += saved;
        }

        System.out.printf("%nTotal: %d sprites convertis dans %s%n", totalSaved, dest);
    }

    // ── Conversion d'un objet ─────────────────────────────────────────────────

    public int convertObject(int objIdx, byte[] wadData, byte[] ptrData,
                              int[] objPal, Path destDir, String baseName) throws Exception {
        int saved = 0;
        int nFrames = lnk.countFrames(objIdx);

        // Lire le header PTR (8 derniers bytes)
        int ptrLen = ptrData.length;
        int hdrOfs = ptrLen - 8;
        int markerWord = hdrOfs >= 0 ? readShortBE(ptrData, hdrOfs) : -1;
        int noff = 0, woff = 0, hoff = 0;
        if (markerWord == 0xFFFF && hdrOfs + 8 <= ptrLen) {
            noff = readShortBE(ptrData, hdrOfs + 2);
            woff = readShortBE(ptrData, hdrOfs + 4);
            hoff = readShortBE(ptrData, hdrOfs + 6);
            System.out.printf("  PTR header: NOFF=%d WOFF=%d HOFF=%d%n", noff, woff, hoff);
        }

        for (int f = 0; f < Math.max(nFrames, 1); f++) {
            LnkParser.FrameDesc fd = lnk.getFrameDesc(objIdx, f);
            if (!fd.isValid()) {
                // Essayer de rendre toute la spritesheet si pas de frame desc
                if (f == 0 && noff > 0 && woff > 0 && hoff > 0) {
                    BufferedImage img = renderSpritesheet(wadData, ptrData, objPal,
                        noff, woff, hoff);
                    if (img != null) {
                        Path outFile = destDir.resolve(baseName + "_sheet.png");
                        ImageIO.write(img, "png", outFile.toFile());
                        saved++;
                    }
                }
                break;
            }
            try {
                BufferedImage img = renderFrame(wadData, ptrData, objPal, fd);
                if (img != null && hasContent(img)) {
                    Path outFile = destDir.resolve(baseName + "_f" + f + ".png");
                    ImageIO.write(img, "png", outFile.toFile());
                    saved++;
                }
            } catch (Exception e) {
                System.out.printf("    WARN frame %d: %s%n", f, e.getMessage());
            }
        }
        return saved;
    }

    /**
     * Rendu d'une frame selon ses descripteurs LX/LY/LW/LH.
     *
     * Depuis WIDTHSHOW (leved303.amos) :
     *   For WAA=0 To W    (W = nombre de colonnes visibles = LW)
     *     WA = WAA + LX   (colonne dans le PTR = LX + colonne locale)
     *     OF = Leek(PTR + WA*4)
     *     SS = OF >> 24 (0, 1 ou 2 = quel tiers)
     *     OF = OF & $FFFFFF
     *     AP = WAD + OF + LY*2    (LY = ligne de départ)
     *     For WB=0 To HOF-1
     *       word = Deek(AP) : Add AP,2
     *       idx = channel(word, SS)
     *       plot(WAA, WB, objPal[idx])
     */
    private BufferedImage renderFrame(byte[] wad, byte[] ptr, int[] pal,
                                       LnkParser.FrameDesc fd) {
        int width  = fd.lw();
        int height = fd.lh();
        if (width <= 0 || height <= 0 || width > 512 || height > 512) return null;

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int x = 0; x < width; x++) {
            int ptrIdx = fd.lx() + x;
            if (ptrIdx * 4 + 4 > ptr.length) break;

            long ptrEntry = readIntBE(ptr, ptrIdx * 4) & 0xFFFFFFFFL;
            int  ss  = (int)(ptrEntry >> 24) & 0xFF;
            int  ofs = (int)(ptrEntry & 0x00FFFFFF);

            int ap = ofs + fd.ly() * 2;
            for (int y = 0; y < height; y++) {
                if (ap + 2 > wad.length) break;
                int word = readShortBE(wad, ap);
                ap += 2;

                int idx32;
                switch (ss & 0xFF) {
                    case 0  -> idx32 = word & 0x1F;
                    case 1  -> idx32 = (word >> 5) & 0x1F;
                    default -> idx32 = (word >> 10) & 0x1F;
                }
                // Index 0 = transparent
                int argb = (idx32 == 0) ? 0x00000000 : pal[idx32 & 0x1F];
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    /**
     * Rendu de toute la spritesheet si pas de frame descripteurs.
     * Rend NOFF frames de WOFF×HOFF pixels côte à côte.
     */
    private BufferedImage renderSpritesheet(byte[] wad, byte[] ptr, int[] pal,
                                             int noff, int woff, int hoff) {
        int totalW = noff * woff;
        if (totalW <= 0 || totalW > 4096 || hoff <= 0 || hoff > 512) return null;
        BufferedImage img = new BufferedImage(totalW, hoff, BufferedImage.TYPE_INT_ARGB);

        for (int x = 0; x < totalW; x++) {
            if (x * 4 + 4 > ptr.length) break;
            long ptrEntry = readIntBE(ptr, x * 4) & 0xFFFFFFFFL;
            int  ss  = (int)(ptrEntry >> 24) & 0xFF;
            int  ofs = (int)(ptrEntry & 0x00FFFFFF);

            int ap = ofs;
            for (int y = 0; y < hoff; y++) {
                if (ap + 2 > wad.length) break;
                int word = readShortBE(wad, ap);
                ap += 2;
                int idx32 = switch (ss & 0xFF) {
                    case 0  -> word & 0x1F;
                    case 1  -> (word >> 5) & 0x1F;
                    default -> (word >> 10) & 0x1F;
                };
                img.setRGB(x, y, idx32 == 0 ? 0x00000000 : pal[idx32 & 0x1F]);
            }
        }
        return img;
    }

    // ── Palette ───────────────────────────────────────────────────────────────

    /**
     * Charge la palette globale depuis 256pal.bin.
     * Format PALC dans l'éditeur : 256 × 6 bytes = [R:word, G:word, B:word]
     * Chaque composante est un mot (0-255).
     */
    public static int[] loadGlobalPalette(Path palFile) throws IOException {
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) pal[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
        if (!Files.exists(palFile)) {
            System.out.println("WARN: 256pal.bin absent, palette gris");
            return pal;
        }
        byte[] raw = Files.readAllBytes(palFile);
        System.out.printf("256pal.bin: %d bytes%n", raw.length);

        if (raw.length >= 256 * 6) {
            // Format PALC : 256 × [R:word, G:word, B:word]
            for (int i = 0; i < 256; i++) {
                int r = readShortBE(raw, i * 6)     & 0xFF;
                int g = readShortBE(raw, i * 6 + 2) & 0xFF;
                int b = readShortBE(raw, i * 6 + 4) & 0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (raw.length >= 256 * 4) {
            // Format RGBA 4 bytes
            for (int i = 0; i < 256; i++) {
                int r = raw[i*4]&0xFF, g = raw[i*4+1]&0xFF, b = raw[i*4+2]&0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (raw.length >= 256 * 3) {
            // Format RGB 3 bytes
            for (int i = 0; i < 256; i++) {
                int r = raw[i*3]&0xFF, g = raw[i*3+1]&0xFF, b = raw[i*3+2]&0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return pal;
    }

    /**
     * Parse la palette objet (.256pal) en couleurs ARGB.
     * Format .256pal (depuis _FRAMEPICK dans leved303.amos) :
     *   32 WORDs : B = Peek(Start(15) + A*2)  -> index dans palette globale
     *
     * Retourne un tableau de 32 couleurs ARGB.
     */
    public static int[] parseObjPalette(byte[] palData, int[] globalPal) {
        int[] pal = new int[32];
        for (int i = 0; i < 32; i++) pal[i] = 0xFF808080; // gris par défaut
        if (palData == null || palData.length < 32 * 2) {
            // Peut aussi être format 32 bytes directs
            if (palData != null && palData.length >= 32) {
                for (int i = 0; i < 32; i++) {
                    int globalIdx = palData[i] & 0xFF;
                    pal[i] = globalIdx < globalPal.length ? globalPal[globalIdx] : 0xFF808080;
                }
            }
            return pal;
        }
        // 32 WORDs big-endian : chaque word = index dans la palette globale
        for (int i = 0; i < 32 && i * 2 + 2 <= palData.length; i++) {
            int globalIdx = readShortBE(palData, i * 2) & 0xFF;
            pal[i] = globalIdx < globalPal.length ? globalPal[globalIdx] : 0xFF808080;
        }
        return pal;
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    private static int readShortBE(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int readIntBE(byte[] data, int off) {
        return ((data[off]   & 0xFF) << 24) | ((data[off+1] & 0xFF) << 16)
             | ((data[off+2] & 0xFF) <<  8) |  (data[off+3] & 0xFF);
    }

    private static boolean hasContent(BufferedImage img) {
        int nonTrans = 0;
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >> 24 & 0xFF) > 0) nonTrans++;
        return nonTrans > 5;
    }

    private static Path findLnkFile(Path srcRes) {
        // Chercher TEST.LNK dans plusieurs endroits
        List<Path> candidates = List.of(
            srcRes.resolve("sounds/raw/TEST.LNK"),
            srcRes.resolve("sounds/raw/test.lnk"),
            srcRes.resolve("TEST.LNK"),
            srcRes.resolve("test.lnk"),
            srcRes.getParent().resolve("ab3d2-tkg-java/src/main/resources/sounds/raw/TEST.LNK")
        );
        for (Path p : candidates) {
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static List<Path> gatherWadSearchPaths(Path srcRes) {
        List<Path> paths = new ArrayList<>();
        // Chercher dans différents dossiers possibles
        for (String sub : List.of("objects", "graphics", "includes", "sounds/raw",
                                   "walls", ".", "assets")) {
            Path p = srcRes.resolve(sub);
            if (Files.exists(p)) paths.add(p);
        }
        // Parent du projet Java
        try {
            Path parent = srcRes.getParent().getParent().getParent();
            paths.add(parent);
            paths.add(parent.resolve("ab3d2-assets"));
        } catch (Exception ignored) {}
        return paths;
    }

    private static byte[] findAndLoad(String filename, List<Path> searchPaths) {
        for (Path dir : searchPaths) {
            // Essayer case-insensitive sur les noms
            for (String name : List.of(filename, filename.toUpperCase(),
                                        filename.toLowerCase())) {
                Path f = dir.resolve(name);
                if (Files.exists(f)) {
                    try { return Files.readAllBytes(f); } catch (IOException ignored) {}
                }
            }
        }
        return null;
    }
}
