package com.ab3d2.app;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.renderer.RenderManager;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Test LWJGL minimal — FBO + blit, exactement comme Renderer2D (qui fonctionne).
 *
 * Si ce test affiche un triangle RGB → le pipeline FBO fonctionne.
 * Si écran noir → problème dans initialize() ou GL context non disponible.
 *
 * Activer depuis Main.java :
 *   stateManager.attach(new TestRenderAppState());
 */
public class TestRenderAppState extends AbstractAppState implements ActionListener {

    private static final Logger log = LoggerFactory.getLogger(TestRenderAppState.class);

    private Application app;

    // FBO
    private int fbo, fboTex, fboDepth;
    private static final int W = 320, H = 200;

    // Shader triangle
    private int progTri, vaoTri, vboTri, iboTri, uTimeTri;

    // Shader blit (copie du Renderer2D — prouvé fonctionnel)
    private int progBlit, vaoBlit;

    private float time = 0f;

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.app = app;
        log.info("TestRenderAppState.initialize()");

        if (app instanceof SimpleApplication sa) {
            sa.getViewPort().setEnabled(false);
            sa.getGuiViewPort().setEnabled(false);
            sa.getFlyByCamera().setEnabled(false);
        }

        // ── FBO (même code que Renderer2D) ───────────────────────────────────
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        fboTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboTex);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W,H,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,fboTex,0);

        fboDepth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER,fboDepth);
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,W,H);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_STENCIL_ATTACHMENT,GL_RENDERBUFFER,fboDepth);

        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        log.info("FBO status: {} ({})", status == GL_FRAMEBUFFER_COMPLETE ? "OK" : "ERREUR",
                 Integer.toHexString(status));
        glBindFramebuffer(GL_FRAMEBUFFER,0);

        // ── Shader triangle rotatif ───────────────────────────────────────────
        String vert = "#version 330 core\n"
            + "layout(location=0) in vec2 aPos;\n"
            + "layout(location=1) in vec3 aCol;\n"
            + "out vec3 vCol;\n"
            + "uniform float uTime;\n"
            + "void main(){\n"
            + "  float s=sin(uTime),c=cos(uTime);\n"
            + "  vec2 p=vec2(aPos.x*c-aPos.y*s, aPos.x*s+aPos.y*c)*0.7;\n"
            + "  gl_Position=vec4(p,0.0,1.0); vCol=aCol;\n"
            + "}\n";
        String frag = "#version 330 core\nin vec3 vCol;out vec4 o;\nvoid main(){o=vec4(vCol,1.0);}\n";

        progTri = compileProgram(vert, frag);
        uTimeTri = glGetUniformLocation(progTri,"uTime");

        float[] verts = { 0f,.8f,1f,0f,0f,  -.7f,-.5f,0f,1f,0f,  .7f,-.5f,0f,0f,1f };
        int[] idx = {0,1,2};

        vaoTri=glGenVertexArrays(); vboTri=glGenBuffers(); iboTri=glGenBuffers();
        glBindVertexArray(vaoTri);
        glBindBuffer(GL_ARRAY_BUFFER,vboTri);
        try(MemoryStack s=stackPush()){FloatBuffer b=s.mallocFloat(15);b.put(verts).flip();glBufferData(GL_ARRAY_BUFFER,b,GL_STATIC_DRAW);}
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,iboTri);
        try(MemoryStack s=stackPush()){IntBuffer b=s.mallocInt(3);b.put(idx).flip();glBufferData(GL_ELEMENT_ARRAY_BUFFER,b,GL_STATIC_DRAW);}
        glEnableVertexAttribArray(0); glVertexAttribPointer(0,2,GL_FLOAT,false,20,0L);
        glEnableVertexAttribArray(1); glVertexAttribPointer(1,3,GL_FLOAT,false,20,8L);
        glBindVertexArray(0);

        // ── Shader blit (identique Renderer2D — prouvé) ───────────────────────
        String blitV = "#version 330 core\nout vec2 vUV;\nvoid main(){\n"
            + "const vec2 pos[3]=vec2[](vec2(-1,-1),vec2(3,-1),vec2(-1,3));\n"
            + "const vec2 uvs[3]=vec2[](vec2(0,0),vec2(2,0),vec2(0,2));\n"
            + "vUV=uvs[gl_VertexID]; gl_Position=vec4(pos[gl_VertexID],0.0,1.0);}\n";
        String blitF = "#version 330 core\nin vec2 vUV;out vec4 o;\n"
            + "uniform sampler2D uScreen;\nvoid main(){o=texture(uScreen,vUV);}\n";
        progBlit = compileProgram(blitV, blitF);
        vaoBlit  = glGenVertexArrays();

        app.getInputManager().addMapping("tx", new KeyTrigger(KeyInput.KEY_ESCAPE));
        app.getInputManager().addListener(this, "tx");

        log.info("TestRenderAppState ready");
        log.info("ATTENDU : fond bleu-nuit + triangle RGB tournant");
        log.info("Si ecran noir : le FBO fonctionne mais le blit ne s'affiche pas → pb GPU/driver");
    }

    @Override public void update(float tpf) { time += tpf; }

    @Override
    public void render(RenderManager rm) {
        if (!isEnabled()) return;

        // ── Étape 1 : rendre le triangle dans le FBO ──────────────────────────
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, W, H);
        glClearColor(0.02f, 0.02f, 0.15f, 1f); // fond bleu nuit
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glDisable(GL_CULL_FACE);
        glDisable(GL_DEPTH_TEST);

        glUseProgram(progTri);
        glUniform1f(uTimeTri, time);
        glBindVertexArray(vaoTri);
        glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);

        // ── Étape 2 : blit FBO → écran (même code que Renderer2D.endFrame) ────
        int winW = app.getCamera().getWidth();
        int winH = app.getCamera().getHeight();

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, winW, winH);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);

        // Letterbox
        float ga=(float)W/H, wa=(float)winW/winH, vw,vh,vx,vy;
        if(wa>ga){vh=winH;vw=vh*ga;vx=(winW-vw)/2f;vy=0;}
        else     {vw=winW;vh=vw/ga;vx=0;vy=(winH-vh)/2f;}
        glViewport((int)vx,(int)vy,(int)vw,(int)vh);

        glUseProgram(progBlit);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTex);
        glUniform1i(glGetUniformLocation(progBlit,"uScreen"), 0);
        glBindVertexArray(vaoBlit);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);

        // Resync JME
        rm.getRenderer().invalidateState();
    }

    @Override
    public void cleanup() {
        var im = app.getInputManager();
        if (im.hasMapping("tx")) im.deleteMapping("tx");
        im.removeListener(this);
        glDeleteVertexArrays(vaoTri); glDeleteBuffers(vboTri); glDeleteBuffers(iboTri);
        glDeleteVertexArrays(vaoBlit);
        glDeleteProgram(progTri); glDeleteProgram(progBlit);
        glDeleteFramebuffers(fbo); glDeleteTextures(fboTex); glDeleteRenderbuffers(fboDepth);
        if (app instanceof SimpleApplication sa) {
            sa.getViewPort().setEnabled(true);
            sa.getGuiViewPort().setEnabled(true);
        }
    }

    @Override
    public void onAction(String n, boolean p, float t) {
        if (p && "tx".equals(n)) app.stop();
    }

    private int compileProgram(String vert, String frag) {
        int v=cs(GL_VERTEX_SHADER,vert), f=cs(GL_FRAGMENT_SHADER,frag);
        int p=glCreateProgram();
        glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
        if (glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE)
            log.error("Link: {}", glGetProgramInfoLog(p));
        glDeleteShader(v); glDeleteShader(f);
        return p;
    }

    private int cs(int type, String src) {
        int s=glCreateShader(type);
        glShaderSource(s,src); glCompileShader(s);
        if (glGetShaderi(s,GL_COMPILE_STATUS)==GL_FALSE)
            log.error("Shader err: {}", glGetShaderInfoLog(s));
        return s;
    }
}
