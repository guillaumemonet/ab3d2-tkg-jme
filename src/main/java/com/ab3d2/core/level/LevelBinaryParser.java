package com.ab3d2.core.level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Parse twolev.bin d'un niveau AB3D2.
 * Voir zone.h, hires.s, defs.i pour les formats.
 */
public class LevelBinaryParser {

    private static final Logger log = LoggerFactory.getLogger(LevelBinaryParser.class);

    public static final int MSG_MAX_LENGTH   = 160;
    public static final int MSG_MAX_CUSTOM   = 10;
    public static final int TEXT_HEADER_SIZE = MSG_MAX_CUSTOM * MSG_MAX_LENGTH; // 1600
    public static final int TLBT_SIZE        = 54;
    public static final int GEOM_DATA_START  = TEXT_HEADER_SIZE + TLBT_SIZE;   // 1654

    public static class BinData {
        public short plr1StartX, plr1StartZ, plr1StartZoneId;
        public short plr2StartX, plr2StartZ, plr2StartZoneId;
        public int numControlPoints, numPoints, numZones, numObjects;
        public int pointsOffset, floorLineOffset, objectDataOffset;
        public int shotDataOffset, alienShotDataOffset;
        public int objectPointsOffset, plr1ObjectOffset, plr2ObjectOffset;
        /**
         * ID de la zone de SORTIE du niveau (Lvl_ExitZoneID_w dans hires.s).
         *
         * <p>Stocke comme word a {@code floorLineOffset - 2} dans twolev.bin.
         * Reference ASM : {@code hires.s:374} :
         * <pre>move.w  -2(a2),Lvl_ExitZoneID_w</pre>
         * ou {@code a2 = floorLineOffset + base}.</p>
         *
         * <p>A {@code hires.s:2089}, le code teste si la zone du joueur correspond
         * a cet ID, et si oui declenche {@code endlevel}.</p>
         *
         * @since session 110
         */
        public int exitZoneId = -1;
        public Vec2W[]  controlPoints;
        public ZEdge[]  edges = new ZEdge[0];
        public Vec2W[]  points;
        public String[] messages;
        public ObjData[] objects = new ObjData[0];
        public byte[]   raw;

        /**
         * Table de luminosite par point : {@code numZones * 40} WORDs au total,
         * soit 40 WORDs par zone. Indexee comme {@code pointBrights[zoneIdx][i]}
         * ou {@code i \u2208 [0..39]}.
         *
         * <p>Format de chaque WORD (cf. {@code hires.s:1502-1543}) :</p>
         * <ul>
         *   <li>Byte BAS : luminosite de base (signed, valeur d'eclairage)</li>
         *   <li>Byte HAUT (si != 0) : animation de lumiere
         *     <ul>
         *       <li>bits 0-3 : index dans {@code Anim_BrightTable_vw}</li>
         *       <li>bits 4-7 : amplitude/phase d'oscillation</li>
         *     </ul>
         *   </li>
         * </ul>
         *
         * <p>Localisation dans le fichier : {@code pointsOffset + numPoints*4 + 4}.
         * La taille totale est {@code numZones * 80} bytes.</p>
         *
         * <p>Critique pour la fidelite du rendu : sans cette table, l'eclairage
         * dynamique des zones (lampes qui pulsent, etc.) ne fonctionne pas.</p>
         *
         * @since session 118
         */
        public short[][] pointBrights = new short[0][];

        /**
         * Liste des point indices "frontiere" par zone, utilisee pour la propagation
         * de lumiere d'une zone vers ses voisines via le PVS.
         *
         * <p>Chaque entree {@code zoneBorderPoints[zoneIdx]} contient une liste de
         * point indices (terminee implicitement par -1 dans le binaire). Le moteur
         * lit ces points pour appliquer l'eclairage radial autour des sources
         * lumineuses (cf. {@code newanims.s:119-121}).</p>
         *
         * <p>Localisation dans le fichier : juste apres {@code PointBrights}, soit
         * {@code pointsOffset + numPoints*4 + 4 + numZones*80}. La structure se
         * termine a {@code floorLineOffset - 2} (juste avant {@code exitZoneId}).</p>
         *
         * <p>Le moteur Amiga adresse {@code BorderPoints[zoneIdx*20]} en bytes
         * (newanims.s:131 {@code muls #20,d4}), ce qui suggere 10 WORDs par zone
         * en moyenne, mais la longueur reelle est variable et terminee par -1.</p>
         *
         * @since session 118
         */
        public short[][] zoneBorderPoints = new short[0][];
    }

