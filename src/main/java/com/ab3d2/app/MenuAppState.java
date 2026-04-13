package com.ab3d2.app;

import com.ab3d2.assets.MenuAssets;
import com.ab3d2.menu.Ab3dFont;
import com.ab3d2.menu.FireEffect;
import com.ab3d2.menu.MenuCursor;
import com.ab3d2.menu.MenuRenderer;
import com.ab3d2.menu.ScrollingBackground;
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

import java.nio.file.Path;
import static org.lwjgl.opengl.GL33.*;

public class MenuAppState extends AbstractAppState implements ActionListener {

    private static final Logger log = LoggerFactory.getLogger(MenuAppState.class);

    private static final int GAME_W = 320, GAME_H = 200;
    private static final int MAIN_X_BYTES = 6, MAIN_CUR_Y = 70, MAIN_SPREAD = 20;

    private static final String[] MAIN_ITEMS = {
        "PLAY GAME","CONTROL OPTIONS","GAME CREDITS","LOAD POSITION","SAVE POSITION","QUIT"};
    private static final String[] QUIT_ITEMS = {"NO, I'M ADDICTED","YES! LET ME OUT"};

    private static final String A_UP="ab3d2_menu_up", A_DOWN="ab3d2_menu_down",
                                 A_OK="ab3d2_menu_ok", A_BACK="ab3d2_menu_back";

    private final Path assetsRoot;  // binaires Amiga
    private final Path jmeAssets;   // assets/ JME (JSON, PNG)
    private Application app;

    private Renderer2D renderer2D; private MenuAssets menuAssets;
    private ScrollingBackground background; private FireEffect fire;
    private MenuRenderer menuRenderer; private MenuCursor cursor; private Ab3dFont font;

    private enum Mode { MAIN, QUIT }
    private Mode  mode = Mode.MAIN; private int selectedItem = 0;
    private float fadeAlpha = 1f;   private boolean fadeIn = true;
    private int prevSelected=-1, prevGlyph=-1; private Mode prevMode=null;

