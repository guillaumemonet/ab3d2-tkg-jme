package com.ab3d2.app;

import com.ab3d2.LevelManager;
import com.ab3d2.render.Renderer2D;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.renderer.RenderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL33.*;

public class LevelSelectAppState extends AbstractAppState implements ActionListener {

    private static final Logger log = LoggerFactory.getLogger(LevelSelectAppState.class);
    private static final int GW=320, GH=200, NUM_LEVELS=16, ITEMS_PER_PAGE=8;

    private static final String[] LEVEL_NAMES = {
        "LEVEL A","LEVEL B","LEVEL C","LEVEL D","LEVEL E","LEVEL F","LEVEL G","LEVEL H",
        "LEVEL I","LEVEL J","LEVEL K","LEVEL L","LEVEL M","LEVEL N","LEVEL O","LEVEL P"
    };

    private static final String A_UP="ls_up",A_DOWN="ls_down",A_OK="ls_ok",A_BACK="ls_back";

    private final Path assetsRoot;
    private final Path jmeAssets;
    private Application app;

    private Renderer2D renderer2D;
    private int screenTexId=-1;
    private final int[] screenBuf=new int[GW*GH];
    private final ByteBuffer uploadBuf=ByteBuffer.allocateDirect(GW*GH*4).order(ByteOrder.nativeOrder());

    private int selectedIndex=0, scrollOffset=0;
    private final boolean[] levelAvailable=new boolean[NUM_LEVELS];
    private float fadeAlpha=0f, fadeTarget=1f;
    private boolean pendingBack=false, pendingPlay=false;

