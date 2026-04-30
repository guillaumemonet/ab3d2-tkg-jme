package com.ab3d2.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;

/**
 * Convertit newtexturemaps (format ASM-strict 68k) en atlas PNG.
 *
 * <h2>Layout confirme (session 64) via TextureLayoutExplorer</h2>
 *
 * <p>La boucle de sampling ASM dans objdrawhires.s/drawpol (l.2540-2559) :</p>
 * <pre>
 *    move.b (a0, d0.w*4), d3
 *  ou d0 = (U_integer &lt;&lt; 8) | V_integer
 * </pre>
 *
 * <p>Donc byte offset = <code>(U &lt;&lt; 10) | (V &lt;&lt; 2) = U * 1024 + V * 4</code></p>
 *
 * <p>Ceci donne le layout suivant :</p>
 * <ul>
 *   <li><b>U</b> (0..63) = row vertical dans la colonne</li>
 *   <li><b>V</b> (0..255) = indice de colonne horizontale</li>
 *   <li>stride row = 1024 bytes (une "ligne" = 256 cols * 4 bytes)</li>
 *   <li>stride col = 4 bytes (chaque colonne occupe 4 bytes consecutifs)</li>
 * </ul>
 *
 * <p>Une banque = <code>64 rows * 1024 bytes = 65536 bytes</code>
 * et contient <b>256 colonnes * 64 pixels</b>.<br>
 * 2 banques = 128 KB total, 512 colonnes utilisables.</p>
 *
 * <p>Les 3 bytes apres chaque pixel (offsets +1, +2, +3) sont en fait
 * <b>3 autres textures INTERLEAVED byte-par-byte</b> dans la meme banque.
 * Decouvert en session 65 : le Mantis utilise texOffsets avec lane=2,3
 * (ex: 0x8302, 0x8303) pour pointer sur les slots 2 et 3. Chaque banque
 * contient donc 4 textures de 256x64 superposees.</p>
 *
 * <p>Decomposition complete du texOffset :</p>
 * <pre>
 *   bank     = (texOffset &amp; 0x8000) != 0 ? 1 : 0
 *   relOfs   = texOffset &amp; 0x7FFF
 *   slot     = relOfs % 4              (0..3 -- lane dans le byte group)
 *   rowStart = relOfs / 1024           (0..63)
 *   colStart = (relOfs % 1024) / 4    (0..255)
 * </pre>
 *
 * <h2>newtexturemaps.pal (16KB = 64 * 256 shade table)</h2>
 * <pre>
 *   shade_table[shade][color_idx] = mapped_palette_idx
 *   Shade 0..31  : niveaux speculaires (blanc -&gt; couleur)
 *   Shade 32     : neutre (identity mapping)
 *   Shade 33..63 : assombrissement (normal -&gt; noir)
 * </pre>
 *
 * <h2>256pal.bin (1536 bytes = 256 * 6 bytes PALC)</h2>
 * <pre>
 *   Palette globale : [R:word, G:word, B:word] big-endian
 *   Valeur effective = LOW byte de chaque WORD
 * </pre>
 *
 * <h2>Sortie</h2>
 * <pre>
 *   texturemaps_bank0.png  (256x64, banque 0)
 *   texturemaps_bank1.png  (256x64, banque 1)
 *   texturemaps_atlas.png  (256x128, deux banques empilees)
 *   texturemaps_preview.png (zoom 4x, 128 premieres cols)
 * </pre>
 *
 * <h2>Utilisation par VectObjConverter</h2>
 * <p>Un polygone a un texOffset (WORD) et des U,V par vertex.
 * Le texOffset est un <b>byte offset arbitraire</b> dans la banque.
 * Il se decompose en :</p>
 * <pre>
 *   bank     = (texOffset &amp; 0x8000) != 0 ? 1 : 0
 *   relOfs   = texOffset &amp; 0x7FFF
 *   rowStart = relOfs / 1024           (0..63)
 *   colStart = (relOfs % 1024) / 4    (0..255)
 * </pre>
 *
 * <p>Les U,V du vertex servent de deltas :</p>
 * <pre>
 *   col_final = colStart + V_vertex   (V monte de 1 = +1 col)
 *   row_final = rowStart + U_vertex   (U monte de 1 = +1 row)
 * </pre>
 *
 * <p>Pour generer des UV JME (sur l'atlas 256x128) :</p>
 * <pre>
 *   u_atlas = col_final / 256.0
 *   v_atlas = (bank * 64 + row_final) / 128.0
 * </pre>
 */
