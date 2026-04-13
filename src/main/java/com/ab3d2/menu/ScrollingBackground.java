package com.ab3d2.menu;

import com.ab3d2.assets.AmigaBitplaneDecoder;
import com.ab3d2.assets.MenuAssets;
import com.ab3d2.render.Renderer2D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Background du menu avec scroll vertical infini (320×512, UV scroll).
 * Reproduit mnu_movescreen / mnu_screen de l'original ASM.
 */
public class ScrollingBackground {

    private static final Logger log = LoggerFactory.getLogger(ScrollingBackground.class);

    private static final int W        = MenuAssets.SCREEN_W; // 320
    private static final int H        = MenuAssets.SCREEN_H; // 256
    private static final int DOUBLED_H = H * 2;              // 512

    private int texture   = -1;
    private int scrollPos = 0;

    public void init(byte[] back2Raw, int[] backpal) {
        if (back2Raw == null) {
            log.warn("back2.raw absent, fond uni");
            texture = solidTexture(backpal != null && backpal.length > 0 ? backpal[0] : 0xFF000000);
            return;
        }
        int[] p256 = AmigaBitplaneDecoder.decode(back2Raw, W, H, 2, backpal);
        int[] p512 = new int[W * DOUBLED_H];
        System.arraycopy(p256, 0, p512, 0,           p256.length);
        System.arraycopy(p256, 0, p512, p256.length, p256.length);

        ByteBuffer buf = ByteBuffer.allocateDirect(W * DOUBLED_H * 4);
        for (int px : p512) {
            buf.put((byte)((px>>16)&0xFF));
            buf.put((byte)((px>>8)&0xFF));
            buf.put((byte)(px&0xFF));
            buf.put((byte)((px>>24)&0xFF));
        }
        buf.flip();

        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, DOUBLED_H, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
        log.info("ScrollingBackground initialized ({}×{})", W, DOUBLED_H);
    }

    public void update() { scrollPos = (scrollPos + 1) & 255; }

    public void render(Renderer2D r, int destW, int destH) {
        if (texture < 0) return;
        float v0 = (float) scrollPos / DOUBLED_H;
        float v1 = v0 + (float) destH / DOUBLED_H;
        r.drawTexture(texture, 0, 0, destW, destH, 0f, v0, 1f, v1);
    }

    private int solidTexture(int argb) {
        ByteBuffer buf = ByteBuffer.allocateDirect(4);
        buf.put((byte)((argb>>16)&0xFF)).put((byte)((argb>>8)&0xFF))
           .put((byte)(argb&0xFF)).put((byte)((argb>>24)&0xFF)).flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        return tex;
    }

    public void destroy() { if (texture >= 0) { glDeleteTextures(texture); texture = -1; } }
    public int getTexture() { return texture; }
}
