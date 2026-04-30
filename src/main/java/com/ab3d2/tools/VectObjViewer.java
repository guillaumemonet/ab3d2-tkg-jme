package com.ab3d2.tools;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.*;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.debug.Grid;
import com.jme3.system.AppSettings;

import java.io.File;
import java.util.*;

/**
 * Viewer standalone pour inspecter les modeles vectobj (.j3o) et leurs animations.
 *
 * <h2>Usage</h2>
 * <pre>
 *   ./gradlew viewVectObj                        # ouvre le premier modele
 *   ./gradlew viewVectObj -Pmodel=rifle          # ouvre un modele specifique
 * </pre>
 *
 * <h2>Controles</h2>
 * <ul>
 *   <li><b>Fleches gauche/droite</b> : modele precedent/suivant</li>
 *   <li><b>Espace</b> : play/pause l'animation</li>
 *   <li><b>+ / -</b> : accelerer / ralentir FPS (pas de 1)</li>
 *   <li><b>N</b> : frame suivante (pause force)</li>
 *   <li><b>B</b> : frame precedente (pause force)</li>
 *   <li><b>R</b> : reset rotation camera</li>
 *   <li><b>G</b> : toggle grille au sol</li>
 *   <li><b>W</b> : toggle mode wireframe</li>
 *   <li><b>A</b> : toggle auto-rotation du modele</li>
 *   <li><b>Souris drag gauche</b> : orbit autour du modele</li>
 *   <li><b>Souris molette</b> : zoom</li>
 *   <li><b>Escape</b> : quitter</li>
 * </ul>
 */
public class VectObjViewer extends SimpleApplication {

    private static final String VECTOBJ_DIR = "assets/Scenes/vectobj";
    private static final float  DEFAULT_CAM_DIST = 5f;
    private static final float  MIN_CAM_DIST = 0.5f;
    private static final float  MAX_CAM_DIST = 50f;
    private static final float  MOUSE_ORBIT_SENSITIVITY = 0.01f;
    private static final float  WHEEL_ZOOM_FACTOR = 1.2f;
    private static final float  AUTO_ROTATE_SPEED = 0.6f;  // rad/s

    // Action names
    private static final String A_PREV_MODEL = "prev_model";
    private static final String A_NEXT_MODEL = "next_model";
    private static final String A_TOGGLE_PLAY = "toggle_play";
    private static final String A_FPS_UP = "fps_up";
    private static final String A_FPS_DOWN = "fps_down";
    private static final String A_FRAME_NEXT = "frame_next";
    private static final String A_FRAME_PREV = "frame_prev";
    private static final String A_RESET_CAM = "reset_cam";
    private static final String A_TOGGLE_GRID = "toggle_grid";
    private static final String A_TOGGLE_WIREFRAME = "toggle_wireframe";
    private static final String A_TOGGLE_AUTOROTATE = "toggle_autorotate";
    private static final String A_MOUSE_LEFT = "mouse_left";
    private static final String A_MOUSE_WHEEL_UP = "mouse_wheel_up";
    private static final String A_MOUSE_WHEEL_DOWN = "mouse_wheel_down";
    private static final String A_MOUSE_X = "mouse_x";
    private static final String A_MOUSE_X_NEG = "mouse_x_neg";
    private static final String A_MOUSE_Y = "mouse_y";
    private static final String A_MOUSE_Y_NEG = "mouse_y_neg";

    // Etat
    private List<String> modelNames = new ArrayList<>();
    private int currentIdx = 0;
    private String forcedModel = null;

    private Node modelNode;               // parent contenant le modele courant
    private Node pivotNode;               // pivot pour auto-rotation
    private Spatial currentModel;

    private Node gridNode;
    private boolean gridVisible = true;
    private boolean wireframe = false;
    private boolean autoRotate = true;

    // Camera orbit
    private float camYaw = FastMath.PI * 0.25f;
    private float camPitch = -FastMath.PI * 0.15f;
    private float camDist = DEFAULT_CAM_DIST;
    private boolean mouseOrbiting = false;
    private float mouseDx = 0f, mouseDy = 0f;

    // UI
    private BitmapText titleText;
    private BitmapText infoText;
    private BitmapText controlsText;

    // Animation state (pour UI)
    private float customFpsOverride = -1f;  // si >0, override le FPS de l'anim
    private boolean paused = false;
    private int manualFrameStep = 0;        // >0 = avance, <0 = recule, puis reset

