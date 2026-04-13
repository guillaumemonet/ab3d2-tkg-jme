package com.ab3d2.tools;

import com.ab3d2.core.level.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/**
 * Convertit les niveaux binaires AB3D2 en JSON intermédiaire.
 *
 * <h2>Champs JSON des murs</h2>
 * <pre>
 *  texIndex   : ZWG(A,B,0) = vrai index texture mur (0..N-1) ← utiliser pour choisir la texture
 *  clipIdx    : ZWG(A,B,2) = index de clip texture (propriété secondaire)
 *  yOffset    : VO = (-floor) & 0xFF = décalage Y de la texture (0-255)
 *  wallLen    : longueur du segment / 2 en unités monde
 *  topWallH   : hauteur haut du mur en unités éditeur (inversé : plus petit = plus haut)
 *  botWallH   : hauteur bas du mur
 *  otherZone  : 0 = mur plein, >0 = portail/porte vers cette zone
 *  brightOfs  : offset luminosité du mur
 * </pre>
 *
 * <h2>Convention hauteurs AMOS (inversée)</h2>
 * Valeur éditeur plus petite = position plus haute dans le monde.
 * Pour JME Y-up : {@code Y_jme = -(hauteur_editeur) / SCALE}
 * Exemples LEVEL_A :
 *   topWallH=-128 → Y_jme = 128/32 = 4.0f  (haut du mur)
 *   botWallH=-8   → Y_jme =   8/32 = 0.25f (bas du mur)
 *   topWallH=208  → Y_jme = -208/32 = -6.5f (zone souterraine)
 */
public class LevelJsonExporter {

    public static void main(String[] args) throws Exception {
        String srcRes  = args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-java/src/main/resources";
        String outBase = args.length > 1 ? args[1]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/levels";

        Path root = Path.of(srcRes), outDir = Path.of(outBase);
        Files.createDirectories(outDir);
        System.out.println("=== LevelJsonExporter ===");
        System.out.println("Source : " + root + "\nSortie : " + outDir + "\n");

        int ok = 0, skip = 0;
        for (char c = 'A'; c <= 'P'; c++) {
            Path bin   = root.resolve("levels/LEVEL_" + c + "/twolev.bin");
            Path graph = root.resolve("levels/LEVEL_" + c + "/twolev.graph.bin");
            if (!Files.exists(bin) || !Files.exists(graph)) { skip++; continue; }
            try {
                String json = exportLevel(String.valueOf(c),
                    Files.readAllBytes(bin), Files.readAllBytes(graph));
                Path out = outDir.resolve("level_" + c + ".json");
                Files.writeString(out, json);
                System.out.printf("  OK  LEVEL_%c → %d bytes%n", c, json.length());
                ok++;
            } catch (Exception e) {
                System.out.printf("  ERR LEVEL_%c : %s%n", c, e.getMessage());
                e.printStackTrace();
                skip++;
            }
        }
        System.out.printf("%nOK: %d  Sauté: %d%n", ok, skip);
    }

