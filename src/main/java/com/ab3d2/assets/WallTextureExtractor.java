package com.ab3d2.assets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Extrait les textures murs depuis les fichiers .256wad.
 *
 * Format : shade table (2048 bytes) + chunk data (pixels 5-bits column-major).
 * 3 texels par WORD : bits[4:0]=PACK0, bits[9:5]=PACK1, bits[14:10]=PACK2.
 */
public class WallTextureExtractor {

    private static final Logger log = LoggerFactory.getLogger(WallTextureExtractor.class);

    static final int SHADE_ROWS        = WadTextureData.SHADE_ROWS;
    static final int ENTRIES_PER_ROW   = WadTextureData.ENTRIES_PER_ROW;
    static final int SHADE_TABLE_BYTES = SHADE_ROWS * ENTRIES_PER_ROW * 2; // 2048

    private static final int[][] CANDIDATE_SIZES = {
        {256,128}, {128,128}, {128, 32}, {256, 64}, {128, 64},
        {256,256}, { 64, 64}, { 32, 64}, { 64, 32}, { 64,128},
        { 32, 32}, { 32,128}, { 16, 64}, { 96, 64}, { 64, 96},
        { 96,128}, {128,256},
    };

    private final int[] palette;

    public WallTextureExtractor(int[] palette) {
        this.palette = Objects.requireNonNull(palette);
    }

    // ── Lecture hauteur depuis le footer du fichier .256wad ───────────────────
    // AMOS LevelED : WGH = Deek(Start(701+ZWGC) + T - 2)  (T = Length du bank)
    // Les 2 DERNIERS bytes du fichier = hauteur texture (big-endian WORD)
    public static int readHeightFromFooter(byte[] raw) {
        if (raw.length < 2) return 0;
        return ((raw[raw.length - 2] & 0xFF) << 8) | (raw[raw.length - 1] & 0xFF);
    }

    // Calcule la largeur depuis taille fichier + hauteur connue
    // chunkBytes = numGroups * texH * 2 ;  numGroups = (texW+2)/3
    public static int computeWidth(int fileSize, int texH) {
        if (texH <= 0) return 0;
        int chunkBytes = fileSize - SHADE_TABLE_BYTES - 2;  // -2 = footer height word
        if (chunkBytes <= 0) return 0;
        if (chunkBytes % (texH * 2) != 0) return 0;
        return (chunkBytes / (texH * 2)) * 3;  // numGroups * 3
    }

    public WadTextureData load(Path path) throws IOException {
        byte[] raw  = Files.readAllBytes(path);
        String name = path.getFileName().toString().replaceAll("(?i)\\.256wad$", "");

        // Hauteur depuis footer (format officiel AMOS)
        int texH = readHeightFromFooter(raw);
        int texW = computeWidth(raw.length, texH);

        if (texH <= 0 || texW <= 0) {
            log.warn("{}: footer h={} invalide, fallback detection",
                     path.getFileName(), texH);
            int[] dims = detectDimensions(raw.length);
            if (dims == null)
                throw new IllegalArgumentException(
                    path.getFileName() + " : dimensions inconnues (" + raw.length + " bytes)");
            texW = dims[0]; texH = dims[1];
        }

        log.debug("{}: {}x{} (footer h={})", name, texW, texH, readHeightFromFooter(raw));
        return decode(name, raw, texW, texH);
    }

    public WadTextureData load(Path path, int width, int height) throws IOException {
        byte[] raw  = Files.readAllBytes(path);
        String name = path.getFileName().toString().replaceAll("(?i)\\.256wad$", "");
        return decode(name, raw, width, height);
    }

    public Map<String, WadTextureData> loadAll(Path dir) throws IOException {
        Map<String, WadTextureData> result = new LinkedHashMap<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.toString().toLowerCase().endsWith(".256wad")).sorted().forEach(p -> {
                try {
                    WadTextureData tex = load(p);
                    result.put(tex.name().toLowerCase(), tex);
                    log.info("Wall texture: {} ({}x{})", tex.name(), tex.width(), tex.height());
                } catch (Exception e) { log.warn("Skip {} : {}", p.getFileName(), e.getMessage()); }
            });
        }
        return result;
    }

    public WadTextureData decode(String name, byte[] raw, int texW, int texH) {
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        int[] shadeTable = new int[SHADE_ROWS * ENTRIES_PER_ROW];
        for (int r = 0; r < SHADE_ROWS; r++)
            for (int e = 0; e < ENTRIES_PER_ROW; e++) {
                int palIdx = buf.get() & 0xFF;
                buf.get(); // byte inutilise
                shadeTable[r * ENTRIES_PER_ROW + e] = palette[palIdx];
            }

        int brightBase = 0; // row 0 = luminosite max
        int numGroups  = (texW + 2) / 3;
        int[] pixels   = new int[texW * texH];

        for (int g = 0; g < numGroups; g++) {
            int baseX = g * 3;
            for (int y = 0; y < texH && buf.remaining() >= 2; y++) {
                int word = buf.getShort() & 0xFFFF;
                int t0   =  word        & 0x1F;
                int t1   = (word >>  5) & 0x1F;
                int t2   = (word >> 10) & 0x1F;
                if (baseX     < texW) pixels[y * texW + baseX    ] = shadeTable[brightBase + t0];
                if (baseX + 1 < texW) pixels[y * texW + baseX + 1] = shadeTable[brightBase + t1];
                if (baseX + 2 < texW) pixels[y * texW + baseX + 2] = shadeTable[brightBase + t2];
            }
        }
        return new WadTextureData(name, texW, texH, pixels, shadeTable);
    }

    public static int[] detectDimensions(int fileSize) {
        int chunkBytes = fileSize - SHADE_TABLE_BYTES - 2;
        if (chunkBytes <= 0) chunkBytes = fileSize - SHADE_TABLE_BYTES;
        if (chunkBytes <= 0) return null;
        for (int[] d : CANDIDATE_SIZES) {
            int g = (d[0] + 2) / 3;
            if (g * d[1] * 2 == chunkBytes) return d.clone();
        }
        // Exhaustif
        int bestW = 0, bestH = 0; double bestScore = Double.MAX_VALUE;
        for (int w = 4; w <= 512; w++) {
            int g = (w + 2) / 3;
            if (chunkBytes % (g * 2) != 0) continue;
            int h = chunkBytes / (g * 2);
            if (h < 4 || h > 512) continue;
            double ratio = (double) Math.max(w, h) / Math.min(w, h);
            if (ratio > 8) continue;
            double score = ratio + (isPow2(w) ? 0 : 0.5) + (isPow2(h) ? 0 : 0.5);
            if (score < bestScore) { bestScore = score; bestW = w; bestH = h; }
        }
        return bestW > 0 ? new int[]{bestW, bestH} : null;
    }

    private static boolean isPow2(int n) { return n > 0 && (n & (n - 1)) == 0; }
}
