package com.ab3d2.world;

import com.ab3d2.combat.PlayerCombatState;
import com.ab3d2.combat.PlayerHealthState;
import com.ab3d2.hud.HudState;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Systeme de ramassage des items au sol (collectables).
 *
 * <h2>Logique</h2>
 *
 * <p>A chaque frame, balaye tous les {@code spriteNode} dans le {@code itemsNode}
 * du niveau. Pour chaque item dont {@code typeId == 1} (objet) et
 * {@code behaviour == 0} (COLLECTABLE) :</p>
 *
 * <ol>
 *   <li>Verifier que le joueur est dans la <b>meme zone</b> que l'item.</li>
 *   <li>Verifier la <b>distance horizontale (XZ)</b> &lt; {@code colRadius/SCALE}.</li>
 *   <li>Verifier la <b>distance verticale (Y)</b> &lt; {@code colHeight/SCALE}.</li>
 *   <li>Si les 3 conditions sont vraies, appliquer la collecte :
 *     <ul>
 *       <li>Lire les tables {@code ammoGive} et {@code gunGive} (chargees une fois\n     *           depuis {@code definitions.json}).</li>
 *       <li>Merger dans le PlayerHealth/PlayerCombat.</li>
 *       <li>Detacher le sprite du itemsNode (= disparait visuellement).</li>
 *       <li>Pousser un message dans le HUD (&quot;Got Medikit&quot;, etc.).</li>
 *       <li>TODO (session future) : jouer le son SFX de l'objet (sample 4 = collect).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Reference ASM : {@code newaliencontrol.s::Collectable} (ligne ~1280) et
 * {@code Plr1_CheckObjectCollide}/{@code Plr1_CollectItem} (ligne ~1700).</p>
 *
 * @since session 112
 */
public class PickupSystem {

    private static final Logger log = LoggerFactory.getLogger(PickupSystem.class);

    /** Conversion Amiga -&gt; JME (32 unites Amiga = 1 unite JME). */
    private static final float SCALE = 32f;

    /**
     * Marge ajoutee a colRadius pour la collision horizontale.
     *
     * <p>L'ASM teste {@code distance &lt; colRadius} en unites Amiga, mais le centre
     * du sprite n'est pas exactement le centre du joueur (qui est au-dessus du sol).
     * Une petite marge permet de ramasser plus naturellement quand on passe a cote.</p>
     */
    private static final float PICKUP_RADIUS_MARGIN = 0.3f;  // 30 cm en unites JME

    /**
     * Description statique d'une definition d'objet (chargee depuis definitions.json).
     *
     * @param ammoGive [Health, JetpackFuel, Ammo[0..19]] (22 ints)
     * @param gunGive  [Shield, JetPack, Weapons[0..9]] (12 ints)
     */
    public record ObjDefStatic(
        int   index,
        String name,
        int   behaviour,    // 0=COLL 1=ACTI 2=DEST 3=DECO
        int   gfxType,
        int   colRadius,    // unites Amiga
        int   colHeight,    // unites Amiga
        int   sfx,          // index dans GLFT_SFXFilenames_l (-1 = aucun)
        int[] ammoGive,
        int[] gunGive
    ) {
        public boolean isCollectable() { return behaviour == 0; }
    }

    private final Node              itemsNode;
    private final ZoneTracker       zoneTracker;
    private final PlayerCombatState combat;
    private final PlayerHealthState health;
    private final HudState          hud;
    private final Map<Integer,ObjDefStatic> defs;

    /** Compteur d'items collectes pour stats (debug). */
    private int collectedCount = 0;

    public PickupSystem(Node itemsNode, ZoneTracker zoneTracker,
                        PlayerCombatState combat, PlayerHealthState health,
                        HudState hud, Path assetsRoot) {
        this.itemsNode   = itemsNode;
        this.zoneTracker = zoneTracker;
        this.combat      = combat;
        this.health      = health;
        this.hud         = hud;
        this.defs        = loadDefs(assetsRoot);
        log.info("PickupSystem initialized : {} object defs loaded, {} items in scene",
            defs.size(), itemsNode != null ? itemsNode.getQuantity() : 0);
    }

