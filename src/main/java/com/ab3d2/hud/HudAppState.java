package com.ab3d2.hud;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;

/**
 * Affichage du HUD d'Alien Breed 3D II dans JME.
 *
 * <p>Architecture (respecte celle du jeu original) :</p>
 * <ul>
 *   <li>{@link HudLayout} : geometrie native Amiga 320x256 (source de verite).</li>
 *   <li>{@link HudScaling} : conversion vers la resolution ecran (quelle qu'elle soit).</li>
 *   <li>{@link HudState} : valeurs dynamiques (AMMO, ENERGY, arme, messages).</li>
 *   <li>Ce AppState : rendu JME qui lit HudState a chaque frame et positionne les
 *       elements via HudScaling.</li>
 * </ul>
 *
 * <p>Elements rendus :</p>
 * <ul>
 *   <li>Fond statique : {@code newborder.png} scale proportionnellement (letterbox si besoin).</li>
 *   <li>Texte AMMO : 3 chiffres sur le panneau bas a la position (HUD_AMMO_COUNT_X, HUD_AMMO_COUNT_Y).</li>
 *   <li>Texte ENERGY : 3 chiffres a (HUD_ENERGY_COUNT_X, HUD_ENERGY_COUNT_Y).</li>
 *   <li>Surbrillance de l'arme selectionnee : chiffre rouge dans les slots.</li>
 *   <li>Messages runtime : liste defilante dans la zone sous la 3D.</li>
 * </ul>
 *
 * <p>La zone 3D (centre noir du HUD) reste transparente au rendu : la vue 3D du
 * {@code GameAppState} etant rendue AVANT ce AppState, elle apparait en dessous
 * aux bons endroits.</p>
 *
 * <h2>Gestion de la resolution</h2>
 * Lors du redimensionnement de la fenetre, appeler {@link #onResize(int, int)}
 * pour recalculer le scaling et repositionner les elements.
 */
public class HudAppState extends AbstractAppState {

    private static final Logger log = LoggerFactory.getLogger(HudAppState.class);

    // ---------- Couleurs (tirees du dump panelcols) ----------

    private static final ColorRGBA COL_AMMO_NORMAL   = new ColorRGBA(0xDD/255f, 0xAA/255f, 0x00/255f, 1f); // $0DA0
    private static final ColorRGBA COL_ENERGY_NORMAL = new ColorRGBA(0xAA/255f, 0x88/255f, 0x00/255f, 1f); // $0A80
    private static final ColorRGBA COL_COUNT_LOW     = new ColorRGBA(0xDD/255f, 0x11/255f, 0x11/255f, 1f); // $0E00 = rouge
    private static final ColorRGBA COL_WEAPON_ACTIVE = new ColorRGBA(0xAA/255f, 0x00/255f, 0x00/255f, 1f); // rouge
    private static final ColorRGBA COL_WEAPON_AVAIL  = new ColorRGBA(0xFF/255f, 0xFF/255f, 0x22/255f, 1f); // $0FF2 = jaune
    private static final ColorRGBA COL_WEAPON_EMPTY  = new ColorRGBA(0x88/255f, 0x88/255f, 0x88/255f, 1f); // $0888 = gris

    private static final ColorRGBA COL_MSG_NARRATIVE = new ColorRGBA(0.2f, 1.0f, 0.2f, 1f);
    private static final ColorRGBA COL_MSG_DEFAULT   = new ColorRGBA(0.2f, 0.8f, 0.2f, 1f);
    private static final ColorRGBA COL_MSG_OPTIONS   = new ColorRGBA(0.5f, 0.5f, 0.8f, 1f);
    private static final ColorRGBA COL_MSG_OTHER     = new ColorRGBA(0.7f, 0.7f, 0.7f, 1f);

    // ---------- Configuration ----------

    /** Modele de l'etat affichable (AMMO, ENERGY, ...). Expose pour mise a jour externe. */
    private final HudState state;
    /** Mode d'affichage : SMALL_SCREEN ou FULL_SCREEN. */
    private HudMode mode;