    public BinData parseBin(Path p) throws IOException {
        return parseBin(Files.readAllBytes(p));
    }

    public BinData parseBin(byte[] raw) {
        if (raw.length < GEOM_DATA_START)
            throw new IllegalArgumentException("Fichier trop petit : " + raw.length);

        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        BinData d    = new BinData();
        d.raw        = raw;
        d.messages   = extractTextMessages(raw);

        b.position(TEXT_HEADER_SIZE);
        d.plr1StartX       = b.getShort();
        d.plr1StartZ       = b.getShort();
        d.plr1StartZoneId  = (short)(b.getShort() & 0xFFFF);
        d.plr2StartX       = b.getShort();
        d.plr2StartZ       = b.getShort();
        d.plr2StartZoneId  = (short)(b.getShort() & 0xFFFF);
        d.numControlPoints = b.getShort() & 0xFFFF;
        d.numPoints        = b.getShort() & 0xFFFF;
        d.numZones         = (b.getShort() & 0xFFFF) + 1;
        b.getShort();
        d.numObjects          = b.getShort() & 0xFFFF;
        d.pointsOffset        = b.getInt();
        d.floorLineOffset     = b.getInt();
        d.objectDataOffset    = b.getInt();
        d.shotDataOffset      = b.getInt();
        d.alienShotDataOffset = b.getInt();
        d.objectPointsOffset  = b.getInt();
        d.plr1ObjectOffset    = b.getInt();
        d.plr2ObjectOffset    = b.getInt();

        log.info("TLBT: plr1=({},{}) z={}, zones={}, pts={}, ctrl={}, objs={}",
            d.plr1StartX, d.plr1StartZ, d.plr1StartZoneId & 0xFFFF,
            d.numZones, d.numPoints, d.numControlPoints, d.numObjects);

        d.controlPoints = new Vec2W[d.numControlPoints];
        b.position(GEOM_DATA_START);
        for (int i = 0; i < d.numControlPoints; i++) {
            if (b.remaining() < 4) break;
            d.controlPoints[i] = new Vec2W(b.getShort(), b.getShort());
        }

        // Session 110 : Lvl_ExitZoneID_w = WORD a (floorLineOffset - 2).
        // L'ASM (hires.s:374) lit ce word juste avant le debut de la liste des edges.
        // C'est l'ID de la zone de sortie du niveau : entrer dans cette zone
        // declenche la fin de niveau.
        if (d.floorLineOffset >= 2 && d.floorLineOffset <= raw.length) {
            ByteBuffer eb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            eb.position(d.floorLineOffset - 2);
            d.exitZoneId = eb.getShort() & 0xFFFF;
            log.info("exitZoneId = {}", d.exitZoneId);
        }

        d.points = new Vec2W[d.numPoints];
        if (d.pointsOffset > 0 && d.pointsOffset + (long)d.numPoints * 4 <= raw.length) {
            b.position(d.pointsOffset);
            for (int i = 0; i < d.numPoints; i++)
                d.points[i] = new Vec2W(b.getShort(), b.getShort());
        } else {
            log.warn("pointsOffset invalide 0x{}", Integer.toHexString(d.pointsOffset));
        }

        // ── Session 118 : PointBrights et ZoneBorderPoints ──────────────────────
        //
        // Apres la table Points (numPoints * 4 bytes) + 4 bytes de padding, le
        // fichier contient deux tables critiques pour le rendu d'eclairage :
        //
        //   1. PointBrights : numZones * 40 WORDs = numZones * 80 bytes.
        //      Chaque WORD encode la luminosite d'un point pour une zone donnee :
        //      - byte BAS = luminosite de base (signed)
        //      - byte HAUT (si != 0) = animation lumiere (bits 0-3 anim idx, 4-7 phase)
        //      Reference ASM : hires.s:1502-1543
        //
        //   2. ZoneBorderPoints : longueur variable, jusqu'a floorLineOffset - 2.
        //      Liste de point indices par zone, separees par WORD -1.
        //      Utilisee pour la propagation lumiere via PVS.
        //      Reference ASM : newanims.s:119-131 ({@code muls #20,d4} suggere
        //      10 WORDs par zone en moyenne, mais varie).
        //
        // Layout dans le binaire (entre la fin de Points et floorLineOffset) :
        //   pointsOffset + numPoints*4         : fin Points
        //                + 4                   : padding
        //                + numZones*80         : fin PointBrights, debut ZoneBorderPoints
        //                + ?                   : fin ZoneBorderPoints
        //   floorLineOffset - 2                : exitZoneId
        //   floorLineOffset                    : debut FloorLines (edges)

        if (d.pointsOffset > 0 && d.numZones > 0) {
            int pointBrightsOffset = d.pointsOffset + d.numPoints * 4 + 4;
            int pointBrightsBytes  = d.numZones * 80;
            int zoneBorderOffset   = pointBrightsOffset + pointBrightsBytes;

            // 1) PointBrights : tableau fixe numZones x 40 WORDs
            if (pointBrightsOffset + pointBrightsBytes <= raw.length) {
                ByteBuffer pb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
                pb.position(pointBrightsOffset);
                d.pointBrights = new short[d.numZones][40];
                for (int z = 0; z < d.numZones; z++) {
                    for (int i = 0; i < 40; i++) {
                        d.pointBrights[z][i] = pb.getShort();
                    }
                }
                log.info("PointBrights : {} zones x 40 WORDs depuis 0x{}",
                    d.numZones, Integer.toHexString(pointBrightsOffset));
            } else {
                log.warn("PointBrights : offset {} + {} > taille fichier {}",
                    pointBrightsOffset, pointBrightsBytes, raw.length);
            }

            // 2) ZoneBorderPoints : longueur variable par zone, terminee par -1.
            //    On lit jusqu'a (floorLineOffset - 2) qui marque le exitZoneId.
            int zoneBorderEnd = d.floorLineOffset - 2;
            if (zoneBorderOffset > 0 && zoneBorderEnd > zoneBorderOffset
                && zoneBorderEnd <= raw.length) {
                ByteBuffer bp = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
                bp.position(zoneBorderOffset);
                d.zoneBorderPoints = new short[d.numZones][];
                int totalBytesAvail = zoneBorderEnd - zoneBorderOffset;
                int totalWords = totalBytesAvail / 2;
                int[] flat = new int[totalWords];
                for (int i = 0; i < totalWords; i++) flat[i] = bp.getShort();

                // Le moteur ASM (newanims.s:131) utilise (zoneIdx*20) comme offset
                // BYTE -> 10 WORDs par zone reservees, mais la liste reelle peut
                // etre plus courte (terminee par -1) ou potentiellement plus longue
                // dans certains niveaux. On suppose 10 WORDs/zone fixe pour le
                // decoupage initial, en tronquant a la premiere occurrence de -1.
                int wordsPerZone = totalWords / d.numZones;
                if (wordsPerZone < 10) wordsPerZone = 10; // floor a la valeur attendue

                for (int z = 0; z < d.numZones; z++) {
                    int start = z * 10;  // 10 WORDs reserves par zone (cf. ASM)
                    java.util.List<Short> ids = new java.util.ArrayList<>();
                    for (int i = 0; i < 10 && start + i < flat.length; i++) {
                        short v = (short) flat[start + i];
                        if (v < 0) break;  // terminator -1
                        ids.add(v);
                    }
                    short[] arr = new short[ids.size()];
                    for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
                    d.zoneBorderPoints[z] = arr;
                }
                log.info("ZoneBorderPoints : {} zones depuis 0x{} ({} bytes total, terminee par exitZoneId @ 0x{})",
                    d.numZones, Integer.toHexString(zoneBorderOffset),
                    totalBytesAvail, Integer.toHexString(zoneBorderEnd));
            } else {
                log.warn("ZoneBorderPoints : range invalide [0x{}..0x{}]",
                    Integer.toHexString(zoneBorderOffset),
                    Integer.toHexString(zoneBorderEnd));
            }
        }

        // ── Parse de la table ObjectPoints (positions monde réelles) ────────────────
        //
        // TLBT_ObjectPointsOffset_l (TLBT+42) pointe une table de numObjects entrées.
        // Chaque entrée = 8 bytes : { xPos:int, zPos:int } (coordonnées monde Amiga).
        //
        // Source : hires.s lignes 2266-2270 :
        //   move.l Lvl_ObjectPointsPtr_l,a1
        //   move.w (a0),d0                     ; (a0)+0 = WORD index dans ObjectPoints
        //   move.l Plr1_XOff_l,(a1,d0.w*8)    ; ObjectPoints[idx].x = long
        //   move.l Plr1_ZOff_l,4(a1,d0.w*8)   ; ObjectPoints[idx].z = long
        int[] objPointsX = new int[d.numObjects + 1];
        int[] objPointsZ = new int[d.numObjects + 1];
        if (d.objectPointsOffset > 0 && d.objectPointsOffset + (long)d.numObjects * 8 <= raw.length) {
            b.position(d.objectPointsOffset);
            for (int i = 0; i < d.numObjects; i++) {
                objPointsX[i] = b.getShort();  // HIGH word = coord X entière (format fixed-point 16.16)
                b.getShort();                   // LOW word = fraction (skip)
                objPointsZ[i] = b.getShort();  // HIGH word = coord Z entière
                b.getShort();                   // LOW word = fraction (skip)
            }
            log.info("ObjectPoints: {} entrées depuis offset 0x{} (format fixed-point 16.16)",
                d.numObjects, Integer.toHexString(d.objectPointsOffset));
        } else {
            log.warn("objectPointsOffset invalide 0x{}",
                Integer.toHexString(d.objectPointsOffset));
        }

        // ── Parse des ObjT/EntT (64 bytes chacun) ───────────────────────────────
        //
        // ObjT dans le FICHIER BINAIRE (difsère de la structure runtime) :
        //   +0..+1 : WORD  objPointIndex   INDEX dans la table ObjectPoints
        //            (hires.s l.2268 : move.w (a0),d0 / d0 = index)
        //   +2..+3 : WORD  padding (0)
        //   +4..+7 : LONG  ZPos placeholder (0 dans le fichier, initialisé runtime)
        //   +8..+11: LONG  YPos placeholder (0 dans le fichier, initialisé runtime)
        //   +12    : WORD  ObjT_ZoneID_w
        //   +14    : WORD  (padding)
        //   +16    : BYTE  ObjT_TypeID_b
        //   +17    : BYTE  ObjT_SeePlayer_b
        //
        // EntT overlay (+18) : tous les champs sont valides dans le fichier
        //   +18 : EntT_HitPoints_b
        //   +21 : EntT_TeamNumber_b
        //   +24 : EntT_DisplayText_w
        //   +30 : EntT_CurrentAngle_w  (0-8191 = 360°)
        //   +32 : EntT_TargetControlPoint_w
        //   +34 : EntT_Timer1_w (STRTANIM)
        //   +50 : EntT_DoorsAndLiftsHeld_l (low word = door bits)
        //   +52 : EntT_Timer3_w (lift bits)
        //   +54 : EntT_Type_b (defIndex)
        //   +55 : EntT_WhichAnim_b (runtime)
        //
        // Position monde réelle = ObjectPoints[objPointIndex].{x, z}
        // YPos = calculé depuis zone.floorH au moment du build de la scène
        if (d.objectDataOffset > 0 && d.numObjects > 0) {
            int OBJ_SIZE = 64;
            int maxObjs  = Math.min(d.numObjects, (raw.length - d.objectDataOffset) / OBJ_SIZE);
            d.objects    = new ObjData[maxObjs];
            for (int i = 0; i < maxObjs; i++) {
                int off = d.objectDataOffset + i * OBJ_SIZE;
                if (off + OBJ_SIZE > raw.length) break;
                b.position(off);

                // +0..+1 : WORD = index dans ObjectPoints (PAS XPos)
                int objPointIndex = b.getShort() & 0xFFFF;  // +0
                b.getShort();                               // +2 padding

                // +4..+7 : LONG contenant le marqueur polygon (byte +6 = 0xFF si polygon)
                // Structure runtime ObjT_ZPos_l, mais dans le FICHIER :
                //   byte +6 = 0xFF = polygon model (sinon 0 = sprite bitmap)
                b.getShort();                               // +4..+5 (padding/flags)
                int polyMarker = b.get() & 0xFF;            // +6 : 0xFF = polygon model
                b.get();                                    // +7 padding

                // +8..+11 : LONG contenant pour les polygon objects :
                //   WORD +8  = poly model index -> Draw_PolyObjects_vl[i] = GLFT_VectorNames_l[i]
                //   WORD +10 = animation frame courante
                // Pour les sprite objects, ces bytes sont 0 (runtime)
                int polyModelIndex = b.getShort() & 0xFFFF; // +8
                b.getShort();                               // +10 anim frame (ignore)

                // Positions réelles depuis la table ObjectPoints
                int xPos = (objPointIndex < objPointsX.length) ? objPointsX[objPointIndex] : 0;
                int zPos = (objPointIndex < objPointsZ.length) ? objPointsZ[objPointIndex] : 0;

                int zoneId     = b.getShort() & 0xFFFF; // +12
                b.getShort();                           // +14 padding
                int typeId     = b.get() & 0xFF;        // +16
                b.get();                                // +17 SeePlayer

                // EntT overlay
                int hitPoints  = b.get() & 0xFF;        // +18
                b.get();                                // +19 DamageTaken
                b.get();                                // +20 CurrentMode
                int teamNumber = b.get() & 0xFF;        // +21
                b.getShort();                           // +22 CurrentSpeed
                int displayText = b.getShort() & 0xFFFF; // +24
                b.getShort();                           // +26 ZoneID dup
                b.getShort();                           // +28 CurrentCP
                int angle      = b.getShort() & 0xFFFF; // +30
                int targetCP   = b.getShort() & 0xFFFF; // +32
                int startAnim  = b.getShort() & 0xFFFF; // +34 STRTANIM
                b.position(off + 50);
                // EntT_DoorsAndLiftsHeld_l (LONG +50..+53) :
                //   High word (+50..+51) = lock bits hauts (padding/unknown)
                //   Low word  (+52..+53) = EntT_Timer3_w = door/lift held bits
                int doorsLifts = b.getInt();                   // +50..+53
                int doorLocks  = doorsLifts & 0xFFFF;          // low word = bytes 52-53
                int liftLocks  = (doorsLifts >> 16) & 0xFFFF; // high word = bytes 50-51
                // EntT_Type_b = defIndex, byte +54 (objet def 0-29 ou alien def 0-19)
                // EntT_WhichAnim_b = byte +55 (animation courante, runtime)
                // NOTE : le getInt() precedent a avance le buffer a off+54 exactement.
                int defIndex   = b.get() & 0xFF;               // +54 = EntT_Type_b
                b.get();                                       // +55 = EntT_WhichAnim_b (skip)

                d.objects[i] = new ObjData(
                    xPos, zPos, 0, zoneId, objPointIndex,
                    typeId, defIndex, startAnim, angle,
                    hitPoints, teamNumber, displayText,
                    doorLocks, liftLocks, targetCP,
                    polyMarker == 0xFF, polyModelIndex);
            }
            log.info("{} objets parsés depuis 0x{} (ObjectPoints @ 0x{})",
                maxObjs, Integer.toHexString(d.objectDataOffset),
                Integer.toHexString(d.objectPointsOffset));
        } else {
            d.objects = new ObjData[0];
        }

        return d;
    }

