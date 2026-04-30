package com.ab3d2.tools;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parser pour le format SBP Object de Sculpt-Animate 4D (Amiga).
 *
 * FORMAT BINAIRE "SBP Object\0" :
 *
 *   "SBP Object\0"  (11 bytes)
 *   BYTE numVertices
 *   BYTE padding (0)
 *   numVertices x (SWORD x + SWORD y + SWORD z)  big-endian, fixed-point /256
 *
 *   6 bytes  : info bounding/color
 *   BYTE numPolys
 *   BYTE padding (0)
 *
 *   Polygones (taille variable = nv*4 + 18 bytes chacun) :
 *     BYTE  nv         : nombre de sommets
 *     BYTE  flags, BYTE flag2, BYTE flag3
 *     nv x vertex_entry (4 bytes chacun) :
 *       BYTE  idx_1based  : index sommet 1-base (soustraire 1)
 *       BYTE  u           : coord texture U
 *       BYTE  v           : coord texture V
 *       BYTE  normal      : index normale par sommet
 *     14 bytes footer :
 *       WORD  color       : couleur Amiga 12-bit (0x0RGB)
 *       BYTE  brightness  : 0=sombre, 100=eclaire
 *       ...  : UV scan, gouraud flags
 *
 * FORMAT MANIFEST "SBPProjV01\0" :
 *   "SBPProjV01\0"  (11 bytes)
 *   BYTE numObjects
 *   numObjects x (BYTE nameLen + nameLen bytes path)
 *   Transform data per object
 *
 * Formules validees empiriquement sur les fichiers grenadelauncher.prj :
 *   - barrel.obj : 15 verts, 9 polys
 *   - handle.obj : 9 verts, 4 polys
 *   - clip.obj   : 15 verts, 7 polys
 *   - lock1.obj  : 13 verts, 4 polys
 */
public class SbpObjParser {

    public record Vertex(float x, float y, float z) {
        /** Convertit en coordonnees JME (flip Z : Amiga Z+ = profondeur, JME Z+ = vers spectateur) */
        public Vector3f toJme() { return new Vector3f(x, y, -z); }
    }

    public record Face(int[] indices, int color, int brightness) {
        /**
         * Triangulation en eventail depuis le premier sommet.
         * Retourne une liste de triplets [i0, i1, i2].
         */
        public List<int[]> triangulate() {
            List<int[]> tris = new ArrayList<>();
            for (int v = 1; v < indices.length - 1; v++)
                tris.add(new int[]{indices[0], indices[v], indices[v + 1]});
            return tris;
        }
    }

    public record SbpMesh(String name, List<Vertex> vertices, List<Face> faces) {
        public boolean isEmpty() { return vertices.isEmpty() || faces.isEmpty(); }
    }

    // ── Parser SBP Object ─────────────────────────────────────────────────────

