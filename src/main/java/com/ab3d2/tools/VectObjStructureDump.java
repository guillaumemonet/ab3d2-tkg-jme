package com.ab3d2.tools;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;

import java.nio.file.Path;

/**
 * Outil de diagnostic : affiche la structure d'un .j3o vectobj converti pour
 * verifier combien de parts / triangles il a.
 *
 * <p>Utilise pour debugger les "trous" dans l'arme : on veut savoir si le
 * shotgun est un seul gros Geometry (= probleme de tri) ou bien un Node avec
 * plusieurs Geometry (= structure correcte).</p>
 *
 * <p>Usage : <code>./gradlew dumpVectObjStructure -Pname=shotgun</code></p>
 */
public class VectObjStructureDump {

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "shotgun";

        AssetManager am = new DesktopAssetManager(true);
        am.registerLocator("assets", FileLocator.class);

        String assetPath = "Scenes/vectobj/" + name + ".j3o";
        System.out.println("=== Structure de " + assetPath + " ===");

        Spatial root;
        try {
            root = am.loadModel(assetPath);
        } catch (Exception e) {
            System.err.println("Impossible de charger : " + e.getMessage());
            return;
        }

        dumpSpatial(root, 0);

        // Stats globales
        int[] stats = new int[4];  // parts, triangles, vertices, animParts
        collectStats(root, stats);
        System.out.printf("%n=== STATS GLOBALES ===%n");
        System.out.printf("  Geometries (= parts ASM)    : %d%n", stats[0]);
        System.out.printf("  Triangles total             : %d%n", stats[1]);
        System.out.printf("  Vertices total              : %d%n", stats[2]);
        System.out.printf("  Parts avec animation frames : %d%n", stats[3]);
    }

    private static void dumpSpatial(Spatial s, int depth) {
        String indent = "  ".repeat(depth);
        String bounds = s.getWorldBound() instanceof BoundingBox bb
            ? String.format(" bbox=(%.2f,%.2f,%.2f)+-(%.2f,%.2f,%.2f)",
                bb.getCenter().x, bb.getCenter().y, bb.getCenter().z,
                bb.getXExtent(), bb.getYExtent(), bb.getZExtent())
            : "";

        if (s instanceof Node n) {
            System.out.printf("%s[Node] %s (%d children)%s%n",
                indent, n.getName(), n.getQuantity(), bounds);
            for (Spatial child : n.getChildren()) {
                dumpSpatial(child, depth + 1);
            }
        } else if (s instanceof Geometry g) {
            Mesh m = g.getMesh();
            int nVerts = m.getBuffer(Type.Position) != null
                ? m.getBuffer(Type.Position).getNumElements() : 0;
            int nTris  = m.getTriangleCount();

            // Check si la Geometry a les frames d'animation stockees en UserData
            boolean hasAnim = g.getUserData("vectobj_anim_frames") != null;

            System.out.printf("%s[Geom] %s : %d tris, %d verts%s%s%n",
                indent, g.getName(), nTris, nVerts,
                hasAnim ? " [ANIM]" : "",
                bounds);

            // Material info
            if (g.getMaterial() != null) {
                var rs = g.getMaterial().getAdditionalRenderState();
                System.out.printf("%s  bucket=%s depthTest=%s depthWrite=%s cull=%s%n",
                    indent,
                    g.getQueueBucket(),
                    rs.isDepthTest(),
                    rs.isDepthWrite(),
                    rs.getFaceCullMode());
            }
        }
    }

    private static void collectStats(Spatial s, int[] stats) {
        if (s instanceof Geometry g) {
            stats[0]++;
            stats[1] += g.getMesh().getTriangleCount();
            if (g.getMesh().getBuffer(Type.Position) != null) {
                stats[2] += g.getMesh().getBuffer(Type.Position).getNumElements();
            }
            if (g.getUserData("vectobj_anim_frames") != null) stats[3]++;
        } else if (s instanceof Node n) {
            for (Spatial c : n.getChildren()) collectStats(c, stats);
        }
    }
}
