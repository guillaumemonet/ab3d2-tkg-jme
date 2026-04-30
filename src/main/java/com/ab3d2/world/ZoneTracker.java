package com.ab3d2.world;

import com.ab3d2.tools.LevelSceneBuilder;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracker de la zone courante du joueur.
 *
 * <h2>Principe</h2>
 *
 * <p>Le runtime original (ASM) maintient {@code Plr1_ZonePtr_l} pointant en
 * permanence sur la zone ou se trouve le joueur. C'est cette information qui
 * est lue pour decider :</p>
 *
 * <ul>
 *   <li><b>Teleport</b> : si {@code Plr1_ZonePtr.TelZone &gt;= 0}, on teleporte
 *       le joueur vers {@code (TelX, TelZ)} dans {@code TelZone}
 *       (cf. {@code hires.s:2902} dans {@code Plr1_Control}).</li>
 *   <li><b>Fin de niveau</b> : si {@code Plr1_ZonePtr.ID == Lvl_ExitZoneID}, on
 *       declenche {@code endlevel} (cf. {@code hires.s:2089}).</li>
 *   <li><b>Brightness</b>, <b>echo audio</b>, <b>floor noise</b> (sons de pas),
 *       etc. (utilisations futures).</li>
 * </ul>
 *
 * <p>L'ASM tracke la zone via une logique liee aux walls : quand le joueur
 * traverse un wall avec {@code otherZone &gt; 0}, il change de pointeur. Plus
 * efficace mais necessite de maintenir le pointeur a chaque mouvement. En
 * Java, vu qu'on n'a que ~134 zones par niveau et qu'un point-in-polygon coute
 * negligeable (10-20 comparaisons par zone), on peut se permettre un balayage
 * brute-force.</p>
 *
 * <h2>Optimisation</h2>
 *
 * <p>On commence par tester la <em>zone courante</em> (cas frequent : joueur
 * immobile ou mouvement local). Si l'on est sorti, on balaye toutes les zones
 * jusqu'a en trouver une qui contienne le point.</p>
 *
 * @since session 110
 */
public class ZoneTracker {

    private static final Logger log = LoggerFactory.getLogger(ZoneTracker.class);

    /**
     * Snapshot des donnees lues depuis le {@code zone_NN} Node JME (userData
     * setees par {@link LevelSceneBuilder} session 110).
     */
    public record ZoneInfo(
        int     id,
        int     floorH,
        int     roofH,
        int     brightness,
        int     telZone,    // -1 = pas de teleport
        int     telX,
        int     telZ,
        float[] floorXZ     // polygone XZ en coords JME (peut etre null si zone degeneree)
    ) {
        public boolean hasTeleport() { return telZone >= 0; }
    }

    private final Map<Integer, ZoneInfo> zonesById = new HashMap<>();
    private final List<ZoneInfo>          zonesList = new ArrayList<>();
    private final int                     exitZoneId;

    /** Zone courante du joueur (-1 si encore inconnu / hors de toute zone). */
    private int currentZoneId = -1;

    public ZoneTracker(Node levelScene) {
        // Session 110 : exitZoneId stocke en userData sur le levelScene (root).
        Integer ezId = levelScene.getUserData("exitZoneId");
        this.exitZoneId = ezId != null ? ezId : -1;

        Node zonesNode = (Node) levelScene.getChild("zones");
        if (zonesNode == null) {
            log.warn("ZoneTracker : pas de Node 'zones' dans le levelScene -> tracking inactif");
            return;
        }

        for (Spatial s : zonesNode.getChildren()) {
            ZoneInfo zi = readZoneInfo(s);
            if (zi == null) continue;
            zonesById.put(zi.id(), zi);
            zonesList.add(zi);
        }
        log.info("ZoneTracker : {} zones chargees, exitZoneId={}", zonesList.size(), exitZoneId);
    }

    private static ZoneInfo readZoneInfo(Spatial s) {
        Integer id      = s.getUserData("id");
        Integer floorH  = s.getUserData("floorH");
        Integer roofH   = s.getUserData("roofH");
        Integer bright  = s.getUserData("brightness");
        Integer telZone = s.getUserData("telZone");
        Integer telX    = s.getUserData("telX");
        Integer telZ    = s.getUserData("telZ");
        String  xzCsv   = s.getUserData("floorXZ");
        if (id == null) return null;
        float[] xz = (xzCsv != null) ? LevelSceneBuilder.csvToFloatArray(xzCsv) : null;
        return new ZoneInfo(
            id,
            floorH != null ? floorH : 0,
            roofH  != null ? roofH  : 0,
            bright != null ? bright : 0,
            telZone != null ? telZone : -1,
            telX != null ? telX : 0,
            telZ != null ? telZ : 0,
            xz);
    }

    /**
     * Met a jour la zone courante en fonction de la position monde du joueur.
     *
     * <p>Strategie : on teste d'abord la zone courante (cache), puis si le
     * joueur n'y est plus, on balaye toutes les zones. La premiere zone qui
     * contient le point gagne.</p>
     *
     * @param playerPos position monde JME du joueur
     * @return l'ID de la zone courante (= {@code currentZoneId}), ou -1 si
     *         le joueur n'est dans aucune zone valide.
     */
    public int update(Vector3f playerPos) {
        float px = playerPos.x;
        float pz = playerPos.z;

        // 1. Tester la zone precedente (cache, cas frequent)
        if (currentZoneId >= 0) {
            ZoneInfo cur = zonesById.get(currentZoneId);
            if (cur != null && cur.floorXZ() != null
                && PolygonXZ.pointInPolygon(cur.floorXZ(), px, pz)) {
                return currentZoneId;
            }
        }

        // 2. Balayer toutes les zones
        for (ZoneInfo zi : zonesList) {
            if (zi.floorXZ() == null) continue;
            if (PolygonXZ.pointInPolygon(zi.floorXZ(), px, pz)) {
                if (zi.id() != currentZoneId) {
                    log.debug("Zone change: {} -> {}", currentZoneId, zi.id());
                }
                currentZoneId = zi.id();
                return currentZoneId;
            }
        }

        // 3. Aucune zone trouvee : on garde la derniere connue (le joueur est
        //    peut-etre temporairement "entre 2 zones" a cause d'imprecisions
        //    flottantes ou de chevauchement de polygones).
        return currentZoneId;
    }

    /** Force la zone courante (utilise au spawn/teleport pour eviter une iteration). */
    public void setCurrentZoneId(int zoneId) {
        this.currentZoneId = zoneId;
    }

    public int      getCurrentZoneId()   { return currentZoneId; }
    public ZoneInfo getCurrentZone()     { return zonesById.get(currentZoneId); }
    public ZoneInfo getZone(int zoneId)  { return zonesById.get(zoneId); }
    public int      getExitZoneId()      { return exitZoneId; }
    public boolean  isExitZone(int zoneId) { return zoneId == exitZoneId && exitZoneId >= 0; }

    /** True si la zone courante est la zone exit du niveau. */
    public boolean inExitZone() { return isExitZone(currentZoneId); }

    /** Nombre de zones avec polygone valide (pour diagnostic). */
    public int trackableZoneCount() { return zonesList.size(); }
}