    // ---------- Scaling / viewport courants ----------

    private HudScaling scaling;
    private SimpleApplication sa;

    // ---------- Node racine dans le GuiNode ----------

    private Node           hudRoot;
    private Geometry       borderQuad;
    private Material       borderMat;

    // Textes dynamiques
    private BitmapText     ammoText;
    private BitmapText     energyText;
    private BitmapText[]   weaponTexts;       // un par slot 1..0
    private BitmapText[]   messageTexts;      // MSG_MAX_LINES_SMALL

    // ---------- Mode debug : rectangles colores aux positions natives ----------

    /** Active/desactive l'overlay debug (rectangles colores sur les elements du HUD). */
    private boolean debugOverlay = false;
    /** Liste des geometries debug (a recreer a chaque relayout). */
    private final java.util.List<Geometry> debugGeoms = new java.util.ArrayList<>();

    public HudAppState() {
        this(new HudState(), HudMode.SMALL_SCREEN);
    }

    public HudAppState(HudState state, HudMode mode) {
        this.state = state;
        this.mode  = mode;
    }

    public HudState getState()              { return state; }
    public HudMode  getMode()               { return mode; }
    public HudScaling getScaling()          { return scaling; }

    public void setMode(HudMode m) {
        this.mode = m;
        if (hudRoot != null) relayout();
    }

    /** Active/desactive l'overlay debug (rectangles sur les elements du HUD). */
    public void setDebugOverlay(boolean enabled) {
        this.debugOverlay = enabled;
        if (hudRoot != null) relayout();
    }

    public boolean isDebugOverlay() { return debugOverlay; }

    /**
     * Retourne le rectangle ecran [x, y, width, height] (coord JME, origine bas-gauche)
     * ou la zone 3D doit etre rendue. A utiliser pour configurer le viewport de la camera 3D.
     *
     * @return [x, y, w, h] ou null si non-initialise
     */
    public float[] get3DViewportRect() {
        if (scaling == null) return null;
        int[] nativeRect = (mode == HudMode.FULL_SCREEN)
            ? HudLayout.getFullscreen3DViewport()
            : HudLayout.getSmall3DViewport(HudLayout.SMALL_YPOS_DEFAULT);
        return scaling.toScreenRect(nativeRect);
    }

    // ────────────────────── Cycle de vie AppState ──────────────────────

    @Override
    public void initialize(AppStateManager sm, Application app) {
        super.initialize(sm, app);
        this.sa = (SimpleApplication) app;

        int w = sa.getCamera().getWidth();
        int h = sa.getCamera().getHeight();
        this.scaling = new HudScaling(w, h);

        hudRoot = new Node("HudRoot");
        sa.getGuiNode().attachChild(hudRoot);

        buildBorder();
        buildCounters();
        buildWeaponSlots();
        buildMessageLines();

        relayout();
        log.info("HudAppState init : ecran {}x{}, scale={}, mode={}",
            w, h, String.format("%.3f", scaling.getScaleX()), mode);
    }

    @Override
    public void update(float tpf) {
        if (!isEnabled() || hudRoot == null) return;

        // AMMO
        ammoText.setText(String.format("%03d", state.getAmmoCount()));
        ammoText.setColor(state.isAmmoLow() ? COL_COUNT_LOW : COL_AMMO_NORMAL);

        // ENERGY
        energyText.setText(String.format("%03d", state.getEnergyCount()));
        energyText.setColor(state.isEnergyLow() ? COL_COUNT_LOW : COL_ENERGY_NORMAL);

        // Weapon slots : mettre en surbrillance l'arme selectionnee
        for (int slot = 0; slot < HudLayout.NUM_WEAPON_SLOTS; slot++) {
            ColorRGBA c;
            if (slot == state.getSelectedWeapon())      c = COL_WEAPON_ACTIVE;
            else if (state.isWeaponAvailable(slot))     c = COL_WEAPON_AVAIL;
            else                                         c = COL_WEAPON_EMPTY;
            weaponTexts[slot].setColor(c);
        }

        // Messages : affiche les derniers messages (max MSG_MAX_LINES_SMALL)
        int visibleCount = 0;
        for (HudState.Message m : state.getMessages()) {
            if (visibleCount >= messageTexts.length) break;
            messageTexts[visibleCount].setText(m.text());
            messageTexts[visibleCount].setColor(colorForTag(m.tag()));
            messageTexts[visibleCount].setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            visibleCount++;
        }
        // Cacher les lignes restantes
        for (int i = visibleCount; i < messageTexts.length; i++) {
            messageTexts[i].setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        }
    }

