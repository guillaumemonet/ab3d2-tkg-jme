package com.ab3d2;

import com.ab3d2.app.MenuAppState;
import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;

/**
 * Alien Breed 3D II — Port Java / JMonkeyEngine 3.8.1
 *
 * Deux chemins racine :
 *   assetsRoot  — ressources binaires Amiga (src/main/resources)
 *   jmeAssets   — assets JME (JSON niveaux, PNG textures)
 *                 = assets/ du projet (sur le classpath via include 'assets')
 */
public class Main extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static final int GAME_W = 320;
    public static final int GAME_H = 200;
    public static final int SCALE  = 3;

    private Path assetsRoot;   // binaires Amiga : src/main/resources
    private Path jmeAssets;    // assets JME   : assets/ projet

    public static void main(String[] args) {
        Main app = new Main();
        app.assetsRoot = resolveAssetRoot(args);
        app.jmeAssets  = resolveJmeAssets(args);
        log.info("assetsRoot : {}", app.assetsRoot.toAbsolutePath());
        log.info("jmeAssets  : {}", app.jmeAssets.toAbsolutePath());

        AppSettings s = new AppSettings(true);
        s.setTitle("Alien Breed 3D II — Java/JME Port");
        s.setWidth(GAME_W * SCALE); s.setHeight(GAME_H * SCALE);
        s.setFullscreen(false); s.setVSync(true); s.setSamples(0);
        s.setGammaCorrection(false); s.setFrameRate(60);
        s.setResizable(true); s.setAudioRenderer("LWJGL");

        app.setSettings(s);
        app.setShowSettings(false);
        app.setDisplayFps(true);
        app.setDisplayStatView(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        log.info("simpleInitApp() — JME {}", com.jme3.system.JmeVersion.FULL_NAME);
        flyCam.setEnabled(false);

        try {
            assetManager.registerLocator(
                jmeAssets.toAbsolutePath().toString(),
                com.jme3.asset.plugins.FileLocator.class);
            log.info("AssetManager FileLocator : {}", jmeAssets.toAbsolutePath());
        } catch (Exception e) {
            log.warn("FileLocator echec : {}", e.getMessage());
        }

        // SKIP_MENU = false : flux normal menu -> niveau
        boolean SKIP_MENU = true;  // menu desactive, chargement direct level A
        if (SKIP_MENU) {
            stateManager.attach(new com.ab3d2.app.GameAppState(assetsRoot, jmeAssets, 0));
        } else {
            stateManager.attach(new MenuAppState(assetsRoot, jmeAssets));
        }
    }

    @Override public void simpleUpdate(float tpf) {}

    @Override
    public void destroy() { log.info("AB3D2 shutdown"); super.destroy(); }

    // ── Résolution assetsRoot (ressources binaires Amiga) ─────────────────────

    private static Path resolveAssetRoot(String[] args) {
        for (int i = 0; i < args.length - 1; i++)
            if ("--assets".equals(args[i])) return Paths.get(args[i+1]);
        for (String c : new String[]{
            "src/main/resources",
            "build/resources/main",
            "../ab3d2-tkg-java/src/main/resources",
        }) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p) && hasAmigaAssets(p)) return p;
        }
        String env = System.getenv("AB3D2_ASSETS");
        if (env != null) return Paths.get(env);
        log.warn("Assets binaires Amiga non trouvés — ./gradlew copyResources");
        return Paths.get("src/main/resources");
    }

    /** Résout le chemin vers assets/ JME (JSON niveaux, PNG textures). */
    private static Path resolveJmeAssets(String[] args) {
        for (int i = 0; i < args.length - 1; i++)
            if ("--jme-assets".equals(args[i])) return Paths.get(args[i+1]);
        for (String c : new String[]{
            "assets",                            // depuis la racine projet (Gradle run)
            "../ab3d2-tkg-jme/assets",            // depuis un autre répertoire
        }) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p) && Files.isDirectory(p.resolve("Textures"))) return p;
        }
        log.warn("assets/ JME non trouvé — ./gradlew convertAssets convertLevels");
        return Paths.get("assets");
    }

    private static boolean hasAmigaAssets(Path p) {
        for (String s : new String[]{"menu","levels","walls"})
            if (Files.isDirectory(p.resolve(s))) return true;
        return false;
    }
}
