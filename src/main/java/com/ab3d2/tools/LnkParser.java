package com.ab3d2.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parseur du fichier GLF database : ab3:includes/test.lnk
 *
 * Structure (depuis leved303.amos, procedure _LOAD_DEF_LINK) :
 *   Taille totale : 86268 bytes
 *   Offsets LINK :
 *     $0000 (+  0)  : Level directory path (64 bytes)
 *     $0040 (+ 64)  : Level names  (16 × 40 bytes = 640)
 *     $02C0 (+704)  : GLFT_ObjGfxNames_l  : 30 × 64 = 1920 bytes  -> noms .WAD
 *     $0A40 (+2624) : GLFT_SFXFilenames_l : 60 × 64 = 3840 bytes  -> noms .fib
 *     $1940 (+6464) : GLFT_FloorFilename  : 64 bytes               -> floortile
 *     $1980 (+6528) : GLFT_TextureFilename: 192 bytes              -> texture
 *     $14760 (+83808): GLFT_WallGFXNames  : 16 × 64 = 1024 bytes  -> .256wad
 *     $14B60 (+84832): GLFT_WallHeights   : 16 × 2  = 32 bytes
 *     $39B0 (+14768) : GLFT_FrameData     : 30 × 256 bytes         -> frame descriptors
 *     $13FE0 (+81888): GLFT_VectorNames_l : 30 × 64 = 1920 bytes   -> noms vectobj
 *     $2C0  (+704)   : GLFT_ObjGfxNames_l  (bitmap objects)
 *     $34D8 (+13528) : GLFT_AlienNames    : 20 × 20 = 400 bytes
 *     $57B0 (+22448) : GLFT_ObjectNames   : 30 × 20 = 600 bytes
 *     $3668 (+13928) : GLFT_AlienDefs     : 20 × 42 = 840 bytes
 *     $5A08 (+23048) : GLFT_ObjectDefs    : 30 × 40 = 1200 bytes
 *
 * Format frame data (depuis leved303.amos, proc _GL_SETOBJFRAMES) :
 *   Pour chaque objet bitmap OG (0..29), chaque frame FRN (0..63) :
 *   Offset = LINK+$39B0 + OG*256 + FRN*8
 *   [LX:word, LY:word, LW/2:word, LH/2:word]
 *   LX = start column in WAD strip table
 *   LY = start row in WAD data
 *   LW = width in pixels (stored /2)
 *   LH = height in pixels (stored /2)
 */
public class LnkParser {

    // Offsets dans TEST.LNK (depuis leved303.amos)
    public static final int OFS_LEVEL_DIR        = 0x0000;   // 64 bytes
    public static final int OFS_LEVEL_NAMES      = 0x0040;   // 16*40 = 640 bytes
    public static final int OFS_OBJ_GFX_NAMES    = 0x02C0;   // 30*64 = 1920 bytes  <- WAD sprites
    public static final int OFS_SFX_FILENAMES    = 0x0A40;   // 60*64 = 3840 bytes
    public static final int OFS_FLOOR_FILENAME   = 0x1940;   // 64 bytes
    public static final int OFS_TEXTURE_FILENAME = 0x1980;   // 192 bytes
    public static final int OFS_FRAME_DATA       = 0x39B0;   // 30*256 = 7680 bytes  <- LX/LY/LW/LH
    public static final int OFS_OBJECT_NAMES     = 0x57B0;   // 30*20 = 600 bytes
    public static final int OFS_OBJECT_DEFS      = 0x5A08;   // 30*40 = 1200 bytes
    public static final int OFS_ALIEN_NAMES      = 0x34D8;   // 20*20 = 400 bytes
    public static final int OFS_ALIEN_DEFS       = 0x3668;   // 20*42 = 840 bytes
    public static final int OFS_WALL_GFX_NAMES   = 0x14760;  // 16*64 = 1024 bytes  <- .256wad
    public static final int OFS_WALL_HEIGHTS     = 0x14B60;  // 16*2  = 32 bytes
    public static final int OFS_VECTOR_NAMES     = 0x13FE0;  // 30*64 = 1920 bytes  <- vectobj

    // Limites
    public static final int NUM_OBJ_GFX   = 30;
    public static final int NUM_VECTORS   = 30;
    public static final int NUM_WALLS     = 16;
    public static final int NUM_SFX       = 60;
    public static final int NUM_ALIENS    = 20;
    public static final int NUM_OBJECTS   = 30;
    public static final int NUM_FRAMES    = 64; // max frames par objet

    private final byte[] data;

    public LnkParser(byte[] lnkData) {
        this.data = lnkData;
    }

    public static LnkParser load(Path lnkFile) throws IOException {
        byte[] raw = Files.readAllBytes(lnkFile);
        System.out.printf("TEST.LNK: %d bytes (attendu 86268)%n", raw.length);
        return new LnkParser(raw);
    }

    // ── Lecture de chaines C (null-terminées) ─────────────────────────────────

