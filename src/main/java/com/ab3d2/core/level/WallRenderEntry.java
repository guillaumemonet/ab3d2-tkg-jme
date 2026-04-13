package com.ab3d2.core.level;

/**
 * Données de rendu d'un mur depuis ZoneGraphAdds de twolev.graph.bin.
 *
 * <h2>Correspondance champs ↔ AMOS _SAVE_LEVEL</h2>
 * <pre>
 *  Java field   AMOS source           Description
 *  ----------   ---------------       ------------------------------------
 *  wallId       PK[B]                 Index du côté B dans la zone
 *  type         PK[0]                 Type : 0=mur, 1=sol, 2=plafond, 4=objet
 *  leftPt       DK[ZO(A,B)]           Index point gauche (dans tableau points)
 *  rightPt      DK[ZO(A,B+1)]         Index point droit
 *  whichLeft    PK[B]                 Index côté gauche
 *  whichRight   PK[NB]                Index côté droit
 *  wallLen      DK[L]                 Longueur segment / 2 (unités monde)
 *  texIndex     HIGH SHORT de LK[W+VO] ZWG(A,B,0) = vrai index texture mur
 *  yOffset      LOW  BYTE  de LK[W+VO] VO = (-floor) & 0xFF = décalage Y texture
 *  clipIdx      DK[ZWG(A,B,2)]        Index de clip texture (pas l'index visuel !)
 *  hMask        PK[CH-1]              Hauteur texture - 1
 *  hShift       PK[CSV]               log2(hauteur texture)
 *  wMask        PK[GW-1]              Largeur texture - 1
 *  whichPbr     PK[TBV*16+BBV]        Flags tiling haut/bas
 *  topWall      LK[H1*256]            Hauteur haut du mur × 256 (signée, éditeur inversé)
 *  botWall      LK[H2*256]            Hauteur bas du mur × 256 (signée)
 *  brightOfs    PK[WB(A,B)]           Offset luminosité du mur
 *  otherZone    PK[ZZ(A,B)]           Zone adjacente (0=mur plein, >0=portail/porte)
 * </pre>
 *
 * <h2>Index texture</h2>
 * {@code texIndex} est le vrai index de texture mur (ZWG(A,B,0)), utilisé pour
 * sélectionner le fichier .256wad dans la liste triée des textures du niveau.
 * {@code clipIdx} est l'index de clip ZWG(A,B,2), une propriété de découpe secondaire.
 *
 * <h2>Hauteurs (convention AMOS inversée)</h2>
 * {@code topWall >> 8} et {@code botWall >> 8} donnent les hauteurs éditeur.
 * Valeur éditeur plus petite = position plus haute dans le monde.
 * Pour JME (Y-up) : {@code Y_jme = -(hauteur_editeur) / SCALE}
 * Exemple : topWall=-128×256 → editeur=-128 → Y_jme = 128/32 = 4.0f
 */
public final class WallRenderEntry {

    public static final int TYPE_WALL   = 0;
    public static final int TYPE_FLOOR  = 1;
    public static final int TYPE_CEIL   = 2;
    public static final int TYPE_OBJECT = 4;
    public static final int TYPE_WATER  = 7;
    public static final int TYPE_END    = 0x80;

    // ── Champs communs ────────────────────────────────────────────────────────
    public final int type;
    public final int wallId;

    // ── Champs mur (type == TYPE_WALL) ────────────────────────────────────────
    public final int leftPt;        // index point gauche
    public final int rightPt;       // index point droit
    public final int whichLeft;     // index côté gauche dans la zone
    public final int whichRight;    // index côté droit dans la zone
    public final int wallLen;       // longueur segment / 2 (était "flags")
    public final int texIndex;      // VRAI index texture mur ZWG(A,B,0) (était "fromTile")
    public final int yOffset;       // décalage Y texture VO (byte, 0-255)
    public final int clipIdx;       // index clip ZWG(A,B,2) (était "texIndex" — RENOMMÉ)
    public final int hMask;         // hauteur texture - 1
    public final int hShift;        // log2(hauteur texture)
    public final int wMask;         // largeur texture - 1
    public final int whichPbr;      // flags tiling TBV*16+BBV
    public final int topWall;       // hauteur haut × 256 (signée)
    public final int botWall;       // hauteur bas × 256 (signée)
    public final int brightOfs;     // offset luminosité
    public final int otherZone;     // zone adjacente (0=plein, >0=portail/porte)

