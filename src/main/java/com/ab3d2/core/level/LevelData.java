package com.ab3d2.core.level;

/**
 * Toutes les données d'un niveau parsé (twolev.bin + twolev.graph.bin).
 */
public class LevelData {

    public final String levelId;

    public final short plr1StartX, plr1StartZ;
    public final int   plr1StartZoneId;
    public final short plr2StartX, plr2StartZ;
    public final int   plr2StartZoneId;

    public final Vec2W[]    controlPoints;
    public final Vec2W[]    points;
    public final ZEdge[]    edges;
    public final ZoneData[] zones;

    /** Zone de sortie du niveau (entree = endlevel). Voir {@link LevelBinaryParser.BinData#exitZoneId}. Session 110. */
    public final int exitZoneId;

    public final int numObjects;
    public final int rawBinSize, rawGraphSize;

    public LevelData(String levelId,
                     short plr1StartX, short plr1StartZ, int plr1StartZoneId,
                     short plr2StartX, short plr2StartZ, int plr2StartZoneId,
                     Vec2W[] controlPoints, Vec2W[] points,
                     ZEdge[] edges, ZoneData[] zones,
                     int exitZoneId,
                     int numObjects, int rawBinSize, int rawGraphSize) {
        this.levelId         = levelId;
        this.plr1StartX      = plr1StartX;
        this.plr1StartZ      = plr1StartZ;
        this.plr1StartZoneId = plr1StartZoneId;
        this.plr2StartX      = plr2StartX;
        this.plr2StartZ      = plr2StartZ;
        this.plr2StartZoneId = plr2StartZoneId;
        this.controlPoints   = controlPoints;
        this.points          = points;
        this.edges           = edges;
        this.zones           = zones;
        this.exitZoneId      = exitZoneId;
        this.numObjects      = numObjects;
        this.rawBinSize      = rawBinSize;
        this.rawGraphSize    = rawGraphSize;
    }

    public int numZones()         { return zones         != null ? zones.length         : 0; }
    public int numEdges()         { return edges         != null ? edges.length         : 0; }
    public int numPoints()        { return points        != null ? points.length        : 0; }
    public int numControlPoints() { return controlPoints != null ? controlPoints.length : 0; }

    public int numValidZones() {
        int c = 0;
        if (zones != null) for (ZoneData z : zones) if (z != null) c++;
        return c;
    }

    public ZoneData zone(int id)  { return (id >= 0 && id < numZones())         ? zones[id]         : null; }
    public ZEdge    edge(int id)  { return (id >= 0 && id < numEdges())         ? edges[id]         : null; }
    public Vec2W    point(int id) { return (id >= 0 && id < numPoints())        ? points[id]        : null; }

    @Override
    public String toString() {
        return String.format(
            "LevelData{id=%s, zones=%d/%d, edges=%d, points=%d, plr1=(%d,%d) z%d}",
            levelId, numValidZones(), numZones(), numEdges(), numPoints(),
            (int) plr1StartX, (int) plr1StartZ, plr1StartZoneId);
    }
}
