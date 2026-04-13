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
        public Vec2W[]  controlPoints;
        public ZEdge[]  edges = new ZEdge[0];
        public Vec2W[]  points;
        public String[] messages;
        public ObjData[] objects = new ObjData[0];
        public byte[]   raw;
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

        d.points = new Vec2W[d.numPoints];
        if (d.pointsOffset > 0 && d.pointsOffset + (long)d.numPoints * 4 <= raw.length) {
            b.position(d.pointsOffset);
            for (int i = 0; i < d.numPoints; i++)
                d.points[i] = new Vec2W(b.getShort(), b.getShort());
        } else {
            log.warn("pointsOffset invalide 0x{}", Integer.toHexString(d.pointsOffset));
        }

        // ── Parse des objets (ObjT/EntT, 64 bytes chacun) ──────────────────
        //
        // ObjT header (defs.i, STRUCTURE ObjT,0) :
        //   +0  : long  ObjT_XPos_l        position X (unités Amiga)
        //   +4  : long  ObjT_ZPos_l        position Z
        //   +8  : long  ObjT_YPos_l        position Y (hauteur)
        //   +12 : word  ObjT_ZoneID_w      zone d'appartenance (-1 = supprimé)
        //   +14 : word  (padding)
        //   +16 : byte  ObjT_TypeID_b      type principal :
        //                 OBJ_TYPE_ALIEN=0, OBJ_TYPE_OBJECT=1, OBJ_TYPE_PROJECTILE=2,
        //                 OBJ_TYPE_AUX=3,   OBJ_TYPE_PLAYER1=4, OBJ_TYPE_PLAYER2=5
        //   +17 : byte  ObjT_SeePlayer_b   line-of-sight (runtime)
        //
        // EntT overlay (STRUCTURE EntT, ObjT_Header_SizeOf_l=18) :
        //   +18 : byte  EntT_HitPoints_b   points de vie initiaux
        //   +19 : byte  EntT_DamageTaken_b  (runtime)
        //   +20 : byte  EntT_CurrentMode_b  (runtime)
        //   +21 : byte  EntT_TeamNumber_b   équipe aliens (0=aucune)
        //   +24 : word  EntT_DisplayText_w  index texte niveau (-1=aucun)
        //   +28 : word  EntT_CurrentControlPoint_w  waypoint courant
        //   +30 : word  EntT_CurrentAngle_w  angle initial (0-8191 = 360°)
        //   +32 : word  EntT_TargetControlPoint_w   waypoint cible (aliens)
        //   +34 : word  EntT_Timer1_w       = STRTANIM pour objets (frame de départ)
        //   +36 : long  EntT_EnemyFlags_l   flags runtime
        //   +50 : long  EntT_DoorsAndLiftsHeld_l  portes/lifts bloquées
        //   +52 : word  EntT_Timer3_w       = LLOCKED (lift lock bits)
        //   +54 : byte  EntT_Type_b         INDEX de définition :
        //                 TypeID=0 (alien)  -> alien def  0-19 dans GLFT_AlienDefs
        //                 TypeID=1 (object) -> object def 0-29 dans GLFT_ObjectDefs
        //   +55 : byte  EntT_WhichAnim_b    frame courante (runtime, typiquement 0)
        //
        // Source : defs.i STRUCTURE EntT + leved303.amos ALIENSAVE/THINGSAVE
        if (d.objectDataOffset > 0 && d.numObjects > 0) {
            int OBJ_SIZE = 64;
            int maxObjs  = Math.min(d.numObjects, (raw.length - d.objectDataOffset) / OBJ_SIZE);
            d.objects    = new ObjData[maxObjs];
            for (int i = 0; i < maxObjs; i++) {
                int off = d.objectDataOffset + i * OBJ_SIZE;
                if (off + OBJ_SIZE > raw.length) break;
                b.position(off);
                int xPos       = b.getInt();             // +0
                int zPos       = b.getInt();             // +4
                int yPos       = b.getInt();             // +8
                int zoneId     = b.getShort() & 0xFFFF; // +12
                b.getShort();                           // +14 padding
                int typeId     = b.get() & 0xFF;        // +16
                b.get();                                // +17 SeePlayer
                int hitPoints  = b.get() & 0xFF;        // +18
                b.get();                                // +19 DamageTaken
                b.get();                                // +20 CurrentMode
                int teamNumber = b.get() & 0xFF;        // +21
                b.getShort();                           // +22 CurrentSpeed
                int displayText = b.getShort() & 0xFFFF; // +24
                b.getShort();                           // +26 ZoneID dup
                b.getShort();                           // +28 CurrentCP
                int angle      = b.getShort() & 0xFFFF; // +30 angle 0-8191
                int targetCP   = b.getShort() & 0xFFFF; // +32
                int startAnim  = b.getShort() & 0xFFFF; // +34 STRTANIM
                b.position(off + 50);
                int doorLocks  = b.getInt() & 0xFFFF;   // +50 low word = door bits
                int liftLocks  = b.getShort() & 0xFFFF; // +52 lift bits
                int defIndex   = b.get() & 0xFF;        // +54 EntT_Type_b
                b.get();                                // +55 WhichAnim (runtime)
                d.objects[i] = new ObjData(
                    xPos, zPos, yPos, zoneId, typeId,
                    defIndex, startAnim, angle,
                    hitPoints, teamNumber, displayText,
                    doorLocks, liftLocks, targetCP);
            }
            log.info("{} objets parsés depuis offset 0x{}", maxObjs,
                Integer.toHexString(d.objectDataOffset));
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
     * Données complètes d'un objet de niveau (ObjT + EntT overlay, 64 bytes).
     *
     * Sources : defs.i STRUCTURE ObjT/EntT, leved303.amos ALIENSAVE/THINGSAVE.
     *
     * @param typeId        ObjT_TypeID_b : ALIEN=0, OBJECT=1, PROJECTILE=2, AUX=3, PLR1=4, PLR2=5
     * @param defIndex      EntT_Type_b : alien def 0-19 (GLFT_AlienDefs) OU objet def 0-29 (GLFT_ObjectDefs)
     * @param startAnim     EntT_Timer1_w : frame d'animation de départ (objets, depuis STRTANIM)
     * @param angle         EntT_CurrentAngle_w : angle initial AB3D (0-8191 = 360°)
     * @param hitPoints     EntT_HitPoints_b : points de vie initiaux
     * @param teamNumber    EntT_TeamNumber_b : équipe pour aliens (0=aucune)
     * @param displayText   EntT_DisplayText_w : index texte niveau (-1=aucun)
     * @param doorLockFlags EntT_DoorsAndLiftsHeld low word : bits portes bloquées par cet ent.
     * @param liftLockFlags EntT_Timer3_w : bits lifts bloquées par cet ent.
     * @param targetCP      EntT_TargetControlPoint_w : waypoint cible (aliens)
     */
    public record ObjData(
        int xPos, int zPos, int yPos, int zoneId,
        int typeId,
        int defIndex,
        int startAnim,
        int angle,
        int hitPoints,
        int teamNumber,
        int displayText,
        int doorLockFlags,
        int liftLockFlags,
        int targetCP
    ) {
        /** Angle en degrés flottants (0-8191 → 0.0-360.0). */
        public float angleDeg() { return (angle * 360f) / 8192f; }
        /** true si c'est un alien (TypeID=0). */
        public boolean isAlien()  { return typeId == OBJ_TYPE_ALIEN; }
        /** true si c'est un objet collectible/activatable/etc. (TypeID=1). */
        public boolean isObject() { return typeId == OBJ_TYPE_OBJECT; }
        /** true si c'est un spawn joueur (TypeID=4 ou 5). */
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
            return String.format("%s def=%d zone=%d angle=%.0f°%s%s",
                typeName(), defIndex, zoneId, angleDeg(),
                doorLockFlags != 0 ? " doors=" + doorLockFlags : "",
                liftLockFlags != 0 ? " lifts=" + liftLockFlags : "");
        }
    }

    /**
     * Compatibilité avec l'ancien code qui utilisait ObjData(xPos,zPos,yPos,zoneId,typeId,entType,whichAnim).
     * entType = defIndex, whichAnim = startAnim.
     */
    public static ObjData makeObjDataCompat(int xPos, int zPos, int yPos, int zoneId,
                                             int typeId, int entType, int whichAnim) {
        return new ObjData(xPos, zPos, yPos, zoneId, typeId, entType, whichAnim,
                           0, 0, 0, -1, 0, 0, 0);
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