    public String readCString(int offset, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLen && offset + i < data.length; i++) {
            byte b = data[offset + i];
            if (b == 0) break;
            char c = (char)(b & 0xFF);
            if (c >= 32 && c < 127) sb.append(c);
            else break;
        }
        return sb.toString();
    }

    public int readShort(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // ── Noms des sprites bitmap (WAD) ─────────────────────────────────────────

    /**
     * Retourne les 30 noms de fichiers WAD (objets bitmap).
     * Exemple : "ab3:includes/alien2", "ab3:includes/pickups", ...
     * Les extensions .WAD, .PTR, .256PAL sont ajoutées lors du chargement.
     */
    public List<String> getObjGfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_OBJ_GFX; i++) {
            int off = OFS_OBJ_GFX_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    /**
     * Extrait juste le nom de fichier (sans chemin ni extension).
     * "ab3:includes/alien2" -> "alien2"
     */
    public static String extractFileName(String path) {
        if (path == null || path.isEmpty()) return "";
        // Séparer sur / et :
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        // Supprimer extension si présente
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Noms des modèles vectoriels ───────────────────────────────────────────

    /**
     * Retourne les 30 noms de fichiers vectoriels.
     * Exemple : "ab3:vectobj/generator", "ab3:vectobj/switch", ...
     */
    public List<String> getVectorNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_VECTORS; i++) {
            int off = OFS_VECTOR_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    // ── Frame data ────────────────────────────────────────────────────────────

    /**
     * Descriptor d'une frame de sprite.
     * Depuis leved303.amos :
     *   LX = Deek(LINK+$39B0 + OG*256 + FRN*8)     -> colonne de départ dans PTR
     *   LY = Deek(LINK+$39B0 + OG*256 + FRN*8 + 2) -> ligne de départ dans WAD
     *   LW = Deek(LINK+$39B0 + OG*256 + FRN*8 + 4) * 2  -> largeur pixels
     *   LH = Deek(LINK+$39B0 + OG*256 + FRN*8 + 6) * 2  -> hauteur pixels
     */
    public record FrameDesc(int lx, int ly, int lw, int lh) {
        public boolean isValid() { return lw > 0 && lh > 0 && lw <= 512 && lh <= 512; }
    }

    public FrameDesc getFrameDesc(int objIndex, int frameIndex) {
        int off = OFS_FRAME_DATA + objIndex * 256 + frameIndex * 8;
        if (off + 8 > data.length) return new FrameDesc(0, 0, 0, 0);
        int lx = readShort(off);
        int ly = readShort(off + 2);
        int lw = readShort(off + 4) * 2;
        int lh = readShort(off + 6) * 2;
        return new FrameDesc(lx, ly, lw, lh);
    }

    /** Nombre de frames valides pour un objet (cherche la première frame invalide). */
    public int countFrames(int objIndex) {
        for (int f = 0; f < NUM_FRAMES; f++) {
            FrameDesc fd = getFrameDesc(objIndex, f);
            if (!fd.isValid()) return f;
        }
        return NUM_FRAMES;
    }

    // ── Noms des murs (.256wad) ───────────────────────────────────────────────

    public List<String> getWallGfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_WALLS; i++) {
            int off = OFS_WALL_GFX_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    public int getWallHeight(int wallIndex) {
        int off = OFS_WALL_HEIGHTS + wallIndex * 2;
        if (off + 2 > data.length) return 0;
        return readShort(off);
    }

    // ── Noms des SFX ─────────────────────────────────────────────────────────

    public List<String> getSfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_SFX; i++) {
            int off = OFS_SFX_FILENAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    // ── Noms des aliens et objets ─────────────────────────────────────────────

    public List<String> getAlienNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_ALIENS; i++) {
            int off = OFS_ALIEN_NAMES + i * 20;
            if (off + 20 > data.length) break;
            names.add(readCString(off, 20));
        }
        return names;
    }

    public List<String> getObjectNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_OBJECTS; i++) {
            int off = OFS_OBJECT_NAMES + i * 20;
            if (off + 20 > data.length) break;
            names.add(readCString(off, 20));
        }
        return names;
    }

    // ── Dump diagnostique ─────────────────────────────────────────────────────

    public void dump() {
        System.out.println("\n=== TEST.LNK DUMP ===");
        System.out.println("Level dir: " + readCString(OFS_LEVEL_DIR, 64));
        System.out.println("\n-- Sprites bitmap (GLFT_ObjGfxNames_l) --");
        List<String> gfx = getObjGfxNames();
        for (int i = 0; i < gfx.size(); i++) {
            if (!gfx.get(i).isEmpty()) {
                int nf = countFrames(i);
                System.out.printf("  [%2d] %-40s  (%d frames)%n", i, gfx.get(i), nf);
            }
        }
        System.out.println("\n-- Modeles vectoriels (GLFT_VectorNames_l) --");
        List<String> vecs = getVectorNames();
        for (int i = 0; i < vecs.size(); i++) {
            if (!vecs.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, vecs.get(i));
        }
        System.out.println("\n-- Murs (.256wad) --");
        List<String> walls = getWallGfxNames();
        for (int i = 0; i < walls.size(); i++) {
            if (!walls.get(i).isEmpty())
                System.out.printf("  [%2d] %s (h=%d)%n", i, walls.get(i), getWallHeight(i));
        }
        System.out.println("\n-- Noms objets --");
        List<String> objs = getObjectNames();
        for (int i = 0; i < objs.size(); i++)
            if (!objs.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, objs.get(i));
        System.out.println("\n-- Noms aliens --");
        List<String> aliens = getAlienNames();
        for (int i = 0; i < aliens.size(); i++)
            if (!aliens.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, aliens.get(i));
    }
}
