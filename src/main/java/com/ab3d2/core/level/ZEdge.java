package com.ab3d2.core.level;

/**
 * Arête du monde 3D (ZEdge, zone.h — 16 bytes big-endian).
 */
public record ZEdge(
        Vec2W pos,
        Vec2W len,
        short joinZoneId,
        short word5,
        byte  byte12,
        byte  byte13,
        short flags
) {
    public static final int   BINARY_SIZE = 16;
    public static final short SOLID_WALL  = -1;

    public boolean isPortal() { return joinZoneId >= 0; }

    public int sideOf(int px, int pz) {
        return (int) len.xi() * (pz - (int) pos.zi())
             - (int) len.zi() * (px - (int) pos.xi());
    }

    @Override
    public String toString() {
        return String.format("ZEdge{pos=%s, len=%s, join=%d, flags=0x%04X}",
            pos, len, joinZoneId, flags & 0xFFFF);
    }
}