    // ── Constantes ObjT_TypeID_b (defs.i lignes 211-216) ─────────────────────
    public static final int OBJ_TYPE_ALIEN      = 0; // alien, EntT_Type_b = alien def 0-19
    public static final int OBJ_TYPE_OBJECT     = 1; // objet, EntT_Type_b = obj def 0-29
    public static final int OBJ_TYPE_PROJECTILE = 2; // projectile (runtime)
    public static final int OBJ_TYPE_AUX        = 3; // sprite auxiliaire (runtime)
    public static final int OBJ_TYPE_PLAYER1    = 4; // spawn joueur 1
    public static final int OBJ_TYPE_PLAYER2    = 5; // spawn joueur 2

    // ── Constantes ENT_TYPE (defs.i, pour TypeID=1 : ODefT_Behaviour_w) ──────
    public static final int ENT_TYPE_COLLECTABLE  = 0; // ramassable (health, ammo, armes, clés)
    public static final int ENT_TYPE_ACTIVATABLE  = 1; // activable (switch, levier)
    public static final int ENT_TYPE_DESTRUCTABLE = 2; // destructible (baril, console)
    public static final int ENT_TYPE_DECORATION   = 3; // décoration sans interaction

    // ── Constantes ODefT_GFXType_w (newaliencontrol.s) ───────────────────────
    public static final int OBJ_GFX_BITMAP = 0; // sprite WAD (alien2.wad, pickups.wad...)
    public static final int OBJ_GFX_VECTOR = 1; // modèle vectoriel (vectobj/blaster...)
    public static final int OBJ_GFX_GLARE  = 2; // effet glare/smoke additif

