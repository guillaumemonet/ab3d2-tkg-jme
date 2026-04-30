package com.ab3d2.combat;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.SphereCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
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
 * Systeme de physique pour les bullets soumises a la gravite et/ou aux rebonds.
 *
 * <p>Equivalent hybride des routines ASM MoveObject + partie physique de
 * ItsABullet dans newanims.s. Utilise Bullet Physics (Minie) pour gerer
 * proprement les collisions murs/sol avec rebonds, au lieu de reimplementer
 * a la main l'algorithme ASM.</p>
 *
 * <p>Pour quelles bullets utiliser ce systeme plutot que BulletUpdateSystem ?
 * Les bullets avec gravite ou rebond : Grenade, Mine, Splutch1. Les bullets
 * rapides sans gravite (Plasma, Rocket, Lazer, Blaster) restent sur le
 * BulletUpdateSystem custom qui est plus performant (raycast simple futur).</p>
 *
 * <p><b>Simplifications vs ASM :</b></p>
 * <ul>
 *   <li>Pas de distinction bounceHoriz/bounceVert (Bullet gere les deux ensemble
 *       via le restitution coefficient)</li>
 *   <li>Pas de reduction de velocite sur rebond (dans l'ASM : asr.l #1 divise
 *       par 2). On utilise un restitution=0.6 qui donne un feeling similaire.</li>
 *   <li>Lifetime gere cote Java via notre timer (pas Bullet)</li>
 * </ul>
 */