public class TextureMapConverter {

    // Layout ASM-strict : 256 cols x 64 rows par banque, stride 4 bytes/col
    public static final int BANK_ROWS   = 64;    // rows par banque
    public static final int BANK_COLS   = 256;   // cols par banque
    public static final int ROW_STRIDE  = 1024;  // bytes entre rows
    public static final int COL_STRIDE  = 4;     // bytes entre cols
    public static final int BANK_SIZE   = BANK_ROWS * ROW_STRIDE; // 65536

    // Aliases pour compatibilite avec code existant
    public static final int COL_HEIGHT  = BANK_ROWS;   // 64
    public static final int NUM_COLS    = BANK_COLS;   // 256 (etait 1024 a tort)

    public static void main(String[] args) throws Exception {
        String vectDir = args.length > 0 ? args[0]
            : "src/main/resources/vectobj";
        String palDir  = args.length > 1 ? args[1]
            : "src/main/resources";
        String outDir  = args.length > 2 ? args[2]
            : "assets/Textures/vectobj";

        Path vect = Path.of(vectDir);
        Path pal  = Path.of(palDir);
        Path out  = Path.of(outDir);
        Files.createDirectories(out);

        System.out.println("=== TextureMapConverter (ASM-strict layout) ===");

        // --- Charger les donnees ---
        byte[] texData   = Files.readAllBytes(vect.resolve("newtexturemaps"));
        byte[] shadeData = Files.readAllBytes(vect.resolve("newtexturemaps.pal"));
        int[]  palette   = loadGlobalPalette(pal);

        int numBanks = texData.length / BANK_SIZE;
        System.out.printf("newtexturemaps: %d bytes = %d banque(s) de 64KB%n",
            texData.length, numBanks);
        System.out.printf("  Chaque banque = %d cols x %d rows, stride=%d bytes/col%n",
            BANK_COLS, BANK_ROWS, COL_STRIDE);
        System.out.printf("shade table:    %d bytes = %d niveaux x 256 entrees%n",
            shadeData.length, shadeData.length / 256);

        // --- Verification palette (8 premieres couleurs) ---
        System.out.println("Palette[0..7]:");
        for (int i = 0; i < 8; i++) {
            int c = palette[i];
            System.out.printf("  [%3d] R=%3d G=%3d B=%3d  #%06X%n",
                i, (c>>16)&0xFF, (c>>8)&0xFF, c&0xFF, c & 0xFFFFFF);
        }

        // --- Generer les atlas individuels (bank x slot) ---
        System.out.printf("%n%d banque(s) x 4 slots = %d textures a generer...%n",
            numBanks, numBanks * 4);
        for (int bank = 0; bank < numBanks; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                BufferedImage img = renderSlot(texData, shadeData, palette, bank, slot);
                Path outFile = out.resolve(String.format("texturemaps_b%d_s%d.png", bank, slot));
                ImageIO.write(img, "PNG", outFile.toFile());
                System.out.printf("  bank%d slot%d -> %s (%dx%d)%n",
                    bank, slot, outFile.getFileName(), BANK_COLS, BANK_ROWS);
            }
        }

        // --- Atlas combine vertical : 8 textures 256x64 empilees ---
        // Layout :
        //   y=0..63   : bank0 slot0
        //   y=64..127 : bank0 slot1
        //   ...
        //   y=448..511: bank1 slot3
        // Total atlas = 256 x (numBanks * 4 * 64) = 256 x 512 pour 2 banques
        // SESSION 81 : atlas RGBA avec index 0 -> alpha=0 (glare/holes + glarebox OK)
        {
            int atlasH = BANK_ROWS * 4 * numBanks;
            BufferedImage atlas = new BufferedImage(BANK_COLS, atlasH,
                BufferedImage.TYPE_INT_ARGB);
            var g = atlas.getGraphics();
            g.setColor(new java.awt.Color(0, 0, 0, 0));
            g.fillRect(0, 0, BANK_COLS, atlasH);
            for (int bank = 0; bank < numBanks; bank++) {
                for (int slot = 0; slot < 4; slot++) {
                    BufferedImage img = renderSlot(texData, shadeData, palette, bank, slot);
                    int y = (bank * 4 + slot) * BANK_ROWS;
                    g.drawImage(img, 0, y, null);
                }
            }
            g.dispose();
            Path atlasFile = out.resolve("texturemaps_atlas.png");
            ImageIO.write(atlas, "PNG", atlasFile.toFile());
            System.out.printf("  atlas -> %s (%dx%d RGBA, %d slots empiles)%n",
                atlasFile.getFileName(), BANK_COLS, atlasH, numBanks * 4);
        }