    /**
     * Données d'un objet de niveau (ObjT + EntT overlay, 64 bytes).
     *
     * IMPORTANT (hires.s lignes 2268-2270) :
     *   Dans le fichier, les bytes +0..+11 NE sont PAS XPos/ZPos/YPos.
     *   - Bytes +0..+1 : WORD = objPointIndex (index dans ObjectPoints table)
     *   - Byte  +6     : 0xFF = polygon model, 0 = sprite bitmap
     *   - WORD  +8     : poly model index -> GLFT_VectorNames_l[i] (si polygon)
     *   - Les positions réelles sont dans ObjectPoints[objPointIndex].{xPos, zPos}
     *   - YPos = calculé depuis zone.floorH au moment du build de scène
     *
     * @param isPolygon      true si byte +6 == 0xFF (polygon model)
     * @param polyModelIndex WORD +8 : index dans GLFT_VectorNames_l (si polygon)
     */
    public record ObjData(
        int xPos, int zPos, int yPos, int zoneId,
        int objPointIndex,
        int typeId,
        int defIndex,
        int startAnim,
        int angle,
        int hitPoints,
        int teamNumber,
        int displayText,
        int doorLockFlags,
        int liftLockFlags,
        int targetCP,
        boolean isPolygon,
        int polyModelIndex
    ) {
        public float angleDeg() { return (angle * 360f) / 8192f; }
        public boolean isAlien()  { return typeId == OBJ_TYPE_ALIEN; }
        public boolean isObject() { return typeId == OBJ_TYPE_OBJECT; }
        public boolean isPlayer() { return typeId == OBJ_TYPE_PLAYER1 || typeId == OBJ_TYPE_PLAYER2; }

        public String typeName() {
            return switch (typeId) {
                case OBJ_TYPE_ALIEN      -> "ALIEN";
                case OBJ_TYPE_OBJECT     -> "OBJECT";
                case OBJ_TYPE_PROJECTILE -> "PROJECTILE";
                case OBJ_TYPE_AUX        -> "AUX";
                case OBJ_TYPE_PLAYER1    -> "PLAYER1";
                case OBJ_TYPE_PLAYER2    -> "PLAYER2";
                default                  -> "UNKNOWN(" + typeId + ")";
            };
        }

        @Override public String toString() {
            return String.format("%s def=%d zone=%d pos=(%d,%d) angle=%.0f°%s%s",
                typeName(), defIndex, zoneId, xPos, zPos, angleDeg(),
                doorLockFlags != 0 ? " doors=" + doorLockFlags : "",
                liftLockFlags != 0 ? " lifts=" + liftLockFlags : "");
        }
    }

