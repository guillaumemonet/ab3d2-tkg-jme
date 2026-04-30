package com.ab3d2.core.level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;

/**
 * Parse twolev.graph.bin (TLGT) et assemble le LevelData complet.
 */
public class GraphicsBinaryParser {

    private static final Logger log = LoggerFactory.getLogger(GraphicsBinaryParser.class);

    public static final int TLGT_HEADER_SIZE    = 20;
    public static final int TLGT_ZONE_TABLE_OFS = 16;

    public static class GraphHeader {
        public int doorDataOffset, liftDataOffset, switchDataOffset, zoneGraphAddsOffset;
    }

    public LevelData load(Path binPath, Path graphPath, String levelId) throws IOException {
        return assemble(Files.readAllBytes(binPath), Files.readAllBytes(graphPath), levelId);
    }

    public LevelData assemble(byte[] binRaw, byte[] graphRaw, String levelId) {
        LevelBinaryParser binParser = new LevelBinaryParser();
        LevelBinaryParser.BinData bd = binParser.parseBin(binRaw);

        if (graphRaw.length < TLGT_HEADER_SIZE)
            throw new IllegalArgumentException("graph.bin trop petit : " + graphRaw.length);

        ByteBuffer gBuf = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
        GraphHeader gh = new GraphHeader();
        gh.doorDataOffset      = gBuf.getInt();
        gh.liftDataOffset      = gBuf.getInt();
        gh.switchDataOffset    = gBuf.getInt();
        gh.zoneGraphAddsOffset = gBuf.getInt();

        int numZones = bd.numZones;
        if (TLGT_ZONE_TABLE_OFS + numZones * 4 > graphRaw.length)
            numZones = (graphRaw.length - TLGT_ZONE_TABLE_OFS) / 4;

        gBuf.position(TLGT_ZONE_TABLE_OFS);
        int[] zonePtrs = new int[numZones];
        for (int i = 0; i < numZones; i++) zonePtrs[i] = gBuf.getInt();

        ZoneData[] zones = new ZoneData[numZones];
        int validZones = 0;
        for (int i = 0; i < numZones; i++) {
            int ptr = zonePtrs[i];
            if (ptr <= 0 || ptr + ZoneData.FIXED_SIZE > binRaw.length) continue;
            try { zones[i] = binParser.parseZoneAt(binRaw, ptr); validZones++; }
            catch (Exception e) { log.warn("Zone[{}] erreur: {}", i, e.getMessage()); }
        }
        log.info("{}/{} zones valides", validZones, numZones);

        binParser.parseEdges(bd, zones, zonePtrs);

        return new LevelData(levelId,
            bd.plr1StartX, bd.plr1StartZ, bd.plr1StartZoneId,
            bd.plr2StartX, bd.plr2StartZ, bd.plr2StartZoneId,
            bd.controlPoints, bd.points, bd.edges, zones,
            bd.exitZoneId,
            bd.numObjects, binRaw.length, graphRaw.length);
    }

    public static String summarize(LevelData lvl) {
        if (lvl == null) return "null";
        return String.format(
            "Level %s : zones=%d/%d, edges=%d, pts=%d, plr1=(%d,%d) z%d",
            lvl.levelId, lvl.numValidZones(), lvl.numZones(),
            lvl.numEdges(), lvl.numPoints(),
            (int) lvl.plr1StartX, (int) lvl.plr1StartZ, lvl.plr1StartZoneId);
    }
}