    public LevelSelectAppState(Path assetsRoot, Path jmeAssets) {
        this.assetsRoot = assetsRoot;
        this.jmeAssets  = jmeAssets;
    }

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm,app); this.app=app;
        renderer2D=new Renderer2D(GW,GH); renderer2D.init();
        LevelManager mgr=new LevelManager(assetsRoot);
        for(String lv:mgr.listAvailableLevels()) {
            if(lv.length()==1){int idx=lv.toUpperCase().charAt(0)-'A';if(idx>=0&&idx<NUM_LEVELS)levelAvailable[idx]=true;}
        }
        renderFrame(); screenTexId=createTexture();
        var im=app.getInputManager();
        im.addMapping(A_UP,  new KeyTrigger(KeyInput.KEY_UP),    new KeyTrigger(KeyInput.KEY_W));
        im.addMapping(A_DOWN,new KeyTrigger(KeyInput.KEY_DOWN),  new KeyTrigger(KeyInput.KEY_S));
        im.addMapping(A_OK,  new KeyTrigger(KeyInput.KEY_RETURN),new KeyTrigger(KeyInput.KEY_SPACE));
        im.addMapping(A_BACK,new KeyTrigger(KeyInput.KEY_ESCAPE));
        im.addListener(this,A_UP,A_DOWN,A_OK,A_BACK);
        if(app instanceof SimpleApplication sa){sa.getViewPort().setEnabled(false);sa.getGuiViewPort().setEnabled(false);}
        fadeAlpha=0f; fadeTarget=1f;
        log.info("LevelSelectAppState ready — {} niveaux",countAvailable());
    }

    @Override
    public void update(float tpf) {
        if(!isEnabled())return;
        if(fadeAlpha<fadeTarget)fadeAlpha=Math.min(1f,fadeAlpha+tpf*3f);
        else if(fadeAlpha>fadeTarget)fadeAlpha=Math.max(0f,fadeAlpha-tpf*3f);
        if(fadeTarget==0f&&fadeAlpha<=0.01f){if(pendingBack){goBack();return;}if(pendingPlay){goPlay();return;}}
        renderFrame(); uploadFrame();
    }

    @Override
    public void render(RenderManager rm) {
        if(!isEnabled())return;
        int w=app.getCamera().getWidth(),h=app.getCamera().getHeight();
        float[] vp=lbVP(w,h);
        renderer2D.beginFrame();
        if(screenTexId>=0)renderer2D.drawTexture(screenTexId,0,0,GW,GH);
        renderer2D.drawFadeOverlay(1f-fadeAlpha);
        renderer2D.endFrame(w,h,vp);
        // Restaurer viewport plein ecran immediatement apres endFrame().
        // endFrame() laisse un viewport letterbox que JME cache comme correct.
        // En le restaurant ICI (dans render(), avant renderManager.render()),
        // le cache JME et la realite GL sont tous les deux a jour.
        glViewport(0, 0, w, h);
    }

    @Override
    public void postRender() { /* vide */ }

    @Override
    public void cleanup() {
        var im=app.getInputManager();
        for(var a:new String[]{A_UP,A_DOWN,A_OK,A_BACK})if(im.hasMapping(a))im.deleteMapping(a);
        im.removeListener(this);
        if(screenTexId>=0){glDeleteTextures(screenTexId);screenTexId=-1;}
        if(renderer2D!=null){renderer2D.destroy();renderer2D=null;}
        if(app instanceof SimpleApplication sa){sa.getViewPort().setEnabled(true);sa.getGuiViewPort().setEnabled(true);}
    }

    @Override
    public void onAction(String name,boolean p,float tpf) {
        if(!p||!isEnabled()||fadeTarget==0f)return;
        switch(name){
            case A_UP  ->{selectedIndex=(selectedIndex-1+NUM_LEVELS)%NUM_LEVELS;clampScroll();}
            case A_DOWN->{selectedIndex=(selectedIndex+1)%NUM_LEVELS;clampScroll();}
            case A_OK  ->{fadeTarget=0f;pendingPlay=true;}
            case A_BACK->{fadeTarget=0f;pendingBack=true;}
        }
    }

    private void goBack(){var sm=app.getStateManager();sm.attach(new MenuAppState(assetsRoot,jmeAssets));sm.detach(this);}
    private void goPlay(){
        log.info("→ GameAppState {}",LEVEL_NAMES[selectedIndex]);
        var sm=app.getStateManager();
        sm.attach(new GameAppState(assetsRoot,jmeAssets,selectedIndex));
        sm.detach(this);
    }

    // ── Rendu software ────────────────────────────────────────────────────────

    private void renderFrame() {
        Arrays.fill(screenBuf,0xFF080818);
        dText("SELECT LEVEL",84,10,0xFFFFFF00); dSep(20,0xFF334455);
        int y=30,end=Math.min(scrollOffset+ITEMS_PER_PAGE,NUM_LEVELS);
        for(int i=scrollOffset;i<end;i++){
            boolean sel=(i==selectedIndex),avail=levelAvailable[i];
            if(sel)fillR(0,y-1,GW,11,0xFF1A1A44);
            int col=sel?0xFF00FF88:(avail?0xFFBBCCBB:0xFF445544);
            if(sel)dText(">",22,y,0xFF00FF88);
            dText(LEVEL_NAMES[i],34,y,col);
            if(!avail)dText("[N/A]",136,y,0xFF664444);
            y+=18;
        }
        dSep(GH-20,0xFF334455);
        dText("ENTER=PLAY",30,GH-14,0xFF667788);dText("ESC=BACK",GW-60,GH-14,0xFF667788);
        if(NUM_LEVELS>ITEMS_PER_PAGE){
            int tH=ITEMS_PER_PAGE*18,bH=Math.max(6,tH*ITEMS_PER_PAGE/NUM_LEVELS);
            int bY=30+(tH-bH)*scrollOffset/Math.max(1,NUM_LEVELS-ITEMS_PER_PAGE);
            fillR(GW-6,30,4,tH,0xFF1A2233);fillR(GW-6,bY,4,bH,0xFF4488AA);
        }
    }
    private void dSep(int y,int col){for(int x=4;x<GW-4;x++)px(x,y,col);}
    private int createTexture(){int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,GW,GH,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glBindTexture(GL_TEXTURE_2D,0);return t;}
    private void uploadFrame(){if(screenTexId<0)return;uploadBuf.clear();for(int p:screenBuf){uploadBuf.put((byte)((p>>16)&0xFF));uploadBuf.put((byte)((p>>8)&0xFF));uploadBuf.put((byte)(p&0xFF));uploadBuf.put((byte)((p>>24)&0xFF));}uploadBuf.flip();glBindTexture(GL_TEXTURE_2D,screenTexId);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,GW,GH,GL_RGBA,GL_UNSIGNED_BYTE,uploadBuf);glBindTexture(GL_TEXTURE_2D,0);}
    private void dText(String t,int x,int y,int col){int cx=x;for(char c:t.toUpperCase().toCharArray()){dChar(c,cx,y,col);cx+=6;}}
    private void dChar(char c,int x,int y,int col){long[] g=glyph(c);if(g==null)return;for(int r=0;r<7;r++)for(int b=4;b>=0;b--)if(((g[r]>>b)&1)!=0)px(x+(4-b),y+r,col);}
    private void px(int x,int y,int col){if(x>=0&&x<GW&&y>=0&&y<GH)screenBuf[y*GW+x]=col;}
    private void fillR(int x,int y,int w,int h,int col){for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++)px(x+dx,y+dy,col);}
    private void clampScroll(){if(selectedIndex<scrollOffset)scrollOffset=selectedIndex;if(selectedIndex>=scrollOffset+ITEMS_PER_PAGE)scrollOffset=selectedIndex-ITEMS_PER_PAGE+1;}
    private int countAvailable(){int n=0;for(boolean b:levelAvailable)if(b)n++;return n;}
    private static float[] lbVP(int w,int h){float ga=(float)GW/GH,wa=(float)w/h,vw,vh,vx,vy;if(wa>ga){vh=h;vw=vh*ga;vx=(w-vw)/2f;vy=0;}else{vw=w;vh=vw/ga;vx=0;vy=(h-vh)/2f;}return new float[]{vx,vy,vw,vh};}

    private static long[] glyph(char c){return switch(c){
        case ' '->new long[]{0,0,0,0,0,0,0};case '>'->new long[]{0b10000,0b01000,0b00100,0b00010,0b00100,0b01000,0b10000};
        case '['->new long[]{0b01110,0b01000,0b01000,0b01000,0b01000,0b01000,0b01110};case ']'->new long[]{0b01110,0b00010,0b00010,0b00010,0b00010,0b00010,0b01110};
        case '='->new long[]{0,0b11111,0,0,0b11111,0,0};case '-'->new long[]{0,0,0,0b11111,0,0,0};
        case 'A'->new long[]{0b01110,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};case 'B'->new long[]{0b11110,0b10001,0b10001,0b11110,0b10001,0b10001,0b11110};
        case 'C'->new long[]{0b01111,0b10000,0b10000,0b10000,0b10000,0b10000,0b01111};case 'D'->new long[]{0b11110,0b10001,0b10001,0b10001,0b10001,0b10001,0b11110};
        case 'E'->new long[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b11111};case 'F'->new long[]{0b11111,0b10000,0b10000,0b11110,0b10000,0b10000,0b10000};
        case 'G'->new long[]{0b01111,0b10000,0b10000,0b10111,0b10001,0b10001,0b01111};case 'H'->new long[]{0b10001,0b10001,0b10001,0b11111,0b10001,0b10001,0b10001};
        case 'I'->new long[]{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b11111};case 'J'->new long[]{0b11111,0b00001,0b00001,0b00001,0b00001,0b10001,0b01110};
        case 'K'->new long[]{0b10001,0b10010,0b10100,0b11000,0b10100,0b10010,0b10001};case 'L'->new long[]{0b10000,0b10000,0b10000,0b10000,0b10000,0b10000,0b11111};
        case 'M'->new long[]{0b10001,0b11011,0b10101,0b10001,0b10001,0b10001,0b10001};case 'N'->new long[]{0b10001,0b11001,0b10101,0b10011,0b10001,0b10001,0b10001};
        case 'O'->new long[]{0b01110,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};case 'P'->new long[]{0b11110,0b10001,0b10001,0b11110,0b10000,0b10000,0b10000};
        case 'Q'->new long[]{0b01110,0b10001,0b10001,0b10001,0b10101,0b10010,0b01101};case 'R'->new long[]{0b11110,0b10001,0b10001,0b11110,0b10100,0b10010,0b10001};
        case 'S'->new long[]{0b01111,0b10000,0b10000,0b01110,0b00001,0b00001,0b11110};case 'T'->new long[]{0b11111,0b00100,0b00100,0b00100,0b00100,0b00100,0b00100};
        case 'U'->new long[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b10001,0b01110};case 'V'->new long[]{0b10001,0b10001,0b10001,0b10001,0b10001,0b01010,0b00100};
        case 'W'->new long[]{0b10001,0b10001,0b10101,0b10101,0b10101,0b11011,0b10001};case 'X'->new long[]{0b10001,0b01010,0b00100,0b00100,0b00100,0b01010,0b10001};
        case 'Y'->new long[]{0b10001,0b01010,0b00100,0b00100,0b00100,0b00100,0b00100};case 'Z'->new long[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b10000,0b11111};
        case '0'->new long[]{0b01110,0b10001,0b10011,0b10101,0b11001,0b10001,0b01110};case '1'->new long[]{0b00100,0b01100,0b00100,0b00100,0b00100,0b00100,0b01110};
        case '2'->new long[]{0b01110,0b10001,0b00001,0b00010,0b00100,0b01000,0b11111};case '3'->new long[]{0b01110,0b10001,0b00001,0b00110,0b00001,0b10001,0b01110};
        case '4'->new long[]{0b00010,0b00110,0b01010,0b10010,0b11111,0b00010,0b00010};case '5'->new long[]{0b11111,0b10000,0b11110,0b00001,0b00001,0b10001,0b01110};
        case '6'->new long[]{0b00110,0b01000,0b10000,0b11110,0b10001,0b10001,0b01110};case '7'->new long[]{0b11111,0b00001,0b00010,0b00100,0b01000,0b01000,0b01000};
        case '8'->new long[]{0b01110,0b10001,0b10001,0b01110,0b10001,0b10001,0b01110};case '9'->new long[]{0b01110,0b10001,0b10001,0b01111,0b00001,0b00010,0b01100};
        default->null;
    };}
}
