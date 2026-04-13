package com.ab3d2.render;

import com.jme3.math.*;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.*;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Renderer 3D LWJGL — FBO interne + blit.
 *
 * Éclairage (aucune normale requise) :
 *   - Ambient : facteur minimum global
 *   - Zone brightness : lumière de base par zone (vertex attribute)
 *   - Headlight : point light à la position caméra (distance attenuation)
 *
 * Vertex format : [x, y, z, u, v, brightness]  stride=6 floats
 */
public class Renderer3D {

    private static final Logger log = LoggerFactory.getLogger(Renderer3D.class);

    private int fbo, fboColor, fboDepth;
    private int fboW, fboH;

    private int prog3D, uMVP, uTex, uUseTexture, uColor, uFogColor, uFogDist;
    private int uCamPos, uAmbient, uHeadlight;
    private int progBlit, blitVao;

    private final Matrix4f proj  = new Matrix4f();
    private final Matrix4f view  = new Matrix4f();
    private final Matrix4f mvp   = new Matrix4f();
    private final float[]  mat16 = new float[16];

    private final Vector3f camPos = new Vector3f();
    private float yaw = 0f, pitch = 0f;

    private static final float FOV  = 80f * FastMath.DEG_TO_RAD;
    private static final float NEAR = 0.05f;
    private static final float FAR  = 2000f;
    private static final float[] FOG_COL = {0.01f, 0.01f, 0.03f};

    // ── Paramètres lumière ────────────────────────────────────────────────────
    /** Lumière ambiante globale (minimum même dans les zones sombres) */
    public static float AMBIENT    = 0.18f;
    /** Intensité du headlight (lumière à la caméra) */
    public static float HEADLIGHT  = 0.65f;
    /** Portée du headlight en unités JME */
    public static float HEADLIGHT_RANGE = 8.0f;

    // ── Init ──────────────────────────────────────────────────────────────────

    public void init(int gameW, int gameH) {
        fboW = gameW; fboH = gameH;
        createFBO(gameW, gameH);
        create3DShader();
        createBlitShader();
        log.info("Renderer3D OK (FBO={}x{}, prog3D={}, progBlit={})",
                 gameW, gameH, prog3D, progBlit);
    }

