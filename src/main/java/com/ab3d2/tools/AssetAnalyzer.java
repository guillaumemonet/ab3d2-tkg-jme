package com.ab3d2.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Analyseur de fichiers binaires AB3D2.
 * Utilise LnkParser pour dumper TEST.LNK.
 * Usage : ./gradlew analyzeAssets
 */
public class AssetAnalyzer {

    public static void main(String[] args) throws Exception {
        String srcRes = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-java/src/main/resources";
        Path src = Path.of(srcRes);
        System.out.println("=== AssetAnalyzer AB3D2 ===");
        System.out.println("Source: " + src);

        // Analyser twolev.obj et twolev.clips
        Path levelA = src.resolve("levels/LEVEL_A");
        analyzeFile(levelA.resolve("twolev.obj"),   "twolev.obj");
        analyzeFile(levelA.resolve("twolev.clips"), "twolev.clips");

        // Dump TEST.LNK (GLF database)
        System.out.println("\n=== TEST.LNK (GLF database) ===");
        Path lnkFile = findLnkFile(src);
        if (lnkFile != null) {
            LnkParser lnk = LnkParser.load(lnkFile);
            lnk.dump();
            checkAssetFiles(lnk, src);
        } else {
            System.out.println("TEST.LNK introuvable!");
            System.out.println("Cherche dans: " + src.resolve("sounds/raw/TEST.LNK"));
        }

        // Chercher des fichiers WAD/PTR sur le disque
        System.out.println("\n=== Recherche assets graphiques sur disque ===");
        searchAssets(src);
    }

    private static Path findLnkFile(Path src) {
        for (Path p : List.of(
            src.resolve("sounds/raw/TEST.LNK"),
            src.resolve("sounds/raw/test.lnk"),
            src.resolve("TEST.LNK"),
            src.resolve("test.lnk"))) {
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static void analyzeFile(Path p, String label) throws Exception {
        if (!Files.exists(p)) { System.out.println("ABSENT: " + label); return; }
        byte[] raw = Files.readAllBytes(p);
        System.out.printf("\n--- %s (%d bytes) ---%n", label, raw.length);
        System.out.print("  HEX[0..31]: ");
        for (int i = 0; i < Math.min(32, raw.length); i++) System.out.printf("%02X ", raw[i] & 0xFF);
        System.out.println();
        int w0 = ((raw[0]&0xFF)<<8)|(raw[1]&0xFF);
        int w1 = raw.length>3?((raw[2]&0xFF)<<8)|(raw[3]&0xFF):0;
        System.out.printf("  header: w0=0x%04X(%d) w1=0x%04X(%d)%n", w0, w0, w1, w1);

        if (label.equals("twolev.obj")) analyzeObjT(raw);
        if (label.equals("twolev.clips")) {
            System.out.println("  -> PVS zone visibility data (FF FF=-1 end, FF FE=-2 clip end)");
            System.out.println("     Confirme par hires.s: cmp.w #-2,(a2,d0.l)");
            // Compter les -1 et -2
            int count1 = 0, count2 = 0;
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            while (bb.remaining() >= 2) {
                short s = bb.getShort();
                if (s == -1) count1++;
                else if (s == -2) count2++;
            }
            System.out.printf("  Marqueurs: -1 x%d  -2 x%d  (nb zones ~%d)%n", count1, count2, count2);
        }
    }

    private static void analyzeObjT(byte[] raw) {
        int OBJ_SIZE = 64, count = raw.length / OBJ_SIZE;
        System.out.printf("  [ObjT runtime] %d objets de 64 bytes:%n", count);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        System.out.println("  idx  xPos     zPos     yPos    zone type");
        for (int i = 0; i < Math.min(count, 20); i++) {
            bb.position(i * OBJ_SIZE);
            int xPos=bb.getInt(), zPos=bb.getInt(), yPos=bb.getInt();
            int zone=bb.getShort()&0xFFFF; bb.getShort();
            int typeId=bb.get()&0xFF;
            int isVec = raw[i*OBJ_SIZE+6]&0xFF; // 0xFF = polygonal model
            System.out.printf("  [%2d] x=%-7d z=%-7d y=%-7d z=%3d t=%d %s%n",
                i, xPos, zPos, yPos, zone, typeId,
                isVec==0xFF?"[VECTOR]":"[BITMAP]");
        }
    }

    private static void checkAssetFiles(LnkParser lnk, Path src) {
        System.out.println("\n=== Verification fichiers WAD/PTR/256PAL ===");
        List<String> gfxNames = lnk.getObjGfxNames();
        List<Path> searchDirs = List.of(
            src, src.resolve("sounds/raw"), src.resolve("graphics"),
            src.resolve("includes"), src.resolve("objects")
        );
        int found = 0, missing = 0;
        for (int i = 0; i < gfxNames.size(); i++) {
            String name = gfxNames.get(i);
            if (name.isEmpty()) continue;
            String base = LnkParser.extractFileName(name);
            boolean hasWad = fileExists(base + ".wad", searchDirs);
            boolean hasPtr = fileExists(base + ".ptr", searchDirs);
            boolean hasPal = fileExists(base + ".256pal", searchDirs);
            if (hasWad && hasPtr) {
                System.out.printf("  OK  [%2d] %s%n", i, base);
                found++;
            } else {
                System.out.printf("  MISS[%2d] %-25s  WAD=%s PTR=%s PAL=%s%n",
                    i, base, hasWad?"OK":"--", hasPtr?"OK":"--", hasPal?"OK":"--");
                missing++;
            }
        }
        System.out.printf("%nSprites: %d OK, %d manquants%n", found, missing);
        if (missing > 0) {
            System.out.println("=> Les fichiers .WAD font partie du jeu AB3D2 original.");
            System.out.println("   Copiez-les dans: " + src.resolve("graphics"));
        }
    }

    private static boolean fileExists(String filename, List<Path> dirs) {
        for (Path d : dirs) {
            if (!Files.exists(d)) continue;
            for (String n : List.of(filename, filename.toUpperCase(), filename.toLowerCase())) {
                if (Files.exists(d.resolve(n))) return true;
            }
        }
        return false;
    }

    private static void searchAssets(Path src) {
        System.out.println("Recherche .WAD/.PTR/.256PAL...");
        Set<String> exts = Set.of(".WAD",".wad",".PTR",".ptr",".256PAL",".256pal");
        int total = 0;
        for (Path base : List.of(src, Path.of("C:/Users/guill"),
                                  Path.of("C:/Games"), Path.of("C:/Program Files (x86)"))) {
            if (!Files.exists(base)) continue;
            try {
                var found = Files.walk(base, 5)
                    .filter(p -> Files.isRegularFile(p) &&
                        exts.stream().anyMatch(p.getFileName().toString()::endsWith))
                    .limit(100).toList();
                if (!found.isEmpty()) {
                    System.out.println("\nDans " + base + ":");
                    found.forEach(p -> { try {
                        System.out.printf("  %s (%d b)%n", p, Files.size(p)); } catch(Exception ig){} });
                    total += found.size();
                }
            } catch (Exception ignored) {}
        }
        if (total == 0) {
            System.out.println("=> Aucun asset .WAD/.PTR trouve.");
            System.out.println("=> Pour convertir les sprites, copiez les fichiers du");
            System.out.println("   repertoire ab3:includes/ et ab3:hqn/ du jeu original dans:");
            System.out.println("   " + src.resolve("graphics"));
        }
    }
}
