package com.ab3d2.render;

import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renderer 2D LWJGL :
 *  - FBO interne gameWidth×gameHeight (pixel art natif)
 *  - Upscale nearest-neighbor → fenêtre (letterbox)
 *  - Sprite batch (coordonnées pixel 0,0 = haut-gauche)
 *
 * Modes de blend (uBlendMode) :
 *   0 = normal alpha    → couleur texture × alpha
 *   1 = additif         → couleur texture + fond (pour effets lumineux)
 *   2 = fade            → overlay noir uniforme, uAlpha = opacité
 *   3 = teinté          → couleur = uTint × alpha texture (ignore couleur texture)
 *                         Permet d'afficher une texture monochrome avec n'importe quelle couleur.
 *                         Utilisé pour la police ab3d2 (atlas blanc → couleur fontpal)
 */
public class Renderer2D {

    private static final Logger log = LoggerFactory.getLogger(Renderer2D.class);

    private static final int MAX_QUADS       = 4096;
    private static final int FLOATS_PER_VERT = 4;
    private static final int VERTS_PER_QUAD  = 6;

    // ── GL objects ────────────────────────────────────────────────────────────
    private int fbo, fboTexture, rbo;
    private int blitProgram, blitVao;
    private int spriteProgram, spriteVao, spriteVbo;

    // Uniform locations
    private int uScreenLoc, uTexLoc, uAlphaLoc, uBlendModeLoc, uTintLoc;

    // ── Batch ─────────────────────────────────────────────────────────────────
    private final float[] batchBuffer = new float[MAX_QUADS * VERTS_PER_QUAD * FLOATS_PER_VERT];
    private int   batchCount  = 0;
    private int   currentTex  = -1;
    private int   currentBlend = 0;

    // Tint courant (r, g, b) pour blend mode 3
    private float tintR = 1f, tintG = 1f, tintB = 1f;
    private float pendingTintR = 1f, pendingTintG = 1f, pendingTintB = 1f;

    private int fadeTexture = -1;

    private final int gameWidth, gameHeight;

