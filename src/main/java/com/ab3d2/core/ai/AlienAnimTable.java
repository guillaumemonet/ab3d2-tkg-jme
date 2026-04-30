package com.ab3d2.core.ai;

/**
 * Tables d'animations sprite des aliens.
 *
 * <p>Equivalent ASM : <code>GLFT_AlienAnims_l[alienType]</code> dans le GLF.
 * L'ASM lit a {@code AlienAnimPtr_l = GLFT_AlienAnims_l + type * A_AnimLen}
 * un pool de 4 sous-anims (TOWARDS / RIGHT / AWAY / LEFT), chacune contenant
 * une sequence de frames + flags (ai_DoAction, ai_FinishedAnim, etc.).</p>
 *
 * <p>Comme on n'a pas (encore) extrait ces tables du binaire, on utilise des
 * <b>conventions deduites de l'observation des sprites convertis</b> :
 * pour les WAD comme {@code alien2}, {@code guard}, {@code worm}, etc., les
 * frames {@code _fN.png} suivent un ordre standard observe :</p>
 *
 * <pre>
 *   Frames 0..3   : walk anim TOWARDS  (4 frames cyclique)
 *   Frames 4..7   : walk anim RIGHT
 *   Frames 8..11  : walk anim AWAY
 *   Frames 12..15 : walk anim LEFT
 *   Frame 16      : attack/shoot      (souvent flash / muzzle)
 *   Frame 17      : hit (encaisse)
 *   Frame 18      : die / splat
 * </pre>
 *
 * <p>Cette heuristique fonctionne pour la majorite des aliens du jeu original.
 * Pour ceux dont le WAD est plus court (ex. {@code priest} a moins de frames),
 * on clamp via {@link #pickFrame}.</p>
 *
 * <p>Quand les vraies tables ASM seront extraites du binaire (phase ulterieure),
 * cette classe pourra etre remplacee par une lecture directe de
 * {@code GLFT_AlienAnims_l}. La signature de {@link #pickFrame} restera la
 * meme.</p>
 *
 * <h2>Cycle d'animation</h2>
 *
 * <p>L'ASM cycle a chaque frame Amiga via {@code EntT_Timer2_w} qui s'incremente.
 * Quand il depasse la longueur de l'anim, retour a 0 (loop). Notre runtime
 * fait la meme chose : {@link #pickFrame} prend une "phase" 0..N-1 et retourne
 * le PNG index correspondant.</p>
 *
 * @since session 113 (phase 2.E)
 */
public final class AlienAnimTable {

    private AlienAnimTable() {} // statique uniquement

    /**
     * Nombre de frames par sous-anim de marche.
     * 4 = nombre standard observe dans tous les WAD aliens convertis.
     */
    public static final int WALK_FRAMES_PER_VIEW = 4;

    /** Index de la frame d'attaque (= {@code _f16.png} typiquement). */
    public static final int ATTACK_FRAME_BASE = 16;

    /** Index de la frame de hit (= {@code _f17.png} typiquement). */
    public static final int HIT_FRAME_BASE = 17;

    /** Index de la frame de mort (= {@code _f18.png} typiquement). */
    public static final int DIE_FRAME_BASE = 18;

    /**
     * Vitesse d'avancement de l'anim (= 1 frame PNG toutes les
     * {@code WALK_TICK_DIVISOR} frames Amiga). Plus c'est haut, plus l'anim
     * est lente. Reproduit le {@code A_FrameLen} ASM.
     *
     * <p>Phase 2.E (fix) : ajuste a 12 (= ~4 frames de marche par seconde a
     * 50 Hz Amiga, soit le cycle complet en ~1 seconde). Avant : 6 (trop
     * rapide, le cycle se faisait en 0.5 sec).</p>
     */
    public static final int WALK_TICK_DIVISOR = 12;

    /**
     * Pour les anims attack / hit / die, on fait moins de frames mais plus
     * lentes (pour bien voir l'effet).
     *
     * <p>Phase 2.E (fix) : ajuste a 8 pour ralentir le clignotement de
     * la frame d'attaque (avant : 4, trop rapide).</p>
     */
    public static final int ATTACK_TICK_DIVISOR = 8;

    /**
     * Selectionne la frame PNG a afficher selon le mode + viewpoint + phase.
     *
     * @param mode      mode courant de l'alien
     * @param vp        viewpoint (calcule par {@link AlienViewpoint#compute})
     * @param amigaFrame compteur de frames Amiga (= alien.timer2)
     * @param maxFrames nombre max de frames PNG dispo dans le WAD (clamp)
     * @return index de frame PNG (0..maxFrames-1) a charger ({@code _fN.png})
     */
    public static int pickFrame(AlienBehaviour mode, AlienViewpoint vp,
                                 int amigaFrame, int maxFrames) {
        int frame = pickFrameRaw(mode, vp, amigaFrame);
        // Clamp : si le WAD a moins de frames que l'index calcule, on retombe
        // sur la 1ere frame du viewpoint, puis 0 en derniere extremite.
        if (frame >= maxFrames) {
            int viewBase = vp.ordinal() * WALK_FRAMES_PER_VIEW;
            if (viewBase < maxFrames) return viewBase;
            return 0;
        }
        return frame;
    }

    /**
     * Calcul brut sans clamping. Sert aux tests pour valider la formule.
     */
    static int pickFrameRaw(AlienBehaviour mode, AlienViewpoint vp, int amigaFrame) {
        return switch (mode) {
            case DEFAULT, FOLLOWUP, RETREAT -> walkFrame(vp, amigaFrame);
            case RESPONSE                  -> attackOrWalkFrame(vp, amigaFrame);
            case TAKE_DAMAGE               -> HIT_FRAME_BASE;
            case DIE                       -> DIE_FRAME_BASE;
        };
    }

    /**
     * Frame de marche : cycle parmi les 4 frames du viewpoint.
     */
    private static int walkFrame(AlienViewpoint vp, int amigaFrame) {
        int phase = (amigaFrame / WALK_TICK_DIVISOR) % WALK_FRAMES_PER_VIEW;
        return vp.ordinal() * WALK_FRAMES_PER_VIEW + phase;
    }

    /**
     * Frame d'attaque : alterne entre la frame d'attaque (visible au moment
     * du tir / contact) et le 1er frame de marche du viewpoint correspondant.
     *
     * <p>Plus precisement : l'ASM fait {@code WhichAnim=1} + cycle de
     * {@code EntT_Timer2_w}. Notre approximation : phase 0 = ATTACK, autres
     * phases = walk. Comme l'attaque dure ~8 frames Amiga, on a :</p>
     * <ul>
     *   <li>frames 0..3 (Amiga) : ATTACK_FRAME_BASE</li>
     *   <li>frames 4..7 (Amiga) : viewpoint walk frame 0 (transition)</li>
     * </ul>
     */
    private static int attackOrWalkFrame(AlienViewpoint vp, int amigaFrame) {
        int phase = (amigaFrame / ATTACK_TICK_DIVISOR);
        if ((phase & 1) == 0) {
            return ATTACK_FRAME_BASE;
        }
        return vp.ordinal() * WALK_FRAMES_PER_VIEW;
    }
}
