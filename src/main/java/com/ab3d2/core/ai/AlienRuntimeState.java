package com.ab3d2.core.ai;

/**
 * Etat runtime d'une instance d'alien (port des champs {@code EntT_*} de
 * {@code defs.i::STRUCTURE EntT}). Une instance par alien vivant dans le niveau,
 * mutable et mise a jour chaque frame par {@link AlienAI}.
 *
 * <p>L'ASM original stocke ces donnees dans une {@code ObjT} de 64 bytes,
 * partagee avec les structures projectiles, decorations, etc., d'ou les noms
 * de champs un peu obscurs. On les rebaptise lisiblement ici.</p>
 *
 * <h2>Mapping ASM &rarr; Java</h2>
 * <table border="1">
 *   <caption>EntT (ASM) vers AlienRuntimeState (Java)</caption>
 *   <tr><th>Offset ASM</th><th>Champ ASM</th><th>Champ Java</th></tr>
 *   <tr><td>+0</td>  <td>ObjT_XPos_l (LONG, 24.8 fp)</td><td>worldX (float)</td></tr>
 *   <tr><td>+4</td>  <td>ObjT_ZPos_l</td>           <td>worldZ</td></tr>
 *   <tr><td>+8</td>  <td>ObjT_YPos_l</td>           <td>worldY</td></tr>
 *   <tr><td>+12</td> <td>ObjT_ZoneID_w</td>         <td>zoneId</td></tr>
 *   <tr><td>+16</td> <td>ObjT_TypeID_b</td>         <td><i>fixe = 0 (alien)</i></td></tr>
 *   <tr><td>+17</td> <td>ObjT_SeePlayer_b</td>      <td>seesPlayer</td></tr>
 *   <tr><td>+18</td> <td>EntT_HitPoints_b</td>      <td>hitPoints</td></tr>
 *   <tr><td>+19</td> <td>EntT_DamageTaken_b</td>    <td>damageTaken</td></tr>
 *   <tr><td>+20</td> <td>EntT_CurrentMode_b</td>    <td>{@link #mode}</td></tr>
 *   <tr><td>+21</td> <td>EntT_TeamNumber_b</td>     <td>teamNumber</td></tr>
 *   <tr><td>+24</td> <td>EntT_DisplayText_w</td>    <td>displayTextId</td></tr>
 *   <tr><td>+26</td> <td>EntT_ZoneID_w (entity copy)</td><td><i>= zoneId</i></td></tr>
 *   <tr><td>+28</td> <td>EntT_CurrentControlPoint_w</td><td>currentControlPoint</td></tr>
 *   <tr><td>+30</td> <td>EntT_CurrentAngle_w (0..4095)</td><td>currentAngle</td></tr>
 *   <tr><td>+32</td> <td>EntT_TargetControlPoint_w</td><td>targetControlPoint</td></tr>
 *   <tr><td>+34</td> <td>EntT_Timer1_w</td>         <td>timer1 (reaction)</td></tr>
 *   <tr><td>+40</td> <td>EntT_Timer2_w</td>         <td>timer2 (anim frame)</td></tr>
 *   <tr><td>+42-47</td><td>Impact[XYZ]/VelocityY</td><td>impactX/Y/Z, velocityY</td></tr>
 *   <tr><td>+50</td> <td>EntT_DoorsAndLiftsHeld_l</td><td>doorsAndLiftsHeld</td></tr>
 *   <tr><td>+52</td> <td>EntT_Timer3_w</td>         <td>timer3 (post-mort fade)</td></tr>
 *   <tr><td>+54</td> <td>EntT_Type_b</td>           <td>defIndex (= type d'alien)</td></tr>
 *   <tr><td>+55</td> <td>EntT_WhichAnim_b</td>      <td>whichAnim</td></tr>
 * </table>
 *
 * @since session 113
 */
public final class AlienRuntimeState {

    // ── Identite ───────────────────────────────────────────────────────────────

    /** Index dans le tableau global d'aliens (= ID unique pour ce niveau). */
    public final int slot;

    /** Index de l'AlienDef (0..19) - selectionne {@code GLFT_AlienDefs_l[defIndex]}. */
    public int defIndex;

    /** Equipe (0..29) ou -1 = solo. Les aliens de la meme equipe partagent la
     *  position du joueur via {@code AI_AlienTeamWorkspace_vl}. */
    public int teamNumber = -1;

    // ── Position monde ────────────────────────────────────────────────────────

    /** Coordonnees monde Amiga (signed 16 bits, mais on garde des float pour les
     *  calculs intermediaires). */
    public float worldX, worldY, worldZ;

    /** Zone courante (= {@code Plr1_Zone_w} pour le joueur, ici = ObjT_ZoneID_w). */
    public int zoneId;

    /** Vrai si l'alien est dans l'etage haut d'une zone double (ZoneT_UpperFloor_l). */
    public boolean inUpperZone;

    // ── Etat IA ──────────────────────────────────────────────────────────────

    /** Mode courant - <b>cle de la machine a etats</b>. */
    public AlienBehaviour mode = AlienBehaviour.DEFAULT;

    /**
     * Frame d'animation a jouer dans le set courant (0=walk/idle, 1=attack,
     * 2=hit, 3=die). Valeur byte ASM ({@code EntT_WhichAnim_b}).
     */
    public int whichAnim = 0;

    /** Angle de regard sur 0..4095 (Amiga units, 4096 = tour complet). */
    public int currentAngle;

    /**
     * Decompte avant reaction au joueur. Fixe a {@code reactionTime} quand on
     * perd la LOS, decremente chaque frame. Quand {@code <= 0} et qu'on voit
     * le joueur, on passe en RESPONSE.
     */
    public int timer1;