    // ── Champ sol/plafond (type == TYPE_FLOOR ou TYPE_CEIL) ───────────────────
    public final int floorWhichTile; // index whichTile texture sol/plafond (-1 si pas un record sol)

    // ── Constructeur mur ──────────────────────────────────────────────────────
    public WallRenderEntry(int type, int wallId,
                           int leftPt,    int rightPt,
                           int whichLeft, int whichRight,
                           int wallLen,   int texIndex,  int yOffset,
                           int clipIdx,   int hMask,     int hShift,
                           int wMask,     int whichPbr,
                           int topWall,   int botWall,
                           int brightOfs, int otherZone) {
        this.type         = type;        this.wallId     = wallId;
        this.leftPt       = leftPt;      this.rightPt    = rightPt;
        this.whichLeft    = whichLeft;   this.whichRight = whichRight;
        this.wallLen      = wallLen;
        this.texIndex     = texIndex;    // vrai index texture mur
        this.yOffset      = yOffset;     // décalage Y texture
        this.clipIdx      = clipIdx;     // index clip
        this.hMask        = hMask;       this.hShift    = hShift;
        this.wMask        = wMask;       this.whichPbr  = whichPbr;
        this.topWall      = topWall;     this.botWall   = botWall;
        this.brightOfs    = brightOfs;   this.otherZone = otherZone;
        this.floorWhichTile = -1;
    }

    // ── Constructeur sol/plafond ──────────────────────────────────────────────
    private WallRenderEntry(int typeFC, int whichTile) {
        this.type    = typeFC; this.wallId = 0;
        this.leftPt  = 0; this.rightPt  = 0;
        this.whichLeft = 0; this.whichRight = 0;
        this.wallLen = 0; this.texIndex = 0; this.yOffset = 0;
        this.clipIdx = 0; this.hMask = 0;   this.hShift  = 0;
        this.wMask   = 0; this.whichPbr = 0;
        this.topWall = 0; this.botWall  = 0;
        this.brightOfs = 0; this.otherZone = 0;
        this.floorWhichTile = whichTile;
    }

    public static WallRenderEntry makeFloor(int wt) { return new WallRenderEntry(TYPE_FLOOR, wt); }
    public static WallRenderEntry makeCeil (int wt) { return new WallRenderEntry(TYPE_CEIL,  wt); }

    // ── Accesseurs ────────────────────────────────────────────────────────────
    public boolean isWall()        { return type == TYPE_WALL; }
    public boolean isFloorRecord() { return type == TYPE_FLOOR && floorWhichTile >= 0; }
    public boolean isCeilRecord()  { return type == TYPE_CEIL  && floorWhichTile >= 0; }
    public boolean isEnd()         { return (type & 0xFF) >= TYPE_END; }

    /** Largeur de la texture en pixels (calculée depuis wMask). */
    public int texWidth()  { return (wMask & 0xFF) + 1; }
    /** Hauteur de la texture en pixels (calculée depuis hMask). */
    public int texHeight() { return (hMask & 0xFF) + 1; }

    /** Hauteur éditeur du haut du mur (valeur AMOS inversée : plus petit = plus haut). */
    public int topWallH()  { return topWall >> 8; }
    /** Hauteur éditeur du bas du mur. */
    public int botWallH()  { return botWall >> 8; }

    @Override public String toString() {
        if (isFloorRecord()) return "WallRenderEntry[FLOOR whichTile=" + floorWhichTile + "]";
        if (isCeilRecord())  return "WallRenderEntry[CEIL  whichTile=" + floorWhichTile + "]";
        if (!isWall())       return "WallRenderEntry[type=" + type + "]";
        return String.format(
            "WallRenderEntry[WALL pts=%d→%d tex=%d clip=%d yOfs=%d top=%d bot=%d otherZ=%d]",
            leftPt, rightPt, texIndex, clipIdx, yOffset, topWallH(), botWallH(), otherZone);
    }
}
