package com.ab3d2.core.ai;

/**
 * Mode courant d'un alien (= valeur runtime de {@code EntT_CurrentMode_b}).
 *
 * <p>L'ASM original ({@code modules/ai.s::AI_MainRoutine}) utilise un BYTE comme
 * dispatcher direct :</p>
 *
 * <pre>
 *   cmp.b   #1, EntT_CurrentMode_b(a0)
 *   blt     ai_DoDefault     ; mode &lt; 1   (= 0)
 *   beq     ai_DoResponse    ; mode == 1
 *   cmp.b   #3, EntT_CurrentMode_b(a0)
 *   blt     ai_DoFollowup    ; mode &lt; 3   (= 2)
 *   beq     ai_DoRetreat     ; mode == 3
 *   cmp.b   #5, EntT_CurrentMode_b(a0)
 *   beq     ai_DoDie         ; mode == 5
 *   ; sinon  -> ai_DoTakeDamage  (= 4)
 * </pre>
 *
 * <p>L'ordre des constantes ci-dessous suit donc EXACTEMENT cette numerotation
 * pour que {@link #ordinal()} corresponde au byte ASM. Ne pas reordonner.</p>
 *
 * <h2>Transitions principales (depuis {@code modules/ai.s})</h2>
 * <ul>
 *   <li><b>DEFAULT &rarr; RESPONSE</b> : LOS sur le joueur + reactionTime ecoule
 *       ({@code ai_ProwlRandom .attack_player})</li>
 *   <li><b>RESPONSE &rarr; FOLLOWUP</b> : fin animation d'attaque ou perte de LOS
 *       ({@code ai_AttackWithGun .not_finished_attacking / .cant_see_player})</li>
 *   <li><b>FOLLOWUP &rarr; RESPONSE</b> : LOS retrouvee + reactionTime ecoule
 *       ({@code ai_PauseBriefly / ai_Approach .attack_player})</li>
 *   <li><b>FOLLOWUP &rarr; DEFAULT</b> : timer1 epuise (timeout)
 *       ({@code ai_PauseBriefly .stillwaiting})</li>
 *   <li><b>* &rarr; TAKE_DAMAGE</b> : 25% chance sur hit (hp/4 &lt; dmg cumules)
 *       ({@code ai_TakeDamage .dodododo})</li>
 *   <li><b>* &rarr; RESPONSE</b> : 75% chance sur hit (alien aggrage par le tir)</li>
 *   <li><b>* &rarr; DIE</b> : hp atteint 0 ({@code ai_JustDied})</li>
 * </ul>
 *
 * <p><b>RETREAT</b> : declare dans la struct AlienT mais l'ASM original a
 * {@code ai_DoRetreat: rts} (vide). Les degats au-dessus de
 * {@code AlienT_DamageToRetreat_w} ne declenchent jamais cet etat dans le jeu
 * tel que livre. On le porte pour fidelite mais le comportement runtime sera
 * un fallback vers DEFAULT (compatible avec l'ASM).</p>
 *
 * @since session 113
 */
public enum AlienBehaviour {

    /** Patrouille (prowl) entre control points. Mode initial des aliens. */
    DEFAULT,

    /** Reponse au joueur : charge ou tir. Active quand l'alien voit le joueur. */
    RESPONSE,

    /** Pause apres un tir / approche entre deux attaques. */
    FOLLOWUP,

    /**
     * Fuite. <b>Non implemente dans l'ASM original</b> ({@code ai_DoRetreat: rts}).
     * Notre runtime fait un fallback vers DEFAULT pour rester ASM-fidele.
     */
    RETREAT,

    /** Animation "hit" : l'alien encaisse un coup (frames {@code WhichAnim=2}). */
    TAKE_DAMAGE,

    /** Animation de mort, puis suppression de l'entite. */
    DIE;

    /**
     * Convertit la valeur byte ASM ({@code EntT_CurrentMode_b}) vers une enum.
     * Les valeurs hors-range tombent sur DEFAULT (comportement de demarrage).
     */
    public static AlienBehaviour fromAsmByte(int v) {
        AlienBehaviour[] values = values();
        if (v < 0 || v >= values.length) return DEFAULT;
        return values[v];
    }

    /** @return la valeur byte ASM equivalente (= {@link #ordinal()}). */
    public int toAsmByte() { return ordinal(); }
}
