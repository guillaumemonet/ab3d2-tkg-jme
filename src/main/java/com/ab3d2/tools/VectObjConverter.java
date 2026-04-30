package com.ab3d2.tools;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/**
 * Convertit les fichiers binaires vectobj Amiga en .j3o JME.
 *
 * FORMAT BINAIRE STRICTEMENT CONFORME a objdrawhires.s / draw_PolygonModel.
 * Toutes les references de ligne se rapportent a objdrawhires.s du repo original.
 *
 * STRUCTURE GLOBALE DU FICHIER
 * ----------------------------
 *   +0    : WORD sortIt         <- move.w (a3)+, draw_SortIt_w (l.1840)
 *   +2    : WORD numPoints      <- move.w (a3)+, draw_NumPoints_w (l.1847)
 *   +4    : WORD numFrames      <- move.w (a3)+, d6 ; num_frames (l.1848)
 *   +6    : PointerTable[numFrames] (draw_PointerTablePtr_l, l.1849)
 *             4 bytes par entree :
 *             - WORD ptsOfs     (ajoute a draw_StartOfObjPtr_l, l.1868)
 *             - WORD angOfs     (ajoute a draw_StartOfObjPtr_l, l.1871)
 *   +6+nf*4 : LinesPtr (l.1851) = liste de parts terminee par sortKey<0 :
 *             - SWORD sortKey   (l.2117 ; <0 = fin via blt doneallparts)
 *             - WORD  bodyOfs   (l.2127)
 *
 * IMPORTANT : draw_StartOfObjPtr_l = fichier + 2 (apres le WORD sortIt).
 * Tous les offsets internes (ptsOfs, angOfs, bodyOfs) sont relatifs a
 * draw_StartOfObjPtr_l donc = fichier + 2 + offset_stocke.
 *
 * STRUCTURE DES POINTS @ ptsOfs (frame courante)
 * ----------------------------------------------
 *   +0                     : LONG onOff_l      <- move.l (a3)+, draw_ObjectOnOff_l (l.1875)
 *                            bit i = part i visible pour cette frame
 *   +4                     : BYTE[roundUp2(numPoints)] point angles <- draw_PointAngPtr_l (l.1876)
 *                            Taille = ((numPoints+1)/2)*2 bytes (l.1877-1882)
 *   +4+angleBytes          : numPoints * 6 bytes (l.1917 : addq #6, a3)
 *                            Chaque point : SWORD x, SWORD y, SWORD z
 *
 * STRUCTURE D'UN POLYGONE @ bodyOfs (taille = N*4 + 18 bytes)
 * -----------------------------------------------------------
 *   +0                     : SWORD numLines (N) <- (a1)+, d7 (l.2223) ; <0 = fin de part
 *   +2                     : WORD  flags        <- (a1)+, draw_PreHoles_b (l.2224)
 *   +4 .. +4+N*4-1         : N vertex * 4 bytes :
 *                            +0 : WORD ptIdx    <- move.w (a1), d0 (l.3091)
 *                            +2 : BYTE V (col)  <- move.b 2(a1), d2 (l.3164)
 *                                                 d2 devient xbitpos, incremente
 *                                                 avec X ecran -> COLONNE texture
 *                            +3 : BYTE U (row)  <- move.b 3(a1), d5 (l.3180)
 *                                                 d5 devient ybitpos, incremente
 *                                                 avec Y ecran -> LIGNE texture
 *                            Note : historiquement on nomme les bytes 'U' et 'V'
 *                            mais le byte +2 correspond en realite a la COLONNE
 *                            de la texture (axe horizontal ecran), et +3 a la
 *                            LIGNE (axe vertical ecran). Voir session 67.
 *   +4+N*4 .. +N*4+11      : 8 bytes phantom (lus par draw_PutInLines mais ignores).
 *                            Le jeu itere N+1 fois la boucle draw_PutInLines
 *                            (dbra d7=N), plus addq #4, a1 final -> avance de
 *                            (N+1)*4 + 4 = N*4+8 bytes apres le debut de vertex.
 *                            => a1 final = poly + 4 + N*4 + 8 = poly + N*4 + 12
 *   +N*4+12                : WORD texOffset    <- (a1)+, d0 (l.2372)
 *                            Offset byte dans Draw_TextureMapsPtr_l.
 *                            bit 15 = banque secondaire (l.2375-2376)
 *   +N*4+14                : BYTE brightness   <- (a1)+, d1 (l.2382)
 *                            0=eclaire, ~100=sombre (multiplie par 32,
 *                            *41/4096, negate, +31, clamp 0-31 en shade level)
 *   +N*4+15                : BYTE polyAngle    <- (a1)+, d2 (l.2407)
 *                            Index dans draw_PolyAngPtr_l pour eclairage directionnel
 *   +N*4+16                : WORD gouraud      <- 12(a1, d7*4) lu quand a1=poly+4 (l.2225)
 *                            = poly + 4 + 12 + N*4 = poly + N*4 + 16
 *
 * Taille totale du polygone = N*4 + 18 (verifie par lea 18(a1,d0.w*4),a1 l.2198)
 *
 * COORDONNEES : SCALE = 128. Les vectobj sont en unites internes Amiga,
 * divisees par 128 pour donner des unites JME raisonnables.
 * Axes : X identique, Y inverse, Z inverse (Amiga Y+ = bas ecran, Z+ = profondeur).
 */
public class VectObjConverter {

    // Les vectobj sont en unites internes plus petites que le niveau
    // Scale empirique : diviser par 128 pour obtenir des unites JME raisonnables
    private static final float SCALE = 128f;

    private final AssetManager am;

    // Donnees texture chargees au demarrage du converter
    private byte[] texData   = null;  // newtexturemaps (128KB)
    private byte[] shadeData = null;  // newtexturemaps.pal (16KB)
    private int[]  palette   = null;  // 256 couleurs RGB depuis 256pal.bin

    public VectObjConverter(AssetManager am) {
        this.am = am;
    }

