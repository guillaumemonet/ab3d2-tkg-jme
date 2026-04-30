package com.ab3d2.core.ai;

/**
 * Vue du sprite alien selon l'angle relatif a la camera.
 *
 * <p>Equivalent ASM : {@code newaliencontrol.s::ViewpointToDraw}. L'ASM
 * choisit l'une des 4 directions selon le produit scalaire de l'angle alien
 * et de l'angle vue camera :</p>
 *
 * <pre>
 *   ViewpointToDraw:
 *     d3 = EntT_CurrentAngle_w(a0) - Vis_AngPos_w  ; angle relatif
 *     d2 = sin(d3)                                  ; composante laterale
 *     d3 = cos(d3)                                  ; composante avant
 *     d0 = -d3                                      ; -cos
 *     if d0 &gt; 0 -&gt; FacingTowardsPlayer (cos &lt; 0 = de dos camera)
 *     ; sinon : FAP (face a la camera, cos &gt; 0)
 * </pre>
 *
 * <p>Resume : on compare |sin| et |cos| pour determiner lequel des 4 quadrants
 * (TOWARDS / RIGHT / AWAY / LEFT) le sprite doit afficher.</p>
 *
 * <p>Ordinal = ordre dans le pool d'anims ASM ({@code GLFT_AlienAnims_l[type]}) :</p>
 * <ul>
 *   <li>0 = TOWARDS (de face, l'alien regarde la camera)</li>
 *   <li>1 = RIGHT (profil, l'alien va vers la droite de la camera)</li>
 *   <li>2 = AWAY (de dos, l'alien tourne le dos a la camera)</li>
 *   <li>3 = LEFT (profil, va vers la gauche)</li>
 * </ul>
 *
 * <p>Cet ordre suit l'ASM ({@code TOWARDSFRAME=0, RIGHTFRAME=1, AWAYFRAME=2, LEFTFRAME=3}).</p>
 *
 * @since session 113 (phase 2.E)
 */
public enum AlienViewpoint {
    TOWARDS,
    RIGHT,
    AWAY,
    LEFT;

    /**
     * Calcule la vue d'un alien depuis la camera, ASM-fidele.
     *
     * @param alienAngle  angle de l'alien sur 0..4095 (= EntT_CurrentAngle_w)
     * @param cameraAngle angle de la camera sur 0..4095 (= Vis_AngPos_w)
     * @return la vue a dessiner
     */
    public static AlienViewpoint compute(int alienAngle, int cameraAngle) {
        // Angle relatif entre [-2048, 2048] (= [-180, 180] deg)
        int rel = (alienAngle - cameraAngle) & 0xFFF;
        // Conversion en radians pour sin/cos
        double rad = rel * (2.0 * Math.PI / 4096.0);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);

        // Reproduit la logique ASM ViewpointToDraw :
        // - cos &lt; 0 (= -cos &gt; 0) = FacingTowardsPlayer (vers la camera)
        // - cos &gt; 0 = FAP (Facing Away from Player)
        if (cos < 0) {
            // FacingTowardsPlayer
            //   if sin &gt; 0: |sin| &gt; |cos| ? LEFT  : TOWARDS
            //   if sin &le; 0: |sin| &gt; |cos| ? RIGHT : TOWARDS
            if (Math.abs(sin) > -cos) {
                return sin > 0 ? LEFT : RIGHT;
            }
            return TOWARDS;
        } else {
            // FacingAwayFromPlayer
            //   if sin &gt; 0: |sin| &gt; |cos| ? LEFT : AWAY
            //   if sin &le; 0: |sin| &gt; |cos| ? RIGHT : AWAY
            if (Math.abs(sin) > cos) {
                return sin > 0 ? LEFT : RIGHT;
            }
            return AWAY;
        }
    }
}
