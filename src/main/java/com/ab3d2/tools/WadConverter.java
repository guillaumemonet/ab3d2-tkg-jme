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
    private final int[] globalPalette;  // 256 entrees ARGB (acces direct pour HQN)

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

        // Nettoyage prealable : supprimer les anciens PNG pour eviter de garder
        // les frames "fantomes" generees avec le bug NUM_FRAMES=64. Sinon, apres
        // le fix, glare_f32..f63 et bigbullet_f33 resteraient sur disque.
        // Fix session 113.
        if (Files.exists(dest)) {
            int[] cleaned = {0};
            try (var stream = Files.walk(dest)) {
                stream.sorted((a, b) -> b.compareTo(a)) // depth-first pour supprimer fichiers avant dossiers
                    .filter(p -> !p.equals(dest))
                    .forEach(p -> {
                        try { Files.delete(p); cleaned[0]++; } catch (IOException ignored) {}
                    });
            }
            System.out.printf("Nettoyage prealable: %d fichiers supprimes dans %s%n",
                cleaned[0], dest);
        }

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
        // Chercher 256pal.bin dans la source principale puis dans media/includes
        int[] globalPal = null;
        for (String name : List.of("256pal.bin", "256pal")) {
            Path pf = src.resolve(name);
            if (Files.exists(pf)) {
                globalPal = loadGlobalPalette(pf);
                break;
            }
        }
        if (globalPal == null) {
            System.out.println("WARN: palette globale introuvable, utilisation palette gris");
            globalPal = new int[256];
            for (int i = 0; i < 256; i++) globalPal[i] = 0xFF000000|(i<<16)|(i<<8)|i;
        }
        // Dump des 8 premieres entrees pour verification
        System.out.println("Palette globale (8 premieres entrees) :");
        for (int i=0; i<8; i++)
            System.out.printf("  [%3d] #%06X  RGB(%d,%d,%d)%n", i,
                globalPal[i] & 0x00FFFFFF,
                (globalPal[i]>>16)&0xFF, (globalPal[i]>>8)&0xFF, globalPal[i]&0xFF);

        WadConverter conv = new WadConverter(lnk, globalPal);

        // Chercher les WAD dans les dossiers resources (priorite media/includes)
        List<Path> wadSearchPaths = gatherWadSearchPaths(src, false);
        // Liste alternative : priorite media/hqn pour les sprites en format HQN
        // (1 byte/pixel). Cf. fix worm/robotright session 113.
        List<Path> hqnSearchPaths = gatherWadSearchPaths(src, true);

        // Convertir chaque sprite objet
        List<String> gfxNames = lnk.getObjGfxNames();
        int totalSaved = 0;
        for (int i = 0; i < gfxNames.size(); i++) {
            String name = gfxNames.get(i);
            if (name.isEmpty()) continue;

            String fileBase = LnkParser.extractFileName(name).toLowerCase(); // lowercase pour compatibilite classpath runtime
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
            // Dump des 4 premieres couleurs mappees pour diagnostic
            System.out.print("  Palette: ");
            for (int pi=0; pi<4; pi++)
                System.out.printf("[%d]#%06X ", pi, objPal[pi]&0x00FFFFFF);
            System.out.println();

            int nFrames = lnk.countFrames(i);
            System.out.printf("  %d frames, WAD=%d bytes, PTR=%d bytes%n",
                nFrames, wadData.length, ptrData.length);

            Path objDir = dest.resolve(fileBase);
            Files.createDirectories(objDir);

            int saved = conv.convertObject(i, wadData, ptrData, objPal, palData, objDir, fileBase);
            System.out.printf("  -> %d sprites PNG%n", saved);
            totalSaved += saved;
        }

        System.out.printf("%nTotal LNK: %d sprites convertis dans %s%n", totalSaved, dest);

        // ── Sprites aliens HQN non references dans GLFT_ObjGfxNames_l ─────────
        // Ces WADs existent dans media/hqn mais ne sont pas dans le LNK.
        // Ils sont utilises par ALIEN_WAD_BY_GFXTYPE[1] et [2] dans LevelSceneBuilder.
        //
        // IMPORTANT : on utilise hqnSearchPaths (priorite media/hqn) car certains
        // sprites comme worm existent AUSSI dans media/includes mais en format
        // 5-bit packe au lieu de HQN. Charger le mauvais fichier produit du
        // bruit visuel (cf. worm_f17 avant fix session 113).
        //
        // Fix session 117 : NOFF confirme visuellement par sprite.
        // - worm       : NOFF=20 (5 vues * 4 frames anim) -> totalCols 1800/20=90  OK
        // - robotright : NOFF=24 (4 vues * 6 frames anim) -> totalCols 3072/24=128 OK
        // HOFF est derive de la taille du fichier .HQN (pixel data brut).
        String[][] extraAlienSprites = {
            // {fileBase, label, NOFF}
            {"worm",       "TKG1:HQN/WORM",       "20"},
            {"robotright", "TKG1:HQN/ROBOTRIGHT", "24"},
        };
        for (String[] extra : extraAlienSprites) {
            String fileBase = extra[0]; // deja en minuscules
            int noff = Integer.parseInt(extra[2]);

            System.out.printf("%n[EXTRA] %s%n", extra[1]);

            byte[] wadData = findAndLoad(fileBase + ".wad",    hqnSearchPaths);
            byte[] ptrData = findAndLoad(fileBase + ".ptr",    hqnSearchPaths);
            byte[] palData = findAndLoad(fileBase + ".256pal", hqnSearchPaths);
            // Le .HQN n'est pas systematiquement present mais sa taille permet
            // de deriver HOFF (= HQN_size / total_cols) quand il existe.
            byte[] hqnData = findAndLoad(fileBase + ".HQN",    hqnSearchPaths);

            if (wadData == null || ptrData == null) {
                // Essayer majuscules
                String fb2 = fileBase.toUpperCase();
                if (wadData == null) wadData = findAndLoad(fb2 + ".wad", hqnSearchPaths);
                if (ptrData == null) ptrData = findAndLoad(fb2 + ".ptr", hqnSearchPaths);
                if (palData == null) palData = findAndLoad(fb2 + ".256pal", hqnSearchPaths);
                if (hqnData == null) hqnData = findAndLoad(fb2 + ".HQN", hqnSearchPaths);
            }
            if (wadData == null || ptrData == null) {
                System.out.printf("  SKIP (WAD/PTR introuvable dans media/hqn)%n"); continue;
            }
            System.out.printf("  WAD=%d bytes, PTR=%d bytes, 256pal=%d bytes, HQN=%s bytes%n",
                wadData.length, ptrData.length, palData != null ? palData.length : 0,
                hqnData != null ? String.valueOf(hqnData.length) : "absent");

            int[] objPal = parseObjPalette(palData, conv.globalPalette);

            Path objDir = dest.resolve(fileBase);
            Files.createDirectories(objDir);
            // Fix session 117 : NOFF fige par sprite (cf. extraAlienSprites)
            // HOFF derive de la taille du .HQN.
            int saved = conv.convertObjectStandalone(wadData, ptrData,
                objPal, palData, hqnData, noff, objDir, fileBase);
            System.out.printf("  -> %d sprites PNG%n", saved);
            totalSaved += saved;
        }
        System.out.printf("%nTotal global: %d sprites convertis%n", totalSaved);
    }

    /**
     * Conversion d'un sprite objet qui n'est PAS reference dans {@code GLFT_ObjGfxNames_l}
     * du LNK (donc pas de frame descs LX/LY/LW/LH disponibles).
     *
     * <p>Le format HQN ne stocke pas de header NOFF/WOFF/HOFF dans le PTR.
     * Les dimensions sont deduites comme suit :</p>
     * <ul>
     *   <li>{@code totalCols = ptrData.length / 4} (chaque entree PTR fait 4 bytes)</li>
     *   <li>{@code HOFF = hqnFileData.length / totalCols} (le .HQN est du pixel
     *       data brut 1 byte/pixel, en column-major : NOFF * WOFF colonnes
     *       de HOFF bytes chacune)</li>
     *   <li>{@code NOFF} doit etre fourni en parametre (impossible a deriver
     *       sans connaissance externe). Pour AB3D2 c'est typiquement
     *       <b>20 = 5 vues directionnelles * 4 frames d'animation</b>.</li>
     * </ul>
     *
     * <p>Validation par {@code guard} (cas connu via LNK) : guard.HQN=134400,
     * guard.PTR=6720 -> totalCols=1680, HOFF=80 ; et guard a 21 frames de 80px
     * = 1680 cols, ce qui est coherent.</p>
     *
     * <p>Confirme visuellement en session 117 : worm et robotright sont tous
     * deux a NOFF=20 (5 vues * 4 frames anim).</p>
     *
     * @param hqnFileData  contenu du fichier .HQN (utilise pour deriver HOFF)
     * @param noff         nombre de frames du sprite (5 * frames_par_vue)
     * @since session 117
     */
    public int convertObjectStandalone(byte[] wadData, byte[] ptrData,
                                        int[] objPal, byte[] rawPalData,
                                        byte[] hqnFileData, int noff,
                                        Path destDir, String baseName) throws Exception {
        // Total nombre de colonnes dans le PTR (4 bytes par entree)
        int totalCols = ptrData.length / 4;
        if (totalCols <= 0) {
            System.out.println("  ERR: PTR vide");
            return 0;
        }
        if (noff <= 0 || totalCols % noff != 0) {
            System.out.printf("  ERR: NOFF=%d ne divise pas totalCols=%d%n",
                noff, totalCols);
            return 0;
        }
        int woff = totalCols / noff;

        // Deriver HOFF depuis la taille du .HQN si disponible.
        int hoff;
        if (hqnFileData != null && hqnFileData.length > 0) {
            hoff = hqnFileData.length / totalCols;
            int residue = hqnFileData.length - (hoff * totalCols);
            if (residue != 0) {
                System.out.printf("  WARN: HQN=%d non multiple de totalCols=%d (residu=%d)%n",
                    hqnFileData.length, totalCols, residue);
            }
        } else {
            hoff = 100; // fallback heuristique
            System.out.printf("  HOFF non derivable (pas de .HQN), fallback=%d%n", hoff);
        }
        if (hoff <= 0 || hoff > 512) {
            System.out.printf("  ERR: HOFF=%d hors bornes [1..512]%n", hoff);
            return 0;
        }
        System.out.printf("  Frames: NOFF=%d WOFF=%d HOFF=%d (totalCols=%d)%n",
            noff, woff, hoff, totalCols);

        boolean isHqn = HQN_SPRITE_NAMES.contains(baseName.toLowerCase());
        if (isHqn) {
            System.out.println("  Mode HQN: 1 byte/pixel, index direct");
        }

        int saved = 0;
        for (int f = 0; f < noff; f++) {
            LnkParser.FrameDesc fd = new LnkParser.FrameDesc(f * woff, 0, woff, hoff);
            try {
                BufferedImage img = isHqn
                    ? renderFrameHqn(wadData, ptrData, globalPalette, fd, 0, rawPalData)
                    : renderFrame(wadData, ptrData, objPal, fd, 0);
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

    /** Noms de sprites qui utilisent le format HQN (1 byte/pixel, index direct). */
    private static final Set<String> HQN_SPRITE_NAMES = Set.of(
        "guard", "priest", "insect", "triclaw", "ashnarg",
        "robotright", "worm", "globe"
    );

    // ── Conversion d'un objet ─────────────────────────────────────────────────

    public int convertObject(int objIdx, byte[] wadData, byte[] ptrData,
                              int[] objPal, byte[] rawPalData, Path destDir, String baseName) throws Exception {
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
        // woff = largeur d'UNE vue de rotation.
        // Pour les sprites HQN (guard, insect...) le LW du LNK couvre
        // toutes les vues concatenees. On utilise woff pour limiter.
        // Pour les sprites standards, woff = LW ou 0 => pas de limitation.

        // Detecter si ce sprite utilise le format HQN (1 byte/pixel, index direct)
        boolean isHqn = HQN_SPRITE_NAMES.contains(baseName.toLowerCase());
        if (isHqn) {
            System.out.println("  Mode HQN: 1 byte/pixel, index direct");
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
                BufferedImage img = isHqn
                    ? renderFrameHqn(wadData, ptrData, globalPalette, fd, woff, rawPalData)
                    : renderFrame(wadData, ptrData, objPal, fd, woff);
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
     *
     * @param woff  largeur d'UNE vue de rotation (depuis header PTR).
     *              Si > 0, on limite fd.lw() à woff pour n'afficher
     *              qu'une seule vue (evite le doublement HQN).
     */
    private BufferedImage renderFrame(byte[] wad, byte[] ptr, int[] pal,
                                       LnkParser.FrameDesc fd, int woff) {
        // Limiter la largeur à une seule vue de rotation si woff est fourni
        int width  = (woff > 0 && woff < fd.lw()) ? woff : fd.lw();
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
     * Rendu d'une frame HQN (High Quality Normal) : format 1 byte/pixel.
     *
     * Identifie dans objdrawhires.s (draw_bitmap_lighted) :
     *   move.b  (a0,d1.w),d0      ; stride=1 OCTET par ligne
     *   beq.s   .skip_black        ; 0 = transparent
     *   move.b  (a4,d0.w),(a6)    ; draw_Pals_vl[pixel_value] = screen color
     *
     * draw_Pals_vl est construit via make_pals_loop (29 iterations):
     *   draw_Pals_vl[group*8 + slot] = BasePal[brightness*8 + slot]
     *   ou BasePal = hqnPal256 + light_type*256
     *   et brightness = 0 (sombre) a 31 (plein eclairage)
     *
     * Pour un rendu preview en plein eclairage :
     *   screen_color = hqnPal256[light_type*256 + 31*8 + (pixel_value % 8)]
     *   rgb = globalPalette[screen_color]
     *
     * @param hqnPal256  donnees brutes du fichier .256pal (1024 bytes),
     *                   null = fallback sur globalPalette direct
     * @param woff       largeur d'une vue (PTR header), 0 = fd.lw()
     */
    private BufferedImage renderFrameHqn(byte[] wad, byte[] ptr, int[] gPal,
                                          LnkParser.FrameDesc fd, int woff,
                                          byte[] hqnPal256) {
        int width  = (woff > 0 && woff < fd.lw()) ? woff : fd.lw();
        int height = fd.lh();
        if (width <= 0 || height <= 0 || width > 512 || height > 512) return null;

        // Pre-calculer la table draw_Pals_vl pour plein eclairage (brightness=0, type=0)
        // Dans le systeme HQN : brightness=0 = pleine illumination
        //                       brightness=31 = sombre/ombre
        int[] palVl = buildHqnPalsVl(hqnPal256, 0, 0); // type=0, brightness=0 = le plus clair

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int x = 0; x < width; x++) {
            int ptrIdx = fd.lx() + x;
            if (ptrIdx * 4 + 4 > ptr.length) break;

            // PTR entry pour HQN : LONG = offset byte dans le WAD (pas de third)
            long ptrEntry = readIntBE(ptr, ptrIdx * 4) & 0xFFFFFFFFL;
            int  ofs = (int)(ptrEntry & 0x00FFFFFF); // 24-bit offset

            // Stride = 1 BYTE par ligne
            int ap = ofs + fd.ly();
            for (int y = 0; y < height; y++) {
                if (ap >= wad.length) break;
                int colorIdx = wad[ap] & 0xFF;  // index direct 0-231
                ap++;                           // stride = 1 octet
                if (colorIdx == 0) { img.setRGB(x, y, 0); continue; } // transparent

                int argb;
                if (palVl != null && colorIdx < palVl.length) {
                    int screenIdx = palVl[colorIdx];
                    argb = (screenIdx == 0) ? 0 : (screenIdx < gPal.length ? gPal[screenIdx] : 0xFF808080);
                } else {
                    // Fallback : index direct (incorrect mais visible)
                    argb = (colorIdx < gPal.length) ? gPal[colorIdx] : 0xFF808080;
                }
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    /**
     * Construit la table draw_Pals_vl pour un type de lumiere et un niveau de luminosite.
     *
     * Format hqnPal256 (1024 bytes) :
     *   4 types * 256 bytes = 4 * (32 brightness * 8 bytes)
     *   BasePal[type][brightness][slot] = hqnPal256[type*256 + brightness*8 + slot]
     *
     * IMPORTANT : le systeme de luminosite HQN est INVERSE :
     *   brightness=0  -> pleine illumination (indices de palette les plus eleves)
     *   brightness=31 -> sombre/ombre       (indices proches de 0 = noir)
     *
     * Pour un preview en pleine lumiere : utiliser brightness=0.
     *
     * @return tableau de 232 entrees : index dans globalPalette pour chaque pixel value
     */
    private static int[] buildHqnPalsVl(byte[] hqnPal256, int lightType, int brightness) {
        int[] palVl = new int[232]; // 29 groupes * 8 bytes
        if (hqnPal256 == null || hqnPal256.length < 256) {
            // Pas de palette : remplir avec identite (sera faux mais visible)
            for (int i = 0; i < 232; i++) palVl[i] = i;
            return palVl;
        }
        int baseOfs = lightType * 256 + brightness * 8; // offset dans hqnPal256
        for (int group = 0; group < 29; group++) {
            for (int slot = 0; slot < 8; slot++) {
                int dstIdx = group * 8 + slot;
                // slot 0 de chaque groupe est zeroed dans draw_bitmap_lighted
                // SAUF pour preview : on garde la vraie valeur
                int srcOfs = baseOfs + slot;
                palVl[dstIdx] = (srcOfs < hqnPal256.length)
                    ? (hqnPal256[srcOfs] & 0xFF) : 0;
            }
        }
        return palVl;
    }
    /**
     * Rendu de toute la spritesheet si pas de frame descripteurs.
     * Rend NOFF frames de WOFF*HOFF pixels cote a cote.
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
     *
     * La valeur de chaque composante est dans le LOW byte du WORD big-endian :
     *   [0x00, 0x29, 0x00, 0x29, 0x00, 0x29] -> R=41, G=41, B=41
     *   readShortBE(raw, i*6) & 0xFF = 0x0029 & 0xFF = 41 CORRECT
     *
     * NE PAS utiliser raw[i*6] (HIGH byte = 0x00 = toujours zero = tout noir !)
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
            // Format PALC : 256 × [R:word, G:word, B:word] big-endian
            // Valeur dans le LOW byte : readShortBE(raw, off) & 0xFF
            for (int i = 0; i < 256; i++) {
                int r = readShortBE(raw, i * 6)     & 0xFF;  // LOW byte du word R
                int g = readShortBE(raw, i * 6 + 2) & 0xFF;  // LOW byte du word G
                int b = readShortBE(raw, i * 6 + 4) & 0xFF;  // LOW byte du word B
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (raw.length >= 256 * 4) {
            for (int i = 0; i < 256; i++) {
                int r = raw[i*4]&0xFF, g = raw[i*4+1]&0xFF, b = raw[i*4+2]&0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (raw.length >= 256 * 3) {
            for (int i = 0; i < 256; i++) {
                int r = raw[i*3]&0xFF, g = raw[i*3+1]&0xFF, b = raw[i*3+2]&0xFF;
                pal[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return pal;
    }

    /**
     * Parse la palette objet (.256pal) en couleurs ARGB.
     *
     * Format .256pal (depuis _FRAMEPICK dans leved303.amos) :
     *   B = Peek(Start(15) + A*2)  -> index dans palette globale
     *   A va de 0 à 31 (32 entrees, correspondant aux 32 couleurs 5-bit)
     *
     * IMPORTANT : AMOS Peek() lit un BYTE, et l'adresse est A*2 (stride 2).
     * Donc la valeur est dans le PREMIER byte de chaque WORD (poids fort).
     * Il faut lire palData[A*2] et NON pas readShortBE & 0xFF
     * (qui lirait le second byte = 0x00 = index 0 = noir pour tout !).
     *
     * Le fichier .256pal peut contenir plusieurs tables de luminosite :
     *   alien2.256pal = 2048 bytes = 32 tables × 64 bytes (32 niveaux de luminosite)
     *   GUARD.256pal  = 1024 bytes = 16 tables × 64 bytes
     * On utilise toujours la table 0 (indice 0, offset 0) qui est la plus lumineuse.
     *
     * Retourne un tableau de 32 couleurs ARGB.
     */
    public static int[] parseObjPalette(byte[] palData, int[] globalPal) {
        int[] pal = new int[32];
        for (int i = 0; i < 32; i++) pal[i] = 0xFF808080; // gris par défaut
        if (palData == null || palData.length < 32 * 2) {
            // Format compacte 32 bytes (un byte par entree, sans padding)
            if (palData != null && palData.length >= 32) {
                for (int i = 0; i < 32; i++) {
                    int globalIdx = palData[i] & 0xFF;
                    pal[i] = globalIdx < globalPal.length ? globalPal[globalIdx] : 0xFF808080;
                }
            }
            return pal;
        }
        // Format normal : 32 WORDs big-endian
        // Valeur reelle = PREMIER byte (poids fort) de chaque WORD = Peek(A*2)
        for (int i = 0; i < 32 && i * 2 + 1 <= palData.length; i++) {
            int globalIdx = palData[i * 2] & 0xFF;  // HIGH byte = Peek(A*2)
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

    /**
     * Construit la liste de dossiers ou chercher les fichiers WAD/PTR/256PAL.
     *
     * <p>Le parametre {@code preferHqn} controle l'ordre de priorite entre
     * {@code media/includes} et {@code media/hqn} :</p>
     *
     * <ul>
     *   <li>{@code preferHqn=false} (defaut) : {@code media/includes} d'abord.
     *       Convient pour les sprites standards (alien2, pickups, etc.) qui ne
     *       sont presents que dans {@code includes/}.</li>
     *   <li>{@code preferHqn=true} : {@code media/hqn} d'abord. Necessaire pour
     *       les sprites en format HQN (worm, robotright) qui peuvent exister en
     *       DOUBLE dans les deux dossiers, mais avec des formats differents :
     *       <ul>
     *         <li>{@code includes/worm.wad} = 5-bit packed (50 KB)</li>
     *         <li>{@code hqn/worm.wad} = 1 byte/pixel HQN (93 KB)</li>
     *       </ul>
     *       Charger le mauvais fichier produit du bruit visuel (le renderer
     *       HQN decode 1 byte/pixel sur des donnees 5-bit packed).</li>
     * </ul>
     */
    private static List<Path> gatherWadSearchPaths(Path srcRes, boolean preferHqn) {
        List<Path> paths = new ArrayList<>();
        // Dossiers dans la source principale
        for (String sub : List.of("objects", "graphics", "includes", "sounds/raw",
                                   "walls", ".", "assets")) {
            Path p = srcRes.resolve(sub);
            if (Files.exists(p)) paths.add(p);
        }
        // Chercher automatiquement ab3d2-tkg-original/media (projet frere)
        // Structure attendue :
        //   ab3d2-tkg-java/src/main/resources  <- srcRes
        //   ab3d2-tkg-original/media/includes  <- WADs objets standards
        //   ab3d2-tkg-original/media/hqn       <- WADs aliens HQN
        // L'ordre depend du flag preferHqn (cf. JavaDoc).
        List<Path> candidates = new ArrayList<>();
        Path base = srcRes;
        for (int up = 0; up < 5; up++) {
            if (base == null) break;
            if (preferHqn) {
                candidates.add(base.resolve("ab3d2-tkg-original/media/hqn"));
                candidates.add(base.resolve("ab3d2-tkg-original/media/includes"));
                candidates.add(base.resolve("../ab3d2-tkg-original/media/hqn"));
                candidates.add(base.resolve("../ab3d2-tkg-original/media/includes"));
            } else {
                candidates.add(base.resolve("ab3d2-tkg-original/media/includes"));
                candidates.add(base.resolve("ab3d2-tkg-original/media/hqn"));
                candidates.add(base.resolve("../ab3d2-tkg-original/media/includes"));
                candidates.add(base.resolve("../ab3d2-tkg-original/media/hqn"));
            }
            base = base.getParent();
        }
        for (Path p : candidates) {
            try {
                Path rp = p.toRealPath();
                if (Files.isDirectory(rp) && !paths.contains(rp)) {
                    paths.add(rp);
                    System.out.println("  WAD search path: " + rp);
                }
            } catch (Exception ignored) {}
        }
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
