package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Convertit twolev.clips en sprites PNG.
 * Format WAD Amiga : 1 byte/pixel indexe 256 couleurs. Index 0 = transparent.
 * Usage : ./gradlew convertClips
 */
public class ClipsConverter {

    public static void main(String[] args) throws Exception {
        String srcRes  = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-java/src/main/resources";
        String destDir = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Textures/objects";
        Path src = Path.of(srcRes);
        Path dest = Path.of(destDir);
        Files.createDirectories(dest);
        System.out.println("=== ClipsConverter ===");
        int[] palette = loadGlobalPalette(src.resolve("256pal.bin"));
        System.out.printf("Palette: %d entrees%n", palette.length);
        int ok = 0;
        for (char c = 'A'; c <= 'P'; c++) {
            Path clipsFile = src.resolve("levels/LEVEL_" + c + "/twolev.clips");
            if (!Files.exists(clipsFile)) continue;
            Path levelDest = dest.resolve("level_" + Character.toLowerCase(c));
            Files.createDirectories(levelDest);
            try {
                int n = convertClips(Files.readAllBytes(clipsFile), palette, levelDest,
                                     "level_" + Character.toLowerCase(c));
                System.out.printf("  OK  LEVEL_%c -> %d sprites%n", c, n);
                ok++;
            } catch (Exception e) {
                System.out.printf("  ERR LEVEL_%c : %s%n", c, e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.printf("Niveaux traites: %d%n", ok);
    }

    public static int[] loadGlobalPalette(Path palFile) throws IOException {
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) pal[i] = 0xFF000000|(i<<16)|(i<<8)|i;
        if (!Files.exists(palFile)) { System.out.println("WARN: 256pal.bin absent"); return pal; }
        byte[] raw = Files.readAllBytes(palFile);
        System.out.printf("256pal.bin: %d bytes%n", raw.length);
        if (raw.length >= 256 * 4) {
            for (int i = 0; i < 256; i++) {
                int r=raw[i*4]&0xFF, g=raw[i*4+1]&0xFF, b=raw[i*4+2]&0xFF;
                pal[i] = 0xFF000000|(r<<16)|(g<<8)|b;
            }
        } else if (raw.length >= 256 * 3) {
            for (int i = 0; i < 256; i++) {
                int r=raw[i*3]&0xFF, g=raw[i*3+1]&0xFF, b=raw[i*3+2]&0xFF;
                pal[i] = 0xFF000000|(r<<16)|(g<<8)|b;
            }
        }
        return pal;
    }

    public static int convertClips(byte[] raw, int[] palette, Path destDir, String prefix) throws Exception {
        System.out.printf("  %s (%d bytes) HEX: ", prefix, raw.length);
        for (int i = 0; i < Math.min(16, raw.length); i++) System.out.printf("%02X ", raw[i]&0xFF);
        System.out.println();
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int numClips = bb.getShort() & 0xFFFF;
        System.out.printf("  numClips(word0)=%d%n", numClips);
        if (numClips > 0 && numClips <= 500) {
            for (int entrySize : new int[]{8, 10, 12, 6}) {
                boolean valid = true;
                List<int[]> entries = new ArrayList<>();
                for (int i = 0; i < numClips; i++) {
                    int off = 2 + i * entrySize;
                    if (off + entrySize > raw.length) { valid=false; break; }
                    bb.position(off);
                    int w=bb.getShort()&0xFFFF, h=bb.getShort()&0xFFFF;
                    int ofs = (entrySize >= 8) ? bb.getInt() : (bb.getShort()&0xFFFF);
                    if (w==0||h==0||w>512||h>512||ofs<0||ofs>=raw.length||(long)ofs+w*h>raw.length) { valid=false; break; }
                    entries.add(new int[]{w,h,ofs});
                }
                if (valid && !entries.isEmpty()) {
                    System.out.printf("  Format valide: entrySize=%d, %d clips%n", entrySize, entries.size());
                    int saved = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        int[] e = entries.get(i);
                        try {
                            BufferedImage img = decodeSprite(raw, e[2], e[0], e[1], palette);
                            ImageIO.write(img, "png", destDir.resolve(prefix+"_clip_"+i+".png").toFile());
                            saved++;
                        } catch (Exception ex) { System.out.printf("    WARN[%d]: %s%n", i, ex.getMessage()); }
                    }
                    return saved;
                }
            }
        }
        System.out.println("  Format non reconnu, scan heuristique...");
        return scanAndSave(raw, palette, destDir, prefix);
    }

    private static int scanAndSave(byte[] raw, int[] palette, Path destDir, String prefix) throws Exception {
        int saved = 0;
        for (int sz : new int[]{32,64,48,16}) {
            int n = raw.length / (sz*sz);
            for (int i = 0; i < Math.min(n, 80); i++) {
                BufferedImage img = decodeSprite(raw, i*sz*sz, sz, sz, palette);
                if (hasContent(img)) {
                    ImageIO.write(img, "png", destDir.resolve(prefix+"_"+sz+"x"+sz+"_"+i+".png").toFile());
                    saved++;
                }
            }
            if (saved > 0) { System.out.printf("  Scan %dx%d: %d sprites%n", sz, sz, saved); return saved; }
        }
        return saved;
    }

    public static BufferedImage decodeSprite(byte[] data, int offset, int width, int height, int[] palette) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                int pixOfs = offset + y*width + x;
                if (pixOfs >= data.length) break;
                int idx = data[pixOfs] & 0xFF;
                img.setRGB(x, y, idx == 0 ? 0x00000000 : palette[idx]);
            }
        return img;
    }

    private static boolean hasContent(BufferedImage img) {
        int n = 0;
        for (int y=0;y<img.getHeight();y++) for (int x=0;x<img.getWidth();x++)
            if ((img.getRGB(x,y)>>24&0xFF)>0) n++;
        return n > img.getWidth()*img.getHeight()/8;
    }
}
