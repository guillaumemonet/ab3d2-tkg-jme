package com.ab3d2.menu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL33.*;

/**
 * Effet de feu pour le menu AB3D2 — algorithme Doom-fire (validé).
 *
 * Chaque pixel = intensité 0-255 → palette noire→rouge→orange→jaune→blanc.
 * Le texte sème intensité=255 → flammes qui montent depuis les lettres.
 * La police (pass 3 dans MenuAppState) est affichée PAR-DESSUS, telle quelle.
 */
public class FireEffect {

    private static final Logger log = LoggerFactory.getLogger(FireEffect.class);

    public static final int W = 320;
    public static final int H = 256;

    private final int[]     fireBuffer = new int[W * H];
    private       boolean[] textLayer  = new boolean[W * H];

    private final int[]      palette;
    private final ByteBuffer pixelBuf;
    private int texture = -1;

    private int rndState = 0x54424C21;
    private int cooling  = 3;

    public FireEffect() {
        this.palette  = buildFirePalette();
        this.pixelBuf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder());
    }

    // ── Init GL ───────────────────────────────────────────────────────────────

    public void init() {
        texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        log.info("FireEffect initialized, texture={}", texture);
    }

    // ── Injection texte ───────────────────────────────────────────────────────

    public void setTextLayer(int[] textBits) {
        for (int i = 0; i < W * H; i++)
            textLayer[i] = textBits[i] != 0;
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public void update() {
        seedSources();
        propagate();
        upload();
    }

    // ── Seeding ───────────────────────────────────────────────────────────────

    private void seedSources() {
        // Pixels texte → sources permanentes à intensité max
        for (int i = 0; i < W * H; i++) {
            if (textLayer[i]) {
                int intensity = 235 + (nextRnd() & 20);
                fireBuffer[i] = Math.min(255, intensity);
                int below = i + W;
                if (below < W * H)
                    fireBuffer[below] = Math.min(255, intensity - 10);
            }
        }
        // Rangée basse : seeds ambiants
        int baseRow = (H - 1) * W;
        for (int x = 0; x < W; x++) {
            if (textLayer[baseRow + x] || (nextRnd() & 7) < 3)
                fireBuffer[baseRow + x] = 200 + (nextRnd() & 55);
        }
    }

    // ── Propagation (Doom-fire) ───────────────────────────────────────────────

    private void propagate() {
        for (int y = 0; y < H - 1; y++) {
            int dstRow = y * W;
            int srcRow = (y + 1) * W;
            for (int x = 0; x < W; x++) {
                int src   = fireBuffer[srcRow + x];
                int decay = nextRnd() & (cooling * 2 + 1);
                int dstX  = x - (decay & 1);
                if (dstX < 0)  dstX = 0;
                if (dstX >= W) dstX = W - 1;
                int dstIdx = dstRow + dstX;
                if (!textLayer[dstIdx])
                    fireBuffer[dstIdx] = Math.max(0, src - decay);
            }
        }
    }

    // ── Upload GL ─────────────────────────────────────────────────────────────

    private void upload() {
        if (texture < 0) return;
        pixelBuf.clear();
        for (int i = 0; i < W * H; i++) {
            int argb = palette[fireBuffer[i] & 0xFF];
            pixelBuf.put((byte)((argb >> 16) & 0xFF));
            pixelBuf.put((byte)((argb >>  8) & 0xFF));
            pixelBuf.put((byte)( argb         & 0xFF));
            pixelBuf.put((byte)((argb >> 24) & 0xFF));
        }
        pixelBuf.flip();
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuf);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // ── Palette ───────────────────────────────────────────────────────────────

    private static int[] buildFirePalette() {
        int[] pal = new int[256];
        pal[0] = 0x00000000;
        for (int i = 1; i < 256; i++) {
            int r = Math.min(255, (i * 255) / 130);
            int g = Math.max(0, Math.min(255, ((i - 80)  * 255) / 110));
            int b = Math.max(0, Math.min(255, ((i - 210) * 255) / 45));
            int a = Math.min(255, (i * 255) / 40);
            pal[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return pal;
    }

    // ── RNG ───────────────────────────────────────────────────────────────────

    private int nextRnd() {
        rndState ^= rndState << 13;
        rndState ^= rndState >>> 17;
        rndState ^= rndState << 5;
        return rndState & 0xFF;
    }

    // ── Accesseurs ────────────────────────────────────────────────────────────

    public void destroy() {
        if (texture >= 0) { glDeleteTextures(texture); texture = -1; }
    }

    public int getTexture()        { return texture; }
    public void setCooling(int c)  { this.cooling = Math.max(1, Math.min(8, c)); }
}