    @Override
    public void cleanup() {
        if (hudRoot != null) {
            sa.getGuiNode().detachChild(hudRoot);
            hudRoot = null;
        }
    }

    /**
     * Doit etre appele si la fenetre est redimensionnee. Recalcule le scaling et
     * repositionne tous les elements du HUD.
     */
    public void onResize(int newWidth, int newHeight) {
        this.scaling = new HudScaling(newWidth, newHeight);
        relayout();
        log.info("HudAppState resize : {}x{}", newWidth, newHeight);
    }

    // ────────────────────── Construction des elements ──────────────────────

    private void buildBorder() {
        // Le fond = newborder.png avec zone centrale rendue transparente (pour voir la 3D a travers)
        //
        // TODO(HD-border) : a terme, refaire les bordures en haute resolution (assets HD custom).
        // L'architecture HudLayout + HudScaling supportera directement un newborder.png
        // plus grand (ex: 1920x1080) sans changement de code : seul le fichier PNG changera.
        // Voir CHANGELOG session 74.
        Texture tex = loadBorderWithTransparentCenter("Interface/newborder.png");
        tex.setMagFilter(Texture.MagFilter.Nearest);   // pixel-art : pas de filtrage
        tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);

        borderMat = new Material(sa.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        borderMat.setTexture("ColorMap", tex);
        borderMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);

