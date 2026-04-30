package com.ab3d2.tools;

import com.ab3d2.assets.AmigaBitplaneDecoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Decodeur RAW Amiga chunky/planar pour les assets HUD AB3D2.
 *
 * <h2>Contexte</h2>
 * Dans AB3D2, les assets HUD (panel, bordures, visages...) sont stockes dans
 * <code>media/includes/</code> sous forme de <b>bytes bruts</b> (pas d'IFF ILBM).
 * Format : bitplanes planar 5 bpp (32 couleurs) la plupart du temps, parfois 8 bpp chunky.
 * La palette est dans un fichier separe du meme nom avec suffixe <code>pal</code>
 * (ex: <code>panelraw</code> + <code>panelpal</code>).
 *
 * <h2>Fichiers cibles</h2>
 * <ul>
 *   <li><b>panelraw</b> (18404 bytes) + <b>panelcols</b> (128 bytes, 32 couleurs)
 *       -&gt; le HUD bas du jeu, probablement 320x92 5bpp</li>
 *   <li><b>smallpanelraw</b> (5120 bytes) + <b>panelcols</b> -&gt; version petite vue</li>
 *   <li><b>newleftbord</b> + <b>newrightbord</b> + <b>borderpal</b> -&gt; bordures laterales</li>
 *   <li><b>facesraw</b> + <b>facespal</b> -&gt; visages du marine dans le HUD</li>
 *   <li><b>stenfontraw</b> -&gt; font des chiffres AMMO/ENERGY</li>
 *   <li><b>healthstrip</b>, <b>ammostrip</b> -&gt; barres</li>
 * </ul>
 *
 * <h2>Strategie</h2>
 * Comme on ne connait pas les dimensions exactes, cet outil tente plusieurs
 * configurations plausibles pour chaque fichier et genere un PNG par configuration
 * testee. L'utilisateur choisit visuellement la bonne configuration (suffixe dans
 * le nom de fichier). Une fois validee, on fixe les dimensions dans
 * {@link #KNOWN_LAYOUTS}.
 *
 * <h2>Format palette</h2>
 * <ul>
 *   <li><b>panelcols</b> (128 bytes) : 32 couleurs x 4 bytes. Deux encodages
 *       possibles : (a) 2 words Amiga 12-bit = 0RGB0RGB ; (b) 4 bytes XRGB.
 *       On essaiera les deux.</li>
 *   <li><b>panelpal</b> (2112 bytes) : probablement 256 entrees x 8 bytes
 *       (palette shade pour effet fondu).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./gradlew extractRaw                      # Extrait tous les candidats
 *   ./gradlew extractRaw -Pname=panelraw      # Un seul fichier
 * </pre>
 */
public class RawExtractor {

    // ──────────────────────── Layouts connus/candidats ────────────────────────

    /** Un layout candidat pour un fichier raw. */
    private record Layout(int width, int height, int bpp, String note) {
        public int expectedBytes() {
            if (bpp == 8) return width * height;        // chunky 8bpp
            return ((width + 15) / 16) * 2 * bpp * height;  // planar, word-aligned
        }
    }

    /** Mapping filename -&gt; liste de layouts a tester (le premier valide est genere). */
    private static final Map<String, Layout[]> KNOWN_LAYOUTS = new LinkedHashMap<>();
    static {
        // panelraw: 18404 bytes
        // Plusieurs hypotheses. 320x92 5bpp = 18400 + 4 padding -> candidat principal
        KNOWN_LAYOUTS.put("panelraw", new Layout[] {
            new Layout(320, 92, 5, "5bpp 320x92 (18400+4pad)"),
            new Layout(320, 115, 4, "4bpp 320x115 (non-rond)"),
            new Layout(320, 57, 8, "8bpp 320x57 chunky (non-rond)"),
            new Layout(288, 128, 4, "4bpp 288x128"),
            new Layout(320, 184, 5, "5bpp 320x184 half"),
        });
        KNOWN_LAYOUTS.put("smallpanelraw", new Layout[] {
            new Layout(320, 16, 8, "8bpp 320x16 chunky"),
            new Layout(128, 64, 5, "5bpp 128x64"),
            new Layout(320, 32, 4, "4bpp 320x32"),
            new Layout(192, 32, 5, "5bpp 192x32"),
        });
        KNOWN_LAYOUTS.put("newleftbord", new Layout[] {
            new Layout(16, 200, 5, "5bpp 16x200 (bordure verticale)"),
            new Layout(32, 200, 5, "5bpp 32x200"),
            new Layout(16, 256, 5, "5bpp 16x256"),
            new Layout(16, 160, 5, "5bpp 16x160"),
        });
        KNOWN_LAYOUTS.put("newrightbord", new Layout[] {
            new Layout(16, 200, 5, "5bpp 16x200 (bordure verticale)"),
            new Layout(32, 200, 5, "5bpp 32x200"),
            new Layout(16, 256, 5, "5bpp 16x256"),
            new Layout(16, 160, 5, "5bpp 16x160"),
        });
        KNOWN_LAYOUTS.put("leftbord", new Layout[] {
            new Layout(16, 200, 5, "5bpp 16x200"),
            new Layout(32, 200, 5, "5bpp 32x200"),
            new Layout(16, 160, 5, "5bpp 16x160"),
        });
        KNOWN_LAYOUTS.put("rightbord", new Layout[] {
            new Layout(16, 200, 5, "5bpp 16x200"),
            new Layout(32, 200, 5, "5bpp 32x200"),
            new Layout(16, 160, 5, "5bpp 16x160"),
        });
        KNOWN_LAYOUTS.put("facesraw", new Layout[] {
            new Layout(32, 32, 5, "5bpp 32x32 (une face)"),
            new Layout(32, 256, 5, "5bpp 32x256 (8 faces empilees)"),
            new Layout(64, 32, 5, "5bpp 64x32"),
            new Layout(256, 32, 5, "5bpp 256x32 (8 faces horizontales)"),
        });
        KNOWN_LAYOUTS.put("faces2raw", new Layout[] {
            new Layout(32, 32, 5, "5bpp 32x32"),
            new Layout(32, 256, 5, "5bpp 32x256"),
        });
        KNOWN_LAYOUTS.put("stenfontraw", new Layout[] {
            new Layout(16, 16, 4, "4bpp 16x16 (1 glyph)"),
            new Layout(16, 256, 4, "4bpp 16x256 (16 glyphs empiles)"),
            new Layout(256, 16, 4, "4bpp 256x16"),
        });
        KNOWN_LAYOUTS.put("healthstrip", new Layout[] {
            new Layout(128, 8, 5, "5bpp 128x8"),
            new Layout(64, 16, 5, "5bpp 64x16"),
            new Layout(256, 16, 4, "4bpp 256x16"),
        });
        KNOWN_LAYOUTS.put("ammostrip", new Layout[] {
            new Layout(128, 8, 5, "5bpp 128x8"),
            new Layout(64, 16, 5, "5bpp 64x16"),
            new Layout(256, 16, 4, "4bpp 256x16"),
        });
        KNOWN_LAYOUTS.put("bordercharsraw", new Layout[] {
            new Layout(16, 256, 4, "4bpp 16x256"),
            new Layout(256, 16, 4, "4bpp 256x16"),
            new Layout(128, 32, 4, "4bpp 128x32"),
        });
    }

    /** Mapping rawname -&gt; palette filename. Si absent, on utilise panelcols par defaut. */
    private static final Map<String, String> PALETTE_FOR = new HashMap<>();
    static {
        PALETTE_FOR.put("panelraw",      "panelcols");
        PALETTE_FOR.put("smallpanelraw", "panelcols");
        PALETTE_FOR.put("newleftbord",   "borderpal");
        PALETTE_FOR.put("newrightbord",  "borderpal");
        PALETTE_FOR.put("leftbord",      "borderpal");
        PALETTE_FOR.put("rightbord",     "borderpal");
        PALETTE_FOR.put("facesraw",      "facespal");
        PALETTE_FOR.put("faces2raw",     "faces2cols");
        PALETTE_FOR.put("stenfontraw",   "panelcols");
        PALETTE_FOR.put("healthstrip",   "healthpal");
        PALETTE_FOR.put("ammostrip",     "panelcols");
    }

    // ──────────────────────── Main ────────────────────────

    public static void main(String[] args) throws Exception {
        String srcDir = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-original/media/includes";
        String outDir = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/Interface";
        String onlyName = args.length > 2 ? args[2] : null;

        Path src = Path.of(srcDir);
        Path out = Path.of(outDir);
        Files.createDirectories(out);

        System.out.println("=== RawExtractor ===");
        System.out.println("Source : " + src);
        System.out.println("Output : " + out);

        if (!Files.isDirectory(src)) {
            System.err.println("ERREUR : dossier source introuvable");
            return;
        }

        int total = 0, candidatesGen = 0, skipped = 0;
        for (var entry : KNOWN_LAYOUTS.entrySet()) {
            String name = entry.getKey();
            if (onlyName != null && !onlyName.equalsIgnoreCase(name)) continue;

            Path raw = src.resolve(name);
            if (!Files.exists(raw)) {
                System.out.printf("  -- %s : fichier absent%n", name);
                skipped++;
                continue;
            }
            byte[] rawBytes = Files.readAllBytes(raw);
            System.out.printf("%n[%s] %d bytes%n", name, rawBytes.length);

            // Charger palette
            String palName = PALETTE_FOR.getOrDefault(name, "panelcols");
            int[] palette = loadPalette(src.resolve(palName), rawBytes.length);
            if (palette == null) {
                System.out.printf("  ! palette %s introuvable, palette debug utilisee%n", palName);
                palette = makeDebugPalette(256);
            }

            // Essayer chaque layout candidat
            for (Layout layout : entry.getValue()) {
                int expected = layout.expectedBytes();
                boolean fits = expected <= rawBytes.length;
                String status = fits ? "OK" : "SKIP";
                if (!fits) {
                    System.out.printf("  SKIP %-32s (besoin %d bytes)%n", layout.note, expected);
                    continue;
                }
                String outName = String.format("%s_%dx%d_%dbpp.png",
                    name, layout.width, layout.height, layout.bpp);
                Path outPath = out.resolve(outName);
                try {
                    generate(rawBytes, palette, layout, outPath);
                    System.out.printf("  OK   %-32s -> %s%n", layout.note, outName);
                    candidatesGen++;
                } catch (Exception e) {
                    System.out.printf("  FAIL %-32s : %s%n", layout.note, e.getMessage());
                }
            }
            total++;
        }
        System.out.printf("%nResume : %d fichiers testes, %d candidats PNG generes, %d skipped%n",
            total, candidatesGen, skipped);
        System.out.println("\n-> Ouvrir assets/Interface/ et identifier visuellement les bons candidats");
    }

    // ──────────────────────── Generation PNG ────────────────────────

    /** Genere un PNG depuis un raw + palette + layout. */
    private static void generate(byte[] data, int[] palette, Layout layout, Path out)
            throws IOException {
        int[] pixels;
        if (layout.bpp == 8) {
            // chunky : chaque byte = index palette direct
            pixels = new int[layout.width * layout.height];
            int n = Math.min(data.length, pixels.length);
            for (int i = 0; i < n; i++) {
                int idx = data[i] & 0xFF;
                pixels[i] = idx < palette.length ? palette[idx] : 0xFF000000;
            }
        } else {
            // planar : utiliser AmigaBitplaneDecoder
            pixels = AmigaBitplaneDecoder.decode(data, layout.width, layout.height,
                                                 layout.bpp, palette);
        }
        BufferedImage img = new BufferedImage(layout.width, layout.height,
                                              BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, layout.width, layout.height, pixels, 0, layout.width);
        Files.createDirectories(out.getParent());
        ImageIO.write(img, "PNG", out.toFile());
    }

    // ──────────────────────── Palette parsing ────────────────────────

    /**
     * Charge une palette depuis un fichier. Plusieurs formats tentes :
     * <ul>
     *   <li>32 couleurs, 4 bytes/entry : word Amiga 0RGB (12-bit) + 2 pad</li>
     *   <li>32 couleurs, 4 bytes/entry : RGBA 8-8-8-8</li>
     *   <li>32 couleurs, 6 bytes/entry : RGB 8-8-8 + 3 pad</li>
     *   <li>256 couleurs, 8 bytes/entry (palette avec shades)</li>
     * </ul>
     *
     * @return palette ARGB ou null si format inconnu
     */
    private static int[] loadPalette(Path palPath, int rawBytesLen) {
        if (!Files.exists(palPath)) return null;
        byte[] pal;
        try { pal = Files.readAllBytes(palPath); }
        catch (IOException e) { return null; }

        // 128 bytes = 32 couleurs, 4 bytes/entry (panelcols, borderpal)
        if (pal.length == 128) {
            return parseAmiga12BitPalette(pal, 32);
        }
        // 2112 bytes = 64 header + 256*8 (panelpal) - format complexe, utiliser les 256 couleurs
        if (pal.length >= 2048) {
            // Probablement header puis 256 entrees. On essaie en partant de 64 puis 0.
            int[] p = parseMultiShadePalette(pal, 64);
            if (p != null) return p;
            p = parseMultiShadePalette(pal, 0);
            if (p != null) return p;
        }
        // 64 bytes = 32 couleurs * 2 bytes (Amiga 12-bit word raw)
        if (pal.length == 64) {
            return parseAmigaColorWords(pal, 32);
        }
        // Cas inconnus : essayer en tant que 12-bit word
        if (pal.length % 2 == 0 && pal.length >= 32) {
            return parseAmigaColorWords(pal, pal.length / 2);
        }
        return null;
    }

    /**
     * Parse une palette Amiga au format copper list (AGA) stockee comme entries de 4 bytes :
     * <pre>
     *   Byte 0-1 : adresse du registre COLORnn (ex: $0180 = COLOR00, $0182 = COLOR01, ...)
     *   Byte 2-3 : valeur couleur 12-bit au format $0RGB (4 bits par composante)
     * </pre>
     * On utilise le <b>second word (bytes 2-3)</b> comme valeur de couleur.
     * (Le premier word est juste l'adresse du registre hardware Amiga.)
     */
    private static int[] parseAmiga12BitPalette(byte[] data, int numColors) {
        int[] pal = new int[numColors];
        for (int i = 0; i < numColors; i++) {
            int offset = i * 4;
            if (offset + 4 > data.length) break;
            // Second word (bytes 2-3) = vraie couleur $0RGB
            int word = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            pal[i] = amiga12BitToARGB(word);
        }
        return pal;
    }

    /** Parse une liste de words 0RGB Amiga (2 bytes/entry). */
    private static int[] parseAmigaColorWords(byte[] data, int numColors) {
        int[] pal = new int[numColors];
        for (int i = 0; i < numColors; i++) {
            int offset = i * 2;
            if (offset + 2 > data.length) break;
            int word = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            pal[i] = amiga12BitToARGB(word);
        }
        return pal;
    }

    /**
     * Format multi-shade : 256 entrees de 8 bytes chacune.
     * Premier long (4 bytes) = couleur de base RGBA ou 0RGB etendu, reste = shades.
     */
    private static int[] parseMultiShadePalette(byte[] data, int startOffset) {
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) {
            int off = startOffset + i * 8;
            if (off + 4 > data.length) { pal[i] = 0xFF000000; continue; }
            // Essai : bytes 0-2 = RGB direct (8-bit par composante)
            int r = data[off + 1] & 0xFF;
            int g = data[off + 2] & 0xFF;
            int b = data[off + 3] & 0xFF;
            pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return pal;
    }

    /** Convertit un word Amiga 0RGB 12-bit en ARGB 32-bit. */
    private static int amiga12BitToARGB(int word) {
        int r = (word >> 8) & 0xF;
        int g = (word >> 4) & 0xF;
        int b = word & 0xF;
        // Amiga 4-bit -> 8-bit : duplication (nibble -> byte)
        int r8 = (r << 4) | r;
        int g8 = (g << 4) | g;
        int b8 = (b << 4) | b;
        return 0xFF000000 | (r8 << 16) | (g8 << 8) | b8;
    }

    /** Palette debug : niveaux de gris. */
    private static int[] makeDebugPalette(int size) {
        int[] pal = new int[size];
        for (int i = 0; i < size; i++) {
            int v = (i * 255) / Math.max(1, size - 1);
            pal[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return pal;
    }
}