public class PhysicsBulletSystem extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(PhysicsBulletSystem.class);

    /** Rayon visuel + collision de la sphere physique. */
    private static final float BULLET_RADIUS = 0.08f;

    /** Masse de la bullet (petite pour pas peturber les objets statiques). */
    private static final float BULLET_MASS = 0.1f;

    /** Restitution coefficient (0 = pas de rebond, 1 = rebond parfait). */
    private static final float BULLET_RESTITUTION = 0.6f;

    /** Friction (sur les rebonds uniquement). */
    private static final float BULLET_FRICTION = 0.3f;

    /**
     * Gravite appliquee aux bullets physiques, en m/s^2.
     *
     * <p>Apres calibration session 86bis, les grenades partent a
     * ~40 JME/s (4 * speed 2^5 * 0.1). Avec une gravite JME standard
     * -9.8 m/s^2, une grenade lancee a 45 degres retombe apres ~4 sec
     * et parcourt ~16m. C'est jouable et visible.</p>
     *
     * <p>Si la grenade retombe trop vite ou trop lentement, ajuster cette
     * valeur (gardee comme gravity JME standard pour l'instant).</p>
     */
    private static final float PHYSICS_GRAVITY = -9.8f;

    private final PlayerShotPool  shotPool;
    private final GlfDatabase     glf;
    private final BulletAppState  bulletAppState;

    private SimpleApplication sa;
    private Node              rootNode;

    /** Refs internes pour tracker le lien PlayerShot -&gt; RigidBody. */
    private final List<PhysicsShot> trackedShots = new ArrayList<>();

    private static class PhysicsShot {
        final PlayerShot         shot;
        final Geometry           geometry;
        final RigidBodyControl   rbc;

        PhysicsShot(PlayerShot s, Geometry g, RigidBodyControl r) {
            this.shot = s; this.geometry = g; this.rbc = r;
        }
    }

    public PhysicsBulletSystem(PlayerShotPool shotPool,
                                GlfDatabase glf,
                                BulletAppState bulletAppState) {
        this.shotPool       = shotPool;
        this.glf            = glf;
        this.bulletAppState = bulletAppState;
    }

    // ── Classement : qui gere quoi ? ──────────────────────────────────────

    /**
     * Indique si une bullet doit etre geree par ce systeme (physique complete)
     * plutot que par {@link BulletUpdateSystem} (mouvement simple).
     *
     * <p>Critere : la bullet a de la gravite OU elle peut rebondir.</p>
     */
    public static boolean shouldUsePhysics(BulletDef def) {
        return def.gravity() != 0
            || def.bounceHoriz() != 0
            || def.bounceVert()  != 0;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa       = (SimpleApplication) app;
        this.rootNode = new Node("physicsBullets");
        sa.getRootNode().attachChild(rootNode);
        log.info("PhysicsBulletSystem init");
    }

    @Override
    public void cleanup() {
        super.cleanup();
        // Retire toutes les bullets physiques du PhysicsSpace
        if (bulletAppState != null && bulletAppState.getPhysicsSpace() != null) {
            for (PhysicsShot ps : trackedShots) {
                bulletAppState.getPhysicsSpace().remove(ps.rbc);
            }
        }
        trackedShots.clear();
        if (rootNode != null) {
            rootNode.removeFromParent();
            rootNode = null;
        }
    }

    // ── Spawn ─────────────────────────────────────────────────────────────

    /**
     * Spawn une bullet physique (gravite + rebonds gere par Bullet).
     * Doit etre appele a la place du spawn classique quand
     * {@link #shouldUsePhysics(BulletDef)} retourne true.
     */
    public void spawnPhysicsBullet(PlayerShot shot) {
        if (sa == null || bulletAppState == null) return;
        PhysicsSpace ps = bulletAppState.getPhysicsSpace();
        if (ps == null) return;

        BulletDef def = glf.getBulletDef(shot.bulletType);

        // Geometry visuelle (sphere coloree)
        Sphere sphere = new Sphere(10, 12, BULLET_RADIUS);
        Geometry geo = new Geometry("physBullet_" + shot.bulletType, sphere);
        Material mat = new Material(sa.getAssetManager(),
            "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", colorForBulletType(shot.bulletType));
        if (def.graphicType() == 2) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Additive);
            geo.setQueueBucket(RenderQueue.Bucket.Transparent);
        }
        geo.setMaterial(mat);
        geo.setLocalTranslation(shot.posX, shot.posY, shot.posZ);
        rootNode.attachChild(geo);

        // Corps rigide Bullet
        SphereCollisionShape shape = new SphereCollisionShape(BULLET_RADIUS);
        RigidBodyControl rbc = new RigidBodyControl(shape, BULLET_MASS);
        rbc.setRestitution(BULLET_RESTITUTION);
        rbc.setFriction(BULLET_FRICTION);
        rbc.setPhysicsLocation(new Vector3f(shot.posX, shot.posY, shot.posZ));

        // Continuous Collision Detection : evite le tunneling (grenade qui
        // traverse un mur fin a haute vitesse). Seuil declencheur = vitesse
        // * tpf > radius. A 40 JME/s et tpf=16ms, on parcourt 0.64 unites
        // alors que le rayon est 0.08 -> CCD necessaire. Le sweep test va
        // de la position actuelle a la position+vel*tpf.
        rbc.setCcdMotionThreshold(BULLET_RADIUS * 0.5f);
        rbc.setCcdSweptSphereRadius(BULLET_RADIUS);

        // Gravite fixe (m/s^2). On n'utilise plus BulT_Gravity_l directement
        // parce que le fixed-point ASM ne mappe pas bien sur les m/s^2 JME.
        // Protect la gravite pour qu'elle ne soit pas ecrasee par la gravite
        // par defaut du PhysicsSpace au moment du add() (warning Minie sinon).
        rbc.setProtectGravity(true);
        rbc.setGravity(new Vector3f(0, PHYSICS_GRAVITY, 0));

        geo.addControl(rbc);
        ps.add(rbc);

        // setLinearVelocity APRES l'ajout au PhysicsSpace : la velocite est
        // remise a zero si on le fait avant (le body est alors detache).
        rbc.setLinearVelocity(new Vector3f(shot.velX, shot.velY, shot.velZ));

        // Garde la ref pour pouvoir la cleanup plus tard
        shot.geometry = geo;
        trackedShots.add(new PhysicsShot(shot, geo, rbc));

        log.debug("Physics bullet spawned: type={} grav={} vel=({},{},{})",
            shot.bulletType, PHYSICS_GRAVITY, shot.velX, shot.velY, shot.velZ);
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        float framesAmigaDelta = tpf * PlayerCombatState.TICKS_PER_SECOND;

        Iterator<PhysicsShot> it = trackedShots.iterator();
        while (it.hasNext()) {
            PhysicsShot ps = it.next();
            PlayerShot  shot = ps.shot;

            // La position logique suit la position physique
            Vector3f physPos = ps.rbc.getPhysicsLocation();
            shot.posX = physPos.x;
            shot.posY = physPos.y;
            shot.posZ = physPos.z;
            // velocity pour coherence si besoin
            Vector3f physVel = ps.rbc.getLinearVelocity();
            shot.velX = physVel.x;
            shot.velY = physVel.y;
            shot.velZ = physVel.z;

            // Lifetime ASM : accumule en frames Amiga
            shot.lifetime += framesAmigaDelta;
            BulletDef def = glf.getBulletDef(shot.bulletType);
            int lifetimeDef = def.lifetime();
            boolean expired;
            if (lifetimeDef < 0) {
                // infinite dans l'ASM -> en JME on laisse 30 secondes max
                expired = shot.lifetime >= 750f;  // 750 frames Amiga = 30 sec
            } else {
                expired = shot.lifetime >= lifetimeDef;
            }

            if (expired) {
                killPhysicsBullet(ps);
                it.remove();
            }
        }
    }

    private void killPhysicsBullet(PhysicsShot ps) {
        if (bulletAppState != null && bulletAppState.getPhysicsSpace() != null) {
            bulletAppState.getPhysicsSpace().remove(ps.rbc);
        }
        ps.geometry.removeFromParent();
        ps.shot.release();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ColorRGBA colorForBulletType(int type) {
        return switch (type) {
            case  3 -> new ColorRGBA(0.8f, 0.3f, 0.2f, 1f);  // Splutch1 : rouge sang
            case  8 -> new ColorRGBA(0.4f, 0.8f, 0.3f, 1f);  // Grenade : vert
            case 15 -> new ColorRGBA(0.6f, 0.6f, 0.6f, 1f);  // Mine : gris
            default -> ColorRGBA.White;
        };
    }
}
