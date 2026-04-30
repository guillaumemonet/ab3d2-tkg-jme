package com.ab3d2.core.ai;

/**
 * Interface qui abstrait les queries au monde pour l'IA des aliens.
 *
 * <p>L'objectif est de garder {@link AlienAI} <em>pur</em> et testable : la
 * machine a etats ne touche jamais directement les structures de niveau, le
 * raycaster, ou le moteur de physique. Tout passe par cette interface, qui sera
 * implementee par {@code world.AiWorldAdapter} en s'appuyant sur les systemes
 * existants ({@code WallCollision}, {@code WorldRaycaster}, {@code ZoneTracker}).</p>
 *
 * <h2>Conventions de coordonnees</h2>
 * <ul>
 *   <li>Toutes les positions sont en <b>unites monde Amiga</b> (signed 16-bit
 *       ou 24.8 fixed-point selon le contexte ASM). Conversion JME =
 *       {@code amiga / 32}.</li>
 *   <li>Les angles sont sur 0..4095 (= 12-bit angles, 4096 = tour complet).
 *       Conversion radians = {@code angle * 2*PI / 4096}.</li>
 *   <li>L'axe Y monte vers le haut (positif), comme JME ; mais les hauteurs
 *       <em>editeur</em> Amiga sont inversees (plus petit = plus haut), elles
 *       devront avoir ete normalisees AVANT d'arriver ici.</li>
 * </ul>
 *
 * <p>Toutes les methodes de cette interface doivent etre <em>side-effect-free</em>
 * <i>vis-a-vis du monde</i> : elles ne doivent pas modifier l'etat des aliens,
 * du joueur, ou du niveau. Les modifications passent par l'AlienRuntimeState
 * lui-meme (que l'IA modifie directement).</p>
 *
 * @since session 113
 */
public interface AiWorld {

    // ── Joueur ────────────────────────────────────────────────────────────────

    /** @return position monde X du joueur (unites Amiga). */
    float playerX();

    /** @return position monde Y du joueur. */
    float playerY();

    /** @return position monde Z du joueur. */
    float playerZ();

    /** @return zone courante du joueur (Plr1_Zone_w). */
    int playerZone();

    /** @return vrai si le joueur est dans l'etage haut d'une zone double. */
    boolean playerInUpperZone();

    /** @return luminosite percue de la zone du joueur (0..63), pour le test
     *  {@code ai_CheckForDark} : plus c'est sombre, plus l'alien a du mal a voir. */
    int playerRoomBrightness();

    /** @return volume sonore que le joueur emet ce tick. {@code Plr1_NoiseVol_w}.
     *  Utilise par {@code ai_Widget} pour faire converger les aliens vers le bruit. */
    int playerNoiseVolume();

    // ── Ligne de vue / visibilite ─────────────────────────────────────────────

    /**
     * Test si le joueur est visible depuis la position de l'alien.
     * Equivalent ASM : {@code AI_LookForPlayer1} (qui appelle {@code CanItBeSeen}).
     *
     * <p>Cet appel fait un raycast a travers les zones traversees, en respectant
     * les portes fermees et le PVS. Ce n'est PAS un simple test de proximite.</p>
     *
     * @param fromX position alien X
     * @param fromY position alien Y
     * @param fromZ position alien Z
     * @param fromZone zone courante alien
     * @param fromUpper si l'alien est dans l'etage haut
     * @return vrai si le joueur peut etre vu
     */
    boolean canSeePlayer(float fromX, float fromY, float fromZ,
                         int fromZone, boolean fromUpper);

    // ── Patrouille / control points ──────────────────────────────────────────

    /**
     * Donne le control point qui mene de {@code fromCP} vers {@code toCP}, en
     * suivant le graphe de zones du niveau.
     *
     * <p>Equivalent ASM : {@code GetNextCPt}. Retourne {@code 0x7F} (= 127)
     * quand aucun chemin n'existe, ce que l'IA ASM utilise pour declencher
     * un comportement de fallback (random target).</p>
     *
     * @return id du control point intermediaire, ou 127 si pas de chemin
     */
    int getNextControlPoint(int fromCP, int toCP);

    /** @return nombre total de control points dans le niveau ({@code Lvl_NumControlPoints_w}). */
    int numControlPoints();

    /** @return coords X du control point. */
    float controlPointX(int cpId);

    /** @return coords Z du control point. */
    float controlPointZ(int cpId);

    /** @return coords Y (hauteur) du control point pour les aliens volants. */
    float controlPointY(int cpId);

    /** @return id du control point de la zone donnee (= ZoneT_ControlPoint).
     *  Pour les zones doubles, byte 0 = etage bas, byte 1 = etage haut. */
    int zoneControlPoint(int zoneId, boolean upperFloor);

    // ── Zones ────────────────────────────────────────────────────────────────

    /** @return luminosite (0..63) de la zone courante, pour les calculs de visibilite. */
    int zoneBrightness(int zoneId);

    /** @return Y (hauteur) du sol de la zone (deja normalise en monde JME). */
    float zoneFloorY(int zoneId, boolean upperFloor);

    /** @return Y du plafond de la zone. */
    float zoneRoofY(int zoneId, boolean upperFloor);

