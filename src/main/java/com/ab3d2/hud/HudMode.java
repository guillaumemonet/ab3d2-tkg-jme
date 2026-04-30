package com.ab3d2.hud;

/**
 * Mode d'affichage du HUD dans AB3D2.
 *
 * <p>Le jeu original supportait deux modes historiques (on les reproduit ici) :</p>
 *
 * <ul>
 *   <li><b>SMALL_SCREEN</b> : le HUD est entierement visible (bordures laterales +
 *       panneau bas + zone messages). La vue 3D est reduite a 192x160 centree en haut.
 *       C'est le mode "classique" avec le plus d'informations affichees.</li>
 *
 *   <li><b>FULL_SCREEN</b> : la vue 3D occupe la majorite de l'ecran (320x240).
 *       Seul le panneau bas reste visible pour afficher AMMO/ENERGY.
 *       Les messages apparaissent en haut en overlay (letterbox).</li>
 * </ul>
 */
public enum HudMode {
    /** HUD complet visible (bordures + panneau + zone messages), 3D 192x160. */
    SMALL_SCREEN,
    /** 3D plein ecran, panneau bas en overlay. */
    FULL_SCREEN
}
