package com.ab3d2.combat;

import com.jme3.math.Vector3f;

/**
 * Contrat permettant au {@link PlayerShootSystem} de connaitre la position
 * et la direction de visee du joueur sans couplage dur avec {@code GameAppState}.
 *
 * <p>L'implementation concrete (dans GameAppState) expose la position de la
 * camera/joueur et l'angle de visee (yaw + pitch).</p>
 *
 * <p>Correspondance ASM : <code>tempxoff</code>/<code>tempzoff</code>/
 * <code>tempyoff</code> + <code>tempangpos</code> qui sont les snapshots du
 * joueur pris au debut de Plr1_Shot.</p>
 */
public interface PlayerAimProvider {

    /**
     * Retourne la position d'ou partent les projectiles (souvent = position
     * camera). Doit pointer sur un Vector3f valide.
     */
    Vector3f getMuzzlePosition();

    /**
     * Retourne la direction de tir normalisee (souvent = direction camera).
     * Doit etre un vecteur unitaire.
     */
    Vector3f getAimDirection();

    /**
     * Angle de visee horizontal (yaw) en radians JME. Utilise par le spawn
     * de bullets multiples (spread shotgun) pour calculer les angles lateraux
     * autour de la direction de visee principale.
     */
    float getYaw();
}
