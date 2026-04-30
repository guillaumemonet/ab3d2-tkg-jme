package com.ab3d2.core;

import com.jme3.math.FastMath;

/**
 * Bob de marche du joueur (head bob).
 *
 * <h2>Reference ASM</h2>
 *
 * <p>Reproduit la mecanique d'Alien Breed 3D 2 trouvee dans
 * <code>hires.s</code> ligne 2842-2867 (fonction {@code Plr1_Control},
 * calcul de {@code plr1_BobbleY_l} + xwobble) :</p>
 *
 * <pre>
 *   phase = plr1_TmpBobble_w           ; angle 16-bit
 *   sin = SinCosTable[phase]            ; -32768..+32767
 *   abs_sin = |sin|
 *   bobbleY = (abs_sin + 16384) >> 4   ; toujours positif
 *   if (!Ducked && !Squished) bobbleY *= 2
 *   plr1_BobbleY_l = bobbleY
 *
 *   ; Application :
 *   ; - camera : Plr1_TmpYOff_l += BobbleY
 *   ; - arme   : weaponY += BobbleY * 1.5 / 256
 *   ; - xwobble = sin(phase) >> 6  (lateral, signed)
 * </pre>
 *
 * <p>Le {@code |sin(phase)|} produit <b>2 oscillations descendantes par cycle</b>
 * (le pas gauche puis le pas droit). C'est le head-bob classique : a chaque
 * pied pose, la tete redescend.</p>
 *
 * <p>Note : dans l'ASM le BobbleY est <em>toujours positif</em> (offset relatif
 * a la position au repos). La camera ne descend donc jamais en-dessous de sa
 * hauteur "debout neutre" — elle pulse vers le haut a chaque pas.</p>
 *
 * <h2>Increment de la phase</h2>
 *
 * <p>L'incrementation de {@code Plr1_Bobble_w} n'est <b>pas presente dans le
 * source ASM disponible</b> (la variable n'est jamais ecrite). On reconstruit
 * donc la mecanique cote Java : phase avance proportionnellement a la vitesse
 * de marche.</p>
 *
 * <h2>Lissage</h2>
 *
 * <p>L'amplitude active est lerpee vers la cible (= {@code MAX_AMP_CAMERA} *
 * walkIntensity) pour avoir des transitions douces start/stop walking.
 * Sans ce lissage, on aurait un "snap" a 0 quand on lache la touche.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>Une seule instance par joueur, partagee entre {@code GameAppState} (qui
 * appelle {@link #update} et applique {@link #getBobYCamera()} +
 * {@link #getBobX()} a la camera) et {@code WeaponViewAppState} (qui applique
 * {@link #getBobYWeaponExtra()} a l'arme).</p>
 */
public class BobController {

    // ─── Parametres (constantes) ──────────────────────────────────────────────

    /** Frequence d'un cycle complet a pleine vitesse de marche (cycles/sec).
     *  1 cycle = 2 oscillations descendantes = 2 pas.
     *  1.6 cycles/sec = ~3.2 pas/sec, rythme de marche legerement soutenue. */
    private static final float WALK_CYCLES_PER_SEC = 1.6f;

    /** Amplitude max du bob camera (Y) en unites JME.
     *
     *  <p>Session 109 patch : passe de 0.04 a 0.10 (~10 cm). 0.04 etait trop
     *  subtil pour etre vu sur des murs lointains (la camera se deplace en
     *  monde, et 4 cm ne represente que quelques pixels a l'ecran). 0.10
     *  reste subtil mais clairement perceptible.</p> */
    private static final float MAX_AMP_CAMERA = 0.10f;

    /** Multiplicateur d'amplitude additionnelle pour l'arme.
     *
     *  <p>L'ASM applique {@code BobbleY * 1.5} a l'arme et {@code BobbleY * 1.0}
     *  a la camera. La camera "porte" deja l'arme en JME (l'arme suit
     *  cam.location), donc l'arme oscille deja avec la camera. Pour atteindre
     *  les 1.5x totaux, on rajoute 0.5x extra a l'arme.</p> */
    private static final float WEAPON_AMP_FACTOR = 0.5f;

