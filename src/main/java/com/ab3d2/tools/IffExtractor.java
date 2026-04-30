package com.ab3d2.tools;

import com.ab3d2.assets.AmigaBitplaneDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Decodeur IFF ILBM (Electronic Arts Interchange File Format, InterLeaved BitMap).
 *
 * <h2>Format</h2>
 * Un IFF commence par un chunk FORM dont le type est ILBM. A l'interieur :
 * <ul>
 *   <li><b>BMHD</b> : dimensions, nb de bitplanes, compression (ByteRun1 = 1 = RLE)</li>
 *   <li><b>CMAP</b> : palette RGB 8 bits par composante, nbColors entries (triplets)</li>
 *   <li><b>CAMG</b> : flags video mode Amiga (inutilise ici)</li>
 *   <li><b>BODY</b> : donnees pixels, nPlanes bitplanes interlaces par ligne, compresses RLE</li>
 * </ul>
 *
 * <h2>Structure BMHD (20 bytes)</h2>
 * <pre>
 *   width     : u16 BE  (pixels)
 *   height    : u16 BE
 *   xOrigin   : s16 BE
 *   yOrigin   : s16 BE
 *   nPlanes   : u8       (1..8 = 2..256 couleurs)
 *   masking   : u8       (0=none, 1=hasMask, 2=hasTransparentColor, 3=lasso)
 *   compression: u8      (0=none, 1=ByteRun1)
 *   pad1      : u8
 *   transClr  : u16 BE
 *   xAspect   : u8
 *   yAspect   : u8
 *   pageWidth : u16 BE
 *   pageHeight: u16 BE
 * </pre>
 *
 * <h2>Compression ByteRun1 (Packer algorithm)</h2>
 * Lit un byte N (signe en complement a 2) :
 * <ul>
 *   <li>N &gt;= 0   : copier les N+1 bytes suivants tels quels</li>
 *   <li>N &lt; 0 (et != -128) : repeter le byte suivant (-N)+1 fois</li>
 *   <li>N == -128 : no-op (skip)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./gradlew extractIff                        # Extrait tous les IFF standard
 *   ./gradlew extractIff -Piff=Panel            # Un seul fichier
 * </pre>
 */
public class IffExtractor {

    // ──────────────────────── Configuration ────────────────────────
    /** Mapping nom source -&gt; chemin relatif dans media/graphics. */
    private static final String[][] DEFAULT_TARGETS = {
        // HUD elements
        {"Panel",        "Panel.png"},
        {"panelraw",     "panelraw.png"},
        {"newborder",    "newborder.png"},
        {"borders",      "borders.png"},
        {"border16",     "border16.png"},
        {"borderchars",  "borderchars.png"},
        // HUD faces (visage du marine)
        {"faces",        "faces.png"},
        {"facesspaced",  "facesspaced.png"},
        // Guns en main
        {"gunsinhand",   "gunsinhand.png"},
        {"GUNFRAMES",    "gunframes.png"},
        // Fonts / pause
        {"FONTX1.IFF",   "fontx1.png"},
        {"PAUSEFONT",    "pausefont.png"},
        // Divers HUD
        {"pickups",      "pickups.png"},
        // Autres decors potentiellement utiles
        {"AB-PANEL.IFF",     "ab-panel.png"},
        {"AB-SIDEPANEL.IFF", "ab-sidepanel.png"},
    };

    // ──────────────────────── Chunk structure ────────────────────────
    private record BMHD(int width, int height, int nPlanes, int masking,
                        int compression, int transClr) {}

