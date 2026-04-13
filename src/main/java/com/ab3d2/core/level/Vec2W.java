package com.ab3d2.core.level;

/**
 * Point 2D en virgule fixe 16 bits.
 */
public record Vec2W(short x, short z) {

    public static Vec2W of(int x, int z) {
        return new Vec2W((short) x, (short) z);
    }

    public int xi() { return x; }
    public int zi() { return z; }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ")";
    }
}
