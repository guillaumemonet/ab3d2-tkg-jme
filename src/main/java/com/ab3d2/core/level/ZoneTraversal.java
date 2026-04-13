package com.ab3d2.core.level;

/**
 * Zone traversal — détecte dans quelle zone se trouve le joueur.
 *
 * Convention : sideOf < 0 = intérieur, sideOf >= 0 = extérieur/franchissement.
 */
public class ZoneTraversal {

    private static final int MAX_DEPTH = 8;

    public static int updateZone(LevelData level, int currentZoneId, float newX, float newZ) {
        return traverse(level, currentZoneId, newX, newZ, 0);
    }

    public static int findZone(LevelData level, int hintZoneId, float x, float z) {
        if (isInsideZone(level, hintZoneId, x, z)) return hintZoneId;
        ZoneData hint = level.zone(hintZoneId);
        if (hint != null) {
            for (ZPVSRecord pvs : hint.pvsRecords) {
                int vid = pvs.zoneId() & 0xFFFF;
                if (isInsideZone(level, vid, x, z)) return vid;
            }
        }
        for (int i = 0; i < level.numZones(); i++) {
            if (i == hintZoneId) continue;
            if (isInsideZone(level, i, x, z)) return i;
        }
        return hintZoneId;
    }

    public static boolean isInsideZone(LevelData level, int zoneId, float x, float z) {
        ZoneData zone = level.zone(zoneId);
        if (zone == null || zone.edgeIds.length == 0) return false;
        for (short edgeId : zone.edgeIds) {
            if (edgeId < 0 || edgeId >= level.numEdges()) continue;
            ZEdge edge = level.edge(edgeId);
            if (edge == null) continue;
            if (sideOf(edge, x, z) > 0) return false;
        }
        return true;
    }

    private static int traverse(LevelData level, int zoneId, float x, float z, int depth) {
        if (depth >= MAX_DEPTH) return zoneId;
        ZoneData zone = level.zone(zoneId);
        if (zone == null) return zoneId;
        for (short edgeId : zone.edgeIds) {
            if (edgeId < 0 || edgeId >= level.numEdges()) continue;
            ZEdge edge = level.edge(edgeId);
            if (edge == null || !edge.isPortal()) continue;
            if (sideOf(edge, x, z) >= 0) {
                int next = edge.joinZoneId() & 0xFFFF;
                if (next >= level.numZones()) continue;
                return traverse(level, next, x, z, depth + 1);
            }
        }
        return zoneId;
    }

    /** lenX*(pz-posZ) - lenZ*(px-posX) : négatif = intérieur. */
    public static int sideOf(ZEdge edge, float px, float pz) {
        long lenX = edge.len().xi(), lenZ = edge.len().zi();
        long posX = edge.pos().xi(), posZ = edge.pos().zi();
        return Long.signum((long) (lenX * (pz - posZ) - lenZ * (px - posX)));
    }
}
