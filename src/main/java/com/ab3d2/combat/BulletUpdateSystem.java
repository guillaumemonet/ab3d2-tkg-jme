package com.ab3d2.combat;

import com.ab3d2.core.ai.AlienRuntimeState;
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

/**
 * Systeme qui met a jour chaque frame les projectiles joueur actifs.
 *
 * <p>Correspondance ASM : routine <code>ItsABullet</code> (newanims.s).
 * Phase 1.C (session 85) : implemente UNIQUEMENT le mouvement physique
 * (position, velocite, gravite, lifetime). <b>Pas de collision</b> (mur,
 * sol, plafond, ennemis) pour l'instant — phases 1.D / 1.E.</p>
 *
 * <p>Extrait simplifie de <code>ItsABullet</code> (newanims.s, l.~1340) :</p>
 * <pre>
 *   ; Calcul de la nouvelle position par interpolation temporelle
 *   move.w (a0), d1
 *   lea    (a1, d1.w*8), a1
 *   move.l (a1), d2                ; oldx
 *   move.l ShotT_VelocityX_w(a0), d3
 *   move.w d3, d4
 *   swap   d3
 *   move.w Anim_TempFrames_w, d5
 *   muls   d5, d3                  ; vel * dt (frames Amiga)
 *   mulu   d5, d4
 *   swap   d3
 *   clr.w  d3
 *   add.l  d4, d3
 *   add.l  d3, d2                  ; newx = oldx + vel*dt
 *   move.l d2, newx
 *
 *   ; Gravite
 *   move.l BulT_Gravity_l(a6), d5
 *   beq.s  nograv
 *   muls   Anim_TempFrames_w, d5
 *   add.l  d5, d3                  ; velY += gravity * dt
 *
 *   ; Lifetime
 *   move.w Anim_TempFrames_w, d2
 *   add.w  d2, ShotT_Lifetime_w(a0)
 *   cmp.w  BulT_Lifetime_l+2(a6), d2
 *   bge    timeout
 * </pre>
 *
 * <p>Placeholder visuel : chaque bullet active est une sphere coloree (couleur
 * selon le bulletType). Phase 1.D passera aux vrais sprites / billboards.</p>
 */