    /** Multiplicateur pour le wobble lateral X (signe).
     *  L'ASM utilise sin(phase) >> 6 pour xwobble (~ /64), pas |sin|.
     *  Ici on prend une fraction de l'amp Y.
     *
     *  <p>Session 109 patch : passe de 0.3 a 0.4 pour rendre le mouvement
     *  lateral plus perceptible (la tete ne fait pas que monter/descendre,
     *  elle tangue aussi un peu droite/gauche).</p> */
    private static final float X_AMP_FACTOR = 0.4f;

    /** Vitesse du lissage start/stop (=8 -> ~0.125 sec pour decroitre). */
    private static final float SMOOTHING = 8f;

    /** Si accroupi, amplitude divisee : ASM ne fait juste pas le {@code *= 2}
     *  qu'il fait quand debout, soit 50% de l'amplitude normale. */
    private static final float CROUCH_FACTOR = 0.5f;

    // ─── Etat ──────────────────────────────────────────────────────────────────

    private float phase = 0f;            // 0 .. 2π, wrap a chaque cycle
    private float activeAmplitude = 0f;  // amplitude lissee (~0 quand a l'arret)

    // ─── API ──────────────────────────────────────────────────────────────────

    /**
     * Avance le bob d'un frame.
     *
     * @param walkIntensity 0 = a l'arret, 1 = pleine vitesse (clampe automatiquement)
     * @param ducked true si accroupi (amplitude reduite a {@link #CROUCH_FACTOR})
     * @param tpf temps ecoule en secondes
     */
    public void update(float walkIntensity, boolean ducked, float tpf) {
        if (walkIntensity < 0f) walkIntensity = 0f;
        if (walkIntensity > 1f) walkIntensity = 1f;

        // Amplitude cible (depend du fait qu'on marche et qu'on est debout)
        float targetAmp = walkIntensity * MAX_AMP_CAMERA * (ducked ? CROUCH_FACTOR : 1f);

        // Lissage exponentiel vers la cible : activeAmp += (target - active) * t
        // avec t = SMOOTHING * tpf (clampe a 1 pour eviter overshoot).
        float t = SMOOTHING * tpf;
        if (t > 1f) t = 1f;
        activeAmplitude += (targetAmp - activeAmplitude) * t;

        // Phase avance proportionnellement a la vitesse (a 0 quand arret)
        float phaseSpeed = WALK_CYCLES_PER_SEC * walkIntensity * FastMath.TWO_PI;
        phase += phaseSpeed * tpf;
        // Wrap modulo 2π (pas besoin d'AMOD_A : un seul wrap suffit a chaque frame)
        while (phase > FastMath.TWO_PI) phase -= FastMath.TWO_PI;
    }

    /**
     * Bob Y a appliquer a la camera (toujours >= 0).
     *
     * <p>= |sin(phase)| * activeAmplitude. La camera monte par rapport a sa
     * hauteur debout neutre, jamais en-dessous (comme l'ASM).</p>
     */
    public float getBobYCamera() {
        return FastMath.abs(FastMath.sin(phase)) * activeAmplitude;
    }

    /**
     * Bob Y additionnel a appliquer a l'arme (en plus du bob camera).
     *
     * <p>= |sin(phase)| * activeAmplitude * 0.5. Combine au bob camera (qui
     * porte deja l'arme en suivant cam.location), donne 1.5x au total comme
     * dans l'ASM.</p>
     */
    public float getBobYWeaponExtra() {
        return FastMath.abs(FastMath.sin(phase)) * activeAmplitude * WEAPON_AMP_FACTOR;
    }

    /**
     * Wobble X lateral (signe, -amp..+amp).
     *
     * <p>= sin(phase) * activeAmplitude * 0.3. Applique en repere monde le
     * long du vecteur lateral de la camera (= cam.getLeft()).</p>
     */
    public float getBobX() {
        return FastMath.sin(phase) * activeAmplitude * X_AMP_FACTOR;
    }

    /** Reset complet (utilise au respawn ou changement de niveau). */
    public void reset() {
        phase = 0f;
        activeAmplitude = 0f;
    }

    // ─── Debug ────────────────────────────────────────────────────────────────

    /** Phase courante en radians (0 .. 2π). Pour debug uniquement. */
    public float getPhase() { return phase; }

    /** Amplitude active courante (lissee). Pour debug uniquement. */
    public float getActiveAmplitude() { return activeAmplitude; }
}