    // ──────────────────────── Main ────────────────────────
    public static void main(String[] args) throws Exception {
        String srcDir = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-original/media/graphics";
        String outDir = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Interface";
        String onlyName = args.length > 2 ? args[2] : null;

        Path src = Path.of(srcDir);
        Path out = Path.of(outDir);
        Files.createDirectories(out);

        System.out.println("=== IffExtractor ===");
        System.out.println("Source : " + src);
        System.out.println("Output : " + out);

        if (!Files.isDirectory(src)) {
            System.err.println("ERREUR : dossier source introuvable");
            return;
        }

        int ok = 0, fail = 0, skipped = 0;
        for (String[] entry : DEFAULT_TARGETS) {
            String srcName = entry[0], outName = entry[1];
            if (onlyName != null && !onlyName.equalsIgnoreCase(srcName)) continue;

            Path iff = src.resolve(srcName);
            if (!Files.exists(iff)) {
                System.out.printf("  -- skip  %-20s (fichier absent)%n", srcName);
                skipped++;
                continue;
            }
            Path png = out.resolve(outName);
            try {
                extract(iff, png);
                System.out.printf("  OK      %-20s -> %s%n", srcName, outName);
                ok++;
            } catch (Exception e) {
                System.out.printf("  FAIL    %-20s : %s%n", srcName, e.getMessage());
                fail++;
            }
        }
        System.out.printf("%nResume : OK=%d  fail=%d  skip=%d%n", ok, fail, skipped);
    }