    public static ObjData makeObjDataCompat(int xPos, int zPos, int yPos, int zoneId,
                                             int typeId, int entType, int whichAnim) {
        return new ObjData(xPos, zPos, yPos, zoneId, 0, typeId, entType, whichAnim,
                           0, 0, 0, -1, 0, 0, 0, false, 0);
    }

    public void parseEdges(BinData d, ZoneData[] zones, int[] zonePtrs) {
        if (d.floorLineOffset <= 0 || d.floorLineOffset >= d.raw.length) {
            d.edges = new ZEdge[0]; return;
        }
        int maxId = -1;
        for (ZoneData z : zones) {
            if (z == null) continue;
            for (short eid : z.edgeIds) if (eid > maxId) maxId = eid;
        }
        int numEdgesFromIds = maxId >= 0 ? maxId + 1 : 0;

        int minBlob = Integer.MAX_VALUE;
        for (int i = 0; i < zones.length; i++) {
            ZoneData z = zones[i];
            if (z == null || i >= zonePtrs.length) continue;
            int ptr = zonePtrs[i];
            if (ptr <= 0 || ptr >= d.raw.length) continue;
            if (z.edgeListOffset < 0) {
                int s = ptr + z.edgeListOffset;
                if (s > 0 && s < minBlob) minBlob = s;
            }
        }
        int numEdgesFromBlob = (minBlob != Integer.MAX_VALUE && minBlob > d.floorLineOffset)
            ? (minBlob - d.floorLineOffset) / ZEdge.BINARY_SIZE : 0;
        int numEdges = Math.max(numEdgesFromIds, numEdgesFromBlob);

        if (numEdges <= 0) { d.edges = new ZEdge[0]; return; }
        int avail = (d.raw.length - d.floorLineOffset) / ZEdge.BINARY_SIZE;
        if (numEdges > avail) numEdges = avail;

        ByteBuffer buf = ByteBuffer.wrap(d.raw).order(ByteOrder.BIG_ENDIAN);
        buf.position(d.floorLineOffset);
        d.edges = new ZEdge[numEdges];
        for (int i = 0; i < numEdges; i++) d.edges[i] = parseEdge(buf);
        log.info("{} edges parsés depuis 0x{}", numEdges, Integer.toHexString(d.floorLineOffset));
    }

