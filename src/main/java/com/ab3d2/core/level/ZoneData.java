package com.ab3d2.core.level;

import java.util.Arrays;

/**
 * Données complètes d'une zone (ZoneT, zone.h — header 48 bytes + listes variables).
 */
public class ZoneData {

    public static final int   FIXED_SIZE           = 48;
    public static final int   DISABLED_HEIGHT       = 5000;
    public static final short ZONE_ID_LIST_END      = -1;
    public static final short EDGE_POINT_ID_LIST_END = -4;

    public final short    zoneId;
    public final int      floor, roof, upperFloor, upperRoof, water;
    public final short    brightness, upperBrightness;
    public final short    controlPoint, backSFXMask, unused;
    public final short    edgeListOffset, pointsOffset;
    public final byte     drawBackdrop, echo;
    public final short    telZone, telX, telZ;
    public final short    floorNoise, upperFloorNoise;

    public final short[]      edgeIds;
    public final short[]      pointIds;
    public final ZPVSRecord[] pvsRecords;

    public ZoneData(short zoneId, int floor, int roof, int upperFloor, int upperRoof,
                    int water, short brightness, short upperBrightness,
                    short controlPoint, short backSFXMask, short unused,
                    short edgeListOffset, short pointsOffset,
                    byte drawBackdrop, byte echo,
                    short telZone, short telX, short telZ,
                    short floorNoise, short upperFloorNoise,
                    short[] edgeIds, short[] pointIds, ZPVSRecord[] pvsRecords) {
        this.zoneId           = zoneId;
        this.floor            = floor;
        this.roof             = roof;
        this.upperFloor       = upperFloor;
        this.upperRoof        = upperRoof;
        this.water            = water;
        this.brightness       = brightness;
        this.upperBrightness  = upperBrightness;
        this.controlPoint     = controlPoint;
        this.backSFXMask      = backSFXMask;
        this.unused           = unused;
        this.edgeListOffset   = edgeListOffset;
        this.pointsOffset     = pointsOffset;
        this.drawBackdrop     = drawBackdrop;
        this.echo             = echo;
        this.telZone          = telZone;
        this.telX             = telX;
        this.telZ             = telZ;
        this.floorNoise       = floorNoise;
        this.upperFloorNoise  = upperFloorNoise;
        this.edgeIds          = edgeIds;
        this.pointIds         = pointIds;
        this.pvsRecords       = pvsRecords;
    }

    public static int heightOf(int raw) { return raw >> 8; }

    public boolean hasUpper() {
        int uf = heightOf(upperFloor);
        return uf < DISABLED_HEIGHT && uf > heightOf(upperRoof);
    }

    public int floorHeight()      { return heightOf(floor); }
    public int roofHeight()       { return heightOf(roof); }
    public int upperFloorHeight() { return heightOf(upperFloor); }
    public int upperRoofHeight()  { return heightOf(upperRoof); }

    @Override
    public String toString() {
        return String.format("Zone{id=%d, floor=%d, roof=%d, brightness=%d, edges=%d, pvs=%d}",
            zoneId, floorHeight(), roofHeight(), brightness, edgeIds.length, pvsRecords.length);
    }
}
