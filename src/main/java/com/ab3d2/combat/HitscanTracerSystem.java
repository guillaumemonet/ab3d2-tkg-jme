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
import com.jme3.scene.shape.Line;
import com.jme3.scene.shape.Cylinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Systeme d'affichage des traceurs de bullets "hitscan".
 *
 * <p>Les bullets flagguees <code>BulT_IsHitScan_l != 0</code> dans l'ASM
 * (Shotgun Round, Machine Gun Bullet, MindZap) ne sont pas des projectiles
 * physiques : elles touchent instantanement via <code>plr1_HitscanSucceded</code>
 * ou <code>plr1_HitscanFailed</code>. Visuellement, l'ASM dessine un flash
 * muzzle + un petit effet a l'impact (pas de traceur de trajectoire).</p>
 *
 * <p>Dans notre port JME, on ajoute un <b>traceur visuel</b> (un rayon qui
 * flash brievement) pour donner du feedback visuel a chaque tir. C'est une
 * amelioration par rapport a l'ASM qui rend le tir plus lisible en 3D.</p>
 *
 * <p>En phase 1.C on ne fait pas de raycast reel : le traceur va jusqu'a une
 * distance fixe (ou jusqu'a un ennemi touche, phase 1.D). Le rayon apparait\n * pendant TRACER_LIFETIME_SEC secondes puis disparait.</p>
 */
public class HitscanTracerSystem extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(HitscanTracerSystem.class);

    /** Duree d'affichage d'un traceur en secondes. */
    private static final float TRACER_LIFETIME_SEC = 0.08f;

    /**
     * Distance maximale du traceur en unites JME si aucun mur n'est touche.
     * Quand un {@link WorldRaycaster} est defini, on s'arrete a l'impact.
     */
    private static final float TRACER_MAX_DISTANCE = 50f;

    private static final float TRACER_THICKNESS = 0.03f;

    private SimpleApplication sa;
    private Node              tracerRoot;
    /** Optionnel : raycaster pour s'arreter sur les murs (session 87). */
    private WorldRaycaster    raycaster;
    /** Optionnel : detecteur de hit alien (session 113 phase 2.C). */
    private AlienHitDetector  alienHitDetector;
    private final List<ActiveTracer> activeTracers = new ArrayList<>();

    /** Un traceur actif en cours d'affichage. */
    private static class ActiveTracer {
        Geometry geometry;
        float    remainingTime;

        ActiveTracer(Geometry geo, float lifetime) {
            this.geometry = geo;
            this.remainingTime = lifetime;
        }
    }

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa = (SimpleApplication) app;
        tracerRoot = new Node("hitscanTracers");
        sa.getRootNode().attachChild(tracerRoot);
        log.info("HitscanTracerSystem init");
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (tracerRoot != null) {
            tracerRoot.removeFromParent();
            tracerRoot = null;
        }
        activeTracers.clear();
    }

    /**
     * Installe le raycaster pour que les traceurs s'arretent sur les murs.
     * Optionnel : sans raycaster, les traceurs vont jusqu'a {@link #TRACER_MAX_DISTANCE}.
     */
    public void setRaycaster(WorldRaycaster raycaster) {
        this.raycaster = raycaster;
    }

    /**
     * Installe le detecteur de hit alien (session 113 phase 2.C). Sans ce
     * detecteur, les hitscan traversent les aliens.
     */
    public void setAlienHitDetector(AlienHitDetector detector) {
        this.alienHitDetector = detector;
    }

    /**
     * Spawn un traceur visible du point de depart vers une direction donnee,
     * pour un bulletType donne (determine la couleur).
     *
     * @param origin    point de depart (position muzzle)
     * @param direction direction du tir (doit etre normalisee)
     * @param bulletType index dans BulletDefs (pour la couleur)
     * @param damage     degats a infliger si on touche un alien (0 = pas de damage)
     * @return le point d'impact (si hit), ou origin+direction*MAX_DISTANCE sinon
     */
    public Vector3f spawnTracer(Vector3f origin, Vector3f direction,
                                 int bulletType, int damage) {
        if (sa == null) return origin;  // pas encore initialise

        // Determine la distance d'arret en prenant le minimum entre :
        //   - distance jusqu'au premier alien touche (= cible)
        //   - distance jusqu'au premier mur touche
        //   - TRACER_MAX_DISTANCE
        // Le tir s'arrete sur le plus proche des trois.
        Vector3f end;
        AlienHitDetector.AlienHit alienHit = (alienHitDetector != null)
            ? alienHitDetector.findHitByRay(origin, direction, TRACER_MAX_DISTANCE)
            : AlienHitDetector.AlienHit.miss();
        WorldRaycaster.RayHit wallHit = (raycaster != null)
            ? raycaster.castRay(origin, direction, TRACER_MAX_DISTANCE)
            : WorldRaycaster.RayHit.miss();

        if (alienHit.isHit() && (!wallHit.hit || alienHit.distance() < wallHit.distance)) {
            // L'alien est plus proche que le mur (ou pas de mur) : on inflige
            // les degats et le traceur s'arrete sur l'alien.
            if (damage > 0) {
                alienHitDetector.applyDamage(alienHit.alien(), damage);
            }
            end = alienHit.impact();
        } else if (wallHit.hit) {
            end = wallHit.impactPoint;
        } else {
            end = origin.add(direction.mult(TRACER_MAX_DISTANCE));
        }

        Geometry geo = createTracerGeometry(origin, end, bulletType);
        tracerRoot.attachChild(geo);
        activeTracers.add(new ActiveTracer(geo, TRACER_LIFETIME_SEC));
        return end;
    }

    /**
     * Surcharge legacy sans damage (compat). Equivaut a {@code spawnTracer(origin, direction, bulletType, 0)}.
     * @deprecated Utiliser la version a 4 parametres pour appliquer les degats.
     */
    @Deprecated
    public Vector3f spawnTracer(Vector3f origin, Vector3f direction, int bulletType) {
        return spawnTracer(origin, direction, bulletType, 0);
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        Iterator<ActiveTracer> it = activeTracers.iterator();
        while (it.hasNext()) {
            ActiveTracer t = it.next();
            t.remainingTime -= tpf;
            if (t.remainingTime <= 0f) {
                t.geometry.removeFromParent();
                it.remove();
            } else {
                // Fade out progressif (alpha)
                float alpha = t.remainingTime / TRACER_LIFETIME_SEC;
                Material mat = t.geometry.getMaterial();
                ColorRGBA col = (ColorRGBA) mat.getParam("Color").getValue();
                mat.setColor("Color", new ColorRGBA(col.r, col.g, col.b, alpha));
            }
        }
    }

    /**
     * Cree un cylindre fin entre origin et end pour representer le traceur.
     * Cylindre plutot qu'une Line pour avoir une epaisseur visible (les lignes
     * JME sont en pixel, pas tres visibles).
     */
    private Geometry createTracerGeometry(Vector3f origin, Vector3f end, int bulletType) {
        float length = origin.distance(end);
        Vector3f midpoint = origin.add(end).multLocal(0.5f);
        Vector3f direction = end.subtract(origin).normalizeLocal();

        Cylinder cyl = new Cylinder(6, 8, TRACER_THICKNESS, length, true);
        Geometry geo = new Geometry("tracer_" + bulletType, cyl);
        geo.setLocalTranslation(midpoint);

        // Oriente le cylindre le long de la direction (par defaut il est sur Z)
        geo.lookAt(end, Vector3f.UNIT_Y);

        Material mat = new Material(sa.getAssetManager(),
            "Common/MatDefs/Misc/Unshaded.j3md");
        ColorRGBA color = colorForBulletType(bulletType);
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setBlendMode(BlendMode.Additive);
        geo.setQueueBucket(RenderQueue.Bucket.Transparent);
        geo.setMaterial(mat);
        return geo;
    }

    private static ColorRGBA colorForBulletType(int type) {
        return switch (type) {
            case  1 -> new ColorRGBA(1.0f, 0.95f, 0.5f, 1f);  // Machine Gun : jaune
            case  7 -> new ColorRGBA(1.0f, 0.85f, 0.3f, 1f);  // Shotgun : jaune dore
            case 12 -> new ColorRGBA(0.6f, 0.3f, 1.0f, 1f);   // MindZap : violet
            default -> new ColorRGBA(1.0f, 1.0f, 0.9f, 1f);   // fallback blanc
        };
    }
}
