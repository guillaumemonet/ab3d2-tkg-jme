package com.ab3d2.weapon;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;

/**
 * Identifiants des armes d'Alien Breed 3D II TKG.
 *
 * <p>Liste exhaustive trouvee dans le fichier {@code TEST.LNK} du jeu original
 * (bloc des noms d'armes et references VECTOBJ/*). Il y a 8 armes reelles + 2
 * slots placeholder ({@code GUN I}, {@code GUN J}) non utilises en jeu.</p>
 *
 * <p>Ordre issu du jeu (extrait de {@code TEST.LNK}, section {@code GLFT_GunNames_l}) :</p>
 * <ol>
 *   <li>Shotgun</li>
 *   <li>Plasma Gun</li>
 *   <li>Grenade Launcher</li>
 *   <li>Assault Rifle</li>
 *   <li>Blaster</li>
 *   <li>Rocket Launcher</li>
 *   <li>Lazer (spelling du jeu)</li>
 *   <li>Drop Mine</li>
 *   <li>GUN I - placeholder TEST.LNK, remappe Plasma Multi-Shot (meme modele 3D que Plasma Gun)</li>
 *   <li>GUN J - placeholder TEST.LNK, remappe MegaLaser (meme modele 3D que Lazer)</li>
 * </ol>
 *
 * <p>Session 91 : ajout des slots 8 et 9 (Plasma Multi-Shot, MegaLaser). Ces armes
 * existent dans le jeu original mais sont configurees a zero dans TEST.LNK. On les
 * reactive via {@code CombatBootstrap.WeaponOverride} en memoire. Visuellement, elles
 * reutilisent le modele 3D de leur arme cousine (plasma/lazer).</p>
 *
 * <p>Chaque arme a sa propre rotation d'affichage car les vectobj n'ont pas tous
 * ete modelises avec la meme orientation canonique. On doit souvent tourner le
 * modele de 180deg autour de Y (canon vers l'avant) + des ajustements fins.</p>
 */
public enum WeaponType {
    // name,            model,               slot, rotation additionnelle (X, Y, Z en radians)
    SHOTGUN              ("shotgun",             0, 0f, FastMath.PI, 0f),
    PLASMA_GUN           ("plasmagun",           1, 0f, FastMath.PI, 0f),
    GRENADE_LAUNCHER     ("grenadelauncher",     2, 0f, FastMath.PI, 0f),
    ASSAULT_RIFLE        ("rifle",               3, 0f, FastMath.PI, 0f),
    BLASTER              ("blaster",             4, 0f, FastMath.PI, 0f),
    ROCKET_LAUNCHER      ("rocketlauncher",      5, 0f, FastMath.PI, 0f),
    LAZER                ("laser",               6, 0f, FastMath.PI, 0f),
    DROP_MINE            ("plink",               7, 0f, FastMath.PI, 0f),
    // Session 91 : slots 8 et 9 (Plasma Multi-Shot, MegaLaser).
    // Meme modele 3D que leur arme cousine (confirmed par utilisateur session 91).
    PLASMA_MULTISHOT     ("plasmagun",           8, 0f, FastMath.PI, 0f),
    MEGALASER            ("laser",               9, 0f, FastMath.PI, 0f);

    /** Nom du fichier vectobj (sans extension). */
    private final String modelName;
    /** Slot HUD (0..7 = touches 1-8). */
    private final int slotIndex;
    /** Rotation d'affichage additionnelle (en plus de WEAPON_ROTATION_BASE). */
    private final float rotX, rotY, rotZ;

    WeaponType(String modelName, int slotIndex, float rotX, float rotY, float rotZ) {
        this.modelName = modelName;
        this.slotIndex = slotIndex;
        this.rotX = rotX;
        this.rotY = rotY;
        this.rotZ = rotZ;
    }

    public String getModelName() { return modelName; }
    public String getAssetPath() { return "Scenes/vectobj/" + modelName + ".j3o"; }
    public int    getSlotIndex() { return slotIndex; }

    /** Rotation d'affichage de cette arme dans le repere camera. */
    public Quaternion getDisplayRotation() {
        return new Quaternion().fromAngles(rotX, rotY, rotZ);
    }

    public float getRotX() { return rotX; }
    public float getRotY() { return rotY; }
    public float getRotZ() { return rotZ; }

    /** Retourne l'arme correspondant a un slot, ou null si aucune. */
    public static WeaponType fromSlot(int slotIndex) {
        for (WeaponType w : values()) {
            if (w.slotIndex == slotIndex) return w;
        }
        return null;
    }
}