    public ZoneData parseZoneAt(byte[] raw, int off) {
        if (off < 0 || off + ZoneData.FIXED_SIZE > raw.length)
            throw new IllegalArgumentException("Zone offset invalide : " + off);

        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        b.position(off);

        short zoneId          = b.getShort();
        int   floor           = b.getInt();
        int   roof            = b.getInt();
        int   upperFloor      = b.getInt();
        int   upperRoof       = b.getInt();
        int   water           = b.getInt();
        short brightness      = b.getShort();
        short upperBrightness = b.getShort();
        short controlPoint    = b.getShort();
        short backSFXMask     = b.getShort();
        short unused          = b.getShort();
        short edgeListOffset  = b.getShort();
        short pointsOffset    = b.getShort();
        byte  drawBackdrop    = b.get();
        byte  echo            = b.get();
        short telZone         = b.getShort();
        short telX            = b.getShort();
        short telZ            = b.getShort();
        short floorNoise      = b.getShort();
        short upperFloorNoise = b.getShort();

        short[] edgeIds  = readIds(raw, off, edgeListOffset);
        short[] pointIds = readIds(raw, off, pointsOffset);

        List<ZPVSRecord> pvs = new ArrayList<>();
        while (b.position() + 2 <= raw.length) {
            short pid = b.getShort();
            if (pid < 0) break;
            if (b.remaining() < 6) break;
            pvs.add(new ZPVSRecord(pid, b.getShort(), b.getShort(), b.getShort()));
        }

        return new ZoneData(zoneId, floor, roof, upperFloor, upperRoof, water,
            brightness, upperBrightness, controlPoint, backSFXMask, unused,
            edgeListOffset, pointsOffset, drawBackdrop, echo,
            telZone, telX, telZ, floorNoise, upperFloorNoise,
            edgeIds, pointIds, pvs.toArray(new ZPVSRecord[0]));
    }