    // ── Aleatoire ─────────────────────────────────────────────────────────────

    /**
     * @return un word non-signe pseudo-aleatoire (0..65535).
     * Equivalent ASM : {@code GetRand}. L'implementation runtime peut utiliser
     * un PRNG seede pour la reproductibilite des replays.
     */
    int getRandom();

    // ── Audio (cris des aliens) ───────────────────────────────────────────────

    /**
     * Joue un sample sonore positionne dans le monde.
     * Equivalent ASM : ecriture dans {@code Aud_NoiseX_w / Aud_NoiseVol_w /
     * Aud_SampleNum_w} suivi de {@code MakeSomeNoise}.
     *
     * @param sampleNum index dans GLFT_SFXFilenames_l (typiquement 14 = explosion,
     *                  ou un scream sound specifique a l'alien)
     * @param volume 0..400 (l'ASM clamp a 200 pour les degats, 400 pour la mort)
     * @param worldX position d'emission X
     * @param worldZ position d'emission Z
     * @param echoLevel niveau de reverb (= ZoneT_Echo_b de la zone alien)
     */
    void playPositionalSound(int sampleNum, int volume,
                              float worldX, float worldZ, int echoLevel);

    // ── Combat alien -&gt; joueur (session 113 phase 2.D) ────────────────────

    /**
     * Inflige des degats au joueur.
     * Equivalent ASM : {@code add.b SHOTPOWER, EntT_DamageTaken_b(Plr1_ObjectPtr_l)}
     * dans {@code ai_AttackWithGun} (modules/ai.s) sur hit.
     *
     * <p>Pour un tir hitscan reussi, l'alien applique directement les degats
     * au {@code damageTaken} du joueur, qui sera traite au prochain tick par
     * {@code Plr1_Control}.</p>
     *
     * @param damage     points de degats a appliquer (= {@code BulT_HitDamage_l})
     * @param fromX      position de l'alien tireur en monde Amiga (pour le knockback)
     * @param fromZ      position de l'alien tireur en monde Amiga
     */
    default void applyDamageToPlayer(int damage, float fromX, float fromZ) {
        // Default no-op : permet aux tests unitaires de ne pas implementer.
        // L'AiWorldAdapter en production fait le vrai routage vers PlayerHealthState.
    }

    /**
     * Spawn un projectile alien (= bullet) qui voyage vers le joueur.
     * Equivalent ASM : {@code FireAtPlayer1} (newaliencontrol.s) appele depuis
     * {@code ai_AttackWithProjectile}.
     *
     * <p>Pour la phase 2.D initiale, l'implementation peut simplement appliquer
     * les degats avec une probabilite (= simulate hit), sans vrai projectile
     * qui voyage. Une 2.E pourra creer un vrai pool de bullets aliens.</p>
     *
     * @param bulletType index dans BulletDefs (= {@code AlienT_BulType_w})
     * @param damage     points de degats du tir
     * @param fromX      position alien X
     * @param fromY      position alien Y
     * @param fromZ      position alien Z
     */
    default void spawnAlienProjectile(int bulletType, int damage,
                                       float fromX, float fromY, float fromZ) {
        // Default no-op pour les tests.
    }

    // ── Multiplicateur de frame skip (Anim_TempFrames_w) ─────────────────────

    /**
     * @return nombre de "frames Amiga" a simuler dans cet appel d'IA.
     * Au framerate plein (50Hz Amiga), c'est 1. Si on rattrape du retard, peut
     * monter a 2-3. Toutes les vitesses et timeouts sont multiplies par cette
     * valeur, conformement a ce que fait l'ASM avec {@code Anim_TempFrames_w}.
     */
    default int tempFrames() { return 1; }

    // ── Collision murs alien (session 113 phase 2.F) ─────────────────────────

    /**
     * Resout le mouvement d'un alien contre les murs du niveau.
     *
     * <p>Equivalent ASM : {@code Obj_DoCollision} (newaliencontrol.s). L'ASM
     * teste {@code AlienT_Girth_w} dans la table {@code diststowall} pour
     * determiner la distance de collision (80, 160 ou 320 unites Amiga selon
     * le girth de l'alien), puis fait un slide-along-wall si necessaire.</p>
     *
     * <p>Coordonnees en <b>unites Amiga</b> (les memes que {@code state.worldX/Z}).</p>
     *
     * <p>Implementation par defaut : aucun mur, retourne directement la
     * position cible. Sert pour les tests unitaires qui ne veulent pas
     * simuler la geometrie.</p>
     *
     * @param fromX        position courante X (Amiga)
     * @param fromZ        position courante Z (Amiga)
     * @param dx           delta X souhaite (Amiga)
     * @param dz           delta Z souhaite (Amiga)
     * @param radiusAmiga  rayon de collision (Amiga, depuis {@link AlienDef#collisionRadius})
     * @return float[] {newX, newZ} apres resolution (Amiga)
     */
    default float[] resolveAlienMove(float fromX, float fromZ,
                                       float dx, float dz,
                                       int radiusAmiga) {
        return new float[]{ fromX + dx, fromZ + dz };
    }
}
