package com.ab3d2.core.level;

/**
 * Entrée PVS (ZPVSRecord, zone.h — 8 bytes).
 */
public record ZPVSRecord(short zoneId, short clipId, short word2, short word3) {
    public static final short ZONE_ID_LIST_END = -1;
    public boolean isEnd() { return zoneId == ZONE_ID_LIST_END; }
    @Override
    public String toString() { return "PVS{zone=" + zoneId + ", clip=" + clipId + "}"; }
}