    /** Frame de l'animation courante. Increment automatique par DoWalkAnim. */
    public int timer2;

    /** Timer de "fade" apres la mort (animation/son d'agonie). */
    public int timer3;

    /**
     * Timer pour la patrouille aleatoire (= changer de direction toutes les
     * ~50 frames Amiga). Champ dedie pour ne pas conflit avec {@link #timer3}
     * qui sert au fade-out de mort dans l'ASM.
     *
     * <p>Phase 2.E (fix) : avant on utilisait {@code timer3} pour les 2
     * usages, ce qui creait des bugs (alien qui change de direction pendant
     * la phase de mort).</p>
     */
    public int prowlTicker;

    // ── Combat ─────────────────────────────────────────────────────────────────

    /**
     * Points de vie <b>initiaux</b> (= {@code AlienT_HitPoints_w} de la def).
     * <b>Immuable apres initFromDef</b> : l'ASM ne decremente jamais
     * {@code EntT_HitPoints_b}, c'est juste le seuil de mort.
     *
     * <p>Modele ASM (cf. {@code ai.s::ai_TakeDamage}) :</p>
     * <pre>
     *   AI_Damaged_vw[idx] += DamageTaken     ; cumul lifetime
     *   d0 = AI_Damaged_vw[idx] / 4           ; divise par 4
     *   d1 = EntT_HitPoints_b(a0)             ; HP initial
     *   if (d1 &le; d0) goto ai_JustDied      ; mort si HP_init &le; cumul/4
     * </pre>
     *
     * <p>Donc Red Alien (hitPoints=2) meurt a {@code totalDamageDone &ge; 8}
     * (= 8 plasmas a 1 dmg, ou 4 shotgun rounds qui font 2 dmg chacun).</p>
     *
     * <p>Mantis Boss (hitPoints=125) meurt a {@code totalDamageDone &ge; 500}.</p>
     */
    public int hitPoints;

    /**
     * Degats accumules <em>depuis le dernier check</em>. Mis a 0 par
     * {@code ai_TakeDamage} apres traitement. Different de {@code AI_Damaged_vw[idx]}
     * qui est cumulatif sur la duree de vie de l'alien.
     */
    public int damageTaken;

    /**
     * Cumul lifetime des degats (= {@code AI_Damaged_vw[idx]} dans l'ASM).
     * <b>Determine la mort</b> via la formule {@code totalDamageDone / 4 &ge; hitPoints}.
     */
    public int totalDamageDone;

    /** Vrai si l'alien a une ligne de vue sur le joueur (= ObjT_SeePlayer_b). */
    public boolean seesPlayer;

    // ── Patrouille ───────────────────────────────────────────────────────────

    /** Control point actuel (zone) - mis a jour quand l'alien y arrive. */
    public int currentControlPoint;

    /** Control point cible vers lequel l'alien se dirige. */
    public int targetControlPoint;

    // ── Forces / impact ──────────────────────────────────────────────────────

    /** Knockback X/Y/Z applique pendant la frame (du a un tir ou explosion). */
    public int impactX, impactY, impactZ;

    /** Vitesse verticale (gravite ou vol). */
    public int velocityY;

    // ── Identifiant pour HUD / messages ──────────────────────────────────────

    /** Index dans Lvl_Messages pour le texte affiche a la mort (-1 = aucun). */
    public int displayTextId = -1;

    /** Bitmask des portes/lifts maintenus ouverts par cet alien (collision). */
    public long doorsAndLiftsHeld;

    // ─────────────────────────────────────────────────────────────────────────

    public AlienRuntimeState(int slot, int defIndex) {
        this.slot = slot;
        this.defIndex = defIndex;
    }

    /**
     * Initialise l'alien depuis sa definition. A appeler apres construction
     * et apres avoir set worldX/worldY/worldZ/zoneId/teamNumber.
     */
    public void initFromDef(AlienDef def) {
        this.hitPoints = def.hitPoints();
        this.timer1 = def.reactionTime();
        this.mode = AlienBehaviour.DEFAULT;
        this.whichAnim = 0;
        this.timer2 = 0;
        this.damageTaken = 0;
        this.totalDamageDone = 0;
        this.seesPlayer = false;
    }

    /**
     * Indique si l'alien est mort et peut etre supprime de la simulation.
     * Equivalent ASM : {@code FREE_ENT a0 ; move.b #OBJ_TYPE_ALIEN, ObjT_TypeID_b ; ...}
     * a la fin de {@code ai_DoDie}.
     */
    public boolean isDeadAndGone() {
        return mode == AlienBehaviour.DIE && timer3 <= 0;
    }

    /**
     * Indique si l'alien est en vie selon le modele ASM :
     * {@code totalDamageDone / 4 < hitPoints} (HP_initial). Une fois en mode
     * DIE, l'alien n'est plus considere vivant meme si l'animation joue encore.
     *
     * <p>Equivalent ASM : {@code cmp.w d0,d1 ; bgt .not_dead_yet} dans
     * {@code ai_TakeDamage} (d0 = cumul/4, d1 = HP_init).</p>
     */
    public boolean isAlive() {
        return mode != AlienBehaviour.DIE
            && (totalDamageDone >> 2) < hitPoints;
    }

    @Override
    public String toString() {
        return String.format(
            "Alien#%d[def=%d, mode=%s, hp=%d, t1=%d, zone=%d, ang=%d, sees=%b]",
            slot, defIndex, mode, hitPoints, timer1, zoneId, currentAngle, seesPlayer
        );
    }
}
