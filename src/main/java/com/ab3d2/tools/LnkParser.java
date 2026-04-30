package com.ab3d2.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parseur du fichier GLF database : ab3:includes/test.lnk
 *
 * Structure (depuis leved303.amos, procedure _LOAD_DEF_LINK) :
 *   Taille totale : 86268 bytes
 *   Offsets LINK :
 *     $0000 (+  0)  : Level directory path (64 bytes)
 *     $0040 (+ 64)  : Level names  (16 × 40 bytes = 640)
 *     $02C0 (+704)  : GLFT_ObjGfxNames_l  : 30 × 64 = 1920 bytes  -> noms .WAD
 *     $0A40 (+2624) : GLFT_SFXFilenames_l : 60 × 64 = 3840 bytes  -> noms .fib
 *     $1940 (+6464) : GLFT_FloorFilename  : 64 bytes               -> floortile
 *     $1980 (+6528) : GLFT_TextureFilename: 192 bytes              -> texture
 *     $14760 (+83808): GLFT_WallGFXNames  : 16 × 64 = 1024 bytes  -> .256wad
 *     $14B60 (+84832): GLFT_WallHeights   : 16 × 2  = 32 bytes
 *     $39B0 (+14768) : GLFT_FrameData     : 30 × 256 bytes         -> frame descriptors
 *     $13FE0 (+81888): GLFT_VectorNames_l : 30 × 64 = 1920 bytes   -> noms vectobj
 *     $2C0  (+704)   : GLFT_ObjGfxNames_l  (bitmap objects)
 *     $34D8 (+13528) : GLFT_AlienNames    : 20 × 20 = 400 bytes
 *     $57B0 (+22448) : GLFT_ObjectNames   : 30 × 20 = 600 bytes
 *     $3668 (+13928) : GLFT_AlienDefs     : 20 × 42 = 840 bytes
 *     $5A08 (+23048) : GLFT_ObjectDefs    : 30 × 40 = 1200 bytes
 *
 * Format frame data (depuis leved303.amos, proc _GL_SETOBJFRAMES) :
 *   Pour chaque objet bitmap OG (0..29), chaque frame FRN (0..63) :
 *   Offset = LINK+$39B0 + OG*256 + FRN*8
 *   [LX:word, LY:word, LW/2:word, LH/2:word]
 *   LX = start column in WAD strip table
 *   LY = start row in WAD data
 *   LW = width in pixels (stored /2)
 *   LH = height in pixels (stored /2)
 */
public class LnkParser {

    // Offsets dans TEST.LNK (depuis leved303.amos)
    public static final int OFS_LEVEL_DIR        = 0x0000;   // 64 bytes
    public static final int OFS_LEVEL_NAMES      = 0x0040;   // 16*40 = 640 bytes
    public static final int OFS_OBJ_GFX_NAMES    = 0x02C0;   // 30*64 = 1920 bytes  <- WAD sprites
    public static final int OFS_SFX_FILENAMES    = 0x0A40;   // 60*64 = 3840 bytes
    public static final int OFS_FLOOR_FILENAME   = 0x1940;   // 64 bytes
    public static final int OFS_TEXTURE_FILENAME = 0x1980;   // 192 bytes
    public static final int OFS_FRAME_DATA       = 0x39B0;   // 30*256 = 7680 bytes  <- LX/LY/LW/LH
    public static final int OFS_OBJECT_NAMES     = 0x57B0;   // 30*20 = 600 bytes
    public static final int OFS_OBJECT_DEFS      = 0x5A08;   // 30*40 = 1200 bytes
    /**
     * GLFT_ObjectDefAnims_l : 30 def * 120 bytes (20 frames de 6 bytes chacune).
     *
     * <p>Offset = OFS_OBJECT_DEFS + 30*40 = 0x5A08 + 1200 = 0x5EB8.</p>
     *
     * <p>Pour chaque def {@code d} et frame {@code f}, l'entree est a
     * {@code OFS_OBJECT_DEF_ANIMS + d*120 + f*6} et contient :</p>
     * <ul>
     *   <li>{@code byte 0} : <b>object index</b> (quelle WAD/sprite collection a dessiner).
     *       Lu par DEFANIMOBJ et copie dans {@code obj+9} (= low byte du WORD a obj+8-9 utilise
     *       comme index dans {@code Draw_ObjectPtrs_vl} et {@code GLFT_FrameData_l}).
     *       Generalement = defIdx pour le default ; peut differer pour les objets qui
     *       changent d'apparence pendant l'animation.</li>
     *   <li>{@code byte 1} : <b>frame index</b> (quelle frame de cette WAD a afficher).
     *       Lu par DEFANIMOBJ et copie dans {@code obj+11} ({@code current frame}).
     *       <strong>C'est ce byte qui correspond au {@code _fN.png}</strong> a charger.</li>
     *   <li>{@code word 2..3} : XPos offset (BITMAP) ou angle adjust (VECTOR)</li>
     *   <li>{@code byte 4} : animation step (decalage Y supplementaire)</li>
     *   <li>{@code byte 5} : duree (timer1) avant la prochaine frame</li>
     * </ul>
     *
     * <p>Reference ASM : {@code newaliencontrol.s::DEFANIMOBJ} ligne ~1538
     * (ecriture) et {@code objdrawhires.s::draw_Bitmap} (lecture des bytes 9 et 11).</p>
     *
     * @since session 111
     */
    public static final int OFS_OBJECT_DEF_ANIMS = 0x5EB8;   // 30*120 = 3600 bytes

