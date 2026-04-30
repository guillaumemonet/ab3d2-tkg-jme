package com.ab3d2.tools;

import com.jme3.export.*;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.control.Control;
import com.jme3.util.BufferUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Base64;

/**
 * Control qui anime les positions d'un Mesh en cyclant entre les frames
 * stockees en tant que UserData sur la Geometry.
 *
 * <p><b>Ce Control n'est PAS stocke dans le j3o.</b> Il est ajoute au
 * runtime via la methode statique {@link #attachIfAnimated(Spatial)} qui
 * lit les UserData et cree les controls en consequence.</p>
 *
 * <p>UserData attendues sur une Geometry animee :</p>
 * <ul>
 *   <li><code>"vectobj.framesB64"</code> : String base64 encodant les float[]
 *       positions (little-endian IEEE754). Total = numFrames * numVerts * 3 floats.</li>
 *   <li><code>"vectobj.numFrames"</code> : int - nombre de frames</li>
 *   <li><code>"vectobj.fps"</code> : float - fps (optionnel, defaut 10)</li>
 * </ul>
 *
 * <p>Pourquoi base64 ? JME UserData n'accepte que Integer, Float, String,
 * Savable, List, Map -- pas les `float[]` bruts (erreur "Unsupported type: [F").
 * Encoder en base64 evite cette limitation tout en gardant la compacite
 * (4 bytes float -> ~5.5 chars base64).</p>
 *
 * <p>Cette approche evite les problemes de serialisation j3o : les UserData
 * sont des types primitifs natifs JME qui se chargent sans risque, et le
 * Control n'a pas besoin d'etre enregistre dans SavableClassUtil.</p>
 */
public class VectObjFrameAnimControl extends AbstractControl {

    public static final String KEY_FRAMES_B64 = "vectobj.framesB64";
    public static final String KEY_NUM_FRAMES = "vectobj.numFrames";
    public static final String KEY_FPS        = "vectobj.fps";

    /** Positions par frame : frames[frameIdx][vertIdx*3 + 0..2]. Reconstruit depuis UserData. */
    private float[][] frames;

    /** FPS de l'animation (frames par seconde). */
    private float fps = 10f;

    /** Frame courante (pour interpolation interne). */
    private float currentFrame = 0f;

    /** Si true, boucle indefiniment. Sinon stoppe a la derniere frame. */
    private boolean looping = true;

    /** Si true, l'anim est en cours. */
    private boolean playing = true;

    /** Pas de constructeur public : utiliser attachIfAnimated(). */
    private VectObjFrameAnimControl() {}

    /**
     * Parcourt recursivement un Spatial et attache un VectObjFrameAnimControl
     * a toutes les Geometry qui ont les UserData d'animation.
     *
     * <p>A appeler apres chargement d'un j3o de vectobj :</p>
     * <pre>
     *   Spatial model = assetManager.loadModel("Scenes/vectobj/passkey.j3o");
     *   VectObjFrameAnimControl.attachIfAnimated(model);
     *   rootNode.attachChild(model);
     * </pre>
     *
     * @return nombre de Geometry animees trouvees
     */
    public static int attachIfAnimated(Spatial root) {
        int count = 0;
        if (root instanceof Geometry geo) {
            if (tryAttach(geo)) count++;
        } else if (root instanceof Node n) {
            for (Spatial child : n.getChildren()) {
                count += attachIfAnimated(child);
            }
        }
        return count;
    }

    private static boolean tryAttach(Geometry geo) {
        // Deja un control attache ? Ne pas dupliquer
        if (geo.getControl(VectObjFrameAnimControl.class) != null) return false;

        Object b64Data = geo.getUserData(KEY_FRAMES_B64);
        Object numFramesData = geo.getUserData(KEY_NUM_FRAMES);
        if (!(b64Data instanceof String b64) || !(numFramesData instanceof Integer)) {
            return false;
        }
        int numFrames = (Integer) numFramesData;
        if (numFrames < 2) return false;

        float[] flat = decodeB64ToFloats(b64);
        if (flat == null || flat.length == 0 || flat.length % numFrames != 0) {
            return false;
        }
        int frameSize = flat.length / numFrames;
        float[][] frames = new float[numFrames][frameSize];
        for (int i = 0; i < numFrames; i++) {
            System.arraycopy(flat, i * frameSize, frames[i], 0, frameSize);
        }

        VectObjFrameAnimControl ctrl = new VectObjFrameAnimControl();
        ctrl.frames = frames;
        Object fpsData = geo.getUserData(KEY_FPS);
        ctrl.fps = (fpsData instanceof Float) ? (Float) fpsData : 10f;
        geo.addControl(ctrl);
        return true;
    }

