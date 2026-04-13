package com.ab3d2.core.level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Parse la section ZoneGraphAdds de twolev.graph.bin.
 *
 * <h2>Format d'un enregistrement mur (TYPE_WALL=0) — 30 bytes</h2>
 * Depuis _SAVE_LEVEL de leved303.amos :
 * <pre>
 *  BYTE  wallId       PK[B]               — index du côté B dans la zone
 *  BYTE  typeByte     PK[0]               — 0=mur, 1=sol, 2=plafond, 4=objet
 *  WORD  leftPt       DK[ZO(A,B)]         — index point gauche
 *  WORD  rightPt      DK[ZO(A,B+1)]       — index point droit
 *  BYTE  whichLeft    PK[B]               — index côté gauche
 *  BYTE  whichRight   PK[NB]              — index côté droit
 *  WORD  wallLen      DK[L]               — longueur segment / 2
 *  LONG  texAndVO     LK[W+VO]            — HIGH WORD = ZWG(A,B,0) = index texture mur
 *                                           LOW  BYTE = VO = (-ZH(A,0)) & 0xFF = yOffset texture
 *  WORD  clipIdx      DK[ZWG(A,B,2)]      — index de clip texture
 *  BYTE  hMask        PK[CH-1]            — hauteur texture - 1 (ex: 127 pour 128px)
 *  BYTE  hShift       PK[CSV]             — log2(hauteur texture)
 *  BYTE  wMask        PK[GW-1]            — largeur texture - 1
 *  BYTE  whichPbr     PK[TBV*16+BBV]      — flags tiling haut/bas
 *  LONG  topWall      LK[H1*256]          — hauteur haut du mur × 256 (signée)
 *  LONG  botWall      LK[H2*256]          — hauteur bas du mur × 256 (signée)
 *  BYTE  brightOfs    PK[WB(A,B)]         — offset luminosité du mur
 *  BYTE  otherZone    PK[ZZ(A,B)]         — zone adjacente (0 = mur plein, >0 = portail/porte)
 *  TOTAL : 30 bytes
 * </pre>
 *
 * <h2>texAndVO décodage</h2>
 * Le LONG {@code LK[W+VO]} est stocké big-endian :
 * <ul>
 *   <li>Bits 16-31 (HIGH SHORT) = {@code ZWG(A,B,0)} = vrai index texture mur</li>
 *   <li>Bits 0-7   (LOW  BYTE)  = {@code VO = (-floor) & 0xFF} = décalage Y texture</li>
 * </ul>
 * → Lu en 2 SHORTs : {@code texIndex = buf.getShort()}, {@code yOffset = buf.getShort() & 0xFF}
 *
 * <h2>Hauteurs</h2>
 * Convention AMOS inversée : valeur plus petite = position plus haute.
 * {@code topWall >> 8} donne la hauteur éditeur du haut du mur (typiquement négatif = au-dessus de 0).
 * Pour JME Y-up : {@code Y_jme = -(hauteur_amos) / SCALE}
 */
public class ZoneGraphParser {

    private static final Logger log = LoggerFactory.getLogger(ZoneGraphParser.class);

    private static final int MAX_RECORDS_PER_ZONE = 512;
    private static final int MAX_FLOOR_SIDES      = 32;

    public static final int TYPE_WALL     = 0;
    public static final int TYPE_FLOOR    = 1;
    public static final int TYPE_CEILING  = 2;
    public static final int TYPE_OBJECT   = 4;
    public static final int TYPE_WATER    = 7;
    public static final int TYPE_BACKDROP = 12;
    public static final int TYPE_END_MIN  = 128;

