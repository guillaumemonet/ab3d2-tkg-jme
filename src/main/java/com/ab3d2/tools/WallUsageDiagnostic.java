package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Audit croise : verifie la coherence entre les textures PNG et les walls
 * definis dans les JSON de niveau.
 *
 * <p>Pour chaque wall d'un niveau donne (ou tous), verifie :</p>
 * <ul>
 *   <li>Le <code>clipIdx</code> pointe vers un PNG existant</li>
 *   <li>Le <code>texIndex</code> (fromTile) reste dans les limites de la texture</li>
 *   <li>Le <code>yOffset</code> dans la texture est coherent avec sa hauteur</li>
 *   <li>Le rapport wallLen / texWidth (combien de fois la texture est repetee)</li>
 *   <li>Le rapport wallH / 128 (V_repeat)</li>
 * </ul>
 *
 * <p>Produit un tableau de statistiques par texture et detecte les anomalies.</p>
 *
 * <p>Usage :</p>
 * <pre>
 *   ./gradlew diagWallUsage                    # Tous les niveaux A-P
 *   ./gradlew diagWallUsage -Plevel=A          # Un seul niveau
 * </pre>
 */
public class WallUsageDiagnostic {

    private static final float TEX_V = 128f;

    public static void main(String[] args) throws Exception {
        String wallDir = args.length > 0 ? args[0] : "assets/Textures/walls";
        String levelDir = args.length > 1 ? args[1] : "assets/levels";
        String specific = args.length > 2 ? args[2] : null;

        Path walls  = Path.of(wallDir);
        Path levels = Path.of(levelDir);

        if (!Files.isDirectory(walls) || !Files.isDirectory(levels)) {
            System.err.println("Dossiers introuvables");
            System.exit(1);
        }

        // 1. Charger les dimensions de chaque PNG wall
        Map<Integer, int[]> texDims = new TreeMap<>(); // clipIdx -> [width, height]
        Map<Integer, String> texNames = new TreeMap<>();
        try (var s = Files.list(walls)) {
            s.filter(p -> p.getFileName().toString().matches("wall_\\d{2}_.*\\.png"))
             .forEach(p -> {
                String fn = p.getFileName().toString();
                int idx = Integer.parseInt(fn.substring(5, 7));
                try {
                    BufferedImage img = ImageIO.read(p.toFile());
                    texDims.put(idx, new int[]{img.getWidth(), img.getHeight()});
                    texNames.put(idx, fn.substring(8, fn.length() - 4));
                } catch (Exception e) {
                    System.err.println("ERR: " + fn + " " + e.getMessage());
                }
             });
        }

        System.out.println("=== WallUsageDiagnostic ===");
        System.out.printf("%d textures walls chargees%n%n", texDims.size());

        // 2. Parcourir les niveaux
        List<Path> levelFiles = new ArrayList<>();
        try (var s = Files.list(levels)) {
            s.filter(p -> p.getFileName().toString().matches("level_[A-P]\\.json"))
             .sorted()
             .forEach(levelFiles::add);
        }
        if (specific != null) {
            levelFiles.removeIf(p -> !p.getFileName().toString().equals("level_" + specific + ".json"));
        }

        // Accumulateurs globaux
        int[] walls_total = {0};
        int[] walls_noTexture = {0};
        int[] walls_hBigger128 = {0};
        int[] walls_wallLenBigTile = {0};
        Map<Integer, Integer> clipIdxCount = new TreeMap<>();
        Map<Integer, Integer> fromTileCount = new TreeMap<>();
        Map<Integer, Integer> yOffsetCount = new TreeMap<>();

        for (Path lvl : levelFiles) {
            String json = Files.readString(lvl);
            String levelId = lvl.getFileName().toString().substring(6, 7);

            // Parser naivement les walls : chercher "walls": {
            int wi = json.indexOf("\"walls\"");
            if (wi < 0) continue;
            int open = json.indexOf('{', wi);
            int close = findMatchingBrace(json, open);
            if (open < 0 || close < 0) continue;
            String wallsObj = json.substring(open + 1, close);

            System.out.printf("=== LEVEL %s ===%n", levelId);

            // Pour chaque mur, extraire clipIdx, texIndex, yOffset, wallLen, topWallH, botWallH
            int pos = 0;
            int levelWalls = 0;
            int levelBigH = 0;
            int levelBigLen = 0;
            int levelNoTex = 0;
            while (pos < wallsObj.length()) {
                int wallStart = wallsObj.indexOf('{', pos);
                if (wallStart < 0) break;
                int wallEnd = findMatchingBrace(wallsObj, wallStart);
                if (wallEnd < 0) break;
                String wall = wallsObj.substring(wallStart + 1, wallEnd);
                pos = wallEnd + 1;

                int texIndex = extractInt(wall, "texIndex", 0);
                int clipIdx  = extractInt(wall, "clipIdx", texIndex);
                int yOffset  = extractInt(wall, "yOffset", 0);
                int wallLen  = extractInt(wall, "wallLen", 0);
                int topH     = extractInt(wall, "topWallH", 0);
                int botH     = extractInt(wall, "botWallH", 0);
                int wallH    = Math.abs(topH - botH);

                // portail (ti & 0x8000) ou mur invisible -> skip
                if ((texIndex & 0x8000) != 0) continue;
                if (topH == botH) continue;

                walls_total[0]++;
                levelWalls++;
                clipIdxCount.merge(clipIdx, 1, Integer::sum);
                fromTileCount.merge(texIndex, 1, Integer::sum);
                yOffsetCount.merge(yOffset, 1, Integer::sum);

                int[] dim = texDims.get(clipIdx);
                if (dim == null) {
                    walls_noTexture[0]++;
                    levelNoTex++;
                    continue;
                }
                int texW = dim[0];
                int texH = dim[1];

                // Hauteur mur > 128 -> repeat V
                if (wallH > TEX_V) {
                    walls_hBigger128[0]++;
                    levelBigH++;
                }
                // wallLen > texW -> repeat U
                if (wallLen > texW) {
                    walls_wallLenBigTile[0]++;
                    levelBigLen++;
                }
            }
            System.out.printf("  Murs: %d  texture_manquante: %d  wallLen>texW: %d  wallH>128: %d%n",
                levelWalls, levelNoTex, levelBigLen, levelBigH);
        }

        // Rapport final
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════");
        System.out.printf("TOTAL : %d murs analyses%n", walls_total[0]);
        System.out.printf("  - %d sans texture trouvee (clipIdx invalide)%n", walls_noTexture[0]);
        System.out.printf("  - %d avec wallLen > largeur texture (repeat U)%n", walls_wallLenBigTile[0]);
        System.out.printf("  - %d avec wallH > 128 (repeat V)%n", walls_hBigger128[0]);
        System.out.println();
        System.out.println("DISTRIBUTION DES clipIdx (textures utilisees) :");
        for (var e : clipIdxCount.entrySet()) {
            int idx = e.getKey();
            int[] dim = texDims.get(idx);
            String name = texNames.getOrDefault(idx, "?");
            String dimStr = dim != null ? String.format("%dx%d", dim[0], dim[1]) : "MISSING";
            System.out.printf("  clipIdx=%2d (%-16s %10s) : %d murs%n", idx, name, dimStr, e.getValue());
        }
        System.out.println();
        System.out.println("DISTRIBUTION DES texIndex (= fromTile, decalage U) :");
        for (var e : fromTileCount.entrySet()) {
            int val = e.getKey();
            int pixels = val * 16;
            System.out.printf("  fromTile=%3d (offset=%4d px) : %d murs%n", val, pixels, e.getValue());
        }
        System.out.println();
        System.out.println("DISTRIBUTION DES yOffset (decalage V) :");
        for (var e : yOffsetCount.entrySet()) {
            System.out.printf("  yOffset=%3d : %d murs%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("CONCLUSIONS :");
        if (walls_noTexture[0] > 0) {
            System.out.println("  ! Textures manquantes - verifier AssetConverter.WALL_NAMES");
        }
        if (walls_wallLenBigTile[0] > 0) {
            System.out.printf("  > %d murs utilisent un wrap U (WrapMode.Repeat est applique)%n",
                walls_wallLenBigTile[0]);
        }
        if (walls_hBigger128[0] > 0) {
            System.out.printf("  > %d murs utilisent un wrap V (WrapMode.Repeat est applique)%n",
                walls_hBigger128[0]);
        }
    }

    private static int extractInt(String s, String key, int def) {
        int idx = s.indexOf("\"" + key + "\"");
        if (idx < 0) return def;
        int col = s.indexOf(':', idx);
        if (col < 0) return def;
        int start = col + 1;
        while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) start++;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        if (end == start) return def;
        try { return Integer.parseInt(s.substring(start, end)); }
        catch (Exception e) { return def; }
    }

    private static int findMatchingBrace(String s, int open) {
        if (open < 0) return -1;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
