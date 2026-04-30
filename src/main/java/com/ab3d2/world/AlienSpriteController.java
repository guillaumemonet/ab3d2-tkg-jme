package com.ab3d2.world;

import com.ab3d2.core.ai.AlienAnimTable;
import com.ab3d2.core.ai.AlienBehaviour;
import com.ab3d2.core.ai.AlienRuntimeState;
import com.ab3d2.core.ai.AlienViewpoint;
import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Anime le sprite d'un alien : choisit la texture {@code _fN.png} a afficher
 * chaque frame selon le mode IA + le viewpoint relatif a la camera + la phase
 * d'animation.
 *
 * <p>Equivalent ASM : {@code newaliencontrol.s::ai_DoWalkAnim} +
 * {@code newaliencontrol.s::ai_DoAttackAnim} qui ecrivent le frame index dans
 * {@code obj+9} ({@code ObjT_FrameIdx_b}). En JME, on cherche la
 * {@link Geometry} sprite dans le sous-arbre et on swap son
 * {@code DiffuseMap}.</p>
 *
 * <h2>Pipeline</h2>
 *
 * <ol>
 *   <li>{@code AlienControlSystem.update()} appelle {@link #update} pour
 *       chaque alien apres l'IA, en passant {@link AlienRuntimeState} et
 *       l'angle camera.</li>
 *   <li>{@link #update} calcule le {@link AlienViewpoint} et le frame
 *       index via {@link AlienAnimTable#pickFrame}.</li>
 *   <li>Si le frame change par rapport au precedent, on charge
 *       {@code Textures/objects/{wadName}/{wadName}_f{frame}.png} et on
 *       l'applique au material du sprite.</li>
 * </ol>
 *
 * <h2>Cache</h2>
 *
 * <p>Les Texture sont cachees par chemin pour eviter de recharger le PNG a
 * chaque tick. Comme un meme alien type partage les memes 19 frames, le cache
 * evite ~19 charges par alien.</p>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li>Aliens "vector" (gfxType=1, ex. Mantis) : pas de swap PNG, ils ont
 *       un Node {@code Spatial} 3D porte par {@code tryLoadVectObj}. Pour
 *       eux, il faudrait animer les keyframes du modele - reporte.</li>
 *   <li>Le clamp si le WAD a moins de frames que demande tombe sur la
 *       frame 0 du viewpoint (cf. {@link AlienAnimTable#pickFrame}).</li>
 *   <li>Pas de transition douce entre frames : c'est un swap direct chaque
 *       N tick Amiga (= comme l'ASM).</li>
 * </ul>
 *
 * @since session 113 (phase 2.E)
 */
public final class AlienSpriteController {

    private static final Logger log = LoggerFactory.getLogger(AlienSpriteController.class);

    private final AssetManager assetManager;
    /** Node racine du sprite dans la scene JME (parent de la Geometry). */
    private final Node spriteNode;
    /** Geometry contenant la {@code Quad} affichee (cherchee une fois au ctor). */
    private final Geometry spriteGeo;
    /** Materiau du sprite (Lighting.j3md ou Unshaded.j3md selon spawn). */
    private final Material material;
    /** Nom du WAD pour construire le path PNG. Ex. {@code "alien2"}. */
    private final String wadName;
    /** Nombre de frames PNG disponibles pour ce WAD (detecte par scan). */
    private final int maxFrames;
    /** Cache des textures par chemin (evite les reloads). */
    private final Map<Integer, Texture> textureCache = new HashMap<>();
    /** Index de la derniere frame appliquee (-1 = jamais set). */
    private int lastFrame = -1;
    /**
     * @param spriteNode  Node JME du sprite (= ce que cree {@code addItems()}
     *                     dans {@code LevelSceneBuilder} pour les sprites bitmap)
     * @param wadName     nom court du WAD ({@code "alien2"}, {@code "guard"}, ...)
     * @param maxFrames   nombre de frames disponibles ({@code _f0.png} a
     *                     {@code _f(maxFrames-1).png})
     * @param assetManager pour charger les textures dynamiquement
     */
    public AlienSpriteController(Node spriteNode, String wadName,
                                  int maxFrames, AssetManager assetManager) {
        this.spriteNode = spriteNode;
        this.wadName = wadName;
        this.maxFrames = Math.max(1, maxFrames);
        this.assetManager = assetManager;
        this.spriteGeo = findSpriteGeometry(spriteNode);
        this.material = (spriteGeo != null) ? spriteGeo.getMaterial() : null;
    }

    /**
     * Indique si ce controleur a trouve un sprite valide a animer. Si false,
     * c'est probablement un alien vector (pas de Geometry quad) - {@link #update}
     * sera un no-op.
     */
    public boolean isAnimatable() {
        return spriteGeo != null && material != null
            && wadName != null && !wadName.isEmpty();
    }

    /**
     * Met a jour la frame affichee selon l'etat de l'alien + l'angle camera.
     *
     * @param state       etat IA de l'alien (mode + timer2 + currentAngle)
     * @param cameraAngle angle camera 0..4095 (= {@code Vis_AngPos_w} ASM)
     */
    public void update(AlienRuntimeState state, int cameraAngle) {
        if (!isAnimatable()) return;

        AlienViewpoint vp = AlienViewpoint.compute(state.currentAngle, cameraAngle);
        // Pour DIE / TAKE_DAMAGE on utilise timer3/timer2 ; pour walk/attack on utilise timer2.
        // En pratique pickFrame se base sur timer2 partout, on lui passe ca et le mode fait le reste.
        int frameIdx = AlienAnimTable.pickFrame(state.mode, vp, state.timer2, maxFrames);
        if (frameIdx == lastFrame) return; // pas de change

        Texture tex = loadFrame(frameIdx);
        if (tex == null) return;
        material.setTexture("DiffuseMap", tex);
        lastFrame = frameIdx;
    }

    /**
     * Charge (ou prend depuis cache) la texture pour {@code _fN.png}.
     * Retourne null si le PNG n'existe pas (ne devrait pas arriver vu le clamp
     * de {@link AlienAnimTable#pickFrame} mais on est defensif).
     *
     * <p>Phase 2.E (fix) : on utilise {@code Map.containsKey} pour distinguer
     * "absent du cache" de "presente avec valeur null". Sans ca, une frame
     * cachee a {@code null} declenchait un re-load + warning JME a chaque
     * appel.</p>
     */
    private Texture loadFrame(int frameIdx) {
        // Cache : a la fois pour les valeurs valides ET les frames absentes
        // (= null cache pour eviter les reloads + warnings JME).
        if (textureCache.containsKey(frameIdx)) {
            return textureCache.get(frameIdx);
        }

        String path = "Textures/objects/" + wadName + "/"
                    + wadName + "_f" + frameIdx + ".png";
        try {
            Texture tex = assetManager.loadTexture(path);
            tex.setWrap(Texture.WrapMode.EdgeClamp);
            tex.setMagFilter(Texture.MagFilter.Nearest);
            tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            textureCache.put(frameIdx, tex);
            return tex;
        } catch (AssetNotFoundException e) {
            log.debug("Sprite manquant : {} (frame {} pour wad {})", path, frameIdx, wadName);
            // Cacher null pour cette frame -> evite les reloads + warnings
            textureCache.put(frameIdx, null);
            // Fallback recursif sur frame 0 (= sprite de base, toujours present)
            if (frameIdx != 0) return loadFrame(0);
            return null;
        }
    }

    /**
     * Trouve la {@link Geometry} contenant le sprite quad dans le sous-arbre
     * du Node. Convention {@code LevelSceneBuilder.tryLoadSprite} :
     * un seul enfant Geometry nomme {@code *_sprite}.
     *
     * @return la Geometry sprite, ou {@code null} si introuvable (alien vector)
     */
    private static Geometry findSpriteGeometry(Node node) {
        if (node == null) return null;
        for (Spatial s : node.getChildren()) {
            if (s instanceof Geometry g) {
                // LevelSceneBuilder nomme la geometry "{nodeName}_sprite"
                if (g.getName() != null && g.getName().endsWith("_sprite")) {
                    return g;
                }
            }
        }
        // Fallback : prendre la 1ere Geometry trouvee
        for (Spatial s : node.getChildren()) {
            if (s instanceof Geometry g) return g;
        }
        return null;
    }
}