    public MenuAppState(Path assetsRoot, Path jmeAssets) {
        this.assetsRoot = assetsRoot;
        this.jmeAssets  = jmeAssets;
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
        super.initialize(stateManager, app);
        this.app = app;
        log.info("MenuAppState.initialize()");

        renderer2D = new Renderer2D(GAME_W, GAME_H); renderer2D.init();
        menuAssets = new MenuAssets(); menuAssets.load(assetsRoot.resolve("menu"));
        background = new ScrollingBackground();
        background.init(menuAssets.getBack2Raw(), menuAssets.getBackpal());
        fire = new FireEffect(); fire.setCooling(3); fire.init();
        menuRenderer = new MenuRenderer(menuAssets.getFontRaw());
        cursor = new MenuCursor();
        font   = new Ab3dFont(menuAssets.getFontTexture());

        var im = app.getInputManager();
        im.addMapping(A_UP,   new KeyTrigger(KeyInput.KEY_UP),     new KeyTrigger(KeyInput.KEY_W));
        im.addMapping(A_DOWN, new KeyTrigger(KeyInput.KEY_DOWN),   new KeyTrigger(KeyInput.KEY_S));
        im.addMapping(A_OK,   new KeyTrigger(KeyInput.KEY_RETURN), new KeyTrigger(KeyInput.KEY_SPACE));
        im.addMapping(A_BACK, new KeyTrigger(KeyInput.KEY_ESCAPE));
        im.addListener(this, A_UP, A_DOWN, A_OK, A_BACK);
        if (app instanceof SimpleApplication sa) {
            sa.getViewPort().setEnabled(false); sa.getGuiViewPort().setEnabled(false);
        }
        rebuildTextLayer();
        log.info("MenuAppState ready");
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled()) return;
        if (fadeIn) { fadeAlpha = Math.max(0f, fadeAlpha - tpf*2f); if (fadeAlpha<=0) fadeIn=false; }
        background.update(); cursor.update(tpf);
        int g=cursor.getCurrentGlyph();
        if(mode!=prevMode||selectedItem!=prevSelected||g!=prevGlyph){
            rebuildTextLayer(); prevMode=mode; prevSelected=selectedItem; prevGlyph=g;}
        fire.update();
    }

    @Override
    public void render(RenderManager rm) {
        if (!isEnabled()||renderer2D==null) return;
        int w=app.getCamera().getWidth(), h=app.getCamera().getHeight();
        float[] vp=letterbox(w,h);
        renderer2D.beginFrame();
        background.render(renderer2D, GAME_W, GAME_H);
        renderer2D.drawTexture(fire.getTexture(), 0, 0, GAME_W, FireEffect.H);
        drawMenuText();
        if (fadeAlpha>0.01f) renderer2D.drawFadeOverlay(fadeAlpha);
        renderer2D.endFrame(w,h,vp);
        // Restaurer viewport plein ecran (meme raison que LevelSelectAppState)
        glViewport(0, 0, w, h);
    }

    @Override
    public void postRender() { /* vide */ }

    @Override
    public void cleanup() {
        var im=app.getInputManager();
        for(var a:new String[]{A_UP,A_DOWN,A_OK,A_BACK}) if(im.hasMapping(a)) im.deleteMapping(a);
        im.removeListener(this);
        if(renderer2D!=null){renderer2D.destroy();renderer2D=null;}
        if(fire!=null){fire.destroy();fire=null;}
        if(background!=null){background.destroy();background=null;}
        if(menuAssets!=null){menuAssets.destroy();menuAssets=null;}
        if(app instanceof SimpleApplication sa){sa.getViewPort().setEnabled(true);sa.getGuiViewPort().setEnabled(true);}
    }

    @Override
    public void onAction(String name, boolean p, float tpf) {
        if(!p||!isEnabled()) return;
        switch(name){
            case A_UP -> moveSelection(-1); case A_DOWN -> moveSelection(1);
            case A_OK -> activate();       case A_BACK -> handleBack();
        }
    }

    private void moveSelection(int d){selectedItem=(selectedItem+d+items().length)%items().length;cursor.reset();}

    private void activate() {
        switch(mode) {
            case MAIN -> {
                switch(items()[selectedItem]) {
                    case "PLAY GAME" -> switchTo(new LevelSelectAppState(assetsRoot, jmeAssets));
                    case "QUIT"      -> {mode=Mode.QUIT;selectedItem=0;cursor.reset();}
                    default          -> log.info("'{}' non implémenté", items()[selectedItem]);
                }
            }
            case QUIT -> { if(selectedItem==1) app.stop(); else {mode=Mode.MAIN;selectedItem=0;cursor.reset();} }
        }
    }

    private void handleBack() {
        if(mode==Mode.MAIN){mode=Mode.QUIT;selectedItem=0;cursor.reset();}
        else{mode=Mode.MAIN;selectedItem=0;cursor.reset();}
    }

    private void switchTo(AbstractAppState next){app.getStateManager().attach(next);app.getStateManager().detach(this);}
    private String[] items(){return mode==Mode.MAIN?MAIN_ITEMS:QUIT_ITEMS;}

    private void rebuildTextLayer() {
        menuRenderer.clear();
        String[] it=items();
        for(int i=0;i<it.length;i++) menuRenderer.drawString(it[i],MAIN_X_BYTES,MAIN_CUR_Y+i*MAIN_SPREAD);
        menuRenderer.drawString(String.valueOf((char)cursor.getCurrentGlyph()),MAIN_X_BYTES-2,MAIN_CUR_Y+selectedItem*MAIN_SPREAD);
        fire.setTextLayer(menuRenderer.getTextLayer());
    }

    private void drawMenuText() {
        if(font==null) return;
        String[] it=items();
        for(int i=0;i<it.length;i++) font.drawStringBytes(renderer2D,it[i],MAIN_X_BYTES,MAIN_CUR_Y+i*MAIN_SPREAD);
        font.drawString(renderer2D,String.valueOf((char)cursor.getCurrentGlyph()),(MAIN_X_BYTES-2)*8f,MAIN_CUR_Y+selectedItem*MAIN_SPREAD);
    }

    private static float[] letterbox(int w,int h){
        float ga=(float)GAME_W/GAME_H,wa=(float)w/h,vw,vh,vx,vy;
        if(wa>ga){vh=h;vw=vh*ga;vx=(w-vw)/2f;vy=0;}
        else{vw=w;vh=vw/ga;vx=0;vy=(h-vh)/2f;}
        return new float[]{vx,vy,vw,vh};
    }
}