    /**
     * Met a jour le systeme : detecte et applique les collectes.
     *
     * @param playerPos position monde JME du joueur
     */
    public void update(Vector3f playerPos) {
        if (itemsNode == null || zoneTracker == null) return;
        int playerZone = zoneTracker.getCurrentZoneId();
        if (playerZone < 0) return;

        // Iteration en sens inverse car detachChild modifie la liste enfants.
        for (int i = itemsNode.getQuantity() - 1; i >= 0; i--) {
            Spatial s = itemsNode.getChild(i);
            if (s == null) continue;

            // userData est setee dans LevelSceneBuilder.addItems
            Integer typeId    = s.getUserData("typeId");
            Integer behaviour = s.getUserData("behaviour");
            Integer zoneId    = s.getUserData("zoneId");
            Integer defIndex  = s.getUserData("defIndex");

            if (typeId == null || behaviour == null || zoneId == null || defIndex == null) continue;
            if (typeId != 1)         continue;  // pas un objet (alien/joueur)
            if (behaviour != 0)      continue;  // pas un COLLECTABLE
            if (zoneId != playerZone) continue;  // pas dans la meme zone

            ObjDefStatic def = defs.get(defIndex);
            if (def == null) continue;

            // Test collision horizontale (XZ).
            // colRadius est en unites Amiga ; on convertit en JME et on
            // ajoute une petite marge pour faciliter le ramassage en passant.
            Vector3f itemPos = s.getWorldTranslation();
            float dx = itemPos.x - playerPos.x;
            float dz = itemPos.z - playerPos.z;
            float distXZ = (float) Math.sqrt(dx * dx + dz * dz);
            float radiusJme = def.colRadius() / SCALE + PICKUP_RADIUS_MARGIN;
            if (distXZ > radiusJme) continue;

            // Test collision verticale (Y).
            // colHeight en Amiga = demi-hauteur du cylindre de collision.
            // On compare la difference entre Y joueur et Y item au demi-hauteur
            // de l'item PLUS une partie raisonnable de la hauteur joueur.
            float dy = Math.abs(itemPos.y - playerPos.y);
            float heightJme = def.colHeight() / SCALE + 1.5f;  // 1.5 JME = ~hauteur joueur
            if (dy > heightJme) continue;

            // Toutes les conditions OK -> COLLECTE !
            collect(s, def);
        }
    }

    /**
     * Applique la collecte d'un item :
     * <ol>
     *   <li>Merge ammoGive et gunGive dans Player[Health/Combat]State.</li>
     *   <li>Detache le sprite du itemsNode.</li>
     *   <li>Pousse un message HUD descriptif.</li>
     * </ol>
     *
     * <p>Reference ASM : {@code Plr1_CollectItem} et {@code Game_AddToInventory}
     * dans {@code newaliencontrol.s} et {@code game.c}.</p>
     */
    private void collect(Spatial sprite, ObjDefStatic def) {
        StringBuilder gained = new StringBuilder();

        // ── ammoGive : [Health, JetpackFuel, Ammo[0..19]] ───────────────────
        int[] ag = def.ammoGive();
        if (ag != null && ag.length >= 2) {
            // Health
            if (ag[0] > 0) {
                int added = health.addHealth(ag[0]);
                if (added > 0) gained.append(" +").append(added).append(" HP");
            }
            // Jetpack fuel
            if (ag[1] > 0) {
                int added = health.addJetpackFuel(ag[1]);
                if (added > 0) gained.append(" +").append(added).append(" fuel");
            }
            // Ammo[0..19]
            int ammoMaxIdx = Math.min(ag.length, 2 + PlayerCombatState.NUM_AMMO_TYPES);
            for (int i = 2; i < ammoMaxIdx; i++) {
                if (ag[i] > 0) {
                    int bulletType = i - 2;
                    combat.addAmmo(bulletType, ag[i]);
                    gained.append(" +").append(ag[i]).append(" ammo[").append(bulletType).append("]");
                }
            }
        }

        // ── gunGive : [Shield, JetPack, Weapons[0..9]] ──────────────────────
        int[] gg = def.gunGive();
        if (gg != null && gg.length >= 2) {
            if (gg[0] > 0) { health.setShield(gg[0]); gained.append(" Shield"); }
            if (gg[1] > 0) { health.giveJetpack();    gained.append(" Jetpack"); }
            int wmax = Math.min(gg.length, 2 + PlayerCombatState.NUM_WEAPONS);
            for (int i = 2; i < wmax; i++) {
                if (gg[i] > 0) {
                    int gunIdx = i - 2;
                    if (!combat.hasWeapon(gunIdx)) {
                        combat.giveWeapon(gunIdx);
                        gained.append(" Weapon[").append(gunIdx).append("]");
                    }
                }
            }
        }

        // Si rien n'a ete reellement collecte (deja max ammo + arme deja
        // possedee), on laisse l'item en place (= comportement ASM).
        if (gained.length() == 0) {
            // log debug pour identifier mais pas trop de spam
            return;
        }

        // ── Retrait visuel ─────────────────────────────────────────────────
        if (sprite.getParent() != null) {
            sprite.getParent().detachChild(sprite);
        }
        collectedCount++;

        // ── Message HUD ────────────────────────────────────────────────────
        String msg = "Got " + def.name();
        if (hud != null) {
            hud.pushMessage(msg, HudState.Message.TAG_NARRATIVE);
            // Met a jour l'energie visible du HUD avec la sante reelle.
            hud.setEnergyCount(health.getHealth());
        }

        log.info("Collected: {} (defIdx={}){} -> {}",
            def.name(), def.index(), gained, health);

        // TODO (session future) : jouer le son sfx[def.sfx] (= sample 4 = collect
        // par defaut). Necessite un AudioManager qui n'existe pas encore.
    }

