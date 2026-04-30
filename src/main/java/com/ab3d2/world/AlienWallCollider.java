package com.ab3d2.world;

import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Detection de collision murs pour les aliens (port partiel de
 * {@code newaliencontrol.s::Obj_DoCollision}).
 *
 * <h2>Principe</h2>
 *
 * <p>L'ASM original utilise une table {@code diststowall} indexee par le
 * {@code AlienT_Girth_w} pour determiner la distance de collision (80, 160 ou
 * 320 unites Amiga). On reproduit ce comportement avec un test
 * cercle&nbsp;vs&nbsp;segment 2D, le rayon etant fourni au moment de la
 * resolution (pas hardcode).</p>
 *
 * <p>C'est analogue a {@link WallCollision} (utilise pour les portes) mais avec
 * un rayon parametrable.</p>
 *
 * <h2>Source des segments</h2>
 *
 * <p>Les segments sont extraits depuis les Geometry {@code walls_*} construites
 * par {@code LevelSceneBuilder.buildWallGeo}. Chaque quad de mur a 4 vertices
 * dans l'ordre BL/BR/TR/TL ; on extrait le segment XZ depuis les vertices 0 et 1
 * (= bord bas du mur, qui correspond exactement a la projection 2D du mur sur
 * le sol).</p>
 *
 * <p>Les portes ne sont PAS incluses ici car elles ont leur propre node
 * {@code doors/} et bougent dynamiquement. Pour les aliens, traverser une porte
 * fermee ferait une collision via la geometrie du mur voisin (le linteau, bot
 * step), donc en pratique ils sont quand meme bloques.</p>
 *
 * <h2>Approche slide vs ASM</h2>
 *
 * <p>L'ASM fait du slide-along-wall : si l'alien rencontre un mur, sa
 * composante de vitesse perpendiculaire au mur est annulee, mais la composante
 * parallele est conservee (= il glisse le long). Notre {@link #move} reproduit
 * ce comportement en poussant l'alien hors du segment dans le sens de la
 * normale, en preservant la composante tangentielle.</p>
 *
 * @since session 113 (phase 2.F)
 */
public final class AlienWallCollider {

    private static final Logger log = LoggerFactory.getLogger(AlienWallCollider.class);

    /** Un segment de mur solide en coordonnees JME (x0,z0)&rarr;(x1,z1). */
    public record Segment(float x0, float z0, float x1, float z1) {}

    private final List<Segment> segments;

    /** Constructeur direct (pour tests). */
    public AlienWallCollider(List<Segment> segments) {
        this.segments = new ArrayList<>(segments);
    }

    /**
     * Construit un AlienWallCollider en scannant les Geometry {@code walls_*}
     * du levelScene.
     *
     * <p>Pour chaque Geometry de mur, on parcourt son VertexBuffer Position
     * par groupes de 4 vertices (= 1 quad). Le segment 2D est extrait depuis
     * le bord bas (vertices 0 et 1, ordre BL puis BR cf.
     * {@code LevelSceneBuilder.buildWallGeo}).</p>
     *
     * <p>Note : on ignore les Geometry des nodes {@code doors/} et
     * {@code lifts/} (elles sont mobiles). Seul le node {@code geometry/}
     * est scanne, qui contient les murs statiques agreges par texture.</p>
     */
    public static AlienWallCollider fromLevelScene(Node levelScene) {
        if (levelScene == null) {
            return new AlienWallCollider(List.of());
        }
        Node geoNode = (Node) levelScene.getChild("geometry");
        if (geoNode == null) {
            log.warn("AlienWallCollider : pas de Node 'geometry' dans levelScene");
            return new AlienWallCollider(List.of());
        }

        List<Segment> result = new ArrayList<>();
        for (Spatial s : geoNode.getChildren()) {
            if (!(s instanceof Geometry g)) continue;
            String name = g.getName();
            // On ne prend que les murs (walls_NN), pas les sols (floor_*) ni
            // les plafonds (ceil_*) qui sont horizontaux et n'imposent pas de
            // collision laterale.
            if (name == null || !name.startsWith("walls_")) continue;
            extractSegmentsFromWallGeo(g, result);
        }
        log.info("AlienWallCollider : {} segments extraits depuis levelScene", result.size());
        return new AlienWallCollider(result);
    }

    /**
     * Extrait les segments XZ d'une Geometry de mur. Pour chaque quad
     * (4 vertices), on prend les vertices 0 et 1 (= bord bas BL et BR).
     */
    private static void extractSegmentsFromWallGeo(Geometry g, List<Segment> out) {
        Mesh mesh = g.getMesh();
        if (mesh == null) return;
        VertexBuffer posBuf = mesh.getBuffer(VertexBuffer.Type.Position);
        if (posBuf == null) return;
        FloatBuffer fb = (FloatBuffer) posBuf.getDataReadOnly();
        if (fb == null) return;
        int floats = fb.limit();
        // Chaque quad = 4 vertices * 3 floats = 12 floats.
        for (int q = 0; q + 12 <= floats; q += 12) {
            // Vertex 0 (BL) : x0, yB, z0
            float x0 = fb.get(q);
            float z0 = fb.get(q + 2);
            // Vertex 1 (BR) : x1, yB, z1
            float x1 = fb.get(q + 3);
            float z1 = fb.get(q + 5);
            // Skip degenerate (longueur quasi nulle)
            float dx = x1 - x0, dz = z1 - z0;
            if (dx * dx + dz * dz < 1e-4f) continue;
            out.add(new Segment(x0, z0, x1, z1));
        }
    }

    /** @return nombre de segments charges (pour diagnostics / tests). */
    public int segmentCount() {
        return segments.size();
    }

    /**
     * Resout le mouvement d'un alien : depuis (px, pz), tente d'aller en
     * (px+dx, pz+dz) avec un rayon de collision {@code radius}. Retourne la
     * position finale apres slide-along-wall.
     *
     * <p>Approche identique a {@link WallCollision#move}, mais le rayon est
     * fourni en parametre. 3 iterations de resolution pour gerer les coins
     * et les configurations multi-murs.</p>
     *
     * @param px      position courante X (JME)
     * @param pz      position courante Z (JME)
     * @param dx      delta X souhaite (JME)
     * @param dz      delta Z souhaite (JME)
     * @param radius  rayon de collision (JME)
     * @return float[] {newX, newZ} apres resolution
     */
    public float[] move(float px, float pz, float dx, float dz, float radius) {
        float nx = px + dx;
        float nz = pz + dz;

        // 3 iterations pour stabilite (cas de coins / multiples murs)
        for (int iter = 0; iter < 3; iter++) {
            for (Segment seg : segments) {
                float[] r = resolve(nx, nz, seg, radius);
                if (r != null) { nx = r[0]; nz = r[1]; }
            }
        }
        return new float[]{nx, nz};
    }

    /**
     * Si l'alien (cercle de rayon {@code radius} en (px,pz)) touche le segment,
     * retourne la nouvelle position poussee hors du mur. {@code null} sinon.
     */
    private static float[] resolve(float px, float pz, Segment seg, float radius) {
        float wx = seg.x1 - seg.x0;
        float wz = seg.z1 - seg.z0;
        float len2 = wx * wx + wz * wz;
        if (len2 < 1e-6f) return null;

        // Projection de (px,pz) sur le segment -> parametre t in [0,1]
        float t = ((px - seg.x0) * wx + (pz - seg.z0) * wz) / len2;
        t = Math.max(0f, Math.min(1f, t));

        // Point le plus proche sur le segment
        float cx = seg.x0 + t * wx;
        float cz = seg.z0 + t * wz;

        // Vecteur point-mur -> alien
        float ex = px - cx;
        float ez = pz - cz;
        float dist = (float) Math.sqrt(ex * ex + ez * ez);

        if (dist >= radius) return null;  // pas de collision
        if (dist < 1e-5f) {
            // Alien exactement sur le segment : pousser le long de la normale
            ex = -wz; ez = wx;
            dist = (float) Math.sqrt(ex * ex + ez * ez);
        }

        // Pousser l'alien hors du mur
        float push = radius - dist;
        float newX = px + (ex / dist) * push;
        float newZ = pz + (ez / dist) * push;
        return new float[]{newX, newZ};
    }
}