    private void createFBO(int w, int h) {
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        fboColor = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboColor);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboColor, 0);

        fboDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, fboDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fboDepth);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            log.info("FBO 3D OK");
        else
            log.error("FBO 3D ERREUR");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void create3DShader() {
        // ── Vertex shader : passe la position monde ───────────────────────────
        String vert = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec2 aUV;
            layout(location=2) in float aBright;

            out vec2  vUV;
            out float vBright;
            out float vDist;
            out vec3  vWorldPos;

            uniform mat4 uMVP;

            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                vUV       = aUV;
                vBright   = aBright;
                vDist     = gl_Position.w;
                vWorldPos = aPos;   // coordonnées JME (monde)
            }
            """;

        // ── Fragment shader : ambient + zone brightness + headlight ───────────
        //
        // Modèle :
        //   ambient      = uAmbient             (minimum global)
        //   zoneBright   = vBright              (luminosité de la zone, 0-1)
        //   headlight    = attenuation(dist_cam) (lumière à la caméra)
        //   totalLight   = max(ambient, zoneBright) + headlight * uHeadlight
        //
        String frag = """
            #version 330 core
            in vec2  vUV;
            in float vBright;
            in float vDist;
            in vec3  vWorldPos;

            out vec4 fragColor;

            uniform sampler2D uTex;
            uniform int   uUseTexture;
            uniform vec3  uColor;
            uniform vec3  uFogColor;
            uniform float uFogDist;

            uniform vec3  uCamPos;      // position caméra en coords JME
            uniform float uAmbient;     // lumière ambiante minimum
            uniform float uHeadlight;   // intensité headlight
            uniform float uHeadRange;   // portée headlight

            void main() {
                // Couleur de base (texture ou couleur unie)
                vec3 col = (uUseTexture > 0) ? texture(uTex, vUV).rgb : uColor;

                // Distance à la caméra (pour headlight)
                float d   = length(vWorldPos - uCamPos);
                // Atténuation quadratique normalisée : max au contact, 0 à uHeadRange
                float atten = clamp(1.0 - (d / uHeadRange), 0.0, 1.0);
                atten = atten * atten;   // quadratique = plus naturel

                // Contribution lumineuse totale
                float light = max(uAmbient, vBright)     // zone brightness ou ambient minimum
                            + atten * uHeadlight;        // headlight
                light = clamp(light, 0.0, 1.5);          // clamp pour éviter le surbrillant excessif

                col *= light;

                // Brouillard (profondeur de perspective)
                float fog = clamp(vDist / uFogDist, 0.0, 1.0);
                fragColor = vec4(mix(col, uFogColor, fog * fog), 1.0);
            }
            """;

        prog3D      = compileProgram(vert, frag);
        uMVP        = glGetUniformLocation(prog3D, "uMVP");
        uTex        = glGetUniformLocation(prog3D, "uTex");
        uUseTexture = glGetUniformLocation(prog3D, "uUseTexture");
        uColor      = glGetUniformLocation(prog3D, "uColor");
        uFogColor   = glGetUniformLocation(prog3D, "uFogColor");
        uFogDist    = glGetUniformLocation(prog3D, "uFogDist");
        uCamPos     = glGetUniformLocation(prog3D, "uCamPos");
        uAmbient    = glGetUniformLocation(prog3D, "uAmbient");
        uHeadlight  = glGetUniformLocation(prog3D, "uHeadlight");
        // Note: uHeadRange réutilise uFogDist → non, on l'ajoute séparément
        int uHeadRange = glGetUniformLocation(prog3D, "uHeadRange");
        // Stocker pour usage dans uploadUniforms
        this.uHeadlightRange = uHeadRange;
    }

    private int uHeadlightRange;  // location de uHeadRange

    private void createBlitShader() {
        String vert = """
            #version 330 core
            out vec2 vUV;
            void main() {
                const vec2 pos[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));
                const vec2 uvs[3]=vec2[](vec2(0,0),vec2(2,0),vec2(0,2));
                vUV=uvs[gl_VertexID]; gl_Position=vec4(pos[gl_VertexID],0.0,1.0);
            }""";
        String frag = """
            #version 330 core
            in vec2 vUV; out vec4 fragColor;
            uniform sampler2D uScreen;
            void main(){fragColor=texture(uScreen,vUV);}""";
        progBlit = compileProgram(vert, frag);
        blitVao  = glGenVertexArrays();
    }

    // ── Uniforms lumière ──────────────────────────────────────────────────────

    /** Upload des uniforms lumière communs à tous les draw calls. */
    private void uploadLightUniforms() {
        glUniform3f(uCamPos, camPos.x, camPos.y, camPos.z);
        glUniform1f(uAmbient, AMBIENT);
        glUniform1f(uHeadlight, HEADLIGHT);
        glUniform1f(uHeadlightRange, HEADLIGHT_RANGE);
        glUniform3f(uFogColor, FOG_COL[0], FOG_COL[1], FOG_COL[2]);
        glUniform1f(uFogDist, FAR * 0.35f);
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public void beginFrame() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, fboW, fboH);
        glClearColor(FOG_COL[0], FOG_COL[1], FOG_COL[2], 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        buildProjection();
    }

    public void setCamera(Vector3f pos, float yawRad, float pitchRad) {
        camPos.set(pos);
        yaw = yawRad; pitch = pitchRad;
        buildView();
        buildMVP();
    }

    public void drawMesh(int vao, int indexCount, int texId) {
        glUseProgram(prog3D);
        uploadMVP();
        uploadLightUniforms();
        glUniform1i(uUseTexture, 1);
        glUniform1i(uTex, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texId);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    public void drawMeshSolid(int vao, int indexCount, float r, float g, float b) {
        glUseProgram(prog3D);
        uploadMVP();
        uploadLightUniforms();
        glUniform1i(uUseTexture, 0);
        glUniform3f(uColor, r, g, b);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);
    }

    public void endFrame(int winW, int winH, float[] vp) {
        glDisable(GL_DEPTH_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, winW, winH);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        glViewport((int)vp[0], (int)vp[1], (int)vp[2], (int)vp[3]);
        glUseProgram(progBlit);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboColor);
        glUniform1i(glGetUniformLocation(progBlit, "uScreen"), 0);
        glBindVertexArray(blitVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);
    }

    // ── Matrices ──────────────────────────────────────────────────────────────

    private void buildProjection() {
        float aspect = (float) fboW / fboH;
        float f = 1f / FastMath.tan(FOV * 0.5f);
        proj.zero();
        proj.m00 = f / aspect;
        proj.m11 = f;
        proj.m22 = -(FAR + NEAR) / (FAR - NEAR);
        proj.m23 = -(2f * FAR * NEAR) / (FAR - NEAR);
        proj.m32 = -1f;
    }

    private void buildView() {
        float cy = FastMath.cos(yaw),   sy = FastMath.sin(yaw);
        float cp = FastMath.cos(pitch), sp = FastMath.sin(pitch);

        Vector3f forward = new Vector3f(cy * cp, sp, sy * cp).normalizeLocal();
        Vector3f right   = forward.cross(Vector3f.UNIT_Y).normalizeLocal();
        Vector3f up      = right.cross(forward).normalizeLocal();

        float tx = -right.dot(camPos);
        float ty = -up.dot(camPos);
        float tz =  forward.dot(camPos);

        view.zero();
        view.m00 =  right.x;    view.m01 =  right.y;    view.m02 =  right.z;    view.m03 = tx;
        view.m10 =  up.x;       view.m11 =  up.y;       view.m12 =  up.z;       view.m13 = ty;
        view.m20 = -forward.x;  view.m21 = -forward.y;  view.m22 = -forward.z;  view.m23 = tz;
        view.m33 = 1f;
    }

    private void buildMVP() {
        proj.mult(view, mvp);
    }

    private void uploadMVP() {
        mat16[ 0] = mvp.m00;  mat16[ 1] = mvp.m10;  mat16[ 2] = mvp.m20;  mat16[ 3] = mvp.m30;
        mat16[ 4] = mvp.m01;  mat16[ 5] = mvp.m11;  mat16[ 6] = mvp.m21;  mat16[ 7] = mvp.m31;
        mat16[ 8] = mvp.m02;  mat16[ 9] = mvp.m12;  mat16[10] = mvp.m22;  mat16[11] = mvp.m32;
        mat16[12] = mvp.m03;  mat16[13] = mvp.m13;  mat16[14] = mvp.m23;  mat16[15] = mvp.m33;
        try (MemoryStack s = stackPush()) {
            FloatBuffer fb = s.mallocFloat(16);
            fb.put(mat16).flip();
            glUniformMatrix4fv(uMVP, false, fb);
        }
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    public static int createVAO(float[] vertices, int[] indices) {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        try (MemoryStack s = stackPush()) {
            FloatBuffer fb = s.mallocFloat(vertices.length);
            fb.put(vertices).flip();
            glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        }

        int ibo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        try (MemoryStack s = stackPush()) {
            IntBuffer ib = s.mallocInt(indices.length);
            ib.put(indices).flip();
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, ib, GL_STATIC_DRAW);
        }

        int stride = 6 * Float.BYTES;
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L*Float.BYTES);
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L*Float.BYTES);
        glBindVertexArray(0);
        return vao;
    }

    public static int uploadTexture(int[] argb, int w, int h) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        for (int px : argb)
            buf.put((byte)((px>>16)&0xFF)).put((byte)((px>>8)&0xFF))
               .put((byte)(px&0xFF)).put((byte)((px>>24)&0xFF));
        buf.flip();
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,buf);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D,0);
        return tex;
    }

    public void destroy() {
        glDeleteFramebuffers(fbo); glDeleteTextures(fboColor); glDeleteRenderbuffers(fboDepth);
        glDeleteProgram(prog3D); glDeleteProgram(progBlit); glDeleteVertexArrays(blitVao);
    }

    private int compileProgram(String vert, String frag) {
        int v=cs(GL_VERTEX_SHADER,vert), f=cs(GL_FRAGMENT_SHADER,frag);
        int p=glCreateProgram(); glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        if(glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE)
            throw new RuntimeException("Link: "+glGetProgramInfoLog(p));
        glDeleteShader(v); glDeleteShader(f); return p;
    }

    private int cs(int type, String src) {
        int s=glCreateShader(type); glShaderSource(s,src); glCompileShader(s);
        if(glGetShaderi(s,GL_COMPILE_STATUS)==GL_FALSE)
            throw new RuntimeException("Shader: "+glGetShaderInfoLog(s));
        return s;
    }

    public Vector3f getCamPos() { return camPos; }
}
