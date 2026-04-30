package com.ab3d2.combat;

import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.input.controls.Trigger;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration des bindings clavier/souris d'AB3D2.
 *
 * <p>Chaque "action" (tir, avancer, utiliser...) est associee a un ou plusieurs
 * triggers JME (touches clavier ou boutons souris). La config par defaut est
 * definie ici, mais peut etre override a chaud (via menu options, a venir).</p>
 *
 * <p>Les noms d'actions sont en enum pour eviter les typos et faciliter
 * l'autocomplete. Le code ASM original utilise <code>prefs_keys.h</code>
 * (a copier plus tard) pour definir les bindings serialises.</p>
 */
public final class KeyBindings {

    /**
     * Ensemble des actions du jeu mappables a des touches.
     *
     * <p>Ordre : les actions de "mouvement" d'abord (deja gerees par
     * GameAppState), puis les actions de "combat" (nouvelles).</p>
     */
    public enum Action {
        // ── Mouvement / navigation ─────────────────────────────────────────
        MOVE_FORWARD,
        MOVE_BACKWARD,
        STRAFE_LEFT,
        STRAFE_RIGHT,
        TURN_LEFT,
        TURN_RIGHT,
        JUMP,
        USE,            // "action" / ouvrir porte / interagir
        EXIT_LEVEL,

        // ── Combat ─────────────────────────────────────────────────────────
        FIRE,           // tir principal (Plr1_TmpFire_b)
        NEXT_WEAPON,
        PREV_WEAPON,
        WEAPON_1,
        WEAPON_2,
        WEAPON_3,
        WEAPON_4,
        WEAPON_5,
        WEAPON_6,
        WEAPON_7,
        WEAPON_8,
        // Session 91 : 10 armes au total (touches 9 et 0 = slots 8 et 9)
        WEAPON_9,
        WEAPON_10,

        // ── Debug ──────────────────────────────────────────────────────────
        DEBUG_HUD,
        TOGGLE_WEAPON_VIEW
    }

    /**
     * Bindings courants (modifiable a chaud).
     * Clef = action, Valeur = liste de triggers JME.
     */
    private final Map<Action, Trigger[]> bindings = new LinkedHashMap<>();

    public KeyBindings() {
        loadDefaults();
    }

    /** Initialise les bindings par defaut conformes a AB3D2. */
    public void loadDefaults() {
        bindings.clear();

        // Mouvement (ZQSD + fleches comme GameAppState actuel)
        bind(Action.MOVE_FORWARD,  new KeyTrigger(KeyInput.KEY_W),
                                    new KeyTrigger(KeyInput.KEY_UP));
        bind(Action.MOVE_BACKWARD, new KeyTrigger(KeyInput.KEY_S),
                                    new KeyTrigger(KeyInput.KEY_DOWN));
        bind(Action.STRAFE_LEFT,   new KeyTrigger(KeyInput.KEY_A));
        bind(Action.STRAFE_RIGHT,  new KeyTrigger(KeyInput.KEY_D));
        bind(Action.TURN_LEFT,     new KeyTrigger(KeyInput.KEY_LEFT));
        bind(Action.TURN_RIGHT,    new KeyTrigger(KeyInput.KEY_RIGHT));
        bind(Action.JUMP,          new KeyTrigger(KeyInput.KEY_SPACE));
        bind(Action.USE,           new KeyTrigger(KeyInput.KEY_E));
        bind(Action.EXIT_LEVEL,    new KeyTrigger(KeyInput.KEY_ESCAPE));

        // Combat - TIR = clic gauche souris par defaut
        bind(Action.FIRE,          new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        bind(Action.NEXT_WEAPON,   new KeyTrigger(KeyInput.KEY_Y));
        bind(Action.PREV_WEAPON,   new KeyTrigger(KeyInput.KEY_T));
        bind(Action.WEAPON_1,      new KeyTrigger(KeyInput.KEY_1));
        bind(Action.WEAPON_2,      new KeyTrigger(KeyInput.KEY_2));
        bind(Action.WEAPON_3,      new KeyTrigger(KeyInput.KEY_3));
        bind(Action.WEAPON_4,      new KeyTrigger(KeyInput.KEY_4));
        bind(Action.WEAPON_5,      new KeyTrigger(KeyInput.KEY_5));
        bind(Action.WEAPON_6,      new KeyTrigger(KeyInput.KEY_6));
        bind(Action.WEAPON_7,      new KeyTrigger(KeyInput.KEY_7));
        bind(Action.WEAPON_8,      new KeyTrigger(KeyInput.KEY_8));
        // Session 91 : slots 8 et 9 (Plasma Multi-Shot, MegaLaser)
        bind(Action.WEAPON_9,      new KeyTrigger(KeyInput.KEY_9));
        bind(Action.WEAPON_10,     new KeyTrigger(KeyInput.KEY_0));

        // Debug
        bind(Action.DEBUG_HUD,          new KeyTrigger(KeyInput.KEY_F3));
        bind(Action.TOGGLE_WEAPON_VIEW, new KeyTrigger(KeyInput.KEY_H));
    }

    /** Definit (ou ecrase) les triggers pour une action. */
    public void bind(Action action, Trigger... triggers) {
        bindings.put(action, triggers);
    }

    /** Retourne les triggers mappes a une action (jamais null). */
    public Trigger[] getTriggers(Action action) {
        Trigger[] t = bindings.get(action);
        return t != null ? t : new Trigger[0];
    }

    /** Retourne le nom logique utilise pour l'InputManager JME. */
    public static String actionName(Action action) {
        return "ab3d2_" + action.name().toLowerCase();
    }

    /** Retourne toutes les actions dont au moins un trigger est defini. */
    public Iterable<Map.Entry<Action, Trigger[]>> entries() {
        return bindings.entrySet();
    }

    /** Representation humaine des triggers d'une action (pour UI options). */
    public String describe(Action action) {
        Trigger[] triggers = getTriggers(action);
        if (triggers.length == 0) return "<non assigne>";
        return Arrays.stream(triggers)
            .map(Trigger::getName)
            .reduce((a, b) -> a + " / " + b)
            .orElse("");
    }
}
