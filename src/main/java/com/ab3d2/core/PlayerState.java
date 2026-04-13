package com.ab3d2.core;

import com.ab3d2.core.math.Tables;

/**
 * État du joueur — coordonnées virgule fixe 24.8, angle 0-4095.
 */
public class PlayerState {

    public static final int PLR_STAND_HEIGHT  = 12 * 1024;
    public static final int PLR_CROUCH_HEIGHT = 8  * 1024;
    public static final int ANGLE_MAX         = 4096;
    public static final int ANGLE_QUARTER     = 1024;
    public static final int INITIAL_ENERGY    = 191;

    public int   xOff, zOff;
    public int   yOff  = PLR_STAND_HEIGHT;
    public int   angle = 0;
    public short currentZoneId;
    public int   energy = INITIAL_ENERGY;
    public boolean ducked = false;
    public boolean dead   = false;

    public PlayerState() {}

    public PlayerState(int xWorld, int zWorld, int angle, short zoneId) {
        this.xOff          = xWorld << 8;
        this.zOff          = zWorld << 8;
        this.angle         = angle & (ANGLE_MAX - 1);
        this.currentZoneId = zoneId;
    }

    public int worldX() { return xOff >> 8; }
    public int worldZ() { return zOff >> 8; }

    public void moveForward(float speed) {
        if (!Tables.isInitialized()) return;
        xOff += (int)(Tables.sinf(angle) * speed * 256);
        zOff += (int)(Tables.cosf(angle) * speed * 256);
    }

    public void moveStrafe(float speed) {
        if (!Tables.isInitialized()) return;
        xOff += (int)( Tables.cosf(angle) * speed * 256);
        zOff += (int)(-Tables.sinf(angle) * speed * 256);
    }

    public void rotate(int delta) {
        angle = (angle + delta + ANGLE_MAX) & (ANGLE_MAX - 1);
    }

    @Override
    public String toString() {
        return String.format("Player{x=%d, z=%d, angle=%d, zone=%d, energy=%d}",
            worldX(), worldZ(), angle, currentZoneId, energy);
    }
}