    public Renderer2D(int gameWidth, int gameHeight) {
        this.gameWidth  = gameWidth;
        this.gameHeight = gameHeight;
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    public void init() {
        createFBO();
        createBlitShader();
        createSpriteShader();
        createUtilityTextures();
        log.info("Renderer2D initialized ({}×{})", gameWidth, gameHeight);
    }

    private void createFBO() {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        fboTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, gameWidth, gameHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexture, 0);

        rbo = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, rbo);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, gameWidth, gameHeight);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rbo);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            throw new RuntimeException("FBO incomplete!");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void createBlitShader() {
        String vert = """
            #version 330 core
            out vec2 vUV;
            void main() {
                const vec2 pos[3] = vec2[](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
                const vec2 uvs[3] = vec2[](vec2(0,0),   vec2(2,0),  vec2(0,2));
                vUV         = uvs[gl_VertexID];
                gl_Position = vec4(pos[gl_VertexID], 0.0, 1.0);
            }
            """;
        String frag = """
            #version 330 core
            in  vec2 vUV;
            out vec4 fragColor;
            uniform sampler2D uScreen;
            void main() { fragColor = texture(uScreen, vUV); }
            """;
        blitProgram = compileProgram(vert, frag);
        blitVao     = glGenVertexArrays();
    }

    private void createSpriteShader() {
        String vert = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            out vec2 vUV;
            uniform vec2 uScreen;
            void main() {
                vec2 ndc = (aPos / uScreen) * 2.0 - 1.0;
                ndc.y = -ndc.y;
                gl_Position = vec4(ndc, 0.0, 1.0);
                vUV = aUV;
            }
            """;
        // Mode 3 = teinté : multiplie la couleur de la texture par uTint
        //   Utilisé pour afficher la police avec la bonne couleur (fontpal[1])
        //   indépendamment des couleurs de la texture atlas.
        String frag = """
            #version 330 core
            in  vec2 vUV;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform float     uAlpha;
            uniform int       uBlendMode;
            uniform vec3      uTint;
            void main() {
                vec4 c = texture(uTex, vUV);
                if (uBlendMode == 2) {
                    // Fade noir
                    fragColor = vec4(0.0, 0.0, 0.0, uAlpha);
                } else if (uBlendMode == 3) {
                    // Teinté : garde uniquement l'alpha de la texture, applique uTint comme couleur.
                    // Permet d'afficher un atlas de glyphes blancs avec n'importe quelle couleur.
                    if (c.a < 0.01) discard;
                    fragColor = vec4(uTint, c.a * uAlpha);
                } else {
                    if (c.a < 0.01) discard;
                    fragColor = vec4(c.rgb, c.a * uAlpha);
                }
            }
            """;
        spriteProgram = compileProgram(vert, frag);
        uScreenLoc    = glGetUniformLocation(spriteProgram, "uScreen");
        uTexLoc       = glGetUniformLocation(spriteProgram, "uTex");
        uAlphaLoc     = glGetUniformLocation(spriteProgram, "uAlpha");
        uBlendModeLoc = glGetUniformLocation(spriteProgram, "uBlendMode");
        uTintLoc      = glGetUniformLocation(spriteProgram, "uTint");

        spriteVao = glGenVertexArrays();
        glBindVertexArray(spriteVao);
        spriteVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, spriteVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) batchBuffer.length * Float.BYTES, GL_DYNAMIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glBindVertexArray(0);
    }

    private void createUtilityTextures() {
        ByteBuffer white = ByteBuffer.allocateDirect(4);
        white.put((byte)0xFF).put((byte)0xFF).put((byte)0xFF).put((byte)0xFF).flip();
        fadeTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fadeTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public void beginFrame() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, gameWidth, gameHeight);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        batchCount = 0; currentTex = -1; currentBlend = 0;
        tintR = 1f; tintG = 1f; tintB = 1f;
    }

    /** Blend normal (alpha). */
    public void drawTexture(int texId, float x, float y, float w, float h) {
        drawTexture(texId, x, y, w, h, 0f, 0f, 1f, 1f);
    }

    /** Blend normal avec UV custom. */
    public void drawTexture(int texId, float x, float y, float w, float h,
                            float u0, float v0, float u1, float v1) {
        if (texId != currentTex || currentBlend != 0) { flushBatch(); currentTex = texId; currentBlend = 0; }
        if (batchCount >= MAX_QUADS) flushBatch();
        pushQuad(x, y, w, h, u0, v0, u1, v1);
    }

    /** Blend additif. */
    public void drawTextureAdditive(int texId, float x, float y, float w, float h) {
        if (texId != currentTex || currentBlend != 1) { flushBatch(); currentTex = texId; currentBlend = 1; }
        if (batchCount >= MAX_QUADS) flushBatch();
        pushQuad(x, y, w, h, 0f, 0f, 1f, 1f);
    }

    /**
     * Blend teinté (mode 3) — affiche la texture avec une couleur RGB imposée.
     * L'alpha de la texture est conservé, mais le RGB est remplacé par (r,g,b).
     *
     * Parfait pour afficher la police AB3D2 avec la bonne couleur fontpal :
     *   drawTextureTinted(fontTex, x, y, w, h, u0, v0, u1, v1, fr, fg, fb)
     *
     * @param r rouge [0..1]
     * @param g vert  [0..1]
     * @param b bleu  [0..1]
     */
    public void drawTextureTinted(int texId, float x, float y, float w, float h,
                                  float u0, float v0, float u1, float v1,
                                  float r, float g, float b) {
        boolean sameSettings = texId == currentTex && currentBlend == 3
            && tintR == r && tintG == g && tintB == b;
        if (!sameSettings) {
            flushBatch();
            currentTex = texId;
            currentBlend = 3;
            pendingTintR = r; pendingTintG = g; pendingTintB = b;
            tintR = r; tintG = g; tintB = b;
        }
        if (batchCount >= MAX_QUADS) flushBatch();
        pushQuad(x, y, w, h, u0, v0, u1, v1);
    }

    /** Overlay fade noir. darkness = 0 transparent, 1 opaque. */
    public void drawFadeOverlay(float darkness) {
        if (darkness <= 0.001f) return;
        flushBatch();
        glUseProgram(spriteProgram);
        glUniform2f(uScreenLoc, gameWidth, gameHeight);
        glUniform1i(uTexLoc, 0);
        glUniform1f(uAlphaLoc, darkness);
        glUniform1i(uBlendModeLoc, 0);
        glUniform3f(uTintLoc, 1f, 1f, 1f);
        float[] q = { 0f,gameWidth,gameHeight,1f, gameWidth,gameHeight,1f,1f,
                      gameWidth,0f,1f,0f, 0f,0f,0f,0f, 0f,gameHeight,0f,1f, gameWidth,gameHeight,1f,1f };
        // quad plein écran
        float[] quad = {
            0f,        0f,         0f,0f,
            gameWidth, gameHeight, 1f,1f,
            gameWidth, 0f,         1f,0f,
            0f,        0f,         0f,0f,
            0f,        gameHeight, 0f,1f,
            gameWidth, gameHeight, 1f,1f,
        };
        glBindTexture(GL_TEXTURE_2D, fadeTexture);
        glBindVertexArray(spriteVao);
        glBindBuffer(GL_ARRAY_BUFFER, spriteVbo);
        try (MemoryStack s = stackPush()) {
            FloatBuffer b = s.mallocFloat(quad.length);
            b.put(quad).flip();
            glBufferSubData(GL_ARRAY_BUFFER, 0, b);
        }
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glDisable(GL_BLEND); glBindVertexArray(0);
    }

    /** Fin de frame : flush + blit FBO → écran avec letterbox. */
    public void endFrame(int winW, int winH, float[] viewport) {
        flushBatch();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport((int)viewport[0], (int)viewport[1], (int)viewport[2], (int)viewport[3]);
        glUseProgram(blitProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glUniform1i(glGetUniformLocation(blitProgram, "uScreen"), 0);
        glBindVertexArray(blitVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0); glBindTexture(GL_TEXTURE_2D, 0);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void pushQuad(float x,float y,float w,float h,float u0,float v0,float u1,float v1) {
        int b = batchCount * VERTS_PER_QUAD * FLOATS_PER_VERT;
        putV(b,    x,   y,   u0,v0); putV(b+4,  x+w, y+h, u1,v1); putV(b+8,  x+w, y,   u1,v0);
        putV(b+12, x,   y,   u0,v0); putV(b+16, x,   y+h, u0,v1); putV(b+20, x+w, y+h, u1,v1);
        batchCount++;
    }

    private void putV(int b,float x,float y,float u,float v) {
        batchBuffer[b]=x; batchBuffer[b+1]=y; batchBuffer[b+2]=u; batchBuffer[b+3]=v;
    }

    private void flushBatch() {
        if (batchCount == 0 || currentTex < 0) { batchCount = 0; return; }
        glUseProgram(spriteProgram);
        glUniform2f(uScreenLoc, gameWidth, gameHeight);
        glUniform1i(uTexLoc, 0);
        glUniform1f(uAlphaLoc, 1f);
        glUniform1i(uBlendModeLoc, currentBlend);
        glUniform3f(uTintLoc, tintR, tintG, tintB);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, currentTex);
        glBindVertexArray(spriteVao);
        glBindBuffer(GL_ARRAY_BUFFER, spriteVbo);

        int vc = batchCount * VERTS_PER_QUAD;
        try (MemoryStack s = stackPush()) {
            FloatBuffer b = s.mallocFloat(vc * FLOATS_PER_VERT);
            b.put(batchBuffer, 0, vc * FLOATS_PER_VERT).flip();
            glBufferSubData(GL_ARRAY_BUFFER, 0, b);
        }

        glEnable(GL_BLEND);
        if (currentBlend == 1) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE); // additif
        } else {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // normal + teinté + fade
        }
        glDrawArrays(GL_TRIANGLES, 0, vc);
        glDisable(GL_BLEND);
        glBindVertexArray(0); glBindBuffer(GL_ARRAY_BUFFER, 0);
        batchCount = 0; currentTex = -1;
    }

    // ── Shader compilation ────────────────────────────────────────────────────

    private int compileProgram(String vert, String frag) {
        int v = compileShader(GL_VERTEX_SHADER, vert);
        int f = compileShader(GL_FRAGMENT_SHADER, frag);
        int p = glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Link: " + glGetProgramInfoLog(p));
        glDeleteShader(v); glDeleteShader(f);
        return p;
    }

    private int compileShader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src); glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader: " + glGetShaderInfoLog(s));
        return s;
    }

    public void destroy() {
        glDeleteFramebuffers(fbo); glDeleteTextures(fboTexture); glDeleteRenderbuffers(rbo);
        glDeleteProgram(blitProgram); glDeleteProgram(spriteProgram);
        glDeleteVertexArrays(blitVao); glDeleteVertexArrays(spriteVao);
        glDeleteBuffers(spriteVbo);
        if (fadeTexture >= 0) glDeleteTextures(fadeTexture);
    }

    public int getGameWidth()  { return gameWidth; }
    public int getGameHeight() { return gameHeight; }
}
