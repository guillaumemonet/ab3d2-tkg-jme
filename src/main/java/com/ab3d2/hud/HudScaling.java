package com.ab3d2.hud;

/**
 * Convertit les coordonnees natives Amiga (320x256) vers les coordonnees pixel
 * de la fenetre JME (n'importe quelle resolution).
 *
 * <p>Le principe est simple : on calcule un <b>facteur de scale uniforme</b>
 * qui preserve le ratio 320:256, avec letterbox si necessaire. Toutes les
 * positions et tailles du HUD passent par cette classe avant d'etre appliquees
 * a l'ecran.</p>
 *
 * <h2>Modes de scaling</h2>
 * <ul>
 *   <li><b>FIT</b> : scale uniforme + letterbox pour preserver le ratio (defaut).
 *       Le HUD s'affiche sans deformation, avec des bandes noires si besoin.</li>
 *   <li><b>STRETCH</b> : scale non-uniforme, utilise toute la fenetre, HUD deforme.
 *       Utile pour des resolutions non-4:3.</li>
 *   <li><b>PIXEL_PERFECT</b> : scale entier uniquement (1x, 2x, 3x...). Rendu net.</li>
 * </ul>
 *
 * <h2>Systeme de coordonnees</h2>
 * <ul>
 *   <li><b>Coord natives</b> : origine en haut-gauche (0,0), y vers le bas, 320x256.</li>
 *   <li><b>Coord ecran JME</b> : origine en bas-gauche (0,0), y vers le haut.
 *       Cette classe fait la conversion automatiquement.</li>
 * </ul>
 */
public final class HudScaling {

    public enum Mode { FIT, STRETCH, PIXEL_PERFECT }

    /** Dimensions de la fenetre JME cible. */
    private final int screenWidth;
    private final int screenHeight;
    /** Mode de scaling choisi. */
    private final Mode mode;

    /** Facteur de scale X (screenPx / nativePx). */
    private final float scaleX;
    /** Facteur de scale Y. */
    private final float scaleY;
    /** Offset horizontal (letterbox gauche) en pixels ecran. */
    private final float offsetX;
    /** Offset vertical (letterbox bas) en pixels ecran. */
    private final float offsetY;

    public HudScaling(int screenWidth, int screenHeight) {
        this(screenWidth, screenHeight, Mode.FIT);
    }

    public HudScaling(int screenWidth, int screenHeight, Mode mode) {
        this.screenWidth  = screenWidth;
        this.screenHeight = screenHeight;
        this.mode         = mode;

        float sx = (float) screenWidth  / HudLayout.NATIVE_WIDTH;
        float sy = (float) screenHeight / HudLayout.NATIVE_HEIGHT;

        switch (mode) {
            case STRETCH -> {
                this.scaleX  = sx;
                this.scaleY  = sy;
                this.offsetX = 0;
                this.offsetY = 0;
            }
            case PIXEL_PERFECT -> {
                // Scale entier maximum qui rentre dans l'ecran
                int integerScale = Math.max(1, (int) Math.floor(Math.min(sx, sy)));
                this.scaleX = integerScale;
                this.scaleY = integerScale;
                this.offsetX = (screenWidth  - HudLayout.NATIVE_WIDTH  * integerScale) / 2f;
                this.offsetY = (screenHeight - HudLayout.NATIVE_HEIGHT * integerScale) / 2f;
            }
            case FIT -> {
                // Scale uniforme maximum qui preserve le ratio
                float uniformScale = Math.min(sx, sy);
                this.scaleX = uniformScale;
                this.scaleY = uniformScale;
                this.offsetX = (screenWidth  - HudLayout.NATIVE_WIDTH  * uniformScale) / 2f;
                this.offsetY = (screenHeight - HudLayout.NATIVE_HEIGHT * uniformScale) / 2f;
            }
            default -> throw new IllegalStateException("mode inconnu : " + mode);
        }
    }

    // ---------- Conversions de coordonnees ----------

    /**
     * Convertit un X natif en X ecran JME.
     * Les coord JME partent du bas-gauche, mais l'axe X est le meme.
     */
    public float toScreenX(int nativeX) {
        return offsetX + nativeX * scaleX;
    }

    /**
     * Convertit un Y natif en Y ecran JME.
     * <b>Attention</b> : Y natif va vers le bas, Y JME va vers le haut.
     * Cette methode fait la conversion : {@code screenY = screenHeight - (offsetY + nativeY*scale) - itemHeight}.
     *
     * <p>Pour un point (sans hauteur), utiliser {@link #toScreenYPoint(int)} a la place.</p>
     *
     * @param nativeY Y natif du <b>bord superieur</b> de l'element
     * @param nativeHeight Hauteur native de l'element
     */
    public float toScreenYWithHeight(int nativeY, int nativeHeight) {
        float scaledHeight = nativeHeight * scaleY;
        return screenHeight - (offsetY + nativeY * scaleY) - scaledHeight;
    }

    /**
     * Convertit un point Y natif (sans hauteur) en Y ecran JME.
     * Attention : pour un element ayant une hauteur, prefere {@link #toScreenYWithHeight}.
     */
    public float toScreenYPoint(int nativeY) {
        return screenHeight - (offsetY + nativeY * scaleY);
    }

    /** Convertit une largeur native en largeur ecran. */
    public float toScreenWidth(int nativeWidth) {
        return nativeWidth * scaleX;
    }

    /** Convertit une hauteur native en hauteur ecran. */
    public float toScreenHeight(int nativeHeight) {
        return nativeHeight * scaleY;
    }

    // ---------- Getters ----------

    public int   getScreenWidth()  { return screenWidth; }
    public int   getScreenHeight() { return screenHeight; }
    public Mode  getMode()         { return mode; }
    public float getScaleX()       { return scaleX; }
    public float getScaleY()       { return scaleY; }
    public float getOffsetX()      { return offsetX; }
    public float getOffsetY()      { return offsetY; }

    /**
     * Retourne le rectangle ecran occupe par le HUD (zone 320x256 scalee, hors letterbox).
     * @return [x, y, width, height] en coord JME (bas-gauche, Y vers le haut)
     */
    public float[] getHudScreenRect() {
        return new float[] {
            offsetX,
            offsetY,  // JME : Y du bas du HUD = offsetY
            HudLayout.NATIVE_WIDTH  * scaleX,
            HudLayout.NATIVE_HEIGHT * scaleY,
        };
    }

    /**
     * Convertit un rectangle natif [x, y, w, h] en rectangle JME [x_bl, y_bl, w, h].
     * JME utilise le coin bas-gauche comme origine, donc y_bl = screenHeight - (nativeY+nativeH)*scale - offset.
     *
     * @param nativeRect [x, y, width, height] en coord natives (top-left, Y vers le bas)
     * @return [x, y, width, height] en coord JME (bottom-left, Y vers le haut)
     */
    public float[] toScreenRect(int[] nativeRect) {
        float w = nativeRect[2] * scaleX;
        float h = nativeRect[3] * scaleY;
        float x = offsetX + nativeRect[0] * scaleX;
        float y = screenHeight - (offsetY + nativeRect[1] * scaleY) - h;
        return new float[] { x, y, w, h };
    }
}