    public static String exportLevel(String levelId, byte[] binRaw, byte[] graphRaw) {
        LevelBinaryParser bp = new LevelBinaryParser();
        LevelBinaryParser.BinData bd = bp.parseBin(binRaw);

        ByteBuffer gb = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
        // TLGT header (4 ints) :
        int doorDataOffset   = gb.getInt();  // +0 : offset vers donnees portes dans graph.bin
        int liftDataOffset   = gb.getInt();  // +4 : offset vers donnees lifts
        gb.getInt();                         // +8 : switch data offset (non utilise)
        int zgaOfs           = gb.getInt();  // +12: zoneGraphAdds offset (= anciien zgaOfs)

        int numZones = bd.numZones;
        gb.position(GraphicsBinaryParser.TLGT_ZONE_TABLE_OFS);
        int[] ptrs = new int[numZones];
        for (int i = 0; i < numZones; i++) ptrs[i] = gb.getInt();

        ZoneData[] zones = new ZoneData[numZones];
        for (int i = 0; i < numZones; i++) {
            int p = ptrs[i];
            if (p <= 0 || p + ZoneData.FIXED_SIZE > binRaw.length) continue;
            try { zones[i] = bp.parseZoneAt(binRaw, p); } catch (Exception ignored) {}
        }
        bp.parseEdges(bd, zones, ptrs);

        WallRenderEntry[][] entries = new ZoneGraphParser().parse(graphRaw, numZones, zgaOfs);
        int[] floorTiles = ZoneGraphParser.extractFloorWhichTiles(entries);
        int[] ceilTiles  = ZoneGraphParser.extractCeilWhichTiles(entries);

        StringBuilder sb = new StringBuilder(1024 * 64);
        sb.append("{\n");
        sb.append("  \"levelId\": \"").append(levelId).append("\",\n");
        sb.append("  \"player1\": {\"worldX\": ").append(bd.plr1StartX)
          .append(", \"worldZ\": ").append(bd.plr1StartZ)
          .append(", \"zoneId\": ").append(bd.plr1StartZoneId & 0xFFFF).append("},\n");
        sb.append("  \"player2\": {\"worldX\": ").append(bd.plr2StartX)
          .append(", \"worldZ\": ").append(bd.plr2StartZ)
          .append(", \"zoneId\": ").append(bd.plr2StartZoneId & 0xFFFF).append("},\n");

        // Points
        sb.append("  \"points\": [\n");
        if (bd.points != null) {
            for (int i = 0; i < bd.points.length; i++) {
                Vec2W p = bd.points[i]; if (p == null) continue;
                sb.append("    {\"id\":").append(i)
                  .append(",\"x\":").append(p.xi())
                  .append(",\"z\":").append(p.zi()).append("}");
                if (i < bd.points.length - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // Edges
        sb.append("  \"edges\": [\n");
        if (bd.edges != null) {
            for (int i = 0; i < bd.edges.length; i++) {
                ZEdge e = bd.edges[i]; if (e == null) continue;
                sb.append("    {\"id\":").append(i)
                  .append(",\"x\":").append(e.pos().xi())
                  .append(",\"z\":").append(e.pos().zi())
                  .append(",\"dx\":").append(e.len().xi())
                  .append(",\"dz\":").append(e.len().zi())
                  .append(",\"joinZone\":").append(e.joinZoneId()).append("}");
                if (i < bd.edges.length - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // Zones
        sb.append("  \"zones\": [\n");
        for (int zi = 0; zi < numZones; zi++) {
            ZoneData z = zones[zi];
            if (z == null) {
                sb.append("    null");
            } else {
                int ft = (zi < floorTiles.length) ? floorTiles[zi] : -1;
                int ct = (zi < ceilTiles.length)  ? ceilTiles[zi]  : -1;
                sb.append("    {\"id\":").append(zi)
                  .append(",\"floorH\":").append(z.floorHeight())
                  .append(",\"roofH\":").append(z.roofHeight())
                  .append(",\"brightness\":").append(z.brightness & 0x3F)
                  .append(",\"floorTile\":").append(ft)
                  .append(",\"ceilTile\":").append(ct)
                  .append(",\"edgeIds\":[");
                for (int k = 0; k < z.edgeIds.length; k++) {
                    if (k > 0) sb.append(",");
                    sb.append(z.edgeIds[k] & 0xFFFF);
                }
                sb.append("]}");
            }
            if (zi < numZones - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");

        // Murs — champs correctement nommés selon la sémantique AMOS
        sb.append("  \"walls\": {\n");
        boolean firstZ = true;
        for (int zi = 0; zi < numZones; zi++) {
            WallRenderEntry[] ent = entries[zi];
            if (ent == null || ent.length == 0) continue;
            boolean has = false;
            for (WallRenderEntry e : ent) if (e.isWall()) { has = true; break; }
            if (!has) continue;

            if (!firstZ) sb.append(",\n");
            firstZ = false;
            sb.append("    \"").append(zi).append("\": [\n");
            boolean first = true;
            for (WallRenderEntry e : ent) {
                if (!e.isWall()) continue;
                if (!first) sb.append(",\n");
                first = false;
                sb.append("      {")
                  .append("\"leftPt\":").append(e.leftPt)
                  .append(",\"rightPt\":").append(e.rightPt)
                  // texIndex = ZWG(A,B,0) = vrai index texture mur (utiliser pour le rendu)
                  .append(",\"texIndex\":").append(e.texIndex)
                  // clipIdx = ZWG(A,B,2) = index de clip (propriété secondaire)
                  .append(",\"clipIdx\":").append(e.clipIdx)
                  .append(",\"yOffset\":").append(e.yOffset)
                  .append(",\"wallLen\":").append(e.wallLen)
                  .append(",\"topWallH\":").append(e.topWallH())
                  .append(",\"botWallH\":").append(e.botWallH())
                  // otherZone : 0=mur plein, >0=portail/porte vers cette zone
                  .append(",\"otherZone\":").append(e.otherZone)
                  .append(",\"brightOfs\":").append(e.brightOfs)
                  .append("}");
            }
            sb.append("\n    ]");
        }
        sb.append("\n  }\n");

        // Donnees de portes depuis graph.bin
        // Structure (zone_liftable.h) :
        //   ZLiftable (36 bytes) :
        //     +0  WORD zl_Bottom       : hauteur bas (sol porte, valeur editeur)
        //     +2  WORD zl_Top          : hauteur haut (plafond, porte ouverte)
        //     +4  WORD zl_OpeningSpeed
        //     +6  WORD zl_ClosingSpeed
        //     +8  WORD zl_OpenDuration (0=jamais, >0=timeout)
        //     +10..33 : sons etc.
        //     +30 WORD zl_ZoneID       : zone associee a cette porte
        //     +34 BYTE zl_RaiseCondition
        //     +35 BYTE zl_LowerCondition
        //   Suivi de N paires ZDoorWall (10 bytes each) :
        //     +0  WORD  zdw_EdgeID
        //     +2  LONG  zdw_GraphicsOffset
        //     +6  LONG  zdw_Long1 (vertical texture offset?)
        //   Termine par -1 (WORD)
        // Liste entiere terminee par 999 (WORD)
        sb.append(",\n  \"doors\": [\n");
        boolean firstDoor = true;
        if (doorDataOffset > 0 && doorDataOffset < graphRaw.length) {
            ByteBuffer db = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
            db.position(doorDataOffset);
            int ZLIFTABLE_SIZE = 36;
            int ZDOORWALL_SIZE = 10;
            while (db.remaining() >= 2) {
                int marker = db.getShort() & 0xFFFF;
                if (marker == 999 || marker == 0xFFFF) break; // fin liste
                // Pas un marqueur de fin : on a lu 2 bytes du ZLiftable
                // Repositionner pour lire le ZLiftable complet
                db.position(db.position() - 2);
                if (db.remaining() < ZLIFTABLE_SIZE) break;
                int posStart = db.position();
                short zl_Bottom       = db.getShort();  // +0
                short zl_Top          = db.getShort();  // +2
                short zl_OpeningSpeed = db.getShort();  // +4
                short zl_ClosingSpeed = db.getShort();  // +6
                short zl_OpenDuration = db.getShort();  // +8
                short zl_OpeningSoundFX = db.getShort(); // +10
                short zl_ClosingSoundFX = db.getShort(); // +12
                short zl_OpenedSoundFX  = db.getShort(); // +14
                short zl_ClosedSoundFX  = db.getShort(); // +16
                db.position(posStart + 30);
                short zl_ZoneID       = db.getShort();  // +30
                db.position(posStart + 34);
                int raiseCondition    = db.get() & 0xFF; // +34
                int lowerCondition    = db.get() & 0xFF; // +35
                // Lire les ZDoorWall jusqu'au marqueur -1
                java.util.List<int[]> walls = new java.util.ArrayList<>();
                while (db.remaining() >= 2) {
                    short edgeId = db.getShort();
                    if (edgeId < 0) break; // -1 = fin des walls de cette porte
                    if (db.remaining() < 8) break;
                    int graphicsOfs = db.getInt();
                    int long1       = db.getInt();
                    walls.add(new int[]{edgeId & 0xFFFF, graphicsOfs, long1});
                }
                if (zl_ZoneID < 0 || zl_ZoneID == 999) continue;
                if (!firstDoor) sb.append(",\n");
                firstDoor = false;
                sb.append("    {")
                  .append("\"zoneId\":").append(zl_ZoneID)
                  .append(",\"bottom\":").append(zl_Bottom)
                  .append(",\"top\":").append(zl_Top)
                  .append(",\"openSpeed\":").append(zl_OpeningSpeed)
                  .append(",\"closeSpeed\":").append(zl_ClosingSpeed)
                  .append(",\"openDuration\":").append(zl_OpenDuration)
                  .append(",\"raiseCondition\":").append(raiseCondition)
                  .append(",\"lowerCondition\":").append(lowerCondition)
                  .append(",\"openSfx\":").append(zl_OpeningSoundFX & 0xFFFF)
                  .append(",\"closeSfx\":").append(zl_ClosingSoundFX & 0xFFFF)
                  .append(",\"openedSfx\":").append(zl_OpenedSoundFX & 0xFFFF)
                  .append(",\"closedSfx\":").append(zl_ClosedSoundFX & 0xFFFF)
                  .append(",\"walls\":[");
                for (int wi = 0; wi < walls.size(); wi++) {
                    if (wi > 0) sb.append(",");
                    sb.append("{").append("\"edgeId\":").append(walls.get(wi)[0])
                      .append(",\"gfxOfs\":").append(walls.get(wi)[1])
                      .append(",\"vOfs\":").append(walls.get(wi)[2])
                      .append("}");
                }
                sb.append("]}");
            }
        }
        sb.append("\n  ]\n");

        // Objets de niveau (items, aliens, decorations)
        // TypeID : 0=alien 1=object(item/deco) 2=projectile 3=aux 4=plr1 5=plr2
        // defIndex (EntT_Type_b) :
        //   TypeID=0 -> alien def index 0-19 dans GLFT_AlienDefs
        //   TypeID=1 -> object def index 0-29 dans GLFT_ObjectDefs
        //              ODefT_Behaviour : 0=COLLECTABLE 1=ACTIVATABLE 2=DESTRUCTABLE 3=DECORATION
        //              ODefT_GFXType   : 0=BITMAP(WAD) 1=VECTOR(polygon) 2=GLARE
        // startAnim (EntT_Timer1_w) : frame d'animation de depart (objets)
        // angle (EntT_CurrentAngle_w) : angle initial 0-8191 = 360 degres
        sb.append(",\n  \"objects\": [\n");
        boolean firstObj = true;
        if (bd.objects != null) {
            for (var obj : bd.objects) {
                if (obj == null) continue;
                // Ignorer projectiles et aux (runtime uniquement)
                if (obj.typeId() == LevelBinaryParser.OBJ_TYPE_PROJECTILE
                 || obj.typeId() == LevelBinaryParser.OBJ_TYPE_AUX) continue;
                if (!firstObj) sb.append(",\n");
                firstObj = false;
                sb.append("    {")
                  .append("\"x\":").append(obj.xPos())
                  .append(",\"z\":").append(obj.zPos())
                  .append(",\"y\":").append(obj.yPos())
                  .append(",\"zoneId\":").append(obj.zoneId())
                  .append(",\"typeId\":").append(obj.typeId())
                  .append(",\"defIndex\":").append(obj.defIndex())
                  .append(",\"startAnim\":").append(obj.startAnim())
                  .append(",\"angle\":").append(obj.angle())
                  .append(",\"hitPoints\":").append(obj.hitPoints())
                  .append(",\"teamNumber\":").append(obj.teamNumber())
                  .append(",\"doorLocks\":").append(obj.doorLockFlags())
                  .append(",\"liftLocks\":").append(obj.liftLockFlags())
                  .append("}");
            }
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }
}