    /**
     * GLFT_ObjectActAnims_l : 30 * 120 bytes (anims des objets ACTIVATED, ex. computer
     * en train de pulser quand on a presse SPACE). Meme structure que ObjectDefAnims.
     *
     * <p>Offset = OFS_OBJECT_DEF_ANIMS + 30*120 = 0x5EB8 + 3600 = 0x6CC8.</p>
     *
     * @since session 112
     */
    public static final int OFS_OBJECT_ACT_ANIMS = 0x6CC8;   // 30*120 = 3600 bytes

    /**
     * GLFT_AmmoGive_l : pour chaque def (30), une struct {@code InvCT} (44 bytes)
     * decrivant ce que la collecte de cet objet ajoute au joueur :
     * <pre>
     *   UWORD Health         ; +0  : sante
     *   UWORD JetpackFuel    ; +2  : carburant jetpack
     *   UWORD AmmoCounts[20] ; +4  : compteur d'ammo par bullet type (NUM_BULLET_DEFS=20)
     * </pre>
     *
     * <p>Offset = OFS_OBJECT_ACT_ANIMS + 30*120 = 0x6CC8 + 3600 = 0x7AD8.</p>
     *
     * <p>Reference ASM : {@code newaliencontrol.s::Plr1_CollectItem}, ligne ~1640 :</p>
     * <pre>
     * lea    GLFT_AmmoGive_l(a2), a1
     * muls   #AmmoGiveLen, d0           ; AmmoGiveLen = 22*2 = 44
     * add.w  d0, a1                     ; a1 -> ammoGive[defIdx]
     * </pre>
     *
     * @since session 112
     */
    public static final int OFS_AMMO_GIVE = 0x7AD8;   // 30*44 = 1320 bytes

    /**
     * GLFT_GunGive_l : pour chaque def (30), une struct {@code InvIT} (24 bytes)
     * decrivant ce que la collecte de cet objet ajoute aux items du joueur :
     * <pre>
     *   UWORD Shield         ; +0  : a/desactive le shield
     *   UWORD JetPack        ; +2  : a/desactive le jetpack
     *   UWORD Weapons[10]    ; +4  : flag possede par arme (NUM_GUN_DEFS=10)
     * </pre>
     *
     * <p>Offset = OFS_AMMO_GIVE + 30*44 = 0x7AD8 + 1320 = 0x7FF8.</p>
     *
     * @since session 112
     */
    public static final int OFS_GUN_GIVE = 0x7FF8;    // 30*24 = 720 bytes
    public static final int OFS_ALIEN_NAMES      = 0x34D8;   // 20*20 = 400 bytes
    public static final int OFS_ALIEN_DEFS       = 0x3668;   // 20*42 = 840 bytes
    public static final int OFS_WALL_GFX_NAMES   = 0x14760;  // 16*64 = 1024 bytes  <- .256wad
    public static final int OFS_WALL_HEIGHTS     = 0x14B60;  // 16*2  = 32 bytes
    public static final int OFS_VECTOR_NAMES     = 0x13FE0;  // 30*64 = 1920 bytes  <- vectobj