    /**
     * Stocke les frames d'animation en tant que UserData sur une Geometry.
     * A utiliser lors de la conversion (VectObjConverter) pour que les frames
     * soient serialisees dans le j3o de facon sure.
     *
     * @param geo       Geometry cible
     * @param frames    float[numFrames][numVerts*3] des positions par frame
     * @param fps       FPS d'animation (ou 0 pour defaut 10)
     */
    public static void storeFrames(Geometry geo, float[][] frames, float fps) {
        if (frames == null || frames.length < 2) return;
        int frameSize = frames[0].length;
        float[] flat = new float[frames.length * frameSize];
        for (int i = 0; i < frames.length; i++) {
            if (frames[i].length != frameSize) return;  // frames incoherentes
            System.arraycopy(frames[i], 0, flat, i * frameSize, frameSize);
        }
        String b64 = encodeFloatsToB64(flat);
        geo.setUserData(KEY_FRAMES_B64, b64);
        geo.setUserData(KEY_NUM_FRAMES, frames.length);
        if (fps > 0) geo.setUserData(KEY_FPS, fps);
    }

    /**
     * Encode un float[] en String base64 (little-endian).
     * Chaque float occupe 4 bytes = ~5.5 chars base64.
     */
    private static String encodeFloatsToB64(float[] floats) {
        ByteBuffer bb = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) bb.putFloat(f);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    /**
     * Decode une String base64 en float[] (little-endian).
     */
    private static float[] decodeB64ToFloats(String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            if (bytes.length % 4 != 0) return null;
            float[] out = new float[bytes.length / 4];
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < out.length; i++) out[i] = bb.getFloat();
            return out;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setFps(float fps)              { this.fps = Math.max(0.1f, fps); }
    public float getFps()                      { return fps; }
    public void setLooping(boolean looping)    { this.looping = looping; }
    public boolean isLooping()                 { return looping; }
    public void setPlaying(boolean playing)    { this.playing = playing; }
    public boolean isPlaying()                 { return playing; }
    public int getNumFrames()                  { return frames == null ? 0 : frames.length; }
    public int getCurrentFrameIndex() {
        if (frames == null || frames.length == 0) return 0;
        int idx = ((int) currentFrame) % frames.length;
        return idx < 0 ? idx + frames.length : idx;
    }

    /**
     * Avance (ou recule) manuellement la frame courante, puis force la mise a jour
     * du mesh avec cette frame. Utile pour un viewer en mode pause.
     *
     * @param delta +1 pour frame suivante, -1 pour precedente
     */
    public void stepFrame(int delta) {
        if (frames == null || frames.length < 2) return;
        currentFrame = getCurrentFrameIndex() + delta;
        // Normaliser dans [0, frames.length)
        while (currentFrame < 0) currentFrame += frames.length;
        currentFrame = currentFrame % frames.length;
        applyCurrentFrameToMesh();
    }

    /** Applique immediatement la frame courante au mesh, independamment de playing. */
    private void applyCurrentFrameToMesh() {
        if (!(spatial instanceof Geometry geo)) return;
        int frameIdx = getCurrentFrameIndex();
        Mesh mesh = geo.getMesh();
        if (mesh == null) return;
        VertexBuffer vb = mesh.getBuffer(VertexBuffer.Type.Position);
        if (vb == null || vb.getData() == null) return;

        FloatBuffer fb = (FloatBuffer) vb.getData();
        float[] frameData = frames[frameIdx];
        int len = Math.min(frameData.length, fb.capacity());
        fb.clear();
        fb.put(frameData, 0, len);
        fb.flip();
        vb.updateData(fb);
        mesh.updateBound();
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (!playing || frames == null || frames.length < 2) return;
        if (!(spatial instanceof Geometry geo)) return;

        currentFrame += tpf * fps;
        int frameIdx;
        if (looping) {
            frameIdx = ((int) currentFrame) % frames.length;
            if (frameIdx < 0) frameIdx += frames.length;
        } else {
            frameIdx = Math.min((int) currentFrame, frames.length - 1);
        }

        Mesh mesh = geo.getMesh();
        if (mesh == null) return;
        VertexBuffer vb = mesh.getBuffer(VertexBuffer.Type.Position);
        if (vb == null || vb.getData() == null) return;

        FloatBuffer fb = (FloatBuffer) vb.getData();
        float[] frameData = frames[frameIdx];
        int len = Math.min(frameData.length, fb.capacity());
        fb.clear();
        fb.put(frameData, 0, len);
        fb.flip();
        vb.updateData(fb);
        mesh.updateBound();
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // rien a faire ici
    }

    @Override
    public Control cloneForSpatial(Spatial spatial) {
        VectObjFrameAnimControl c = new VectObjFrameAnimControl();
        c.frames = frames;
        c.fps = fps;
        c.looping = looping;
        c.playing = playing;
        return c;
    }
}