    public static SbpMesh parseSbpObject(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".obj")) name = name.substring(0, name.length() - 4);
        return parseSbpObject(name, Files.readAllBytes(file));
    }

    public static SbpMesh parseSbpObject(String name, byte[] data) {
        if (data == null || data.length < 16) return empty(name);
        if (!startsWith(data, "SBP Object"))     return empty(name);

        int pos = 11; // apres "SBP Object\0"

        // BYTE numVertices + BYTE padding
        int numPts = data[pos] & 0xFF;
        pos += 2;

        if (numPts == 0 || pos + numPts * 6 > data.length)
            return empty(name);

        // Vertices : SWORD[3] big-endian, fixed-point /256
        List<Vertex> verts = new ArrayList<>(numPts);
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numPts; i++) {
            float x = b.getShort(pos + i * 6)     / 256.0f;
            float y = b.getShort(pos + i * 6 + 2) / 256.0f;
            float z = b.getShort(pos + i * 6 + 4) / 256.0f;
            verts.add(new Vertex(x, y, z));
        }
        pos += numPts * 6;  // poly_ofs

        // Header 8 bytes : 6 info + BYTE numPolys + BYTE padding
        if (pos + 8 > data.length) return new SbpMesh(name, verts, List.of());
        int numPolys = data[pos + 6] & 0xFF;
        pos += 8;

        // Polygones : taille variable = nv*4 + 18 bytes
        List<Face> faces = new ArrayList<>(numPolys);
        for (int pi = 0; pi < numPolys && pos < data.length; pi++) {
            int nv       = data[pos] & 0xFF;
            int polySize = nv * 4 + 18;

            if (nv < 3 || nv > 32) break;
            if (pos + polySize > data.length) break;

            // Footer couleur a pos+4+nv*4
            int footerOfs  = pos + 4 + nv * 4;
            int faceColor  = (footerOfs + 2 <= data.length)
                ? ((data[footerOfs] & 0xFF) << 8) | (data[footerOfs + 1] & 0xFF)
                : 0x0555;
            int brightness = (footerOfs + 3 <= data.length)
                ? (data[footerOfs + 2] & 0xFF) : 50;

            // Vertex entries : BYTE 1-based_idx + BYTE u + BYTE v + BYTE normal
            int[] idxs  = new int[nv];
            boolean valid = true;
            for (int v = 0; v < nv; v++) {
                int raw = data[pos + 4 + v * 4] & 0xFF;  // 1-based
                if (raw == 0 || raw > numPts) { valid = false; break; }
                idxs[v] = raw - 1;  // 0-based
            }

            if (valid) faces.add(new Face(idxs, faceColor, brightness));
            pos += polySize;
        }

        return new SbpMesh(name, verts, faces);
    }

    // ── Parser SBPProjV01 manifest ────────────────────────────────────────────

    /**
     * Lit le manifest SBPProjV01 et retourne la liste des noms de fichiers .obj.
     * Format : "SBPProjV01\0" + BYTE numObjects + (BYTE nameLen + name)*n
     */
    public static List<String> parseProject(byte[] data) {
        List<String> parts = new ArrayList<>();
        if (data == null || data.length < 12) return parts;
        if (!startsWith(data, "SBPProjV01"))    return parts;

        int pos = 11;
        if (pos >= data.length) return parts;

        int numObjects = data[pos++] & 0xFF;

        for (int i = 0; i < numObjects && pos < data.length; i++) {
            int nameLen = data[pos] & 0xFF;
            if (nameLen == 0 || pos + 1 + nameLen > data.length) break;
            String fullPath = new String(data, pos + 1, nameLen,
                java.nio.charset.StandardCharsets.ISO_8859_1);
            pos += 1 + nameLen;
            // Extraire le nom de fichier (derniere partie du chemin Amiga "Work:dir/file")
            String filename = fullPath.contains("/")
                ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath;
            if (!filename.isEmpty() && filename.toLowerCase().endsWith(".obj"))
                parts.add(filename);
        }
        return parts;
    }

    /**
     * Charge et assemble toutes les parties d'un dossier .prj en un seul SbpMesh.
     * Utilise le fichier "project" pour l'ordre si present, sinon tous les .obj alphabetiquement.
     */
    public static SbpMesh loadProject(String objectName, Path prjDir) throws IOException {
        if (!Files.isDirectory(prjDir)) return empty(objectName);

        // Ordre depuis le manifest
        List<String> partNames;
        Path manifest = prjDir.resolve("project");
        if (Files.exists(manifest)) {
            partNames = parseProject(Files.readAllBytes(manifest));
        } else {
            try (var s = Files.list(prjDir)) {
                partNames = s.filter(p -> p.toString().toLowerCase().endsWith(".obj"))
                             .map(p -> p.getFileName().toString())
                             .sorted().toList();
            }
        }

        List<Vertex> allVerts = new ArrayList<>();
        List<Face>   allFaces = new ArrayList<>();

        for (String partName : partNames) {
            Path partFile = findFile(prjDir, partName);
            if (partFile == null) {
                System.out.printf("    WARN part not found: %s%n", partName);
                continue;
            }
            SbpMesh part = parseSbpObject(partFile);
            if (part.isEmpty()) { System.out.printf("    WARN empty part: %s%n", partName); continue; }

            int vertOffset = allVerts.size();
            allVerts.addAll(part.vertices());
            for (Face f : part.faces()) {
                int[] newIdx = Arrays.stream(f.indices())
                    .map(idx -> idx + vertOffset).toArray();
                allFaces.add(new Face(newIdx, f.color(), f.brightness()));
            }
            System.out.printf("    part %-20s %3d verts  %2d faces%n",
                partName, part.vertices().size(), part.faces().size());
        }

        return new SbpMesh(objectName, allVerts, allFaces);
    }

    // ── Couleur Amiga -> JME ──────────────────────────────────────────────────

    /**
     * Convertit une couleur Amiga 12-bit (0x0RGB) + brightness en ColorRGBA JME.
     * bits 11-8 = R, bits 7-4 = G, bits 3-0 = B  (4 bits each, 0-15)
     * brightness : 0=sombre, 100=eclaire.
     */
    public static ColorRGBA amigaColorToJme(int color12, int brightness) {
        float r  = ((color12 >> 8) & 0xF) / 15.0f;
        float g  = ((color12 >> 4) & 0xF) / 15.0f;
        float bl = ( color12       & 0xF) / 15.0f;
        float shade = Math.max(0.15f, brightness / 100.0f);
        return new ColorRGBA(r * shade, g * shade, bl * shade, 1.0f);
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    private static SbpMesh empty(String name) {
        return new SbpMesh(name, List.of(), List.of());
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++)
            if (data[i] != (byte) prefix.charAt(i)) return false;
        return true;
    }

    /** Recherche un fichier dans un dossier, insensible a la casse */
    private static Path findFile(Path dir, String name) throws IOException {
        Path direct = dir.resolve(name);
        if (Files.exists(direct)) return direct;
        String lower = name.toLowerCase();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().toLowerCase().equals(lower))
                    .findFirst().orElse(null);
        }
    }
}