    private short[] readIds(byte[] raw, int baseOffset, short negOffset) {
        if (negOffset >= 0) return new short[0];
        int start = baseOffset + negOffset;
        if (start < 0) return new short[0];
        List<Short> ids = new ArrayList<>();
        ByteBuffer tmp = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        tmp.position(start);
        while (tmp.position() < baseOffset && tmp.remaining() >= 2) {
            short v = tmp.getShort();
            if (v < 0) break;
            ids.add(v);
        }
        short[] a = new short[ids.size()];
        for (int i = 0; i < a.length; i++) a[i] = ids.get(i);
        return a;
    }

    private ZEdge parseEdge(ByteBuffer b) {
        short px = b.getShort(), pz = b.getShort();
        short lx = b.getShort(), lz = b.getShort();
        short join = b.getShort(), w5 = b.getShort();
        byte b12 = b.get(), b13 = b.get();
        return new ZEdge(new Vec2W(px, pz), new Vec2W(lx, lz), join, w5, b12, b13, b.getShort());
    }

    public static String[] extractTextMessages(byte[] raw) {
        String[] msgs = new String[MSG_MAX_CUSTOM];
        for (int i = 0; i < MSG_MAX_CUSTOM; i++) {
            int s = i * MSG_MAX_LENGTH;
            if (s + MSG_MAX_LENGTH > raw.length) { msgs[i] = ""; continue; }
            int len = 0;
            while (len < MSG_MAX_LENGTH && raw[s + len] != 0) len++;
            msgs[i] = new String(raw, s, len, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        }
        return msgs;
    }
}