    // Limites
    public static final int NUM_OBJ_GFX   = 30;
    public static final int NUM_VECTORS   = 30;
    public static final int NUM_WALLS     = 16;
    public static final int NUM_SFX       = 60;
    public static final int NUM_ALIENS    = 20;
    public static final int NUM_OBJECTS   = 30;
    /**
     * Max frames par objet bitmap.
     *
     * <p>Calcul : la section {@code GLFT_FrameData_l} fait 30 * 256 = 7680 bytes
     * (de {@code OFS_FRAME_DATA = 0x39B0} a {@code OFS_OBJECT_NAMES = 0x57B0}),
     * soit 256 bytes par objet. Chaque frame fait 8 bytes (LX, LY, LW/2, LH/2),
     * donc 256 / 8 = <b>32 frames</b> max par objet.</p>
     *
     * <p><b>Bug historique</b> : valeur initialement positionnee a 64, ce qui
     * faisait que {@link #countFrames(int)} et {@link #getFrameDesc(int, int)}
     * lisaient au-dela des 256 bytes de l'objet et tombaient dans les donnees de
     * l'objet suivant. Resultat : sprites "fantomes" en frame 32+ (par exemple
     * {@code bigbullet_f33.png} affichait 4 sprites en grille 2x2 voles a
     * l'objet voisin, et {@code glare/} exportait 64 frames dont 32 venaient
     * d'autres objets).</p>
     *
     * <p>Le commentaire de {@code leved303.amos} mentionnait "FRN (0..63)"
     * mais cela contredisait la structure binaire reelle. Fix session 113.</p>
     */
    public static final int NUM_FRAMES    = 32;

    private final byte[] data;

    public LnkParser(byte[] lnkData) {
        this.data = lnkData;
    }

    public static LnkParser load(Path lnkFile) throws IOException {
        byte[] raw = Files.readAllBytes(lnkFile);
        System.out.printf("TEST.LNK: %d bytes (attendu 86268)%n", raw.length);
        return new LnkParser(raw);
    }

    /** Accès aux données brutes (pour LevelJsonExporter). */
    public byte[] getData() { return data; }

    // ── Lecture de chaines C (null-terminées) ─────────────────────────────────