    // ── Entry point Gradle ────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String srcDir  = args.length > 0 ? args[0] : "src/main/resources/vectobj";
        String destDir = args.length > 1 ? args[1] : "assets/Scenes/vectobj";
        String asDir   = args.length > 2 ? args[2] : "assets";

        Path src  = Path.of(srcDir);
        Path dest = Path.of(destDir);
        Files.createDirectories(dest);

        AssetManager am = new DesktopAssetManager(true);
        am.registerLocator(asDir, FileLocator.class);
        VectObjConverter conv = new VectObjConverter(am);

        // Charger les donnees texture si disponibles
        conv.loadTextureData(Path.of(srcDir), Path.of(asDir, "../src/main/resources"));

        System.out.printf("=== VectObjConverter ===%n  src:%s  dest:%s%n%n", src, dest);
        int ok = 0, skip = 0;

        try (var stream = Files.list(src)) {
            List<Path> files = stream
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> !p.getFileName().toString().equals("SBDepack"))
                .sorted()
                .toList();

            for (Path file : files) {
                String name = file.getFileName().toString().toLowerCase();
                System.out.printf("[%s]%n", name);
                try {
                    // PRIORITE : le fichier binaire vectobj est le modele compile du JEU (ADF disk)
                    // Le dossier .prj contient les sources editeur (Sculpt-Animate 4D)
                    // On utilise TOUJOURS le binaire si present, le .prj est ignore
                    byte[] data = Files.readAllBytes(file);
                    Node node = conv.convert(name, data);
                    if (node != null) {
                        Path out = dest.resolve(name + ".j3o");
                        BinaryExporter.getInstance().save(node, out.toFile());
                        System.out.printf("  -> OK  %s.j3o%n", name);
                        ok++;
                    } else {
                        System.out.printf("  -> SKIP (node null)%n");
                        skip++;
                    }
                } catch (Exception e) {
                    System.out.printf("  -> ERR : %s%n", e.getMessage());
                    skip++;
                }
            }
        }
        System.out.printf("%nOK:%d  Skip:%d%n", ok, skip);
    }

    // ── Chargement des donnees texture ─────────────────────────────────────────

    /**
     * Charge newtexturemaps, newtexturemaps.pal et 256pal.bin si presents.
     * Appele au demarrage du converter pour avoir les vraies couleurs.
     */
    public void loadTextureData(Path vectDir, Path resourceDir) {
        try {
            Path texPath   = vectDir.resolve("newtexturemaps");
            Path shadePath = vectDir.resolve("newtexturemaps.pal");
            if (java.nio.file.Files.exists(texPath) && java.nio.file.Files.exists(shadePath)) {
                texData   = java.nio.file.Files.readAllBytes(texPath);
                shadeData = java.nio.file.Files.readAllBytes(shadePath);
                System.out.printf("  Texture data: %d bytes tex, %d bytes shade%n",
                    texData.length, shadeData.length);
            } else {
                System.out.println("  WARN: newtexturemaps non trouve dans " + vectDir);
            }
            // Chercher 256pal.bin dans le repertoire resources
            palette = TextureMapConverter.loadGlobalPalette(
                java.nio.file.Files.exists(resourceDir) ? resourceDir : vectDir);
        } catch (Exception e) {
            System.out.println("  WARN texture data: " + e.getMessage());
        }
    }

    // ── Conversion depuis SBP source ─────────────────────────────────────────

    /**
     * Construit un Node JME depuis un SbpMesh parse par SbpObjParser.
     * Utilise les couleurs Amiga 12-bit des polygones comme vertex colors.
     */
    public Node buildFromSbp(SbpObjParser.SbpMesh mesh) {
        Node root = new Node("vectobj_" + mesh.name());
        Material mat = buildMaterial();

        // Accumuler tous les triangles avec couleur
        List<Triangle> tris = new ArrayList<>();
        for (SbpObjParser.Face face : mesh.faces()) {
            ColorRGBA col = SbpObjParser.amigaColorToJme(face.color(), face.brightness());
            for (int[] tri : face.triangulate()) {
                if (tri[0] >= mesh.vertices().size() ||
                    tri[1] >= mesh.vertices().size() ||
                    tri[2] >= mesh.vertices().size()) continue;
                // SBP n'a pas d'UV textures : fallback en UV (0,0).
                tris.add(new Triangle(
                    mesh.vertices().get(tri[0]).toJme(),
                    mesh.vertices().get(tri[1]).toJme(),
                    mesh.vertices().get(tri[2]).toJme(),
                    Vector2f.ZERO, Vector2f.ZERO, Vector2f.ZERO,
                    col
                ));
            }
        }

        System.out.printf("  SBP: %d verts, %d faces -> %d tris%n",
            mesh.vertices().size(), mesh.faces().size(), tris.size());

        if (tris.isEmpty()) return null;
        Geometry geo = buildGeometry(mesh.name(), tris);
        if (geo != null) {
            geo.setMaterial(mat);
            root.attachChild(geo);
        }
        return root.getChildren().isEmpty() ? null : root;
    }

    public Node convert(String name, byte[] data) {
        if (data.length < 6) return null;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int sortIt    = b.getShort(0) & 0xFFFF;
        int numPoints = b.getShort(2) & 0xFFFF;
        int numFrames = b.getShort(4) & 0xFFFF;
        int startOfs  = 2;

        System.out.printf("  sortIt=%d numPoints=%d numFrames=%d fileSize=%d%n",
            sortIt, numPoints, numFrames, data.length);

        if (numPoints > 4096 || numFrames > 64 || numFrames == 0)
            return null;

        int frameTableOfs = 6;
        if (frameTableOfs + numFrames * 4 > data.length) return null;

        int[] ptsOfs = new int[numFrames];
        int[] angOfs = new int[numFrames];
        for (int i = 0; i < numFrames; i++) {
            int ofs = frameTableOfs + i * 4;
            ptsOfs[i] = startOfs + (b.getShort(ofs) & 0xFFFF);
            angOfs[i] = startOfs + (b.getShort(ofs + 2) & 0xFFFF);
        }

        // Part list (LinesPtr)
        //
        // Format strict d'apres l'ASM putinunsorted (l.2116) et PutinParts (l.2133) :
        //   l.2117 : move.w (a1)+, d7       -> d7 = PREMIER WORD = bodyOfs du polygone
        //                                     (si negatif -> fin de liste via blt doneallparts)
        //   l.2127 : move.w (a1)+, d6       -> d6 = DEUXIEME WORD = offset de reference dans
        //                                     draw_3DPointsRotated_vl (pour calcul profondeur)
        //   l.2129 : move.w d7, (a0)        -> d7 est stocke dans le PartBuffer comme l'offset poly
        //   l.2183 : move.w (a0), d0        -> lu depuis le PartBuffer
        //   l.2185 : add.l draw_StartOfObjPtr_l, d0  -> absolu a file+2
        //
        // CORRECTION SESSION 59 : on avait inverse les deux WORDs. Le premier WORD est
        // le bodyOfs (termine par -1), le deuxieme est un reference point offset.
        int linesPtr = frameTableOfs + numFrames * 4;
        List<int[]> parts = new ArrayList<>();
        int pos = linesPtr;
        while (pos + 2 <= data.length) {
            short firstWord = b.getShort(pos);
            if (firstWord < 0) break;  // -1 = fin de la liste
            if (pos + 4 > data.length) break;
            int bodyOfs      = startOfs + (firstWord & 0xFFFF);          // premier WORD
            int refPointOfs  = b.getShort(pos + 2) & 0xFFFF;             // deuxieme WORD
            parts.add(new int[]{bodyOfs, refPointOfs});
            pos += 4;
        }
        System.out.printf("  LinesPtr=0x%x  %d parts%n", linesPtr, parts.size());

        if (parts.isEmpty() && numPoints == 0) {
            System.out.printf("  -> Objet vide (no points, no parts)%n");
            return null;
        }

        // Point data pour frame 0 + lecture du bitmask onOff
        Vector3f[] points = null;
        long onOffMask = 0xFFFFFFFFL; // par defaut : toutes les parts visibles
        if (numPoints > 0 && numFrames > 0) {
            points = readPoints(data, ptsOfs[0], numPoints);
            if (points == null)
                System.out.printf("  WARN: cannot read point data @ 0x%x%n", ptsOfs[0]);
            else {
                // Le LONG onOff est les 4 premiers bytes du point data
                // Chaque bit = 1 part : bit 0 = part 0, bit 1 = part 1, etc.
                // bit = 1 : part visible ; bit = 0 : part invisible pour cette frame
                if (ptsOfs[0] + 4 <= data.length) {
                    ByteBuffer bo = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    onOffMask = bo.getInt(ptsOfs[0]) & 0xFFFFFFFFL;
                    System.out.printf("  onOff=0x%08x%n", onOffMask);
                }
            }
        }
        if (points == null && numPoints > 0) return null;
        if (points == null) points = new Vector3f[0]; // objet 0 points

        // Build scene
        Node root = new Node("vectobj_" + name);
        Material mat = buildMaterial();

        // Decide quel masque utiliser :
        //
        // Si TOUTES les frames ont le meme onOffMask  -> objet statique multi-parts
        //   (ex : un fusil avec plusieurs composants toujours visibles ensemble)
        //   -> utiliser le masque (peut exclure certaines parts jamais visibles)
        //
        // Si les frames ont des masques DIFFERENTS    -> animation par frame
        //   (ex : passkey en rotation : 13 frames, chacune montre un wedge different)
        //   -> utiliser uniquement le masque de frame 0 (une seule pose)
        //
        // Pour determiner : on lit les onOff de toutes les frames et on voit si
        // elles sont toutes identiques. Dans les deux cas on utilise onOffMask (frame 0)
        // qui est le comportement correct du jeu pour la frame affichee au repos.
        long unionMask = onOffMask;
        boolean allFramesSame = true;
        for (int fi = 1; fi < numFrames; fi++) {
            if (ptsOfs[fi] + 4 > data.length) continue;
            long frMask = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(ptsOfs[fi]) & 0xFFFFFFFFL;
            unionMask |= frMask;
            if (frMask != onOffMask) allFramesSame = false;
        }
        System.out.printf("  frames %s  union=0x%08x  (using frame0 mask 0x%08x)%n",
            allFramesSame ? "SAME" : "DIFFERENT", unionMask, onOffMask);

        int totalTris = 0;

        // ── Lire les points de TOUTES les frames pour l'animation ────────────
        // Chaque frame a son propre ptsOfs qui pointe vers un jeu complet
        // de positions. On pre-calcule tout pour eviter de re-parser pendant
        // le rendu.
        Vector3f[][] framePoints = new Vector3f[numFrames][];
        long[] frameOnOff = new long[numFrames];
        for (int fi = 0; fi < numFrames; fi++) {
            framePoints[fi] = readPoints(data, ptsOfs[fi], numPoints);
            if (framePoints[fi] == null) framePoints[fi] = points;  // fallback frame 0
            if (ptsOfs[fi] + 4 <= data.length) {
                frameOnOff[fi] = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                                  .getInt(ptsOfs[fi]) & 0xFFFFFFFFL;
            } else {
                frameOnOff[fi] = onOffMask;
            }
        }

        boolean hasAnimation = numFrames > 1;

        for (int pi = 0; pi < parts.size(); pi++) {
            // Determiner les frames dans lesquelles cette part est visible
            boolean visibleAtFrame0 = (onOffMask & (1L << pi)) != 0;
            if (!visibleAtFrame0) {
                System.out.printf("  part[%d] OFF (frame0 bit %d=0, skip)%n", pi, pi);
                continue;
            }
            int bodyOfs = parts.get(pi)[0];  // SESSION 59 : premier WORD = bodyOfs
            List<TriangleIdx> tris = readPolygons(data, bodyOfs, numPoints, name + "_p" + pi);
            if (tris.isEmpty()) continue;

            // Construire la geometry avec les positions de frame 0
            Geometry geo = buildGeometryIdx(name + "_part" + pi, tris, points);
            if (geo != null) {
                geo.setMaterial(mat);
                root.attachChild(geo);
                totalTris += tris.size();

                // Si le modele a plusieurs frames, stocker les positions
                // par frame en UserData sur la geometry. Un VectObjFrameAnimControl
                // sera attache au runtime (apres chargement du j3o) via
                // VectObjFrameAnimControl.attachIfAnimated(). Cette approche
                // evite les problemes de serialisation d'un Control custom
                // dans les j3o (editeur SDK JME, etc.).
                if (hasAnimation) {
                    float[][] framePositions = buildFramePositions(tris, framePoints, pi, frameOnOff);
                    if (framePositions != null && framePositions.length > 1) {
                        VectObjFrameAnimControl.storeFrames(geo, framePositions, 10f);
                        System.out.printf("  part[%d] anim: %d frames stockees en UserData%n",
                            pi, framePositions.length);
                    }
                }
            }
        }

        System.out.printf("  %d triangles dans %d parts%n", totalTris, parts.size());
        return root.getChildren().isEmpty() ? null : root;
    }

    // ── Lecture des points ────────────────────────────────────────────────────

    private Vector3f[] readPoints(byte[] data, int ofs, int numPts) {
        if (ofs >= data.length) return null;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Points data structure @ ofs :
        //   +0 : LONG  objectOnOff_l         (bitmask des parties visibles par frame)
        //   +4 : BYTE[(numPts+1)/2*2]        : per-point angle bytes (arrondi au WORD)
        //   +4+angleBytes : SWORD[3]*numPts  : coordonnees (x, y, z) en unites Amiga
        //
        // AXES Amiga -> JME :
        //   X : identique (gauche-droite)
        //   Y : INVERTE  → jme_y = -amiga_y
        //       (Amiga : Y+ = bas ecran = vers le sol ; JME : Y+ = haut)
        //       Preuve : projection `add.w draw_PolygonCentreY_w, d4` sans inversion
        //       → d4 positif = vers le bas de l'ecran = Y+ = bas dans le monde Amiga
        //   Z : INVERTE  → jme_z = -amiga_z
        //       (Amiga : Z+ = profondeur ; JME : Z+ = vers la camera)
        int onOffSize  = 4;
        int angleBytes = ((numPts + 1) / 2) * 2;
        int ptsStart   = ofs + onOffSize + angleBytes;

        if (ptsStart + numPts * 6 > data.length) {
            System.out.printf("  WARN pts @ 0x%x: besoin %d bytes, dispo %d%n",
                ofs, numPts*6, data.length - ptsStart);
            return null;
        }

        Vector3f[] pts = new Vector3f[numPts];
        for (int i = 0; i < numPts; i++) {
            float ax = b.getShort(ptsStart + i * 6)     / SCALE;
            float ay = b.getShort(ptsStart + i * 6 + 2) / SCALE;
            float az = b.getShort(ptsStart + i * 6 + 4) / SCALE;
            // Conversion axes : Y et Z invertis
            pts[i] = new Vector3f(ax, -ay, -az);
        }
        return pts;
    }

    // ── Lecture des polygones ─────────────────────────────────────────────────

    /**
     * Triangle avec coordonnees par vertex. Chaque vertex a :
     * - une position 3D (issue du point)
     * - des UV texture (dans [0,1] sur l'atlas PNG)
     * - une couleur shadee (brightness applique a la luminosite)
     *
     * Les UV de triangle sont stockes par sommet pour preserver le texturing
     * perspective-correct lors du rendu par JME.
     */
    record Triangle(
        Vector3f pa, Vector3f pb, Vector3f pc,
        Vector2f ua, Vector2f ub, Vector2f uc,
        ColorRGBA color
    ) {}

    /**
     * Triangle avec INDICES de points (pour animation multi-frame).
     * Les positions sont reconstruites a chaque frame depuis un array
     * de Vector3f[] indexe par l'index de point.
     */
    record TriangleIdx(
        int ia, int ib, int ic,
        Vector2f ua, Vector2f ub, Vector2f uc,
        ColorRGBA color
    ) {}

    /**
     * Lit les polygones d'une part et retourne des TriangleIdx (indices de points).
     * Les positions sont resolues en dehors, permettant l'animation multi-frame.
     */
    private List<TriangleIdx> readPolygons(byte[] data, int bodyOfs, int numPoints, String label) {
        List<TriangleIdx> tris = new ArrayList<>();
        if (bodyOfs == 0 || bodyOfs >= data.length) return tris;

        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int pos = bodyOfs;
        int polyCount = 0;

        while (pos + 4 <= data.length) {
            short numLines = b.getShort(pos);
            if (numLines < 0) break;   // -1 = fin de la liste
            if (numLines > 64) break;  // sanity check

            // Taille totale polygone = N*4 + 18 (verifie par 'lea 18(a1,d0.w*4), a1' l.2198)
            int polySize = 18 + numLines * 4;
            if (pos + polySize > data.length) break;

            // --- Footer : lu dans cet ordre strict par doapoly ---
            // a1 arrive a poly+N*4+12 apres draw_PutInLines (voir doc JavaDoc de la classe)
            //   l.2372 : move.w (a1)+, d0     -> texOffset     (WORD @ poly+N*4+12)
            //   l.2382 : move.b (a1)+, d1     -> brightness    (BYTE @ poly+N*4+14)
            //   l.2407 : move.b (a1)+, d2     -> polyAngle     (BYTE @ poly+N*4+15)
            //   l.2225 : move.w 12(a1,d7*4)   -> gouraud       (WORD @ poly+N*4+16)
            int footerOfs    = pos + numLines * 4 + 12;
            int rawTexOffset = b.getShort(footerOfs) & 0xFFFF;  // inclut bit 15 pour banque
            // ASM l.2372-2376 :
            //   move.w (a1)+, d0
            //   bge.s  .notsec           ; si d0 positif, banque primaire
            //   and.w  #$7fff, d0        ; sinon masquer bit 15
            //   add.l  #65536, a0        ; et ajouter 64KB (offset banque secondaire)
            int texBank   = ((rawTexOffset & 0x8000) != 0) ? 1 : 0;
            int texOffset = rawTexOffset & 0x7FFF;
            // sampleColor attend le rawTexOffset original (avec bit 15) pour choisir la banque
            int brightness = data[footerOfs + 2] & 0xFF;
            int polyAngle  = data[footerOfs + 3] & 0xFF;
            int gouraud    = b.getShort(footerOfs + 4) & 0xFFFF;
            ColorRGBA col  = brightnessToColor(rawTexOffset, brightness);

            // -----------------------------------------------------------------
            // FORMAT VERTEX STRICT selon draw_PutInLines (l.3090-3356) :
            //
            //   +0 : WORD ptIdx   <- move.w (a1), d0                   (l.3091)
            //                        puis (a3, d0.w*4) index les points projetes
            //                        (4 bytes/pt) => ptIdx est un INDEX DE POINT sur 16 bits
            //   +2 : BYTE u       <- move.b 2(a1), d2                  (l.3164)
            //   +3 : BYTE v       <- move.b 3(a1), d5                  (l.3180)
            //
            // NOMBRE DE VERTEX = numLines + 1 (FIX SESSION 61)
            // ------------------------------------------------
            // La boucle draw_PutInLines tourne numLines+1 fois (dbra d7=numLines),
            // chacune dessinant un EDGE v[i] -> v[i+1]. Pour un polygone ferme a
            // V vertices il faut V edges, donc V = numLines+1.
            //
            // Exemples verifies sur passkey (9 points) :
            //   numLines=3 : quad de 4 vertices (ex: ptIdx 3,2,1,0 = rectangle z=-16)
            //   numLines=2 : triangle de 3 vertices
            //
            // Budget memoire pour le polygone (polySize = 18 + numLines*4) :
            //   +0..+3   : header (numLines + flags) = 4 bytes
            //   +4..     : (numLines+1) * 4 bytes de vertex = 4*(numLines+1)
            //   +.. : 1 * 4 bytes phantom (lu par PutInLines mais pas utilise)
            //   +N*4+12  : footer 6 bytes (texOffset WORD + brightness BYTE +
            //              polyAngle BYTE + gouraud WORD)
            //   Total : 4 + 4*(numLines+1) + 4 + 6 = 4*numLines + 18 = polySize ✓
            //
            // CORRECTION SESSION 58 : lecture vertex par WORD complet (pas byte+byte).
            // -----------------------------------------------------------------

            int numVerts = numLines + 1;  // SESSION 61 : FIX
            if (numVerts < 3) { pos += polySize; polyCount++; continue; }

            int[]     vtxIdx   = new int[numVerts];
            int[]     vtxU     = new int[numVerts];
            int[]     vtxV     = new int[numVerts];
            boolean[] vtxValid = new boolean[numVerts];
            int validCount = 0;
            for (int v = 0; v < numVerts; v++) {
                int vOfs = pos + 4 + v * 4;
                int ptIdx = b.getShort(vOfs) & 0xFFFF;  // WORD complet comme l'ASM
                vtxU[v]   = data[vOfs + 2] & 0xFF;
                vtxV[v]   = data[vOfs + 3] & 0xFF;
                if (ptIdx < numPoints) {
                    vtxIdx[v]   = ptIdx;
                    vtxValid[v] = true;
                    validCount++;
                } else {
                    vtxIdx[v]   = 0;
                    vtxValid[v] = false;
                }
            }

            // ---------------------------------------------------------------
            // GENERATION DES UV ATLAS (SESSION 78 : layout 32 tiles de 64x64)
            // ---------------------------------------------------------------
            // DECOUVERTE SESSION 78 : Le vrai layout de l'atlas est une grille
            // 4 colonnes x 8 lignes de tiles de 64x64 pixels (total 256x512).
            //
            // Confirme par VectObjTexOffsetInventory sur le crab :
            //   Les deltas UV (V = byte +3 = byte "colonne") vont de 0 a 63
            //   maximum. Aucun polygone ne depasse 64 en delta.
            //   => une tile fait 64 pixels de large, pas 256.
            //
            // Dans l'atlas PNG (genere par TextureMapConverter) :
            //   - Y (vertical) : 8 "slots" empiles = bank*4 + slot, 0..7
            //   - X (horizontal) : 256 cols, mais contient 4 tiles de 64 cote a cote
            //
            // Donc le texOffset du footer se decompose en :
            //   bank     = (texOfs & 0x8000) ? 1 : 0
            //   slot     = (texOfs & 0x7FFF) % 4                 -> 0..3
            //   rowBk    = (texOfs & 0x7FFF) / 1024              -> 0..63 (dans la tile)
            //   colBk    = ((texOfs & 0x7FFF) % 1024) / 4        -> 0..255 dans le slot
            //     -> tileCol = colBk / 64    (0..3, quelle tile dans le slot)
            //     -> colIn64 = colBk % 64    (colonne de depart DANS la tile 64x64)
            //
            // Et dans l'atlas (grille 4x8 de tiles) :
            //   tileRow (dans atlas, vertical)   = bank * 4 + slot    (0..7)
            //   tileCol (dans atlas, horizontal) = colBk / 64          (0..3)
            //   X pixel dans atlas = tileCol * 64 + colIn64 + vtxV
            //   Y pixel dans atlas = tileRow * 64 + rowIn64 + vtxU
            //
            // WRAP INTRA-TILE : l'ASM fait un wrap via mask $3fffff (22 bits).
            // Pour un polygone avec rowIn64 + vtxU > 63 ou colIn64 + vtxV > 63,
            // il faut wrapper modulo 64 a l'interieur de SA tile.
            // ---------------------------------------------------------------
            int bank2    = (rawTexOffset & 0x8000) != 0 ? 1 : 0;
            int relOfs2  = rawTexOffset & 0x7FFF;
            int slot2    = relOfs2 & 3;
            int rowBk2   = relOfs2 / 1024;                          // 0..63
            int colBk2   = (relOfs2 % 1024) / 4;                     // 0..255
            int tileCol  = colBk2 / 64;                              // 0..3
            int tileRow  = bank2 * 4 + slot2;                        // 0..7
            int colIn64  = colBk2 % 64;                              // 0..63
            int rowIn64  = rowBk2;                                   // 0..63

            // Atlas dimensions : 256x512 (4 cols x 8 rows de tiles 64x64)
            float atlasW2 = 256f;
            float atlasH2 = 512f;
            int TILE_SIZE = 64;

            Vector2f[] vtxUV = new Vector2f[numVerts];
            for (int v = 0; v < numVerts; v++) {
                // MAPPING UV CORRIGE (session 67) : SWAP U <-> V
                // byte +2 du vertex -> xbitpos -> COLONNE texture (axe X)
                // byte +3 du vertex -> ybitpos -> LIGNE   texture (axe Y)
                int colRaw = colIn64 + vtxU[v];     // byte +2 -> colonne dans la tile
                int rowRaw = rowIn64 + vtxV[v];     // byte +3 -> ligne dans la tile

                // WRAP INTRA-TILE modulo 64 (comme le mask $3fffff de l'ASM)
                // Si colRaw=70 (wraparound), on retombe a 6 dans LA MEME tile.
                int colFinal = ((colRaw % TILE_SIZE) + TILE_SIZE) % TILE_SIZE;
                int rowFinal = ((rowRaw % TILE_SIZE) + TILE_SIZE) % TILE_SIZE;

                // Position absolue dans l'atlas
                int pixelX = tileCol * TILE_SIZE + colFinal;
                int pixelY = tileRow * TILE_SIZE + rowFinal;

                float u      = pixelX / atlasW2;
                float vAtlas = pixelY / atlasH2;
                vtxUV[v] = new Vector2f(u, vAtlas);
            }

            // Triangulation en eventail depuis vtx[0] (comme le game draw en polyline
            // fermee vertex[0] -> vertex[1] -> ... -> vertex[N-1] -> vertex[0])
            // Skip les triangles avec un vertex invalide (preserve la geometrie
            // partielle plutot que de tout jeter comme le fait le game).
            if (vtxValid[0]) {
                for (int v = 1; v < numVerts - 1; v++) {
                    if (vtxValid[v] && vtxValid[v + 1]) {
                        tris.add(new TriangleIdx(
                            vtxIdx[0],
                            vtxIdx[v],
                            vtxIdx[v + 1],
                            vtxUV[0],
                            vtxUV[v],
                            vtxUV[v + 1],
                            col
                        ));
                    }
                }
            }

            pos += polySize;
            polyCount++;
        }

        if (polyCount > 0)
            System.out.printf("  %s: %d polys -> %d tris%n", label, polyCount, tris.size());
        return tris;
    }

    /**
     * Convertit texIdx + brightness en couleur JME.
     *
     * Sans la vraie texture map (Draw_TextureMapsPtr_l), on utilise :
     * - texIdx pour choisir une teinte parmi 16 couleurs fixes
     *   (simule les 16 textures de mur d'AB3D2)
     * - brightness (0-100) converti en luminosite :
     *   brightness=0 → index palette 31 (clair)
     *   brightness=100 → index palette ~0 (sombre)
     *   formule ASM : brightness*32/100, inverted → shade = 1-(b/100)
     */
    private static final ColorRGBA[] BASE_COLORS = {
        new ColorRGBA(0.60f, 0.55f, 0.45f, 1f),  // 0: beige mur
        new ColorRGBA(0.50f, 0.60f, 0.70f, 1f),  // 1: bleu-gris metal
        new ColorRGBA(0.55f, 0.50f, 0.45f, 1f),  // 2: brun
        new ColorRGBA(0.65f, 0.60f, 0.50f, 1f),  // 3: beige clair
        new ColorRGBA(0.40f, 0.50f, 0.40f, 1f),  // 4: vert militaire
        new ColorRGBA(0.70f, 0.65f, 0.55f, 1f),  // 5: creme
        new ColorRGBA(0.45f, 0.45f, 0.55f, 1f),  // 6: gris-bleu
        new ColorRGBA(0.60f, 0.50f, 0.40f, 1f),  // 7: orange-brun
        new ColorRGBA(0.50f, 0.55f, 0.50f, 1f),  // 8: gris-vert
        new ColorRGBA(0.75f, 0.70f, 0.60f, 1f),  // 9: sable
        new ColorRGBA(0.55f, 0.55f, 0.60f, 1f),  // 10: gris
        new ColorRGBA(0.65f, 0.55f, 0.45f, 1f),  // 11: brun-rouge
        new ColorRGBA(0.45f, 0.55f, 0.65f, 1f),  // 12: bleu acier
        new ColorRGBA(0.70f, 0.60f, 0.50f, 1f),  // 13: brun clair
        new ColorRGBA(0.50f, 0.45f, 0.55f, 1f),  // 14: mauve sombre
        new ColorRGBA(0.60f, 0.65f, 0.55f, 1f),  // 15: vert-gris
    };

    /**
     * Convertit texOffset + brightness en couleur JME de modulation.
     *
     * <p>SESSION 82 : FIX MAJEUR. Avant, on samplait UNE couleur dans la texture
     * et on l'utilisait comme vertex_color. Probleme : si le sample tombait sur
     * un pixel d'index palette 0 (noir), la vertex_color etait noire et donc
     * <code>texture * vertex_color = noir</code>, ecrasant toute la texture.</p>
     *
     * <p>Le bug etait particulierement visible sur le crab, ou 48% des pixels
     * de la tile 26 (corps) sont d'index 0. Le sample tombait tres souvent
     * sur un pixel 0 -&gt; tout le polygone devenait noir.</p>
     *
     * <p>Fix : la vertex_color est maintenant un <b>facteur de luminosite
     * uniforme</b> (gris), pas une couleur echantillonnee. La texture est
     * sauvegardee dans l'atlas et le shade est juste un multiplicateur.</p>
     *
     * <p>Formule ASM originale dans doapoly :</p>
     * <pre>
     *   asl.w  #5,d1           ; d1 = brightness * 32
     *   muls.w #41,d1           ; * 41
     *   asr.l  #12,d1           ; / 4096
     *   neg.w  d1
     *   add.w  #31,d1           ; d1 = 31 - (brightness*32*41/4096)
     *   clamp 0..31
     * </pre>
     * Puis `draw_TexturePalettePtr_l + 256*32 + (shade << 8)` : shade=0 c'est
     * "plein eclaire", shade=31 c'est "plein sombre".
     *
     * <p>En JME, on convertit ca en facteur gris 0..1 : shade=0 -&gt; 1.0 (clair),
     * shade=31 -&gt; 0.15 (sombre mais pas noir pour garder la texture visible).</p>
     */
    private ColorRGBA brightnessToColor(int texOffset, int brightness) {
        // Formule ASM-strict du shade level
        int shadeCalc = (brightness * 32 * 41) >> 12;  // == brightness * 1312 / 4096
        int shade     = Math.max(0, Math.min(31, 31 - shadeCalc));
        // shade=0 (clair) -> facteur 1.0
        // shade=31 (sombre) -> facteur 0.25
        float factor = 1.0f - (shade / 31.0f) * 0.75f;
        return new ColorRGBA(factor, factor, factor, 1.0f);
    }

    // ── Construction du Mesh JME ──────────────────────────────────────────────

    private Geometry buildGeometry(String name, List<Triangle> tris) {
        if (tris.isEmpty()) return null;
        int n = tris.size();
        float[] pos = new float[n * 9];
        float[] nor = new float[n * 9];
        float[] uv  = new float[n * 6];   // 2 floats UV par vertex (SESSION 62)
        float[] col = new float[n * 12];  // 4 floats RGBA par vertex (shade modulation)
        int[]   idx = new int[n * 3];

        for (int i = 0; i < n; i++) {
            Triangle t = tris.get(i);

            // Positions
            pos[i*9+0] = t.pa().x; pos[i*9+1] = t.pa().y; pos[i*9+2] = t.pa().z;
            pos[i*9+3] = t.pb().x; pos[i*9+4] = t.pb().y; pos[i*9+5] = t.pb().z;
            pos[i*9+6] = t.pc().x; pos[i*9+7] = t.pc().y; pos[i*9+8] = t.pc().z;

            // UV par vertex (SESSION 62 : texture atlas)
            uv[i*6+0] = t.ua().x; uv[i*6+1] = t.ua().y;
            uv[i*6+2] = t.ub().x; uv[i*6+3] = t.ub().y;
            uv[i*6+4] = t.uc().x; uv[i*6+5] = t.uc().y;

            // Normale (calculee a partir des 3 positions)
            Vector3f ab = t.pb().subtract(t.pa());
            Vector3f ac = t.pc().subtract(t.pa());
            Vector3f n3 = ab.cross(ac);
            if (n3.lengthSquared() > 0) n3.normalizeLocal();
            for (int j = 0; j < 3; j++) {
                nor[i*9+j*3+0] = n3.x;
                nor[i*9+j*3+1] = n3.y;
                nor[i*9+j*3+2] = n3.z;
            }

            // Couleur par face : meme couleur pour les 3 vertices du triangle.
            // Sert de modulateur a la texture (brightness du polygone).
            // En mode Unshaded + VertexColor + ColorMap : pixel = texture * vertex_color
            ColorRGBA c = t.color();
            for (int j = 0; j < 3; j++) {
                col[i*12+j*4+0] = c.r;
                col[i*12+j*4+1] = c.g;
                col[i*12+j*4+2] = c.b;
                col[i*12+j*4+3] = c.a;
            }

            // Indices
            idx[i*3+0] = i*3; idx[i*3+1] = i*3+1; idx[i*3+2] = i*3+2;
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Color,    4, BufferUtils.createFloatBuffer(col));
        mesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(idx));
        mesh.setStatic();
        mesh.updateBound();

        Geometry geo = new Geometry(name, mesh);
        geo.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        return geo;
    }

    /**
     * Construit une Geometry JME a partir de TriangleIdx (indices de points) +
     * positions de la frame de reference. Identique a buildGeometry() mais
     * utilise un lookup d'indice pour resoudre les positions.
     *
     * <p>Permet l'animation : les memes indices servent pour toutes les frames,
     * seules les positions changent.</p>
     */
    private Geometry buildGeometryIdx(String name, List<TriangleIdx> tris, Vector3f[] pts) {
        if (tris.isEmpty()) return null;
        int n = tris.size();
        float[] pos = new float[n * 9];
        float[] nor = new float[n * 9];
        float[] uv  = new float[n * 6];
        float[] col = new float[n * 12];
        int[]   idx = new int[n * 3];

        for (int i = 0; i < n; i++) {
            TriangleIdx t = tris.get(i);
            Vector3f pa = pts[t.ia()], pb = pts[t.ib()], pc = pts[t.ic()];

            pos[i*9+0] = pa.x; pos[i*9+1] = pa.y; pos[i*9+2] = pa.z;
            pos[i*9+3] = pb.x; pos[i*9+4] = pb.y; pos[i*9+5] = pb.z;
            pos[i*9+6] = pc.x; pos[i*9+7] = pc.y; pos[i*9+8] = pc.z;

            uv[i*6+0] = t.ua().x; uv[i*6+1] = t.ua().y;
            uv[i*6+2] = t.ub().x; uv[i*6+3] = t.ub().y;
            uv[i*6+4] = t.uc().x; uv[i*6+5] = t.uc().y;

            Vector3f ab = pb.subtract(pa);
            Vector3f ac = pc.subtract(pa);
            Vector3f n3 = ab.cross(ac);
            if (n3.lengthSquared() > 0) n3.normalizeLocal();
            for (int j = 0; j < 3; j++) {
                nor[i*9+j*3+0] = n3.x;
                nor[i*9+j*3+1] = n3.y;
                nor[i*9+j*3+2] = n3.z;
            }

            ColorRGBA c = t.color();
            for (int j = 0; j < 3; j++) {
                col[i*12+j*4+0] = c.r;
                col[i*12+j*4+1] = c.g;
                col[i*12+j*4+2] = c.b;
                col[i*12+j*4+3] = c.a;
            }

            idx[i*3+0] = i*3; idx[i*3+1] = i*3+1; idx[i*3+2] = i*3+2;
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Color,    4, BufferUtils.createFloatBuffer(col));
        mesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(idx));
        // Note : pas de setStatic() ici car le Position buffer sera updated
        // au runtime par VectObjFrameAnimControl (quand attache apres chargement
        // du j3o). Si pas d'animation, c'est une Geometry statique classique.
        mesh.updateBound();

        Geometry geo = new Geometry(name, mesh);
        geo.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        return geo;
    }

    /**
     * Construit les positions par frame pour l'animation.
     *
     * @param tris        triangles de la part (en indices de points)
     * @param framePoints positions de chaque frame (framePoints[frame][ptIdx])
     * @param partIdx     index de la part pour verifier si elle est on/off dans chaque frame
     * @param frameOnOff  masques onOff par frame
     * @return float[numFrames][numTris*9] des positions, ou null si tous identique
     */
    private float[][] buildFramePositions(List<TriangleIdx> tris, Vector3f[][] framePoints,
                                            int partIdx, long[] frameOnOff) {
        int n = tris.size();
        int numFrames = framePoints.length;
        float[][] out = new float[numFrames][n * 9];

        // Derniere frame ou la part etait visible (pour "freezer" la pose si OFF)
        int lastVisibleFrame = -1;
        for (int fi = 0; fi < numFrames; fi++) {
            boolean visible = (frameOnOff[fi] & (1L << partIdx)) != 0;
            Vector3f[] pts = visible ? framePoints[fi]
                                      : (lastVisibleFrame >= 0 ? framePoints[lastVisibleFrame]
                                                               : framePoints[0]);
            if (visible) lastVisibleFrame = fi;

            for (int i = 0; i < n; i++) {
                TriangleIdx t = tris.get(i);
                if (t.ia() < pts.length && t.ib() < pts.length && t.ic() < pts.length) {
                    Vector3f pa = pts[t.ia()], pb = pts[t.ib()], pc = pts[t.ic()];
                    out[fi][i*9+0] = pa.x; out[fi][i*9+1] = pa.y; out[fi][i*9+2] = pa.z;
                    out[fi][i*9+3] = pb.x; out[fi][i*9+4] = pb.y; out[fi][i*9+5] = pb.z;
                    out[fi][i*9+6] = pc.x; out[fi][i*9+7] = pc.y; out[fi][i*9+8] = pc.z;
                }
            }
        }

        // Detect si toutes les frames sont identiques (objet statique) -> pas d'anim utile
        boolean allSame = true;
        for (int fi = 1; fi < numFrames && allSame; fi++) {
            for (int i = 0; i < n*9 && allSame; i++) {
                if (Math.abs(out[0][i] - out[fi][i]) > 0.0001f) allSame = false;
            }
        }
        if (allSame) return null;  // pas d'animation reelle
        return out;
    }

    // ── Materiau par defaut ───────────────────────────────────────────────────

    /**
     * Cree le materiau JME partage pour tous les vectobj.
     *
     * Configuration :
     * - Unshaded.j3md : pas d'eclairage dynamique (conforme au rendu original Amiga)
     * - ColorMap      : texture atlas 256x512 (8 slots empiles, RGB depuis session 80)
     * - VertexColor   : modulation par la couleur par-polygone (shade brightness)
     * - NearestNoMipMaps/Nearest : rendering pixel-parfait retro (pas de filtering)
     * - FaceCullMode.Off : on affiche le recto ET le verso car le ASM ne fait pas
     *   de backface culling strict (et notre triangulation en fan peut inverser
     *   la normale pour des polygones concaves).
     *
     * <p>SESSION 80 : pas de transparence alpha. Le mode GLARE qui skippe
     * l'index 0 est en fait tres rarement utilise par les vectobj (seuls les
     * polys avec <code>draw_PreGouraud_b != 0</code> = byte HAUT du WORD
     * gouraud passent par <code>drawpolGL</code>). Les aliens comme crab
     * utilisent le mode GOURAUD normal (<code>drawpolg</code>) qui dessine
     * tous les pixels, y compris ceux d'index 0 (qui apparaitront en noir).</p>
     */
    private Material buildMaterial() {
        // Session 92 : Lighting.j3md au lieu de Unshaded, pour que les vectobj
        // (armes, boss polygonaux) reagissent a l'eclairage JME. Le VertexColor
        // baked par polygone devient un modulateur Diffuse, et le lighting
        // dynamique (Ambient + headlight + PointLights par zone) s'ajoute.
        Material mat = new Material(am, "Common/MatDefs/Light/Lighting.j3md");

        // Charger l'atlas de texture genere par TextureMapConverter.
        // Si absent (premier run avant convertTextureMaps), on se rabat sur
        // vertex-colors seuls (comportement sessions 58-61).
        try {
            TextureKey key = new TextureKey("Textures/vectobj/texturemaps_atlas.png", false);
            key.setGenerateMips(false);
            Texture atlas = am.loadTexture(key);
            atlas.setMagFilter(Texture.MagFilter.Nearest);
            atlas.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            atlas.setWrap(Texture.WrapMode.Repeat);
            mat.setTexture("DiffuseMap", atlas);
            System.out.println("  material: DiffuseMap=texturemaps_atlas.png (Lighting + vertex modulation)");
        } catch (Exception e) {
            System.out.println("  WARN: atlas non trouve (" + e.getMessage() + "), fallback vertex-color only");
        }

        // UseVertexColor : module la couleur Diffuse par la shade baked-in
        // des polygones (brightness Amiga converti session 82).
        mat.setBoolean("UseVertexColor", true);

        // Parametres lighting standard
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Ambient", new ColorRGBA(0.45f, 0.45f, 0.45f, 1f));
        mat.setColor("Diffuse", ColorRGBA.White);

        // SESSION 81 : support transparence index palette 0 (marche pour glarebox
        // et pour les modes glare/holes ASM minoritaires)
        mat.setFloat("AlphaDiscardThreshold", 0.5f);
        mat.getAdditionalRenderState().setBlendMode(
            com.jme3.material.RenderState.BlendMode.Alpha);

        mat.getAdditionalRenderState().setFaceCullMode(
            com.jme3.material.RenderState.FaceCullMode.Off);
        return mat;
    }
}
