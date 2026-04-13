package com.ab3d2.menu;

/**
 * Curseur animé du menu. mnu_cursanim: 130,129,128,127,126,125,124,123
 */
public class MenuCursor {

    private static final int[] ANIM  = {130,129,128,127,126,125,124,123};
    private static final double SPEED = 1.0 / 25.0;

    private int    frame = 0;
    private double timer = 0;

    public void update(double dt) {
        timer += dt;
        if (timer >= SPEED) { timer -= SPEED; frame = (frame + 1) % ANIM.length; }
    }

    public void reset() { frame = 0; timer = 0; }

    public int getCurrentGlyph() { return ANIM[frame]; }
}