    public WallRenderEntry[][] parse(byte[] graphRaw, int numZones, int zoneGraphAddsOffset) {
        WallRenderEntry[][] result = new WallRenderEntry[numZones][];
        for (int i = 0; i < numZones; i++) result[i] = new WallRenderEntry[0];

        if (zoneGraphAddsOffset <= 0 || zoneGraphAddsOffset + numZones * 8 > graphRaw.length)
            return result;

        ByteBuffer buf = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
        buf.position(zoneGraphAddsOffset);
        int[] lowerOfs = new int[numZones];
        for (int i = 0; i < numZones; i++) { lowerOfs[i] = buf.getInt(); buf.getInt(); }

        int wallZones = 0, wallEntries = 0;
        for (int zi = 0; zi < numZones; zi++) {
            int ofs = lowerOfs[zi];
            if (ofs <= 0 || ofs + 2 > graphRaw.length) continue;

            List<WallRenderEntry> entries = new ArrayList<>();
            buf.position(ofs);
            buf.getShort(); // zone_id (skip)

            for (int k = 0; k < MAX_RECORDS_PER_ZONE; k++) {
                if (buf.remaining() < 2) break;
                int wallId   = buf.get() & 0xFF;
                int typeByte = buf.get() & 0xFF;
                if (typeByte >= TYPE_END_MIN) break;

                if (typeByte == TYPE_WALL) {
                    if (buf.remaining() < 28) break;

                    int leftPt   = buf.getShort() & 0xFFFF;  // ZO(A,B)    — point gauche
                    int rightPt  = buf.getShort() & 0xFFFF;  // ZO(A,B+1)  — point droit
                    int whichL   = buf.get() & 0xFF;          // B          — index côté gauche
                    int whichR   = buf.get() & 0xFF;          // NB         — index côté droit
                    int wallLen  = buf.getShort() & 0xFFFF;   // L          — longueur segment / 2
                    // LK[W+VO] lu en 2 SHORTs big-endian :
                    int texIndex = buf.getShort() & 0xFFFF;   // HIGH SHORT = ZWG(A,B,0) = index texture mur
                    int yOffset  = buf.getShort() & 0xFF;     // LOW  SHORT, seul octet bas = VO décalage Y
                    int clipIdx  = buf.getShort() & 0xFFFF;   // DK[ZWG(A,B,2)] = index clip
                    int hMask    = buf.get() & 0xFF;          // CH-1       — hauteur texture - 1
                    int hShift   = buf.get() & 0xFF;          // CSV        — log2(hauteur)
                    int wMask    = buf.get() & 0xFF;          // GW-1       — largeur texture - 1
                    int whichPbr = buf.get() & 0xFF;          // TBV*16+BBV — flags tiling
                    int topWall  = buf.getInt();               // H1*256     — hauteur haut (signée)
                    int botWall  = buf.getInt();               // H2*256     — hauteur bas (signée)
                    int brightO  = buf.get();                  // WB(A,B)    — offset luminosité
                    int otherZ   = buf.get() & 0xFF;          // ZZ(A,B)    — zone adjacente

                    entries.add(new WallRenderEntry(typeByte, wallId,
                        leftPt, rightPt, whichL, whichR, wallLen, texIndex, yOffset,
                        clipIdx, hMask, hShift, wMask, whichPbr, topWall, botWall, brightO, otherZ));
                    wallEntries++;
                    continue;
                }

                if (typeByte == TYPE_FLOOR || typeByte == TYPE_CEILING) {
                    if (buf.remaining() < 4) break;
                    buf.getShort(); // floorY (skip)
                    int sidesMinus1 = buf.getShort() & 0xFFFF;
                    int N = sidesMinus1 + 1;
                    if (N < 1 || N > MAX_FLOOR_SIDES || buf.remaining() < N * 2 + 8) break;
                    buf.position(buf.position() + N * 2);
                    buf.getShort(); // skip
                    buf.getShort(); // scaleval
                    int whichTile = buf.getShort() & 0xFFFF;
                    buf.getShort(); // lighttype
                    entries.add(typeByte == TYPE_FLOOR
                        ? WallRenderEntry.makeFloor(whichTile)
                        : WallRenderEntry.makeCeil(whichTile));
                    continue;
                }
                break; // type inconnu → fin de zone
            }

            if (!entries.isEmpty()) { result[zi] = entries.toArray(new WallRenderEntry[0]); wallZones++; }
        }
        log.info("ZoneGraphAdds : {} zones, {} murs", wallZones, wallEntries);
        return result;
    }

    public static int[] extractFloorWhichTiles(WallRenderEntry[][] ze) {
        int[] r = new int[ze.length]; Arrays.fill(r, -1);
        for (int i = 0; i < ze.length; i++)
            for (WallRenderEntry e : ze[i]) if (e.isFloorRecord()) { r[i] = e.floorWhichTile; break; }
        return r;
    }

    public static int[] extractCeilWhichTiles(WallRenderEntry[][] ze) {
        int[] r = new int[ze.length]; Arrays.fill(r, -1);
        for (int i = 0; i < ze.length; i++)
            for (WallRenderEntry e : ze[i]) if (e.isCeilRecord()) { r[i] = e.floorWhichTile; break; }
        return r;
    }

    public static Set<Integer> collectTexIndices(WallRenderEntry[][] ze) {
        Set<Integer> s = new TreeSet<>();
        for (WallRenderEntry[] arr : ze)
            for (WallRenderEntry e : arr)
                if (e.isWall() && e.texIndex >= 0 && e.texIndex < 16) s.add(e.texIndex);
        return s;
    }
}