        // Quad de taille 1x1 : on le scalera via setLocalScale() dans relayout()
        borderQuad = new Geometry("HudBorder", new Quad(1f, 1f));
        borderQuad.setMaterial(borderMat);
        borderQuad.setQueueBucket(Bucket.Gui);
        hudRoot.attachChild(borderQuad);
    }

    private void buildCounters() {
        BitmapFont font = loadFont();
        ammoText   = makeText(font, "000");
        energyText = makeText(font, "000");
        hudRoot.attachChild(ammoText);
        hudRoot.attachChild(energyText);
    }

    private void buildWeaponSlots() {
        BitmapFont font = loadFont();
        weaponTexts = new BitmapText[HudLayout.NUM_WEAPON_SLOTS];
        // Les chiffres affiches sont "1, 2, 3, 4, 5, 6, 7, 8, 9, 0"
        String[] labels = {"1","2","3","4","5","6","7","8","9","0"};
        for (int i = 0; i < HudLayout.NUM_WEAPON_SLOTS; i++) {
            weaponTexts[i] = makeText(font, labels[i]);
            hudRoot.attachChild(weaponTexts[i]);
        }
    }

    private void buildMessageLines() {
        BitmapFont font = loadFont();
        messageTexts = new BitmapText[HudLayout.MSG_MAX_LINES_SMALL];
        for (int i = 0; i < messageTexts.length; i++) {
            messageTexts[i] = makeText(font, "");
            messageTexts[i].setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            hudRoot.attachChild(messageTexts[i]);
        }
    }

    // ────────────────────── Positionnement (tout passe par HudScaling) ──────────────────────

    /**
     * Recalcule la taille et la position de tous les elements en fonction du scaling
     * courant. A appeler apres {@link #onResize} ou un changement de mode.
     */
    private void relayout() {
        layoutBorder();
        layoutCounters();
        layoutWeaponSlots();
        layoutMessages();
        layoutDebugOverlay();
    }

    /**
     * Dessine (si activee) une grille debug avec :
     * <ul>
     *   <li>Cyan : contour de la zone HUD complete (320x256 native)</li>
     *   <li>Jaune : zone 3D (small screen)</li>
     *   <li>Magenta : rectangles de chaque compteur (AMMO/ENERGY)</li>
     *   <li>Vert : rectangles de chaque slot d'arme</li>
     *   <li>Orange : zone messages</li>
     * </ul>
     */
    private void layoutDebugOverlay() {
        // Nettoyer les anciens
        for (Geometry g : debugGeoms) g.removeFromParent();
        debugGeoms.clear();
        if (!debugOverlay) return;

        // Zone HUD complete
        addDebugRect(0, 0, HudLayout.NATIVE_WIDTH, HudLayout.NATIVE_HEIGHT,
            new ColorRGBA(0, 1, 1, 0.15f), "debugHudBounds");

        // Zone 3D (small screen)
        int[] v3d = HudLayout.getSmall3DViewport(HudLayout.SMALL_YPOS_DEFAULT);
        addDebugRect(v3d[0], v3d[1], v3d[2], v3d[3],
            new ColorRGBA(1, 1, 0, 0.10f), "debug3DView");

        // AMMO counter
        addDebugRect(HudLayout.HUD_AMMO_COUNT_X, HudLayout.HUD_AMMO_COUNT_Y,
            HudLayout.COUNT_W, HudLayout.HUD_CHAR_H,
            new ColorRGBA(1, 0, 1, 0.5f), "debugAmmoRect");

        // ENERGY counter
        addDebugRect(HudLayout.HUD_ENERGY_COUNT_X, HudLayout.HUD_ENERGY_COUNT_Y,
            HudLayout.COUNT_W, HudLayout.HUD_CHAR_H,
            new ColorRGBA(1, 0, 1, 0.5f), "debugEnergyRect");

        // Weapon slots
        for (int i = 0; i < HudLayout.NUM_WEAPON_SLOTS; i++) {
            addDebugRect(
                HudLayout.getWeaponSlotX(i), HudLayout.getWeaponSlotTopY(),
                HudLayout.HUD_CHAR_SMALL_W, HudLayout.HUD_CHAR_SMALL_H,
                new ColorRGBA(0, 1, 0, 0.5f), "debugSlot" + i);
        }

        // Zone messages
        int msgTopY = HudLayout.getMessageTopY(HudLayout.SMALL_YPOS_DEFAULT);
        int msgBotY = HudLayout.getMessageBottomY();
        addDebugRect(HudLayout.getMessageLeftX(), msgTopY,
            HudLayout.NATIVE_WIDTH - 2 * HudLayout.getMessageLeftX(),
            msgBotY - msgTopY,
            new ColorRGBA(1, 0.5f, 0, 0.15f), "debugMsgRect");

        log.info("Debug overlay actif : {} rectangles", debugGeoms.size());
    }

    /** Ajoute un rectangle colore a la position native [nativeX, nativeY, nativeW, nativeH]. */
    private void addDebugRect(int nativeX, int nativeY, int nativeW, int nativeH,
                              ColorRGBA color, String name) {
        float[] r = scaling.toScreenRect(new int[] { nativeX, nativeY, nativeW, nativeH });
        Material mat = new Material(sa.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        Geometry g = new Geometry(name, new Quad(r[2], r[3]));
        g.setMaterial(mat);
        g.setQueueBucket(Bucket.Gui);
        g.setLocalTranslation(r[0], r[1], 2f);  // z=2 : devant borders et textes
        hudRoot.attachChild(g);
        debugGeoms.add(g);
    }

    private void layoutBorder() {
        if (mode == HudMode.FULL_SCREEN) {
            // En fullscreen, le panneau bas seul reste visible (on cachera le reste
            // via une texture partielle plus tard). Pour l'instant on affiche tout le border.
            borderQuad.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            return;
        }
        borderQuad.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);

        float[] r = scaling.getHudScreenRect();  // [x, y_bl, w, h]
        borderQuad.setLocalTranslation(r[0], r[1], 0f);
        borderQuad.setLocalScale(r[2], r[3], 1f);
    }

    private void layoutCounters() {
        // AMMO
        placeText(ammoText,
            HudLayout.HUD_AMMO_COUNT_X,
            HudLayout.HUD_AMMO_COUNT_Y,
            HudLayout.HUD_CHAR_H);

        // ENERGY
        placeText(energyText,
            HudLayout.HUD_ENERGY_COUNT_X,
            HudLayout.HUD_ENERGY_COUNT_Y,
            HudLayout.HUD_CHAR_H);
    }

    private void layoutWeaponSlots() {
        for (int i = 0; i < HudLayout.NUM_WEAPON_SLOTS; i++) {
            placeText(weaponTexts[i],
                HudLayout.getWeaponSlotX(i),
                HudLayout.getWeaponSlotTopY(),
                HudLayout.HUD_CHAR_SMALL_H);
        }
    }

    private void layoutMessages() {
        int smallY = HudLayout.SMALL_YPOS_DEFAULT;
        int startY = HudLayout.getMessageTopY(smallY);

        for (int i = 0; i < messageTexts.length; i++) {
            int lineY = startY + i * (HudLayout.MSG_CHAR_H + HudLayout.TEXT_Y_SPACING);
            placeText(messageTexts[i],
                HudLayout.getMessageLeftX(),
                lineY,
                HudLayout.MSG_CHAR_H);
        }
    }

    // ────────────────────── Helpers ──────────────────────

    /**
     * Positionne un BitmapText a une coordonnee native, en calculant la taille de police
     * et la position JME (bas-gauche).
     *
     * <p>JME {@code BitmapText} pose son {@code localTranslation} sur le <b>coin
     * haut-gauche du rectangle de texte</b>. La taille passee a {@code setSize(s)}
     * est la hauteur de ligne (ascent+descent), pas la hauteur du glyph lui-meme.</p>
     *
     * <p>Probleme : la police BitmapFont par defaut de JME (Arial 16) a des
     * metriques differentes du pixel-font Amiga (chaque glyph fait pile la hauteur
     * specifiee). Il faut donc sur-dimensionner legerement la police ecran pour
     * que la <i>hauteur visible</i> des chiffres corresponde a nativeGlyphH.</p>
     *
     * @param txt          le BitmapText a positionner
     * @param nativeX      X natif du coin haut-gauche du glyph
     * @param nativeTopY   Y natif du coin haut-gauche du glyph
     * @param nativeGlyphH hauteur native du glyph (pour calculer la taille police)
     */
    private void placeText(BitmapText txt, int nativeX, int nativeTopY, int nativeGlyphH) {
        // La hauteur ecran desiree pour le glyph = hauteur native * scale
        float glyphScreenH = scaling.toScreenHeight(nativeGlyphH);

        // BitmapText.setSize fixe la hauteur de LIGNE (ascent+descent de la police),
        // qui est typiquement ~1.3x la hauteur des capitales. On sur-dimensionne donc
        // pour que les chiffres (tous majuscules/digits) aient la bonne hauteur visible.
        // NOTE : le facteur exact depend de la police. Pour Arial 16 (JME default),
        // le rapport capHeight/lineHeight est ~0.70.
        float lineHeight = glyphScreenH / 0.70f;
        txt.setSize(lineHeight);

        // Position ecran du coin haut-gauche du texte
        float sx = scaling.toScreenX(nativeX);
        float sy = scaling.toScreenYPoint(nativeTopY);

        // Compensation verticale : BitmapText localTranslation se place en haut de la
        // ligne (inclut l'ascent au-dessus des capitales). On remonte de (lineHeight-glyphScreenH)/2
        // pour centrer le glyph visible sur la coord native.
        float verticalOffset = (lineHeight - glyphScreenH) * 0.5f;
        sy += verticalOffset;

        txt.setLocalTranslation(sx, sy, 1f);  // z=1 pour etre devant le border
    }

    private BitmapFont loadFont() {
        return sa.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
    }

    /**
     * Charge newborder.png et remplace les pixels (quasi-)noirs de la zone 3D centrale
     * par des pixels transparents (alpha=0). Cela permet a la vue 3D rendue dessous
     * d'apparaitre à travers le cadre HUD, sans toucher au fichier original.
     *
     * <p>Methodologie :</p>
     * <ul>
     *   <li>On delimite la zone centrale en coords image (pas en coords ecran) :
     *       [HUD_BORDER_WIDTH, HUD_BORDER_WIDTH] -> [NATIVE_WIDTH-HUD_BORDER_WIDTH,
     *       HUD_BORDER_WIDTH + SMALL_HEIGHT + zone messages]. On evite le panneau
     *       bas (qui contient AMMO/ENERGY) et les bordures laterales.</li>
     *   <li>Pour chaque pixel de cette zone, si R+G+B &lt; seuil, on met alpha=0.</li>
     * </ul>
     */
    private Texture loadBorderWithTransparentCenter(String path) {
        BufferedImage src;
        try (InputStream in = sa.getAssetManager().locateAsset(
                new com.jme3.asset.AssetKey<>(path)).openStream()) {
            src = ImageIO.read(in);
        } catch (IOException e) {
            log.error("Impossible de charger {} : {}", path, e.getMessage());
            return sa.getAssetManager().loadTexture(path);
        }

        int imgW = src.getWidth();
        int imgH = src.getHeight();

        // Le PNG peut etre stocke dans une taille differente de 320x256 (par ex si
        // IffExtractor a produit un PNG 320x256 exactement, ou une autre taille).
        // On applique le meme ratio pour trouver la zone 3D dans l'image.
        float scaleX = (float) imgW / HudLayout.NATIVE_WIDTH;
        float scaleY = (float) imgH / HudLayout.NATIVE_HEIGHT;

        // Zone a rendre transparente = zone 3D + zone messages (tout ce qui n'est pas
        // bordure ni panneau bas). En natif : x=[16, 304], y=[16, 232].
        int clearX0 = Math.round(HudLayout.HUD_BORDER_WIDTH * scaleX);
        int clearY0 = Math.round(HudLayout.HUD_BORDER_WIDTH * scaleY);
        int clearX1 = Math.round((HudLayout.NATIVE_WIDTH  - HudLayout.HUD_BORDER_WIDTH) * scaleX);
        int clearY1 = Math.round(HudLayout.getMessageBottomY() * scaleY);  // 232 en natif

        // Seuil "presque noir" : R+G+B &lt; 30 (evite de trouer les parties colorees
        // qui auraient une composante sombre)
        final int BLACK_THRESHOLD = 30;

        BufferedImage out = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        int transparentPixels = 0;
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                int argb = src.getRGB(x, y);
                if (x >= clearX0 && x < clearX1 && y >= clearY0 && y < clearY1) {
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8)  & 0xFF;
                    int b =  argb        & 0xFF;
                    if (r + g + b < BLACK_THRESHOLD) {
                        argb = 0x00000000;  // fully transparent
                        transparentPixels++;
                    }
                }
                out.setRGB(x, y, argb);
            }
        }
        log.info("newborder : zone 3D = [{}..{}, {}..{}], {} pixels rendus transparents",
            clearX0, clearX1, clearY0, clearY1, transparentPixels);

        // Convertir BufferedImage en Texture JME via AWTLoader
        AWTLoader loader = new AWTLoader();
        Image jmeImg = loader.load(out, true);  // flipY=true pour JME
        return new Texture2D(jmeImg);
    }

    private BitmapText makeText(BitmapFont font, String initial) {
        BitmapText t = new BitmapText(font, false);
        t.setText(initial);
        t.setQueueBucket(Bucket.Gui);
        return t;
    }

    private ColorRGBA colorForTag(int tag) {
        return switch (tag) {
            case HudState.Message.TAG_NARRATIVE -> COL_MSG_NARRATIVE;
            case HudState.Message.TAG_OPTIONS   -> COL_MSG_OPTIONS;
            case HudState.Message.TAG_OTHER     -> COL_MSG_OTHER;
            default                             -> COL_MSG_DEFAULT;
        };
    }
}