    public String readCString(int offset, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLen && offset + i < data.length; i++) {
            byte b = data[offset + i];
            if (b == 0) break;
            char c = (char)(b & 0xFF);
            if (c >= 32 && c < 127) sb.append(c);
            else break;
        }
        return sb.toString();
    }

    public int readShort(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    // ── Noms des sprites bitmap (WAD) ─────────────────────────────────────────

    /**
     * Retourne les 30 noms de fichiers WAD (objets bitmap).
     * Exemple : "ab3:includes/alien2", "ab3:includes/pickups", ...
     * Les extensions .WAD, .PTR, .256PAL sont ajoutées lors du chargement.
     */
    public List<String> getObjGfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_OBJ_GFX; i++) {
            int off = OFS_OBJ_GFX_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    /**
     * Extrait juste le nom de fichier (sans chemin ni extension).
     * "ab3:includes/alien2" -> "alien2"
     */
    public static String extractFileName(String path) {
        if (path == null || path.isEmpty()) return "";
        // Séparer sur / et :
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        String name = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        // Supprimer extension si présente
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Noms des modèles vectoriels ───────────────────────────────────────────

    /**
     * Retourne les 30 noms de fichiers vectoriels.
     * Exemple : "ab3:vectobj/generator", "ab3:vectobj/switch", ...
     */
    public List<String> getVectorNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_VECTORS; i++) {
            int off = OFS_VECTOR_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    // ── Frame data ────────────────────────────────────────────────────────────

    /**
     * Descriptor d'une frame de sprite.
     * Depuis leved303.amos :
     *   LX = Deek(LINK+$39B0 + OG*256 + FRN*8)     -> colonne de départ dans PTR
     *   LY = Deek(LINK+$39B0 + OG*256 + FRN*8 + 2) -> ligne de départ dans WAD
     *   LW = Deek(LINK+$39B0 + OG*256 + FRN*8 + 4) * 2  -> largeur pixels
     *   LH = Deek(LINK+$39B0 + OG*256 + FRN*8 + 6) * 2  -> hauteur pixels
     */
    public record FrameDesc(int lx, int ly, int lw, int lh) {
        public boolean isValid() { return lw > 0 && lh > 0 && lw <= 512 && lh <= 512; }
    }

    public FrameDesc getFrameDesc(int objIndex, int frameIndex) {
        int off = OFS_FRAME_DATA + objIndex * 256 + frameIndex * 8;
        if (off + 8 > data.length) return new FrameDesc(0, 0, 0, 0);
        int lx = readShort(off);
        int ly = readShort(off + 2);
        int lw = readShort(off + 4) * 2;
        int lh = readShort(off + 6) * 2;
        return new FrameDesc(lx, ly, lw, lh);
    }

    /** Nombre de frames valides pour un objet (cherche la première frame invalide). */
    public int countFrames(int objIndex) {
        for (int f = 0; f < NUM_FRAMES; f++) {
            FrameDesc fd = getFrameDesc(objIndex, f);
            if (!fd.isValid()) return f;
        }
        return NUM_FRAMES;
    }

    // ── Noms des murs (.256wad) ───────────────────────────────────────────────

    public List<String> getWallGfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_WALLS; i++) {
            int off = OFS_WALL_GFX_NAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    public int getWallHeight(int wallIndex) {
        int off = OFS_WALL_HEIGHTS + wallIndex * 2;
        if (off + 2 > data.length) return 0;
        return readShort(off);
    }

    // ── Object def animations (sprite frame de chaque def au repos) ──────────

    /** Taille d'une frame d'anim dans GLFT_ObjectDefAnims_l (= O_FrameStoreSize). */
    private static final int OBJ_DEF_ANIM_FRAME_SIZE = 6;
    /** Nombre de frames par def (= 20, taille totale = 120 bytes). */
    private static final int OBJ_DEF_ANIM_FRAMES_PER_DEF = 20;
    /** Taille totale par def dans GLFT_ObjectDefAnims_l (= O_AnimSize = 120). */
    private static final int OBJ_DEF_ANIM_SIZE = OBJ_DEF_ANIM_FRAME_SIZE * OBJ_DEF_ANIM_FRAMES_PER_DEF;

    /**
     * Retourne le {@code sprite frame index} a utiliser au repos pour l'objet de
     * {@code defIdx} = byte 1 de la frame 0 de l'anim.
     *
     * <p>Pour les BITMAP (gfxType=0), c'est l'index dans le PNG du WAD :
     * {@code <wadName>_f<index>.png}. C'est exactement ce que DEFANIMOBJ
     * ASM ecrit a {@code obj+11}, lu plus tard par draw_Bitmap pour selectionner
     * la frame dans {@code GLFT_FrameData_l[gfx_idx]}.</p>
     *
     * <p><strong>Note (session 111 fix)</strong> : initialement on lisait le byte 0 par
     * erreur (= {@code object index} servant a choisir la WAD), ce qui donnait
     * la mauvaise image car byte 0 vaut souvent {@code defIdx} (auto-reference).
     * Le byte qui correspond reellement au {@code _fN.png} est le byte 1.</p>
     *
     * <p>Pour les VECTOR (gfxType=1), c'est plutot un index de model vectobj
     * (semantique differente, lecture identique).</p>
     *
     * @return frame index 0..255, ou 0 si offset invalide
     * @since session 111
     */
    public int getObjectDefaultFrameIndex(int defIdx) {
        return getObjectAnimFrameByte(defIdx, 0, 1);
    }

    /**
     * Retourne le {@code gfx index} (= index WAD source) pour l'objet de
     * {@code defIdx} = byte 0 de la frame 0 de l'anim.
     *
     * <p>Pour les BITMAP, c'est l'index dans {@code GLFT_ObjGfxNames_l} du WAD
     * source ou aller chercher la frame. Generalement = {@code defIdx} (un objet
     * utilise son propre WAD), mais peut differer pour les objets qui partagent
     * un WAD avec un autre.</p>
     *
     * <p>Exemples ({@code level A}) :</p>
     * <ul>
     *   <li>Medipac (defIdx=1) : byte 0 = 1 → utilise PICKUPS (= sa propre WAD)</li>
     *   <li>ShotgunShells (defIdx=2) : byte 0 = 1 → REDIRIGE vers PICKUPS
     *       (et non vers BIGBULLET = sa propre WAD, qui contient les sprites
     *       de projectile, pas l'item ramassable)</li>
     *   <li>Plasmaclip (defIdx=5) : byte 0 = 1 → REDIRIGE vers PICKUPS
     *       (au lieu de KEYS qui ne contient que des passkeys)</li>
     * </ul>
     *
     * <p>Lu par DEFANIMOBJ ASM et copie dans {@code obj+9}, puis lu par
     * draw_Bitmap pour indexer {@code Draw_ObjectPtrs_vl[gfx_idx]} et
     * {@code GLFT_FrameData_l[gfx_idx]}.</p>
     *
     * @return gfx index 0..29, ou 0 si offset invalide
     * @since session 111 (fix textures partagees)
     */
    public int getObjectDefaultGfxIndex(int defIdx) {
        return getObjectAnimFrameByte(defIdx, 0, 0);
    }

    // ── AlienT : structure complete des 20 definitions d'aliens ───────────────────

    /** Taille d'une entree AlienT dans GLFT_AlienDefs_l (= AlienT_SizeOf_l = 42). */
    public static final int ALIEN_DEF_SIZE = 42;

    /**
     * Structure complete d'un alien tel que decrit dans {@code defs.i::STRUCTURE AlienT}.
     * 21 champs UWORD soit 42 bytes au total.
     *
     * <p>Tous les comportements (Default/Response/Retreat/Followup) sont des INDEX
     * dans des tables de routines ASM (cf. {@code modules/ai.s}) :</p>
     *
     * <ul>
     *   <li><b>DefaultBehaviour</b> : 0=ProwlRandom, 1=ProwlRandomFlying</li>
     *   <li><b>ResponseBehaviour</b> : 0=Charge, 1=ChargeToSide, 2=AttackWithGun,
     *       3=ChargeFlying, 4=ChargeToSideFlying, 5=AttackWithGunFlying</li>
     *   <li><b>FollowupBehaviour</b> : 0=PauseBriefly, 1=Approach, 2=ApproachToSide,
     *       3=ApproachFlying, 4=ApproachToSideFlying</li>
     *   <li><b>RetreatBehaviour</b> : non implemente dans l'ASM original ({@code ai_DoRetreat: rts})</li>
     * </ul>
     *
     * <p>Les Speed sont des vitesses de deplacement multipliees par {@code Anim_TempFrames_w}.
     * Les Timeout sont des compteurs de frames (a 50Hz Amiga).</p>
     *
     * <p>{@code DamageToRetreat} et {@code DamageToFollowup} sont des seuils en HP
     * (cumules dans {@code AI_Damaged_vw[idx]}) pour declencher les transitions
     * d'etat. Voir {@code modules/ai.s::ai_TakeDamage}.</p>
     *
     * @param index             index dans {@code GLFT_AlienDefs_l} (0..19)
     * @param gfxType           +0  : 0=BITMAP std, 1=VECTOR (boss polygonaux),
     *                                2-5 = variantes BITMAP avec palette differente
     * @param defaultBehaviour  +2  : routine de patrouille (0=marche, 1=vol)
     * @param reactionTime      +4  : delai avant reaction quand le joueur est vu (frames)
     * @param defaultSpeed      +6  : vitesse de patrouille
     * @param responseBehaviour +8  : routine d'attaque (charge/tir, sol/vol)
     * @param responseSpeed     +10 : vitesse en mode attaque
     * @param responseTimeout   +12 : duree maximale en mode Response (frames)
     * @param damageToRetreat   +14 : seuil de degats cumules pour partir en Retreat
     * @param damageToFollowup  +16 : seuil de degats cumules pour rentrer en Followup
     * @param followupBehaviour +18 : routine apres avoir tire (pause/approche)
     * @param followupSpeed     +20 : vitesse en mode Followup
     * @param followupTimeout   +22 : duree du mode Followup (frames)
     * @param retreatBehaviour  +24 : routine de fuite (non utilisee dans l'ASM)
     * @param retreatSpeed      +26 : vitesse de fuite
     * @param retreatTimeout    +28 : duree de la fuite
     * @param bulType           +30 : index dans {@code GLFT_BulletDefs_l} (type de tir)
     * @param hitPoints         +32 : HP de base pour cet alien
     * @param height            +34 : hauteur du modele en unites monde Amiga
     * @param girth             +36 : largeur (girth) - influence collision murs
     * @param splatType         +38 : type de projectile/alien spawne a la mort
     * @param auxilliary        +40 : objet auxiliaire (torche/glow attache au corps)
     */
    public record AlienDef(
        int index,
        int gfxType,
        int defaultBehaviour,
        int reactionTime,
        int defaultSpeed,
        int responseBehaviour,
        int responseSpeed,
        int responseTimeout,
        int damageToRetreat,
        int damageToFollowup,
        int followupBehaviour,
        int followupSpeed,
        int followupTimeout,
        int retreatBehaviour,
        int retreatSpeed,
        int retreatTimeout,
        int bulType,
        int hitPoints,
        int height,
        int girth,
        int splatType,
        int auxilliary,
        String name
    ) {}

    /**
     * Lit la definition complete d'un alien depuis {@code GLFT_AlienDefs_l[idx]}.
     *
     * <p>Les 21 UWORDs sont lus en BIG-ENDIAN. Si l'offset deborde du fichier,
     * un AlienDef avec tous les champs a 0 est retourne.</p>
     *
     * @param idx index alien (0..19)
     * @return la definition complete
     * @since session 113
     */
    public AlienDef getAlienDef(int idx) {
        if (idx < 0 || idx >= NUM_ALIENS) {
            return new AlienDef(idx, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, "");
        }
        int base = OFS_ALIEN_DEFS + idx * ALIEN_DEF_SIZE;
        if (base + ALIEN_DEF_SIZE > data.length) {
            return new AlienDef(idx, 0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0, "");
        }
        // Nom (depuis GLFT_AlienNames_l)
        String name = readCString(OFS_ALIEN_NAMES + idx * 20, 20);
        return new AlienDef(
            idx,
            readShort(base + 0),   // gfxType
            readShort(base + 2),   // defaultBehaviour
            readShort(base + 4),   // reactionTime
            readShort(base + 6),   // defaultSpeed
            readShort(base + 8),   // responseBehaviour
            readShort(base + 10),  // responseSpeed
            readShort(base + 12),  // responseTimeout
            readShort(base + 14),  // damageToRetreat
            readShort(base + 16),  // damageToFollowup
            readShort(base + 18),  // followupBehaviour
            readShort(base + 20),  // followupSpeed
            readShort(base + 22),  // followupTimeout
            readShort(base + 24),  // retreatBehaviour
            readShort(base + 26),  // retreatSpeed
            readShort(base + 28),  // retreatTimeout
            readShort(base + 30),  // bulType
            readShort(base + 32),  // hitPoints
            readShort(base + 34),  // height
            readShort(base + 36),  // girth
            readShort(base + 38),  // splatType
            readShort(base + 40),  // auxilliary
            name
        );
    }

    // ── Inventaire donne par les objets collectables (Game_AddToInventory ASM) ───────────────────

    /** Taille d'une entree dans GLFT_AmmoGive_l (= AmmoGiveLen = 22*2 = 44). */
    private static final int AMMO_GIVE_SIZE = 44;
    /** Nombre de WORDs dans chaque entree AmmoGive (Health + JetpackFuel + Ammo[20] = 22). */
    public static final int AMMO_GIVE_FIELDS = 22;

    /** Taille d'une entree dans GLFT_GunGive_l (= GunGiveLen = 12*2 = 24). */
    private static final int GUN_GIVE_SIZE = 24;
    /** Nombre de WORDs dans chaque entree GunGive (Shield + JetPack + Weapons[10] = 12). */
    public static final int GUN_GIVE_FIELDS = 12;

    /**
     * Lit la table {@code GLFT_AmmoGive_l[defIdx]} : ce que la collecte de l'objet
     * {@code defIdx} ajoute au joueur (sante, fuel, ammo).
     *
     * @param defIdx index de l'objet (0..29)
     * @return tableau de 22 WORDs : [Health, JetpackFuel, Ammo[0]..Ammo[19]],
     *         ou un tableau de 22 zeros si offset invalide.
     * @since session 112
     */
    public int[] getAmmoGive(int defIdx) {
        int[] result = new int[AMMO_GIVE_FIELDS];
        if (defIdx < 0 || defIdx >= NUM_OBJECTS) return result;
        int base = OFS_AMMO_GIVE + defIdx * AMMO_GIVE_SIZE;
        if (base + AMMO_GIVE_SIZE > data.length) return result;
        for (int i = 0; i < AMMO_GIVE_FIELDS; i++) {
            result[i] = readShort(base + i * 2);
        }
        return result;
    }

    /**
     * Lit la table {@code GLFT_GunGive_l[defIdx]} : ce que la collecte de l'objet
     * {@code defIdx} ajoute aux items du joueur (shield, jetpack, weapons).
     *
     * @param defIdx index de l'objet (0..29)
     * @return tableau de 12 WORDs : [Shield, JetPack, Weapons[0]..Weapons[9]],
     *         ou un tableau de 12 zeros si offset invalide.
     * @since session 112
     */
    public int[] getGunGive(int defIdx) {
        int[] result = new int[GUN_GIVE_FIELDS];
        if (defIdx < 0 || defIdx >= NUM_OBJECTS) return result;
        int base = OFS_GUN_GIVE + defIdx * GUN_GIVE_SIZE;
        if (base + GUN_GIVE_SIZE > data.length) return result;
        for (int i = 0; i < GUN_GIVE_FIELDS; i++) {
            result[i] = readShort(base + i * 2);
        }
        return result;
    }

    /**
     * Lit un byte specifique d'une frame d'anim dans {@code GLFT_ObjectDefAnims_l}.
     *
     * @param defIdx     index de l'objet (0..29)
     * @param frameIdx   index de frame (0..19)
     * @param byteOffset offset dans la frame (0..5)
     * @since session 111
     */
    public int getObjectAnimFrameByte(int defIdx, int frameIdx, int byteOffset) {
        if (defIdx < 0 || defIdx >= NUM_OBJECTS) return 0;
        if (frameIdx < 0 || frameIdx >= OBJ_DEF_ANIM_FRAMES_PER_DEF) return 0;
        if (byteOffset < 0 || byteOffset >= OBJ_DEF_ANIM_FRAME_SIZE) return 0;
        int off = OFS_OBJECT_DEF_ANIMS + defIdx * OBJ_DEF_ANIM_SIZE
                + frameIdx * OBJ_DEF_ANIM_FRAME_SIZE + byteOffset;
        if (off >= data.length) return 0;
        return data[off] & 0xFF;
    }

    // ── Noms des SFX ─────────────────────────────────────────────────────────

    public List<String> getSfxNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_SFX; i++) {
            int off = OFS_SFX_FILENAMES + i * 64;
            if (off + 64 > data.length) break;
            String name = readCString(off, 64);
            names.add(name);
        }
        return names;
    }

    // ── Noms des aliens et objets ─────────────────────────────────────────────

    public List<String> getAlienNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_ALIENS; i++) {
            int off = OFS_ALIEN_NAMES + i * 20;
            if (off + 20 > data.length) break;
            names.add(readCString(off, 20));
        }
        return names;
    }

    public List<String> getObjectNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < NUM_OBJECTS; i++) {
            int off = OFS_OBJECT_NAMES + i * 20;
            if (off + 20 > data.length) break;
            names.add(readCString(off, 20));
        }
        return names;
    }

    // ── Dump diagnostique ─────────────────────────────────────────────────────

    public void dump() {
        System.out.println("\n=== TEST.LNK DUMP ===");
        System.out.println("Level dir: " + readCString(OFS_LEVEL_DIR, 64));
        System.out.println("\n-- Sprites bitmap (GLFT_ObjGfxNames_l) --");
        List<String> gfx = getObjGfxNames();
        for (int i = 0; i < gfx.size(); i++) {
            if (!gfx.get(i).isEmpty()) {
                int nf = countFrames(i);
                System.out.printf("  [%2d] %-40s  (%d frames)%n", i, gfx.get(i), nf);
            }
        }
        System.out.println("\n-- Modeles vectoriels (GLFT_VectorNames_l) --");
        List<String> vecs = getVectorNames();
        for (int i = 0; i < vecs.size(); i++) {
            if (!vecs.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, vecs.get(i));
        }
        System.out.println("\n-- Murs (.256wad) --");
        List<String> walls = getWallGfxNames();
        for (int i = 0; i < walls.size(); i++) {
            if (!walls.get(i).isEmpty())
                System.out.printf("  [%2d] %s (h=%d)%n", i, walls.get(i), getWallHeight(i));
        }
        System.out.println("\n-- Noms objets --");
        List<String> objs = getObjectNames();
        for (int i = 0; i < objs.size(); i++)
            if (!objs.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, objs.get(i));
        System.out.println("\n-- Noms aliens --");
        List<String> aliens = getAlienNames();
        for (int i = 0; i < aliens.size(); i++)
            if (!aliens.get(i).isEmpty())
                System.out.printf("  [%2d] %s%n", i, aliens.get(i));
    }
}