        // --- Preview zoome (128 premieres cols x zoom 4, slot 0 seulement) ---
        renderPreview(texData, shadeData, palette, numBanks,
            out.resolve("texturemaps_preview.png"));

        System.out.printf("%nDone. Fichiers dans: %s%n", out.toAbsolutePath());
    }

    // ── Rendu d'une banque ────────────────────────────────────────────────────

    /**
     * Rend une texture (256x64) pour un slot et une banque donnes.
     * Layout : pixel(row=U, col=V) = texData[bankBase + U*1024 + V*4 + slot]
     *
     * <p>SESSION 81 : RGBA avec alpha=0 pour index 0 (marche pour glarebox et
     * pour les modes glare/holes qui sont minoritaires mais existent).</p>
     *
     * @param slot  0..3 : quel slot lire dans chaque groupe de 4 bytes
     */
    public static BufferedImage renderSlot(byte[] texData, byte[] shadeData,
                                            int[] palette, int bankIdx, int slot) {
        BufferedImage img = new BufferedImage(BANK_COLS, BANK_ROWS,
            BufferedImage.TYPE_INT_ARGB);
        int bankBase = bankIdx * BANK_SIZE;
        for (int U = 0; U < BANK_ROWS; U++) {          // U = row
            for (int V = 0; V < BANK_COLS; V++) {      // V = col
                int byteOfs = bankBase + U * ROW_STRIDE + V * COL_STRIDE + slot;
                if (byteOfs >= texData.length) continue;
                int rawIdx = texData[byteOfs] & 0xFF;
                if (rawIdx == 0) {
                    img.setRGB(V, U, 0x00000000);  // alpha = 0
                } else {
                    int mappedIdx = shadeData[32 * 256 + rawIdx] & 0xFF;
                    img.setRGB(V, U, 0xFF000000 | (palette[mappedIdx] & 0xFFFFFF));
                }
            }
        }
        return img;
    }

    /**
     * Rend une banque complete au slot 0 (ancien comportement, conservation de l'API).
     * Equivalent a renderSlot(texData, shadeData, palette, bankIdx, 0).
     */
    public static BufferedImage renderBank(byte[] texData, byte[] shadeData,
                                            int[] palette, int bankIdx) {
        return renderSlot(texData, shadeData, palette, bankIdx, 0);
    }

    /**
     * Rend un preview zoome (4x) des 128 premieres cols de chaque banque.
     */
    private static void renderPreview(byte[] texData, byte[] shadeData, int[] palette,
                                       int numBanks, Path outFile) throws IOException {
        int preview = 128, zoom = 4;
        int nBanks = Math.min(numBanks, 2);
        BufferedImage prev = new BufferedImage(preview * zoom,
            BANK_ROWS * zoom * nBanks, BufferedImage.TYPE_INT_RGB);

        for (int bank = 0; bank < nBanks; bank++) {
            int bankBase = bank * BANK_SIZE;
            for (int U = 0; U < BANK_ROWS; U++) {
                for (int V = 0; V < preview; V++) {
                    int byteOfs = bankBase + U * ROW_STRIDE + V * COL_STRIDE;
                    if (byteOfs >= texData.length) continue;
                    int rawIdx    = texData[byteOfs] & 0xFF;
                    int mappedIdx = shadeData[32 * 256 + rawIdx] & 0xFF;
                    int rgb       = palette[mappedIdx] & 0xFFFFFF;
                    for (int dy = 0; dy < zoom; dy++)
                        for (int dx = 0; dx < zoom; dx++)
                            prev.setRGB(V * zoom + dx,
                                        bank * BANK_ROWS * zoom + U * zoom + dy, rgb);
                }
            }
        }
        ImageIO.write(prev, "PNG", outFile.toFile());
        System.out.println("  preview -> " + outFile.getFileName());
    }

    // ── API publique pour VectObjConverter ────────────────────────────────────

    /**
     * Calcule la couleur representative d'un polygone (fallback vertex color).
     * Conforme au layout ASM-strict.
     *
     * @param texData   contenu de newtexturemaps (128KB)
     * @param shadeData contenu de newtexturemaps.pal (16KB)
     * @param palette   256 couleurs ARGB (depuis 256pal.bin)
     * @param texOffset valeur du footer polygone (bit15 = banque secondaire)
     * @param brightness valeur de luminosite du footer (0=clair, ~100=sombre)
     * @return couleur ARGB
     */
    public static int sampleColor(byte[] texData, byte[] shadeData, int[] palette,
                                   int texOffset, int brightness) {
        if (texData == null || texData.length < BANK_SIZE) return 0xFF808080;

        int bank     = texOffsetToBank(texOffset);
        int bankBase = bank * BANK_SIZE;
        int relOfs   = texOffset & 0x7FFF;
        int slot     = relOfs & 3;                          // SESSION 65 : slot 0-3

        // Layout ASM-strict : relOfs = rowStart * 1024 + colStart * 4 + slot
        // Sample un peu plus loin que le debut pour avoir une couleur "typique"
        int rowStart = relOfs / ROW_STRIDE;
        int colStart = (relOfs % ROW_STRIDE) / COL_STRIDE;
        int sampleRow = Math.min(rowStart + 8, BANK_ROWS - 1);
        int sampleCol = Math.min(colStart + 2, BANK_COLS - 1);
        int sampleOfs = bankBase + sampleRow * ROW_STRIDE + sampleCol * COL_STRIDE + slot;

        if (sampleOfs >= texData.length) return 0xFF808080;

        int rawIdx = texData[sampleOfs] & 0xFF;

        // Shade : brightness 0 = clair, 100 = sombre
        // Formule ASM : shade = 32 + (31 - (brightness * 41) >> 7) clampe a 0..31
        int shadeCalc = (brightness * 41) >> 7;
        int shade     = 32 + Math.max(0, Math.min(31, 31 - shadeCalc));

        int mappedIdx = shadeData[shade * 256 + rawIdx] & 0xFF;

        return 0xFF000000 | (palette[mappedIdx] & 0x00FFFFFF);
    }

    /**
     * Retourne la colonne de depart (0..255) pour un texOffset donne.
     *
     * Layout ASM-strict : relOfs = rowStart * 1024 + colStart * 4
     * donc colStart = (relOfs % 1024) / 4.
     *
     * @param texOffset valeur brute du footer (bit15 = banque, bits 0-14 = offset)
     * @return colonne de depart dans la banque (0..255)
     */
    public static int texOffsetToColumn(int texOffset) {
        int relOfs = texOffset & 0x7FFF;
        return (relOfs % ROW_STRIDE) / COL_STRIDE;  // 0..255
    }

    /**
     * Retourne la row de depart (0..63) pour un texOffset donne.
     *
     * Layout ASM-strict : relOfs = rowStart * 1024 + colStart * 4
     * donc rowStart = relOfs / 1024.
     *
     * @param texOffset valeur brute du footer
     * @return row de depart dans la banque (0..63)
     */
    public static int texOffsetToRowStart(int texOffset) {
        int relOfs = texOffset & 0x7FFF;
        return relOfs / ROW_STRIDE;  // 0..63
    }

    /**
     * Retourne l'index de banque (0 ou 1) pour un texOffset donne.
     */
    public static int texOffsetToBank(int texOffset) {
        return (texOffset & 0x8000) != 0 ? 1 : 0;
    }

    /**
     * Retourne le slot (0..3) dans lequel ce polygone echantillonne.
     *
     * Chaque banque de 64KB contient 4 textures de 256x64 interleaved
     * byte-par-byte. Le slot = relOfs % 4 indique laquelle est utilisee.
     *
     * @param texOffset valeur brute du footer
     * @return slot (0..3)
     */
    public static int texOffsetToSlot(int texOffset) {
        return (texOffset & 0x7FFF) & 3;
    }

    /**
     * Retourne l'index global de "tile" dans l'atlas combine (0..7).
     *
     * Atlas layout (vertical empile) :
     *   tile 0 = bank0 slot0   (atlas y 0..63)
     *   tile 1 = bank0 slot1   (atlas y 64..127)
     *   tile 2 = bank0 slot2   (atlas y 128..191)
     *   tile 3 = bank0 slot3   (atlas y 192..255)
     *   tile 4 = bank1 slot0   (atlas y 256..319)
     *   tile 5 = bank1 slot1   (atlas y 320..383)
     *   tile 6 = bank1 slot2   (atlas y 384..447)
     *   tile 7 = bank1 slot3   (atlas y 448..511)
     */
    public static int texOffsetToAtlasTile(int texOffset) {
        return texOffsetToBank(texOffset) * 4 + texOffsetToSlot(texOffset);
    }

    /**
     * Charge la palette globale depuis 256pal.bin ou 256pal.
     * Format PALC : 256 × [R:word, G:word, B:word] big-endian.
     * La valeur est dans le LOW byte de chaque WORD.
     *
     * <p>Le fichier est cherche dans plusieurs emplacements, en ordre :</p>
     * <ol>
     *   <li>Le dossier passe en parametre (ex: src/main/resources)</li>
     *   <li>Le projet frere ab3d2-tkg-java/src/main/resources/</li>
     *   <li>Workspace NetBeansProjects si trouve</li>
     * </ol>
     */
    public static int[] loadGlobalPalette(Path resourceDir) throws IOException {
        int[] pal = new int[256];
        // Fallback gris
        for (int i = 0; i < 256; i++) pal[i] = 0xFF000000 | (i << 16) | (i << 8) | i;

        Path found = findPaletteFile(resourceDir);
        if (found == null) {
            System.out.println("WARN: 256pal/256pal.bin introuvable, palette gris utilisee");
            System.out.println("  Emplacements cherches :");
            for (Path p : buildPaletteSearchPaths(resourceDir))
                System.out.println("    - " + p);
            return pal;
        }

        byte[] raw = Files.readAllBytes(found);
        System.out.printf("Palette: %s (%d bytes)%n", found, raw.length);

        if (raw.length >= 256 * 6) {
            // Format PALC : R_word(2) + G_word(2) + B_word(2) par couleur
            // Valeur effective = LOW byte de chaque WORD (big-endian)
            for (int i = 0; i < 256; i++) {
                int r  = readShortBE(raw, i * 6)     & 0xFF;
                int g  = readShortBE(raw, i * 6 + 2) & 0xFF;
                int bv = readShortBE(raw, i * 6 + 4) & 0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | bv;
            }
            return pal;
        } else if (raw.length >= 256 * 4) {
            for (int i = 0; i < 256; i++) {
                pal[i] = 0xFF000000 | ((raw[i*4]&0xFF)<<16) | ((raw[i*4+1]&0xFF)<<8) | (raw[i*4+2]&0xFF);
            }
            return pal;
        } else if (raw.length >= 256 * 3) {
            for (int i = 0; i < 256; i++) {
                pal[i] = 0xFF000000 | ((raw[i*3]&0xFF)<<16) | ((raw[i*3+1]&0xFF)<<8) | (raw[i*3+2]&0xFF);
            }
            return pal;
        }
        System.out.println("WARN: taille fichier palette inattendue : " + raw.length);
        return pal;
    }

    /**
     * Construit la liste des emplacements ou chercher 256pal.bin / 256pal.
     */
    private static java.util.List<Path> buildPaletteSearchPaths(Path resourceDir) {
        java.util.List<Path> list = new java.util.ArrayList<>();

        // 1. Dossier passe directement (src/main/resources typiquement)
        list.add(resourceDir.resolve("256pal.bin"));
        list.add(resourceDir.resolve("256pal"));

        // 2. Chemin relatif depuis la racine du projet
        list.add(Path.of("src/main/resources/256pal.bin"));
        list.add(Path.of("src/main/resources/256pal"));

        // 3. Projet frere ab3d2-tkg-java
        list.add(Path.of("../ab3d2-tkg-java/src/main/resources/256pal.bin"));
        list.add(Path.of("../ab3d2-tkg-java/src/main/resources/256pal"));

        // 4. Remonte jusqu'a NetBeansProjects/
        Path workspace = resourceDir.toAbsolutePath();
        for (int up = 0; up < 6 && workspace != null; up++) {
            if (workspace.getFileName() != null
                && workspace.getFileName().toString().equals("NetBeansProjects")) {
                list.add(workspace.resolve("ab3d2-tkg-java/src/main/resources/256pal.bin"));
                list.add(workspace.resolve("ab3d2-tkg-java/src/main/resources/256pal"));
                break;
            }
            workspace = workspace.getParent();
        }
        return list;
    }

    private static Path findPaletteFile(Path resourceDir) {
        for (Path p : buildPaletteSearchPaths(resourceDir)) {
            if (Files.exists(p) && Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static int readShortBE(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }
}