    // ──────────────────────── Public API ────────────────────────
    /** Lit un IFF ILBM depuis iff et ecrit un PNG en out. */
    public static void extract(Path iff, Path out) throws IOException {
        byte[] data = Files.readAllBytes(iff);
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // FORM <size> ILBM
        if (bb.remaining() < 12) throw new IOException("fichier trop petit");
        int form = bb.getInt();
        if (form != chunkId("FORM")) throw new IOException("pas un IFF (pas de FORM)");
        int formSize = bb.getInt();
        int formType = bb.getInt();
        if (formType != chunkId("ILBM")) throw new IOException("pas un ILBM");

        BMHD bmhd = null;
        int[] palette = null;
        byte[] body = null;

        // Parcours des sub-chunks
        while (bb.remaining() >= 8) {
            int chunkType = bb.getInt();
            int chunkSize = bb.getInt();
            int nextChunkStart = bb.position() + chunkSize + (chunkSize & 1);  // pad to even

            if (chunkType == chunkId("BMHD")) {
                bmhd = readBMHD(bb);
            }
            else if (chunkType == chunkId("CMAP")) {
                int numColors = chunkSize / 3;
                palette = new int[numColors];
                for (int i = 0; i < numColors; i++) {
                    int r = bb.get() & 0xFF;
                    int g = bb.get() & 0xFF;
                    int b = bb.get() & 0xFF;
                    palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            else if (chunkType == chunkId("BODY")) {
                body = new byte[chunkSize];
                bb.get(body);
            }
            // Autres chunks (CAMG, CRNG, DRNG, ANNO, ...) : skip

            // Avancer au prochain chunk (chunks sont pad a taille paire)
            bb.position(Math.min(nextChunkStart, bb.limit()));
        }

        if (bmhd == null) throw new IOException("BMHD manquant");
        if (body == null) throw new IOException("BODY manquant");
        if (palette == null) {
            // Generer une palette grayscale par defaut
            int size = 1 << bmhd.nPlanes;
            palette = new int[size];
            for (int i = 0; i < size; i++) {
                int v = (i * 255) / Math.max(1, size - 1);
                palette[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
        }

        // Decompresser le BODY si besoin
        byte[] raw = (bmhd.compression == 1) ? decodeByteRun1(body) : body;

        // BMHD masking=1 indique un plan de masque supplementaire (mask bitplane)
        // Pour la plupart des ILBM AB3D2, on ignore ce plan mais il faut le consommer
        int totalPlanes = bmhd.nPlanes + (bmhd.masking == 1 ? 1 : 0);

        int rowBytes  = ((bmhd.width + 15) / 16) * 2;  // arrondi au word (2 bytes)
        int expected  = rowBytes * totalPlanes * bmhd.height;
        if (raw.length < expected) {
            throw new IOException("BODY trop court : " + raw.length + " < " + expected
                + " (w=" + bmhd.width + " h=" + bmhd.height + " planes=" + totalPlanes + ")");
        }

        // Reorganiser : ILBM est stocke row-by-row plane-interleaved ([P0 P1...Pn] par ligne)
        // AmigaBitplaneDecoder attend plane-contigu : [P0 complet][P1 complet]...
        byte[] reorganized = interleavedToContiguous(raw, bmhd.width, bmhd.height,
                                                     bmhd.nPlanes, totalPlanes);

        int[] pixels = AmigaBitplaneDecoder.decode(
            reorganized, bmhd.width, bmhd.height, bmhd.nPlanes, palette);

        BufferedImage img = new BufferedImage(bmhd.width, bmhd.height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, bmhd.width, bmhd.height, pixels, 0, bmhd.width);
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "PNG", out.toFile());
    }

    // ──────────────────────── Internals ────────────────────────
    private static BMHD readBMHD(ByteBuffer bb) {
        int w         = bb.getShort() & 0xFFFF;
        int h         = bb.getShort() & 0xFFFF;
        bb.getShort();  // xOrigin
        bb.getShort();  // yOrigin
        int nPlanes   = bb.get() & 0xFF;
        int masking   = bb.get() & 0xFF;
        int compType  = bb.get() & 0xFF;
        bb.get();       // pad1
        int transClr  = bb.getShort() & 0xFFFF;
        bb.get();       // xAspect
        bb.get();       // yAspect
        bb.getShort();  // pageWidth
        bb.getShort();  // pageHeight
        return new BMHD(w, h, nPlanes, masking, compType, transClr);
    }

    /**
     * Decode ByteRun1 (aka PackBits) : stream sur-compresse avec des runs.
     * <pre>
     *   while not end:
     *     N = read_signed_byte
     *     if N &gt;= 0           : copy N+1 bytes literal
     *     elif N &gt; -128 (i.e. -127..-1) : repeat next byte (-N)+1 times
     *     else (N == -128)    : no-op
     * </pre>
     */
    private static byte[] decodeByteRun1(byte[] compressed) {
        // Taille de sortie : on agrandit a la demande
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(compressed.length * 2);
        int i = 0;
        while (i < compressed.length) {
            int n = compressed[i++];  // signe preserve
            if (n >= 0) {
                int count = n + 1;
                if (i + count > compressed.length) count = compressed.length - i;
                out.write(compressed, i, count);
                i += count;
            } else if (n != -128) {
                int count = 1 - n;  // -N + 1
                if (i >= compressed.length) break;
                byte b = compressed[i++];
                for (int k = 0; k < count; k++) out.write(b);
            }
            // n == -128 : no-op
        }
        return out.toByteArray();
    }

    /**
     * Convertit une representation ILBM (plan-interleaved par ligne) vers plan-contigu.
     *
     * <p>Dans ILBM, chaque ligne est stockee :
     * <pre>[P0 row][P1 row]...[Pn row][P0 row+1][P1 row+1]...</pre>
     * avec chaque "P row" etant rowBytes bytes.</p>
     *
     * <p>AmigaBitplaneDecoder attend :
     * <pre>[plane 0 complet (rowBytes*height)][plane 1 complet]...</pre></p>
     *
     * <p>skipPlanes permet d'ignorer les derniers plans (mask bitplane).</p>
     */
    private static byte[] interleavedToContiguous(byte[] interleaved, int width, int height,
                                                  int keepPlanes, int totalPlanesInBody) {
        int rowBytes = ((width + 15) / 16) * 2;
        byte[] out = new byte[rowBytes * keepPlanes * height];
        for (int y = 0; y < height; y++) {
            for (int p = 0; p < keepPlanes; p++) {
                int srcOff = (y * totalPlanesInBody + p) * rowBytes;
                int dstOff = p * (rowBytes * height) + y * rowBytes;
                System.arraycopy(interleaved, srcOff, out, dstOff, rowBytes);
            }
        }
        return out;
    }

    /** Convertit "FORM" en entier 32-bit BE ('F'=0x46, 'O'=0x4F, 'R'=0x52, 'M'=0x4D). */
    private static int chunkId(String s) {
        if (s.length() != 4) throw new IllegalArgumentException("chunk id != 4 chars");
        return ((s.charAt(0) & 0xFF) << 24)
             | ((s.charAt(1) & 0xFF) << 16)
             | ((s.charAt(2) & 0xFF) << 8)
             |  (s.charAt(3) & 0xFF);
    }
}
