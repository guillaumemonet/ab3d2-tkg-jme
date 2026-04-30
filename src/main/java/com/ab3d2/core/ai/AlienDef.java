package com.ab3d2.core.ai;

/**
 * Definition immutable d'un alien (port runtime de {@code defs.i::STRUCTURE AlienT}).
 *
 * <p>21 champs UWORD soit 42 bytes au total. Cette classe est la version
 * <em>runtime</em> de la struct ASM ; elle est chargee une fois depuis
 * {@code definitions.json} (genere par {@link com.ab3d2.tools.LevelJsonExporter})
 * et partagee par toutes les instances d'aliens du meme type.</p>
 *
 * <h2>Mapping comportements &rarr; routines ASM</h2>
 *
 * <table border="1">
 *   <caption>Comportements et routines ai.s</caption>
 *   <tr><th>Champ</th><th>0</th><th>1</th><th>2</th><th>3</th><th>4</th><th>5</th></tr>
 *   <tr><td>defaultBehaviour</td><td>ProwlRandom</td><td>ProwlRandomFlying</td>
 *       <td>-</td><td>-</td><td>-</td><td>-</td></tr>
 *   <tr><td>responseBehaviour</td><td>Charge</td><td>ChargeToSide</td>
 *       <td>AttackWithGun</td><td>ChargeFlying</td>
 *       <td>ChargeToSideFlying</td><td>AttackWithGunFlying</td></tr>
 *   <tr><td>followupBehaviour</td><td>PauseBriefly</td><td>Approach</td>
 *       <td>ApproachToSide</td><td>ApproachFlying</td>
 *       <td>ApproachToSideFlying</td><td>-</td></tr>
 *   <tr><td>retreatBehaviour</td><td colspan="6"><i>non implemente dans l'ASM</i></td></tr>
 * </table>
 *
 * <h2>Notes sur les unites</h2>
 * <ul>
 *   <li><b>Speeds</b> : valeur Amiga, multipliee par {@code Anim_TempFrames_w}
 *       (typiquement 1-3) pour donner le deplacement par tick.
 *       Conversion approximative en JME : <code>jmeSpeed = speed * 4 / 32</code>
 *       (4 = subticks par frame, 32 = unites Amiga / unite JME).</li>
 *   <li><b>Timeouts</b> : compteurs de frames a 50 Hz Amiga.
 *       <code>seconds = frames / 50</code></li>
 *   <li><b>Height / Girth</b> : unites monde Amiga. JME = valeur / 32.
 *       Girth : 0=mince, 1=normal, 2=large (cf. {@code newaliencontrol.s::diststowall}).</li>
 * </ul>
 *
 * @since session 113
 */
public record AlienDef(
    int index,
    String name,

    /* +0 */  int gfxType,

    /* +2 */  int defaultBehaviour,
    /* +4 */  int reactionTime,
    /* +6 */  int defaultSpeed,

    /* +8 */  int responseBehaviour,
    /* +10 */ int responseSpeed,
    /* +12 */ int responseTimeout,

    /* +14 */ int damageToRetreat,
    /* +16 */ int damageToFollowup,

    /* +18 */ int followupBehaviour,
    /* +20 */ int followupSpeed,
    /* +22 */ int followupTimeout,

    /* +24 */ int retreatBehaviour,
    /* +26 */ int retreatSpeed,
    /* +28 */ int retreatTimeout,

    /* +30 */ int bulType,
    /* +32 */ int hitPoints,
    /* +34 */ int height,
    /* +36 */ int girth,
    /* +38 */ int splatType,
    /* +40 */ int auxilliary
) {

    /**
     * Indique si cet alien <em>vole</em>. Determine par defaultBehaviour :
     * 0 = marche, 1 = vol (cf. {@code ai_ProwlRandomFlying} dans ai.s).
     *
     * <p>Quand l'alien vole, sa hauteur Y est ajustee par {@code ai_FlyToCPTHeight}
     * ou {@code ai_FlyToPlayerHeight}, et la gravite ne s'applique pas.</p>
     */
    public boolean isFlying() {
        return defaultBehaviour >= 1;
    }

    /**
     * Indique si cet alien attaque a distance (avec une arme) plutot qu'au corps a corps.
     * Determine par responseBehaviour : 2 ou 5 = AttackWithGun (sol/vol), autres = Charge.
     */
    public boolean attacksWithGun() {
        return responseBehaviour == 2 || responseBehaviour == 5;
    }

    /**
     * Distance de "girth" en unites Amiga, depuis la table {@code diststowall}
     * de {@code newaliencontrol.s} :
     * <pre>
     *   girth=0 : 80 unites  (mince)
     *   girth=1 : 160 unites (normal)
     *   girth=2 : 320 unites (large)
     * </pre>
     * Utilise pour la collision murs et l'evitement de coins.
     */
    public int collisionRadius() {
        return switch (girth) {
            case 0 -> 80;
            case 1 -> 160;
            case 2 -> 320;
            default -> 160;
        };
    }
}