public class BulletUpdateSystem extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(BulletUpdateSystem.class);

    /**
     * Rayon visuel de la sphere placeholder (unites JME).
     * Les vraies bullets ASM ont <code>ShotT_Size_b</code> qui controle la
     * taille mais c'est utilise au render (2D sprite), pas pour la physique.
     */
    private static final float BULLET_DISPLAY_RADIUS = 0.05f;

    /**
     * Nombre max de frames Amiga pendant lesquelles une bullet avec
     * <code>lifetime &lt; 0</code> (infini dans l'ASM) reste en vie dans notre
     * port. On n'a pas encore les collisions murs, donc si on laisse les
     * bullets voler a l'infini, elles traversent le niveau indefiniment.
     * 500 frames = 20 secondes @ 25Hz.
     *
     * <p>En session 1.D (collisions), on enlevera ce fallback et les vraies
     * bullets infinies seront tuees par impact mural.</p>
     */
    private static final float PHASE_1C_INFINITE_FALLBACK_FRAMES = 500f;

    private final PlayerShotPool shotPool;
    private final GlfDatabase    glf;

    private SimpleApplication sa;
    private Node              bulletRoot;
    /** Optionnel : raycaster pour detecter la collision mur (session 87). */
    private WorldRaycaster    raycaster;
    /** Optionnel : systeme d'effet d'impact (flash au point de collision). */
    private ImpactEffectSystem impactSystem;
    /** Optionnel : detecteur de hit alien (session 113 phase 2.C). */
    private AlienHitDetector   alienHitDetector;

    public BulletUpdateSystem(PlayerShotPool shotPool, GlfDatabase glf) {
        this.shotPool = shotPool;
        this.glf      = glf;
    }

    /**
     * Installe le raycaster pour que les bullets meurent en touchant les
     * murs. Sans raycaster, les bullets traversent tout (comportement de
     * la phase 1.C, fallback timeout).
     */
    public void setRaycaster(WorldRaycaster raycaster) {
        this.raycaster = raycaster;
    }

    /** Installe le systeme d'effets d'impact (optionnel). */
    public void setImpactSystem(ImpactEffectSystem impactSystem) {
        this.impactSystem = impactSystem;
    }

    /**
     * Installe le detecteur de hit alien (session 113 phase 2.C). Sans ce
     * detecteur, les bullets traversent les aliens sans degats.
     */
    public void setAlienHitDetector(AlienHitDetector detector) {
        this.alienHitDetector = detector;
    }

    // ── AppState lifecycle ───────────────────────────────────────────────

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa = (SimpleApplication) app;

        // Node parent pour toutes les geometries de bullets (facilite le cleanup)
        bulletRoot = new Node("playerBullets");
        sa.getRootNode().attachChild(bulletRoot);

        log.info("BulletUpdateSystem init ({} slots max)", PlayerShotPool.NUM_PLR_SHOT_DATA);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        if (bulletRoot != null) {
            bulletRoot.removeFromParent();
            bulletRoot = null;
        }
        shotPool.releaseAll();
    }

    // ── Update (= ItsABullet) ────────────────────────────────────────────

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;

        float framesAmigaDelta = tpf * PlayerCombatState.TICKS_PER_SECOND;

        for (PlayerShot shot : shotPool.getSlots()) {
            if (shot.isFree()) continue;

            // Skip les bullets gerees par PhysicsBulletSystem (grenade, mine, splutch).
            // Chaque bullet a un seul handler responsable du mouvement.
            if (shot.handler != PlayerShot.HANDLER_SIMPLE) continue;

            // Creer la geometry visuelle si pas encore fait (premier update
            // apres spawn). On le fait ici plutot que dans PlayerShootSystem
            // pour keep separation des responsabilites (shoot = logic, bullet
            // = mouvement + rendu).
            if (shot.geometry == null) {
                shot.geometry = createBulletGeometry(shot);
                bulletRoot.attachChild(shot.geometry);
            }

            // ASM: newx = oldx + vel * Anim_TempFrames_w
            // En JME : dpos = vel * tpf (velocite deja en unites/seconde)
            // On garde la position precedente pour faire le raycast du segment
            // (detection de collision mur entre deux frames).
            float oldX = shot.posX;
            float oldY = shot.posY;
            float oldZ = shot.posZ;
            shot.posX += shot.velX * tpf;
            shot.posY += shot.velY * tpf;
            shot.posZ += shot.velZ * tpf;

            // Session 113 phase 2.C : test collision avec aliens vivants.
            // Fait AVANT le test mur car on veut que la bullet touche
            // l'alien, pas le mur derriere lui.
            if (alienHitDetector != null) {
                AlienRuntimeState hitAlien = alienHitDetector.findHitByPoint(
                    shot.posX, shot.posY, shot.posZ);
                if (hitAlien != null) {
                    alienHitDetector.applyDamage(hitAlien, shot.power);
                    if (impactSystem != null) {
                        impactSystem.spawnImpact(
                            new Vector3f(shot.posX, shot.posY, shot.posZ),
                            shot.bulletType);
                    }
                    killBullet(shot);
                    continue;
                }
            }

            // Collision test : raycast de (oldPos) a (newPos). Si on touche
            // un mur/sol/plafond, la bullet meurt a l'impact.
            if (raycaster != null) {
                Vector3f oldPos = new Vector3f(oldX, oldY, oldZ);
                Vector3f newPos = new Vector3f(shot.posX, shot.posY, shot.posZ);
                float stepDistance = oldPos.distance(newPos);
                if (stepDistance > 0.001f) {
                    Vector3f dir = newPos.subtract(oldPos).normalizeLocal();
                    WorldRaycaster.RayHit hit = raycaster.castRay(oldPos, dir, stepDistance);
                    if (hit.hit) {
                        // Impact sur un mur : positionne la bullet au point
                        // d'impact et la tue.
                        shot.posX = hit.impactPoint.x;
                        shot.posY = hit.impactPoint.y;
                        shot.posZ = hit.impactPoint.z;
                        if (impactSystem != null) {
                            impactSystem.spawnImpact(hit.impactPoint, shot.bulletType);
                        }
                        killBullet(shot);
                        continue;
                    }
                }
            }

            // ASM: velY += gravity * Anim_TempFrames_w (quand gravity != 0)
            // La gravity dans l'ASM est en "unites Amiga par frame^2".
            // On convertit : gravJme = gravAmiga * TICKS^2 / SCALE.
            // Pour l'instant on applique simplement velY -= gravity * tpf *
            // factor_empirique car la magnitude depend de l'unite precise.
            if (shot.gravity != 0) {
                // L'ASM : gravity value typique 20 (grenade) ou 40 (splutch1).
                // Apres conversion : gravJme = gravity * 25 / 128 unit/s/frame.
                // On applique sur la velocite Y (vers le bas = negatif en JME).
                float gravJme = shot.gravity * PlayerCombatState.TICKS_PER_SECOND
                                / 128f;
                shot.velY -= gravJme * tpf * PlayerCombatState.TICKS_PER_SECOND;
            }

            // ASM: accumulate Lifetime ; timeout si depasse BulT_Lifetime_l
            shot.lifetime += framesAmigaDelta;
            BulletDef def = glf.getBulletDef(shot.bulletType);
            int bulletLifetime = def.lifetime();
            boolean timeout;
            if (bulletLifetime < 0) {
                // ASM: "infini" — mais avec une gestion differente :
                // en phase 1.C on ne peut pas supporter des vies vraiment
                // infinies parce qu'il n'y a pas encore de collision mur.
                // Fallback : 500 frames Amiga (~20 secondes).
                timeout = shot.lifetime >= PHASE_1C_INFINITE_FALLBACK_FRAMES;
            } else {
                timeout = shot.lifetime >= bulletLifetime;
            }

            if (timeout) {
                killBullet(shot);
                continue;
            }

            // Mise a jour visuelle de la geometry
            if (shot.geometry != null) {
                shot.geometry.setLocalTranslation(shot.posX, shot.posY, shot.posZ);
            }
        }
    }

    /**
     * Libere le slot + detache la geometry de la scene.
     */
    private void killBullet(PlayerShot shot) {
        if (shot.geometry != null) {
            shot.geometry.removeFromParent();
        }
        shot.release();
    }

    // ── Rendu placeholder ────────────────────────────────────────────────

    /**
     * Cree une sphere coloree en placeholder pour la bullet. La couleur
     * depend du bulletType (distingue visuellement les differents projectiles).
     *
     * <p>A remplacer en phase 1.D par un vrai sprite billboard base sur les
     * ressources <code>bigbullet.dat</code> / <code>glare.dat</code> etc.</p>
     */
    private Geometry createBulletGeometry(PlayerShot shot) {
        Sphere sphere = new Sphere(8, 12, BULLET_DISPLAY_RADIUS);
        Geometry geo = new Geometry("bullet_" + shot.bulletType, sphere);

        Material mat = new Material(sa.getAssetManager(),
            "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", colorForBulletType(shot.bulletType));
        // Additive pour effet "energie" sur les projectiles type glare
        BulletDef def = glf.getBulletDef(shot.bulletType);
        if (def.graphicType() == 2) {
            // GraphicType=2 : additive (plasma, lazer, megaplasma, explosion)
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Additive);
            geo.setQueueBucket(RenderQueue.Bucket.Transparent);
        }
        geo.setMaterial(mat);
        geo.setLocalTranslation(shot.posX, shot.posY, shot.posZ);
        return geo;
    }

    /**
     * Couleurs arbitraires par bullet type pour distinguer les projectiles
     * visuellement en debug. A remplacer par les vrais sprites en phase 1.D.
     */
    private static ColorRGBA colorForBulletType(int type) {
        return switch (type) {
            case  0 -> new ColorRGBA(0.3f, 0.6f, 1.0f, 1f);  // Plasma Bolt : bleu
            case  1 -> new ColorRGBA(1.0f, 0.9f, 0.5f, 1f);  // Machine Gun : jaune pale
            case  2 -> new ColorRGBA(1.0f, 0.4f, 0.1f, 1f);  // Rocket : orange
            case  7 -> new ColorRGBA(1.0f, 0.9f, 0.3f, 1f);  // Shotgun : jaune doré
            case  8 -> new ColorRGBA(0.4f, 0.8f, 0.3f, 1f);  // Grenade : vert
            case  9 -> new ColorRGBA(0.6f, 0.2f, 1.0f, 1f);  // Blaster : violet
            case 10 -> new ColorRGBA(0.9f, 0.3f, 0.9f, 1f);  // Assault Lazer : magenta
            case 13 -> new ColorRGBA(0.2f, 0.4f, 1.0f, 1f);  // MegaPlasma : bleu electrique
            case 14 -> new ColorRGBA(1.0f, 0.2f, 0.2f, 1f);  // Lazer : rouge
            case 15 -> new ColorRGBA(0.6f, 0.6f, 0.6f, 1f);  // Mine : gris
            default -> ColorRGBA.White;
        };
    }
}
