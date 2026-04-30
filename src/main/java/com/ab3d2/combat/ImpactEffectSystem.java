package com.ab3d2.combat;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Sphere;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Systeme d'affichage des effets d'impact (petit flash au point ou la bullet
 * touche un mur, un sol, ou un ennemi).
 *
 * <p>Placeholder en phase 1.D : sphere qui grossit puis disparait. En phase
 * ulterieure on utilisera les vrais sprites d'animation de BulT_PopData_vb
 * (120 bytes de frames depuis le GLF) et les ressources includes/explosion.dat
 * + includes/splutch.dat pour afficher les vraies animations d'impact.</p>
 *
 * <p>Equivalent ASM : dans ItsABullet, quand la bullet touche un mur on passe
 * en mode "pop" (ShotT_Status_b = 1) et on joue l'animation de
 * BulT_PopData_vb pendant BulT_PopFrames_l frames puis le slot est libere.</p>
 */
public class ImpactEffectSystem extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(ImpactEffectSystem.class);

    /** Duree du flash d'impact en secondes. */
    private static final float FLASH_LIFETIME_SEC = 0.15f;

    /** Rayon initial du flash. */
    private static final float FLASH_RADIUS_START = 0.05f;

    /** Rayon final du flash (grossit pour l'effet). */
    private static final float FLASH_RADIUS_END = 0.25f;

    private SimpleApplication sa;
    private Node              impactRoot;
    private final List<ActiveImpact> activeImpacts = new ArrayList<>();

    private static class ActiveImpact {
        final Geometry  geometry;
        float           remainingTime;
        final float     totalTime;
        final ColorRGBA color;

        ActiveImpact(Geometry g, float lifetime, ColorRGBA c) {
            this.geometry      = g;
            this.remainingTime = lifetime;
            this.totalTime     = lifetime;
            this.color         = c;
        }
    }

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa = (SimpleApplication) app;
        impactRoot = new Node("impactEffects");
        sa.getRootNode().attachChild(impactRoot);
        log.info("ImpactEffectSystem init");
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (impactRoot != null) {
            impactRoot.removeFromParent();
            impactRoot = null;
        }
        activeImpacts.clear();
    }

    /**
     * Spawn un flash d'impact a la position donnee, colore selon le type de bullet.
     *
     * @param position  point d'impact (deja recule par WorldRaycaster.IMPACT_BACKOFF)
     * @param bulletType index dans BulletDefs (pour la couleur)
     */
    public void spawnImpact(Vector3f position, int bulletType) {
        if (sa == null) return;

        ColorRGBA color = colorForBulletType(bulletType);
        Sphere sphere = new Sphere(8, 12, FLASH_RADIUS_START);
        Geometry geo = new Geometry("impact_" + bulletType, sphere);

        Material mat = new Material(sa.getAssetManager(),
            "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Additive);
        geo.setQueueBucket(RenderQueue.Bucket.Transparent);
        geo.setMaterial(mat);
        geo.setLocalTranslation(position);
        impactRoot.attachChild(geo);

        activeImpacts.add(new ActiveImpact(geo, FLASH_LIFETIME_SEC, color));
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        Iterator<ActiveImpact> it = activeImpacts.iterator();
        while (it.hasNext()) {
            ActiveImpact imp = it.next();
            imp.remainingTime -= tpf;
            if (imp.remainingTime <= 0f) {
                imp.geometry.removeFromParent();
                it.remove();
                continue;
            }

            // Progression 0 (naissance) -> 1 (mort)
            float progress = 1f - (imp.remainingTime / imp.totalTime);

            // Le flash grossit et s'attenue
            float radius = FLASH_RADIUS_START + (FLASH_RADIUS_END - FLASH_RADIUS_START) * progress;
            imp.geometry.setLocalScale(radius / FLASH_RADIUS_START);

            float alpha = 1f - progress;
            Material mat = imp.geometry.getMaterial();
            mat.setColor("Color", new ColorRGBA(
                imp.color.r, imp.color.g, imp.color.b, alpha));
        }
    }

    /** Couleur du flash selon le type de bullet. */
    private static ColorRGBA colorForBulletType(int type) {
        return switch (type) {
            case  0 -> new ColorRGBA(0.5f, 0.8f, 1.0f, 1f);  // Plasma : bleu clair
            case  1 -> new ColorRGBA(1.0f, 0.9f, 0.6f, 1f);  // Machine Gun : jaune
            case  2 -> new ColorRGBA(1.0f, 0.6f, 0.2f, 1f);  // Rocket : orange
            case  7 -> new ColorRGBA(1.0f, 0.9f, 0.4f, 1f);  // Shotgun : jaune dore
            case  8 -> new ColorRGBA(1.0f, 0.7f, 0.3f, 1f);  // Grenade : orange
            case  9 -> new ColorRGBA(0.8f, 0.4f, 1.0f, 1f);  // Blaster : violet
            case 10 -> new ColorRGBA(1.0f, 0.5f, 1.0f, 1f);  // Assault Lazer : magenta
            case 12 -> new ColorRGBA(0.8f, 0.4f, 1.0f, 1f);  // MindZap : violet
            case 13 -> new ColorRGBA(0.5f, 0.7f, 1.0f, 1f);  // MegaPlasma : bleu
            case 14 -> new ColorRGBA(1.0f, 0.4f, 0.4f, 1f);  // Lazer : rouge
            case 15 -> new ColorRGBA(1.0f, 0.7f, 0.3f, 1f);  // Mine : orange
            default -> new ColorRGBA(1.0f, 1.0f, 1.0f, 1f);
        };
    }
}
