package com.ab3d2.weapon;

import com.ab3d2.core.BobController;
import com.ab3d2.tools.VectObjFrameAnimControl;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Affichage de l'arme en main (first-person view).
 *
 * <h2>Architecture</h2>
 * Un Node <code>weaponRoot</code> attache directement au rootNode. A chaque
 * frame, sa position et rotation monde sont calculees depuis la camera + les
 * offsets configures.
 *
 * <p>Le modele vectobj est pre-normalise au chargement : centrage sur l'origine
 * et scale uniforme pour atteindre {@link #TARGET_WEAPON_SIZE} en unites JME.</p>
 *
 * <h2>Principes issus du code ASM (objdrawhires.s : draw_PolygonModel)</h2>
 * <ul>
 *   <li>L'arme est dessinee a z=1 devant la camera (cas special player weapon).</li>
 *   <li>Centre horizontal = {@code Vid_CentreX_w = 160}, vertical = {@code SMALL_HEIGHT/2 = 80}.</li>
 *   <li>Animation en pause par defaut (frame 0 = idle), ne tourne qu'au tir.</li>
 * </ul>
 *
 * <h2>LECON IMPORTANTE (session 76)</h2>
 * <p>Pour positionner une arme devant la camera en vue FPS, il NE FAUT PAS utiliser
 * {@code cam.getRotation().mult(offset)}. La rotation camera de JME a une
 * convention qui ne produit pas le resultat attendu (Z local camera != -forward world).</p>
 *
 * <p>La methode correcte est d'utiliser les vecteurs {@code cam.getDirection()},
 * {@code cam.getLeft()}, {@code cam.getUp()} qui sont les vrais vecteurs monde
 * coherents, et de composer la position manuellement :</p>
 * <pre>
 *   pos = cam.location
 *       + cam.getLeft() * (-offset.x)     // x positif = droite
 *       + cam.getUp() * offset.y           // y positif = haut
 *       + cam.getDirection() * (-offset.z) // z negatif = devant
 * </pre>
 *
 * <p>De plus, {@code cullHint = Never} sur weaponRoot evite que JME la cull
 * a tort (le frustum check peut utiliser d'anciennes world bounds).</p>
 */
public class WeaponViewAppState extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(WeaponViewAppState.class);

    // ─────────── Configuration visuelle ───────────

    /**
     * Offset local de l'arme par rapport a la camera, en unites JME.
     * Convention : X positif = droite, Y negatif = bas, Z negatif = devant.
     *
     * <p>Session 89bis : revert aux valeurs sess 85 (0.45/-0.45/-0.55 pas OK
     * en visuel), on garde plus en bas-droite mais moins extreme.</p>
     *
     * <p><b>TODO session 90</b> : passer a un offset/scale/rotation
     * <em>par arme</em> (via {@link WeaponType}) comme dans l'ASM, au lieu
     * d'une normalisation uniforme. Chaque vectobj a sa propre echelle et sa
     * propre orientation canonique.</p>
     */
    private static final Vector3f WEAPON_OFFSET = new Vector3f(0.40f, -0.38f, -0.55f);

    /**
     * Rotation additionnelle commune a toutes les armes (dans le repere camera).
     *
     * <p>Chaque arme peut avoir sa propre rotation additionnelle dans
     * {@link WeaponType#getDisplayRotation()} pour ajuster son orientation
     * canonique (les vectobj n'ont pas tous la meme orientation de depart).</p>
     *
     * <p>Ici c'est l'identite : la rotation complete de l'arme vient de
     * {@code WeaponType.getDisplayRotation()} uniquement.</p>
     */
    private static final Quaternion WEAPON_ROTATION_BASE = new Quaternion();  // identite

    /**
     * Taille cible uniforme de l'arme (plus grande dimension de la bbox
     * normalisee), en unites JME.
     *
     * <p>Session 89bis : revert a 0.5 (0.7 = arme aberrement grande).</p>
     *
     * <p><b>TODO session 90</b> : supprimer cette normalisation uniforme. Chaque
     * vectobj a son echelle native qu'il faut preserver (on choisit une
     * taille par arme dans {@link WeaponType}).</p>
     */
    private static final float TARGET_WEAPON_SIZE = 0.5f;

    // ─────────── Etat ───────────

    private SimpleApplication sa;
    private Node              weaponRoot;        // attache directement au rootNode
    private Spatial           currentWeaponModel;
    private WeaponType        currentWeapon;
    private boolean           weaponVisible = true;

    /** Reference au BobController du jeu pour appliquer un bob d'arme additionnel.
     *  Session 109 : null = pas de bob (l'arme reste plate). */
    private BobController     bobController;

    public WeaponViewAppState() { this(WeaponType.BLASTER); }
    public WeaponViewAppState(WeaponType initial) { this.currentWeapon = initial; }

    public WeaponType getCurrentWeapon() { return currentWeapon; }
    public boolean isWeaponVisible()     { return weaponVisible; }
    public void setWeaponVisible(boolean v) {
        this.weaponVisible = v;
        if (weaponRoot != null) {
            weaponRoot.setCullHint(v ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        }
    }

    /**
     * Connecte le BobController pour appliquer le bob de marche a l'arme.
     *
     * <p>Session 109 : l'arme suit deja la camera (qui a son propre bob via
     * {@link com.ab3d2.app.GameAppState}), mais l'ASM applique un facteur
     * <b>1.5x</b> supplementaire a l'arme pour qu'elle oscille plus que la
     * camera. On obtient ce surplus via {@link BobController#getBobYWeaponExtra}
     * (= 0.5x de plus en absolu) qu'on ajoute le long de {@code cam.getUp()}.</p>
     */
    public void setBobController(BobController bc) {
        this.bobController = bc;
    }

    // ─────────── Cycle de vie ───────────

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa = (SimpleApplication) app;

        weaponRoot = new Node("weaponRoot");
        // CullHint.Never : l'arme est un "screen overlay" toujours visible, pas
        // besoin de frustum culling. JME peut a tort considerer l'arme hors
        // frustum car les world bounds ne sont pas toujours synchronises avec
        // notre setLocalTranslation dans update().
        weaponRoot.setCullHint(Spatial.CullHint.Never);
        sa.getRootNode().attachChild(weaponRoot);

        // Lumieres dediees a l'arme (n'affectent que weaponRoot)
        AmbientLight ambient = new AmbientLight(new ColorRGBA(0.9f, 0.9f, 0.95f, 1f));
        weaponRoot.addLight(ambient);
        DirectionalLight fill = new DirectionalLight();
        fill.setDirection(new Vector3f(-0.3f, -0.5f, -1f).normalizeLocal());
        fill.setColor(ColorRGBA.White.mult(0.6f));
        weaponRoot.addLight(fill);

        loadWeapon(currentWeapon);
        log.info("WeaponViewAppState init : arme = {}", currentWeapon);
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled() || weaponRoot == null) return;

        Camera cam = sa.getCamera();

        // Calcul manuel de la position monde depuis les vecteurs camera.
        //
        // IMPORTANT : ne PAS utiliser cam.getRotation().mult(WEAPON_OFFSET) :
        // la convention de la rotation camera JME ne produit pas le resultat
        // attendu (cam.getRotation() * (0,0,-1) != cam.getDirection()).
        //
        // Methode correcte : utiliser getDirection/getLeft/getUp qui donnent
        // les vrais vecteurs monde coherents.
        Vector3f forward = cam.getDirection();  // devant
        Vector3f left    = cam.getLeft();       // gauche
        Vector3f up      = cam.getUp();         // haut

        // Position monde = cam + X*right + Y*up + |Z|*forward
        //                = cam - X*left + Y*up - Z*forward  (car forward = -Z local)
        Vector3f weaponPos = cam.getLocation().clone();
        weaponPos.addLocal(left.mult(-WEAPON_OFFSET.x));    // X positif = a droite
        weaponPos.addLocal(up.mult(WEAPON_OFFSET.y));       // Y negatif = en bas
        weaponPos.addLocal(forward.mult(-WEAPON_OFFSET.z)); // Z negatif = devant

        // Session 109 : bob d'arme additionnel (en plus du bob camera qui porte
        // deja l'arme). L'ASM applique BobbleY*1.5 a l'arme et BobbleY*1.0 a
        // la camera, soit 0.5x extra sur l'arme dans notre architecture.
        // BobYWeaponExtra est >= 0 -> arme monte au pas, comme la camera.
        if (bobController != null) {
            float bobYExtra = bobController.getBobYWeaponExtra();
            if (bobYExtra > 0f) {
                weaponPos.addLocal(up.mult(bobYExtra));
            }
        }

        weaponRoot.setLocalTranslation(weaponPos);

        // Rotation : suivre l'orientation camera + rotation d'affichage specifique
        // a cette arme (chaque vectobj peut avoir une orientation canonique differente)
        Quaternion weaponRot = currentWeapon != null
            ? currentWeapon.getDisplayRotation()
            : WEAPON_ROTATION_BASE;
        weaponRoot.setLocalRotation(cam.getRotation().mult(weaponRot));

        // Forcer le recalcul des world transforms pour ce frame
        weaponRoot.updateGeometricState();
    }

    @Override
    public void cleanup() {
        if (weaponRoot != null) {
            sa.getRootNode().detachChild(weaponRoot);
            weaponRoot = null;
        }
        currentWeaponModel = null;
    }

    // ─────────── Chargement d'arme ───────────

    /**
     * Charge le modele vectobj et l'ajoute au weaponRoot (pre-normalise).
     */
    public void loadWeapon(WeaponType weapon) {
        if (weapon == null) return;
        if (currentWeaponModel != null && weapon == currentWeapon) return;

        if (currentWeaponModel != null) {
            weaponRoot.detachChild(currentWeaponModel);
            currentWeaponModel = null;
        }

        try {
            Spatial model = sa.getAssetManager().loadModel(weapon.getAssetPath());

            int nAnim = VectObjFrameAnimControl.attachIfAnimated(model);
            stopAnimations(model);
            resetAnimationsToFrame0(model);
            fixWeaponMaterials(model);

            // Pre-normalise : centrer sur l'origine + scale uniforme
            normalizeModelVertices(model);

            weaponRoot.attachChild(model);
            currentWeaponModel = model;
            currentWeapon = weapon;

            log.info("Arme '{}' chargee : {} anim", weapon, nAnim);
        } catch (Exception e) {
            log.error("Impossible de charger l'arme '{}' : {}", weapon.getAssetPath(), e.getMessage());
        }
    }

    /**
     * Centre le modele sur l'origine et applique un scale uniforme pour que sa
     * plus grande dimension fasse {@link #TARGET_WEAPON_SIZE} unites.
     *
     * <p>Les vectobj Amiga ont des origines et des echelles tres variables :
     * le shotgun par exemple a son centre a (-1.52, -0.04, -0.13) dans ses coord
     * natives. Pour un usage en FPS, on normalise pour qu'il apparaisse centre
     * et a la bonne taille devant la camera.</p>
     */
    private void normalizeModelVertices(Spatial model) {
        model.updateModelBound();
        BoundingVolume bv = (model instanceof Node n)
            ? computeLocalModelBound(n)
            : (model instanceof Geometry g ? g.getModelBound() : null);

        if (!(bv instanceof BoundingBox bb)) {
            log.warn("Arme sans BoundingBox, pas de normalisation");
            return;
        }

        Vector3f center = bb.getCenter().clone();
        Vector3f extent = new Vector3f();
        bb.getExtent(extent);
        float maxExtent = Math.max(Math.max(extent.x, extent.y), extent.z);
        if (maxExtent <= 0.001f) return;

        float scale = (TARGET_WEAPON_SIZE * 0.5f) / maxExtent;

        // On translate le modele de -center*scale et on applique le scale.
        // Apres transformation : v_final = scale * (v - center), donc le centre
        // de la bbox tombe bien sur (0,0,0) dans le repere weaponRoot.
        model.setLocalTranslation(center.negate().multLocal(scale));
        model.setLocalScale(scale);
    }

    /**
     * Calcule la bbox locale d'un Node (union des bbox locales des enfants Geometry,
     * transformes par leurs transformations locales). Utilise a la place de
     * {@code getWorldBound()} qui inclut les transformations des parents (ici
     * {@code weaponRoot} qui bouge avec la camera).
     */
    private BoundingBox computeLocalModelBound(Node node) {
        BoundingBox result = null;
        for (Spatial child : node.getChildren()) {
            BoundingBox childBB;
            if (child instanceof Geometry g) {
                g.updateModelBound();
                BoundingVolume mb = g.getModelBound();
                if (!(mb instanceof BoundingBox)) continue;
                childBB = (BoundingBox) mb.clone();
                childBB.transform(child.getLocalTransform(), childBB);
            } else if (child instanceof Node cn) {
                childBB = computeLocalModelBound(cn);
                if (childBB != null) {
                    childBB.transform(child.getLocalTransform(), childBB);
                }
            } else continue;

            if (childBB == null) continue;
            if (result == null) result = childBB;
            else result = (BoundingBox) result.merge(childBB);
        }
        return result;
    }

    // ─────────── Animation ───────────

    /**
     * Declenche l'animation de tir sur l'arme courante.
     *
     * <p>Dans le jeu Amiga, au tir : {@code Plr1_GunFrame_w = MaxFrame}, puis la
     * frame decremente chaque tick jusqu'a 0. On simule en mode non-loop : reset
     * a frame 0, puis play (s'arrete a la derniere frame).</p>
     *
     * <p>TODO(weapon-anim) : distinguer des plages de frames idle/fire par arme
     * en etudiant newplayershoot.s en detail.</p>
     */
    public void playFireAnimation() {
        if (currentWeaponModel == null) return;
        currentWeaponModel.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                var ctrl = g.getControl(VectObjFrameAnimControl.class);
                if (ctrl != null) {
                    int cur = ctrl.getCurrentFrameIndex();
                    if (cur > 0) ctrl.stepFrame(-cur);
                    ctrl.setLooping(false);
                    ctrl.setPlaying(true);
                }
            }
        });
    }

    /** Met toutes les animations en pause (reste sur la frame courante). */
    private void stopAnimations(Spatial model) {
        model.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                var ctrl = g.getControl(VectObjFrameAnimControl.class);
                if (ctrl != null) ctrl.setPlaying(false);
            }
        });
    }

    /** Positionne toutes les animations a la frame 0 (pose idle). */
    private void resetAnimationsToFrame0(Spatial model) {
        model.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                var ctrl = g.getControl(VectObjFrameAnimControl.class);
                if (ctrl != null) {
                    int cur = ctrl.getCurrentFrameIndex();
                    if (cur != 0) ctrl.stepFrame(-cur);
                }
            }
        });
    }

    // ─────────── Materiaux ───────────

    /**
     * Configure les materiaux pour un rendu FPS classique.
     *
     * <h3>Session 92 : lighting moderne JME</h3>
     *
     * <p>Depuis la session 92, les vectobj utilisent <code>Lighting.j3md</code>
     * (avant : Unshaded). Les lumieres attachees a <code>weaponRoot</code>
     * (AmbientLight + DirectionalLight) eclairent enfin l'arme.</p>
     *
     * <h3>Session 90 : fix des trous par depthTest</h3>
     *
     * <p>Apres avoir verifie que VectObjConverter fait deja <b>un Geometry par
     * Part</b> (architecture conforme a l'ASM), le seul changement restant est
     * d'activer le depth test entre les triangles pour qu'ils se trient
     * correctement entre eux.</p>
     *
     * <p>Session 89 avait teste depthTest=true + FaceCullMode.Back : le Back
     * culling inversait l'arme (normales vectobj incoherentes). Ici on ne
     * change QUE le depthTest sans toucher au FaceCullMode (reste .Off).</p>
     */
    private void fixWeaponMaterials(Spatial model) {
        model.depthFirstTraversal(s -> {
            if (!(s instanceof Geometry g)) return;
            Material mat = g.getMaterial();
            if (mat == null) return;

            // Session 92 : si le materiau est Unshaded (ancien .j3o genere avant
            // session 92), on le convertit en Lighting a la vole pour que l'arme
            // beneficie quand meme du nouvel eclairage. Apres reconversion
            // (./gradlew convertVectObj), les .j3o seront deja en Lighting.
            String defName = mat.getMaterialDef().getName();
            if (defName.contains("Unshaded")) {
                mat = upgradeVectObjMaterialInPlace(g, mat);
                if (mat == null) return;
            }

            // Session 90 : depth test ACTIVE pour trier les triangles entre eux.
            // C'est la difference cle avec session 85/89bis qui avait
            // depthTest=false -> triangles dessines dans l'ordre du buffer.
            mat.getAdditionalRenderState().setDepthTest(true);
            mat.getAdditionalRenderState().setDepthWrite(true);
            // FaceCullMode.Off : les vectobj ont des normales pas toujours
            // coherentes (triangulation en fan de polygones concaves inverse
            // parfois la normale). Off = on dessine les 2 cotes.
            mat.getAdditionalRenderState().setFaceCullMode(
                com.jme3.material.RenderState.FaceCullMode.Off);
            // Bucket.Transparent : JME trie les Geometries par distance.
            // Combine avec depthTest=true, les parts eloignees sont dessinees
            // d'abord et les plus proches par-dessus.
            g.setQueueBucket(Bucket.Transparent);
        });
    }

    /**
     * Convertit a la volee un materiau Unshaded d'un vectobj ancien en
     * Lighting.j3md (avec UseVertexColor preserve). Utilise pour compat avec
     * des .j3o pre-session 92.
     */
    private Material upgradeVectObjMaterialInPlace(Geometry g, Material old) {
        com.jme3.texture.Texture colorMap = null;
        try {
            var tp = old.getTextureParam("ColorMap");
            if (tp != null && tp.getValue() != null) colorMap = (com.jme3.texture.Texture) tp.getValue();
        } catch (Exception ignored) {}

        boolean hasVertexColor = false;
        try {
            var vc = old.getParam("VertexColor");
            if (vc != null && Boolean.TRUE.equals(vc.getValue())) hasVertexColor = true;
        } catch (Exception ignored) {}

        Material neu = new Material(sa.getAssetManager(), "Common/MatDefs/Light/Lighting.j3md");
        if (colorMap != null) neu.setTexture("DiffuseMap", colorMap);
        if (hasVertexColor) neu.setBoolean("UseVertexColor", true);
        neu.setBoolean("UseMaterialColors", true);
        neu.setColor("Ambient", new ColorRGBA(0.45f, 0.45f, 0.45f, 1f));
        neu.setColor("Diffuse", ColorRGBA.White);
        neu.setFloat("AlphaDiscardThreshold", 0.5f);
        neu.getAdditionalRenderState().setBlendMode(
            com.jme3.material.RenderState.BlendMode.Alpha);
        neu.getAdditionalRenderState().setFaceCullMode(
            com.jme3.material.RenderState.FaceCullMode.Off);
        g.setMaterial(neu);
        return neu;
    }
}