    public static void main(String[] args) {
        VectObjViewer app = new VectObjViewer();
        if (args.length > 0) app.forcedModel = args[0];

        AppSettings settings = new AppSettings(true);
        settings.setTitle("VectObj Viewer");
        settings.setResolution(1200, 800);
        settings.setResizable(true);
        settings.setVSync(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // Background neutre gris foncé
        viewPort.setBackgroundColor(new ColorRGBA(0.15f, 0.15f, 0.18f, 1f));
        flyCam.setEnabled(false);

        // Scan des vectobj
        scanModels();
        if (modelNames.isEmpty()) {
            System.err.println("Aucun .j3o trouve dans " + VECTOBJ_DIR);
            System.err.println("Lancer 'gradlew convertVectObj' d'abord");
            stop();
            return;
        }

        // Modele force en parametre ?
        if (forcedModel != null) {
            String n = forcedModel.toLowerCase().replace(".j3o", "");
            int found = modelNames.indexOf(n);
            if (found >= 0) currentIdx = found;
            else System.err.println("Modele non trouve : " + forcedModel + " - utilise " + modelNames.get(0));
        }

        // Pivot + modelNode pour auto-rotation autour de l'axe Y du modele
        pivotNode = new Node("pivot");
        modelNode = new Node("modelRoot");
        pivotNode.attachChild(modelNode);
        rootNode.attachChild(pivotNode);

        // Lumieres
        setupLights();
        setupGrid();
        setupInput();
        setupUI();

        loadCurrentModel();
        updateCamera();
    }

    private void scanModels() {
        modelNames.clear();
        File dir = new File(VECTOBJ_DIR);
        if (!dir.isDirectory()) {
            // Essai alternatif via Path relatif au repertoire d'execution
            dir = new File("assets/Scenes/vectobj");
        }
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".j3o"));
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            modelNames.add(n.substring(0, n.length() - 4));
        }
        Collections.sort(modelNames);
    }

    private void setupLights() {
        AmbientLight ambient = new AmbientLight(new ColorRGBA(0.4f, 0.4f, 0.45f, 1f));
        rootNode.addLight(ambient);

        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-0.5f, -0.8f, -0.3f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(1.1f));
        rootNode.addLight(sun);

        DirectionalLight fill = new DirectionalLight();
        fill.setDirection(new Vector3f(0.5f, -0.3f, 0.8f).normalizeLocal());
        fill.setColor(new ColorRGBA(0.6f, 0.6f, 0.7f, 1f));
        rootNode.addLight(fill);
    }

    private void setupGrid() {
        gridNode = new Node("grid");
        Grid grid = new Grid(21, 21, 0.5f);
        Geometry g = new Geometry("grid", grid);
        Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", new ColorRGBA(0.3f, 0.3f, 0.35f, 1f));
        m.getAdditionalRenderState().setLineWidth(1f);
        g.setMaterial(m);
        // Centrer la grille et la placer au sol (y=0)
        g.setLocalTranslation(-5.25f, 0f, -5.25f);
        gridNode.attachChild(g);

        // Axes XYZ colorés au centre
        gridNode.attachChild(makeAxisLine("axisX", new Vector3f(0,0,0), new Vector3f(1,0,0), ColorRGBA.Red));
        gridNode.attachChild(makeAxisLine("axisY", new Vector3f(0,0,0), new Vector3f(0,1,0), ColorRGBA.Green));
        gridNode.attachChild(makeAxisLine("axisZ", new Vector3f(0,0,0), new Vector3f(0,0,1), ColorRGBA.Blue));

        rootNode.attachChild(gridNode);
    }

    private Geometry makeAxisLine(String name, Vector3f from, Vector3f to, ColorRGBA color) {
        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Lines);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{
            from.x, from.y, from.z, to.x, to.y, to.z
        });
        mesh.setBuffer(VertexBuffer.Type.Index, 2, new short[]{0, 1});
        mesh.updateBound();

        Geometry geo = new Geometry(name, mesh);
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setLineWidth(2f);
        geo.setMaterial(mat);
        return geo;
    }

    private void setupInput() {
        inputManager.deleteMapping(INPUT_MAPPING_EXIT);  // on le re-ajoute

        // Clavier
        inputManager.addMapping(A_PREV_MODEL, new KeyTrigger(KeyInput.KEY_LEFT));
        inputManager.addMapping(A_NEXT_MODEL, new KeyTrigger(KeyInput.KEY_RIGHT));
        inputManager.addMapping(A_TOGGLE_PLAY, new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping(A_FPS_UP, new KeyTrigger(KeyInput.KEY_ADD),
                                            new KeyTrigger(KeyInput.KEY_EQUALS));
        inputManager.addMapping(A_FPS_DOWN, new KeyTrigger(KeyInput.KEY_SUBTRACT),
                                            new KeyTrigger(KeyInput.KEY_MINUS));
        inputManager.addMapping(A_FRAME_NEXT, new KeyTrigger(KeyInput.KEY_N));
        inputManager.addMapping(A_FRAME_PREV, new KeyTrigger(KeyInput.KEY_B));
        inputManager.addMapping(A_RESET_CAM, new KeyTrigger(KeyInput.KEY_R));
        inputManager.addMapping(A_TOGGLE_GRID, new KeyTrigger(KeyInput.KEY_G));
        inputManager.addMapping(A_TOGGLE_WIREFRAME, new KeyTrigger(KeyInput.KEY_W));
        inputManager.addMapping(A_TOGGLE_AUTOROTATE, new KeyTrigger(KeyInput.KEY_A));
        inputManager.addMapping(INPUT_MAPPING_EXIT, new KeyTrigger(KeyInput.KEY_ESCAPE));

        // Souris
        inputManager.addMapping(A_MOUSE_LEFT, new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping(A_MOUSE_WHEEL_UP, new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
        inputManager.addMapping(A_MOUSE_WHEEL_DOWN, new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));
        inputManager.addMapping(A_MOUSE_X, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping(A_MOUSE_X_NEG, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping(A_MOUSE_Y, new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping(A_MOUSE_Y_NEG, new MouseAxisTrigger(MouseInput.AXIS_Y, true));

        ActionListener actions = (name, isPressed, tpf) -> {
            if (!isPressed) {
                if (name.equals(A_MOUSE_LEFT)) mouseOrbiting = false;
                return;
            }
            switch (name) {
                case A_PREV_MODEL      -> cycleModel(-1);
                case A_NEXT_MODEL      -> cycleModel(+1);
                case A_TOGGLE_PLAY     -> togglePlay();
                case A_FPS_UP          -> bumpFps(+1f);
                case A_FPS_DOWN        -> bumpFps(-1f);
                case A_FRAME_NEXT      -> { paused = true; manualFrameStep = +1; applyPausedState(); }
                case A_FRAME_PREV      -> { paused = true; manualFrameStep = -1; applyPausedState(); }
                case A_RESET_CAM       -> resetCamera();
                case A_TOGGLE_GRID     -> { gridVisible = !gridVisible;
                                            gridNode.setCullHint(gridVisible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always); }
                case A_TOGGLE_WIREFRAME-> toggleWireframe();
                case A_TOGGLE_AUTOROTATE -> autoRotate = !autoRotate;
                case A_MOUSE_LEFT      -> mouseOrbiting = true;
                case A_MOUSE_WHEEL_UP  -> camDist = Math.max(MIN_CAM_DIST, camDist / WHEEL_ZOOM_FACTOR);
                case A_MOUSE_WHEEL_DOWN-> camDist = Math.min(MAX_CAM_DIST, camDist * WHEEL_ZOOM_FACTOR);
                case INPUT_MAPPING_EXIT -> stop();
            }
        };

        AnalogListener analogs = (name, value, tpf) -> {
            switch (name) {
                case A_MOUSE_X     -> mouseDx += value;
                case A_MOUSE_X_NEG -> mouseDx -= value;
                case A_MOUSE_Y     -> mouseDy += value;
                case A_MOUSE_Y_NEG -> mouseDy -= value;
            }
        };

        inputManager.addListener(actions,
            A_PREV_MODEL, A_NEXT_MODEL, A_TOGGLE_PLAY, A_FPS_UP, A_FPS_DOWN,
            A_FRAME_NEXT, A_FRAME_PREV, A_RESET_CAM, A_TOGGLE_GRID,
            A_TOGGLE_WIREFRAME, A_TOGGLE_AUTOROTATE, A_MOUSE_LEFT,
            A_MOUSE_WHEEL_UP, A_MOUSE_WHEEL_DOWN, INPUT_MAPPING_EXIT);
        inputManager.addListener(analogs,
            A_MOUSE_X, A_MOUSE_X_NEG, A_MOUSE_Y, A_MOUSE_Y_NEG);

        inputManager.setCursorVisible(true);
    }

    private void setupUI() {
        // Titre en haut
        titleText = new BitmapText(guiFont);
        titleText.setSize(guiFont.getCharSet().getRenderedSize() * 1.6f);
        titleText.setColor(ColorRGBA.Yellow);
        titleText.setLocalTranslation(16f, settings.getHeight() - 16f, 0f);
        guiNode.attachChild(titleText);

        // Info animation en haut a droite
        infoText = new BitmapText(guiFont);
        infoText.setSize(guiFont.getCharSet().getRenderedSize());
        infoText.setColor(ColorRGBA.White);
        infoText.setLocalTranslation(16f, settings.getHeight() - 60f, 0f);
        guiNode.attachChild(infoText);

        // Controles en bas
        controlsText = new BitmapText(guiFont);
        controlsText.setSize(guiFont.getCharSet().getRenderedSize() * 0.9f);
        controlsText.setColor(new ColorRGBA(0.75f, 0.85f, 1f, 1f));
        controlsText.setLocalTranslation(16f, 16f + controlsText.getSize() * 7, 0f);
        controlsText.setText(
            "[<-/->] model  [Space] play/pause  [+/-] fps  [N/B] step frame  [R] reset cam\n" +
            "[G] grid  [W] wireframe  [A] autorotate  [Esc] quit  |  Drag L=orbit  Wheel=zoom"
        );
        guiNode.attachChild(controlsText);
    }

    private void loadCurrentModel() {
        // Detacher l'ancien
        if (currentModel != null) {
            modelNode.detachChild(currentModel);
            currentModel = null;
        }

        String name = modelNames.get(currentIdx);
        String path = "Scenes/vectobj/" + name + ".j3o";

        try {
            currentModel = assetManager.loadModel(path);
        } catch (Exception e) {
            System.err.println("Erreur chargement " + path + " : " + e.getMessage());
            return;
        }
        modelNode.attachChild(currentModel);

        // Normaliser l'echelle pour que le modele tienne dans une sphere de ~2 unites
        normalizeScale(currentModel);

        // Attacher les controles d'animation (lit les UserData framesB64)
        int animated = VectObjFrameAnimControl.attachIfAnimated(currentModel);

        // Reset auto-rotation
        pivotNode.setLocalRotation(pivotNode.getLocalRotation().IDENTITY);
        pivotNode.rotate(0, 0, 0);

        // Reset etats anim
        paused = false;
        customFpsOverride = -1f;
        manualFrameStep = 0;
        applyPausedState();

        updateTitleAndInfo(animated);
    }

    /** Ramène le modele a une taille "raisonnable" et le centre a l'origine verticalement. */
    private void normalizeScale(Spatial model) {
        model.setLocalScale(1f);
        model.setLocalTranslation(0f, 0f, 0f);
        model.updateModelBound();
        com.jme3.bounding.BoundingVolume bv = model.getWorldBound();
        if (bv instanceof com.jme3.bounding.BoundingBox bb) {
            float maxExtent = Math.max(bb.getXExtent(), Math.max(bb.getYExtent(), bb.getZExtent()));
            if (maxExtent > 0.001f) {
                float targetHalfSize = 1.5f;
                float s = targetHalfSize / maxExtent;
                model.setLocalScale(s);
                // Centrer verticalement (base au sol)
                float yMin = (bb.getCenter().y - bb.getYExtent()) * s;
                model.setLocalTranslation(0f, -yMin, 0f);
            }
        }
    }

    private void cycleModel(int delta) {
        currentIdx = (currentIdx + delta + modelNames.size()) % modelNames.size();
        loadCurrentModel();
    }

    private void togglePlay() {
        paused = !paused;
        applyPausedState();
    }

    private void applyPausedState() {
        if (currentModel == null) return;
        currentModel.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                VectObjFrameAnimControl c = g.getControl(VectObjFrameAnimControl.class);
                if (c != null) c.setPlaying(!paused);
            }
        });
    }

    private void bumpFps(float delta) {
        if (customFpsOverride < 0f) {
            // Premiere modification : partir du fps courant (ou 10 par defaut)
            float[] currentFps = {10f};
            if (currentModel != null) {
                currentModel.depthFirstTraversal(s -> {
                    if (s instanceof Geometry g) {
                        VectObjFrameAnimControl c = g.getControl(VectObjFrameAnimControl.class);
                        if (c != null) currentFps[0] = c.getFps();
                    }
                });
            }
            customFpsOverride = currentFps[0];
        }
        customFpsOverride = Math.max(0.5f, customFpsOverride + delta);
        if (currentModel != null) {
            float f = customFpsOverride;
            currentModel.depthFirstTraversal(s -> {
                if (s instanceof Geometry g) {
                    VectObjFrameAnimControl c = g.getControl(VectObjFrameAnimControl.class);
                    if (c != null) c.setFps(f);
                }
            });
        }
    }

    private void toggleWireframe() {
        wireframe = !wireframe;
        if (currentModel == null) return;
        currentModel.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                Material m = g.getMaterial();
                if (m != null) m.getAdditionalRenderState().setWireframe(wireframe);
            }
        });
    }

    private void resetCamera() {
        camYaw = FastMath.PI * 0.25f;
        camPitch = -FastMath.PI * 0.15f;
        camDist = DEFAULT_CAM_DIST;
    }

    private void updateTitleAndInfo(int animatedGeos) {
        String name = modelNames.get(currentIdx);
        titleText.setText(String.format("%s  (%d/%d)",
            name, currentIdx + 1, modelNames.size()));

        StringBuilder info = new StringBuilder();
        info.append("Modele : ").append(name).append(".j3o\n");

        // Compter geometries + vertices
        int[] counters = {0, 0};  // {geoCount, vertCount}
        currentModel.depthFirstTraversal(s -> {
            if (s instanceof Geometry g) {
                counters[0]++;
                Mesh m = g.getMesh();
                if (m != null) counters[1] += m.getVertexCount();
            }
        });
        info.append(String.format("Geometries : %d  |  Vertices : %d%n",
            counters[0], counters[1]));

        if (animatedGeos == 0) {
            info.append("Animation : aucune (modele statique)");
        } else {
            // Infos sur la premiere geometry animee
            int[] frameCount = {0};
            float[] fps = {0f};
            boolean[] foundOne = {false};
            currentModel.depthFirstTraversal(s -> {
                if (foundOne[0]) return;
                if (s instanceof Geometry g) {
                    VectObjFrameAnimControl c = g.getControl(VectObjFrameAnimControl.class);
                    if (c != null) {
                        frameCount[0] = c.getNumFrames();
                        fps[0] = c.getFps();
                        foundOne[0] = true;
                    }
                }
            });
            info.append(String.format("Animation : %d frames @ %.1f fps  (%d geos animees)",
                frameCount[0], fps[0], animatedGeos));
        }
        infoText.setText(info.toString());
    }

    @Override
    public void simpleUpdate(float tpf) {
        // Gestion souris orbit
        if (mouseOrbiting) {
            camYaw -= mouseDx * MOUSE_ORBIT_SENSITIVITY;
            camPitch -= mouseDy * MOUSE_ORBIT_SENSITIVITY;
            camPitch = FastMath.clamp(camPitch,
                -FastMath.HALF_PI * 0.95f, FastMath.HALF_PI * 0.95f);
        }
        mouseDx = mouseDy = 0f;

        // Auto-rotation du modele autour de Y (sauf si paused par utilisateur)
        if (autoRotate && !paused) {
            pivotNode.rotate(0, AUTO_ROTATE_SPEED * tpf, 0);
        }

        // Step manuel de frame (N/B)
        if (manualFrameStep != 0 && currentModel != null) {
            final int step = manualFrameStep;
            manualFrameStep = 0;
            currentModel.depthFirstTraversal(s -> {
                if (s instanceof Geometry g) {
                    VectObjFrameAnimControl c = g.getControl(VectObjFrameAnimControl.class);
                    if (c != null) c.stepFrame(step);
                }
            });
        }

        updateCamera();

        // Mise a jour HUD (FPS anim peut changer)
        if (titleText != null && customFpsOverride > 0f) {
            String name = modelNames.get(currentIdx);
            titleText.setText(String.format("%s  (%d/%d)   FPS=%.1f %s",
                name, currentIdx + 1, modelNames.size(),
                customFpsOverride,
                paused ? "[PAUSED]" : ""));
        } else if (titleText != null && paused) {
            String name = modelNames.get(currentIdx);
            titleText.setText(String.format("%s  (%d/%d)   [PAUSED]",
                name, currentIdx + 1, modelNames.size()));
        }
    }

    private void updateCamera() {
        // Camera spherique autour de l'origine (le modele est centre)
        float cx = FastMath.cos(camYaw) * FastMath.cos(camPitch) * camDist;
        float cy = FastMath.sin(camPitch) * camDist;
        float cz = FastMath.sin(camYaw) * FastMath.cos(camPitch) * camDist;
        cam.setLocation(new Vector3f(cx, cy + 0.75f, cz));  // regarde un peu au-dessus du centre
        cam.lookAt(new Vector3f(0f, 0.75f, 0f), Vector3f.UNIT_Y);
    }
}
