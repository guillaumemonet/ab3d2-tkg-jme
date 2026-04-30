package com.ab3d2.combat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory + initialisation du systeme de combat au demarrage d'un niveau.
 *
 * <p>Centralise la creation de {@link PlayerCombatState} avec les valeurs de
 * depart. L'idee est de faire correspondre progressivement aux defaults du
 * jeu original (fichiers de config .HQN, prefs, etc.).</p>
 *
 * <p>Session 91 : les 10 armes du jeu officiel sont donnees, y compris les
 * slots 8 et 9 ("Plasma Multi-Shot" et "MegaLaser") qui dans <code>TEST.LNK</code>
 * sont configures comme des placeholders "GUN I"/"GUN J" avec count=0.
 * On les override en memoire avec des valeurs plausibles pour retrouver le
 * comportement du jeu original.</p>
 */
public final class CombatBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CombatBootstrap.class);

    private CombatBootstrap() {}

    /**
     * Override des slots 8 et 9 du GLF pour reproduire le jeu original.
     *
     * <p>Dans <code>TEST.LNK</code>, les slots 8 et 9 ont <code>delay=0,
     * count=0, sfx=0</code> (placeholders). Dans le jeu original on observe :</p>
     *
     * <ul>
     *   <li><b>Slot 8 (touche 9) = Plasma Multi-Shot</b> : meme modele 3D que
     *       le Plasma Gun (slot 1). Tire plusieurs MegaPlasma (bullet 13) d'un
     *       coup, plus lent a recharger que le Plasma simple.</li>
     *   <li><b>Slot 9 (touche 0) = MegaLaser</b> : meme modele 3D que le Lazer
     *       (slot 6). Tire de l'Assault Lazer (bullet 10) plus rapide et
     *       plus puissant que le Lazer simple.</li>
     * </ul>
     *
     * <p>Les valeurs ci-dessous sont des estimations basees sur :</p>
     * <ul>
     *   <li>Le bulletType d'origine lu dans le GLF (slot 8 = 8, slot 9 = 9)
     *       semble etre une valeur par defaut inutilisee → on override avec
     *       les bullets MegaPlasma et Assault Lazer qui sont dans la table
     *       BulletDefs mais non-utilisees par les 8 premieres armes.</li>
     *   <li>Les delays/counts sont calibres par analogie avec les armes du
     *       meme groupe (plasma, lazer).</li>
     * </ul>
     *
     * <p>Ces overrides restent en memoire et ne touchent PAS le GLF sur
     * disque. Si le jeu est modde, les vraies valeurs du GLF prendront le
     * dessus (voir {@link #getEffectiveShootDef}).</p>
     */
    public static final class WeaponOverride {
        /** Slot 8 (touche 9) : Plasma Multi-Shot — plusieurs MegaPlasma */
        public static final ShootDef SLOT_8_PLASMA_MULTISHOT = new ShootDef(
            /* bulletType */ 13,   // MegaPlasma
            /* delay      */ 30,   // cooldown moyen (1.2s @ 25Hz)
            /* bulletCount*/ 3,    // multishot : 3 bolts en eventail
            /* sfx        */ 1     // meme SFX que Plasma Gun
        );

        /** Slot 9 (touche 0) : MegaLaser — tir rapide Assault Lazer */
        public static final ShootDef SLOT_9_MEGALASER = new ShootDef(
            /* bulletType */ 10,   // Assault Lazer
            /* delay      */ 10,   // cooldown court (0.4s) — rafale
            /* bulletCount*/ 1,    // 1 bullet
            /* sfx        */ 28    // meme SFX que Lazer
        );
    }

    /**
     * Retourne le ShootDef effectif pour un slot donne. Si le GLF a un
     * placeholder (count=0), substitue l'override en memoire. Sinon renvoie
     * la valeur du GLF.
     *
     * @param glf la DB GLF chargee depuis TEST.LNK
     * @param gunIdx index 0..9 du slot d'arme
     * @return ShootDef utilisable (jamais count=0 sauf si override absent)
     */
    public static ShootDef getEffectiveShootDef(GlfDatabase glf, int gunIdx) {
        ShootDef sd = glf.getShootDef(gunIdx);
        if (sd.bulletCount() > 0) return sd;  // GLF configure, rien a faire

        // Placeholder dans le GLF : applique l'override si disponible
        return switch (gunIdx) {
            case 8  -> WeaponOverride.SLOT_8_PLASMA_MULTISHOT;
            case 9  -> WeaponOverride.SLOT_9_MEGALASER;
            default -> sd;   // autres slots : garde le placeholder (count=0)
        };
    }

    /**
     * Cree un {@link PlayerCombatState} initialise pour un nouveau niveau.
     *
     * <p>Defaults session 91 :</p>
     * <ul>
     *   <li>20 ammo de chaque bullet type utilise (armes 0..9)</li>
     *   <li>Les 10 armes possedees (0..9) avec override des slots 8/9</li>
     *   <li>Arme selectionnee : Shotgun (index 0)</li>
     *   <li>Cooldown : 0 (pret a tirer)</li>
     * </ul>
     */
    public static PlayerCombatState newLevelStart(GlfDatabase glf) {
        PlayerCombatState state = new PlayerCombatState();

        // Donne 20 ammo pour chaque bullet type utilise par chaque arme
        // (en passant par getEffectiveShootDef pour inclure les overrides
        // MegaPlasma et Assault Lazer des slots 8/9).
        for (int gunIdx = 0; gunIdx < PlayerCombatState.NUM_WEAPONS; gunIdx++) {
            ShootDef sd = getEffectiveShootDef(glf, gunIdx);
            int bullet = sd.bulletType();
            if (bullet >= 0 && bullet < PlayerCombatState.NUM_AMMO_TYPES
                && sd.bulletCount() > 0) {
                state.addAmmo(bullet, 20);
            }
        }

        // Donne les 10 armes (8 du GLF + 2 overrides session 91)
        for (int gunIdx = 0; gunIdx < PlayerCombatState.NUM_WEAPONS; gunIdx++) {
            ShootDef sd = getEffectiveShootDef(glf, gunIdx);
            if (sd.bulletCount() > 0) {
                state.giveWeapon(gunIdx);
            }
        }

        // Arme de depart : Shotgun
        state.setGunSelected(0);

        log.info("Combat bootstrap : {} armes donnees (incl. overrides slots 8/9), "
            + "20 ammo par type utilise", countOwned(state));
        return state;
    }

    private static int countOwned(PlayerCombatState state) {
        int n = 0;
        for (int i = 0; i < PlayerCombatState.NUM_WEAPONS; i++) {
            if (state.hasWeapon(i)) n++;
        }
        return n;
    }
}