    /** Charge les definitions d'objets depuis assets/levels/definitions.json. */
    private static Map<Integer,ObjDefStatic> loadDefs(Path assetsRoot) {
        Map<Integer,ObjDefStatic> result = new HashMap<>();
        Path p = (assetsRoot != null) ? assetsRoot.resolve("levels/definitions.json")
                                       : Path.of("assets/levels/definitions.json");
        if (!Files.exists(p)) {
            log.warn("PickupSystem : definitions.json absent ({}), inventaire ne fonctionnera pas", p);
            return result;
        }
        try {
            String json = Files.readString(p);
            // On reutilise le mini-parser de LevelSceneBuilder (deja eprouve).
            // Acces aux methodes statiques via classe friend (meme package n'est
            // pas le cas ici, donc on parse minimal a la main).
            int objsStart = json.indexOf("\"objects\"");
            if (objsStart < 0) return result;
            int arrStart = json.indexOf('[', objsStart);
            if (arrStart < 0) return result;
            int depth = 0;
            int objStart = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '{') {
                    if (depth == 0) objStart = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        ObjDefStatic def = parseDef(json.substring(objStart, i + 1));
                        if (def != null) result.put(def.index(), def);
                        objStart = -1;
                    }
                } else if (c == ']' && depth == 0) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("PickupSystem : erreur lecture definitions.json : {}", e.getMessage());
        }
        return result;
    }

    /**
     * Parser ad-hoc d'une entree d'objet du definitions.json.
     *
     * <p>Fait au plus simple sans dependance JSON : extraction par recherche
     * de mot-cles. Suffisant pour notre format genere (cle entre guillemets,
     * valeur soit nombre soit string soit array de nombres).</p>
     */
    private static ObjDefStatic parseDef(String s) {
        try {
            int   index     = (int) extractNumber(s, "index");
            String name     = extractString(s, "name");
            int   behaviour = (int) extractNumber(s, "behaviour");
            int   gfxType   = (int) extractNumber(s, "gfxType");
            int   colRadius = (int) extractNumber(s, "colRadius");
            int   colHeight = (int) extractNumber(s, "colHeight");
            int   sfx       = (int) extractNumber(s, "sfx");
            int[] ammoGive  = extractIntArray(s, "ammoGive");
            int[] gunGive   = extractIntArray(s, "gunGive");
            return new ObjDefStatic(index, name, behaviour, gfxType,
                colRadius, colHeight, sfx, ammoGive, gunGive);
        } catch (Exception e) {
            return null;
        }
    }

    private static double extractNumber(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return 0;
        int co = json.indexOf(':', ki);
        if (co < 0) return 0;
        int vs = co + 1;
        while (vs < json.length() && Character.isWhitespace(json.charAt(vs))) vs++;
        int ve = vs;
        while (ve < json.length() && (json.charAt(ve)=='-' || Character.isDigit(json.charAt(ve)))) ve++;
        if (ve == vs) return 0;
        try { return Double.parseDouble(json.substring(vs, ve)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String extractString(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return "";
        int co = json.indexOf(':', ki);
        if (co < 0) return "";
        int qs = json.indexOf('"', co + 1);
        if (qs < 0) return "";
        int qe = json.indexOf('"', qs + 1);
        if (qe < 0) return "";
        return json.substring(qs + 1, qe);
    }

    private static int[] extractIntArray(String json, String key) {
        int ki = json.indexOf("\"" + key + "\"");
        if (ki < 0) return new int[0];
        int as = json.indexOf('[', ki);
        if (as < 0) return new int[0];
        int ae = json.indexOf(']', as);
        if (ae < 0) return new int[0];
        String content = json.substring(as + 1, ae).trim();
        if (content.isEmpty()) return new int[0];
        String[] parts = content.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    public int getCollectedCount() { return collectedCount; }
}
