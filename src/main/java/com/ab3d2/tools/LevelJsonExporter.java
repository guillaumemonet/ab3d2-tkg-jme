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

        // ── Exporter definitions.json depuis TEST.LNK (une seule fois) ────────
        Path lnkFile = findLnkFile(root);
        if (lnkFile != null) {
            try {
                LnkParser lnk = LnkParser.load(lnkFile);
                String defsJson = exportDefinitions(lnk);
                Path defsOut = outDir.resolve("definitions.json");
                Files.writeString(defsOut, defsJson);
                System.out.println("  OK  definitions.json");
            } catch (Exception e) {
                System.out.println("  WARN definitions.json : " + e.getMessage());
            }
        } else {
            System.out.println("  WARN TEST.LNK introuvable – definitions.json non generé");
        }

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
        int switchDataOffset = gb.getInt();  // +8 : switch data offset (parse session 99 plus tard)
        int zgaOfs           = gb.getInt();  // +12: zoneGraphAdds offset

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
        // Session 110 : ID de la zone de sortie du niveau (Lvl_ExitZoneID_w).
        // Lue par LevelBinaryParser depuis (floorLineOffset - 2). Le moteur
        // declenche la fin de niveau quand le joueur entre dans cette zone
        // (cf. hires.s:2089).
        sb.append("  \"exitZoneId\": ").append(bd.exitZoneId).append(",\n");
        sb.append("  \"player1\": {\"worldX\": ").append(bd.plr1StartX)
          .append(", \"worldZ\": ").append(bd.plr1StartZ)
          .append(", \"zoneId\": ").append(bd.plr1StartZoneId & 0xFFFF).append("},\n");
        sb.append("  \"player2\": {\"worldX\": ").append(bd.plr2StartX)
          .append(", \"worldZ\": ").append(bd.plr2StartZ)
          .append(", \"zoneId\": ").append(bd.plr2StartZoneId & 0xFFFF).append("},\n");

        // Session 99 : Messages texte du niveau (briefings, hints, narrative)
        // 10 messages de 160 chars max chacun, depuis le header texte de twolev.bin
        sb.append("  \"messages\": [\n");
        if (bd.messages != null) {
            for (int i = 0; i < bd.messages.length; i++) {
                String msg = bd.messages[i] != null ? bd.messages[i] : "";
                sb.append("    {\"id\":").append(i)
                  .append(",\"text\":\"").append(escape(msg)).append("\"}");
                if (i < bd.messages.length - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // Session 99 : Control points (points de patrouille des aliens)
        // Indexes par EntT_CurrentControlPoint_w / EntT_TargetControlPoint_w des objets aliens
        sb.append("  \"controlPoints\": [\n");
        if (bd.controlPoints != null) {
            for (int i = 0; i < bd.controlPoints.length; i++) {
                Vec2W cp = bd.controlPoints[i];
                if (cp == null) continue;
                sb.append("    {\"id\":").append(i)
                  .append(",\"x\":").append(cp.xi())
                  .append(",\"z\":").append(cp.zi()).append("}");
                if (i < bd.controlPoints.length - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

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

        // Session 118 : Tables de luminosite (PointBrights) et frontieres de zone
        // (ZoneBorderPoints) lues depuis le binaire APRES la table Points.
        // Critique pour la fidelite du rendu d'eclairage dynamique.
        sb.append("  \"pointBrights\": [\n");
        if (bd.pointBrights != null && bd.pointBrights.length > 0) {
            for (int z = 0; z < bd.pointBrights.length; z++) {
                short[] zb = bd.pointBrights[z];
                sb.append("    [");
                if (zb != null) {
                    for (int i = 0; i < zb.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(zb[i] & 0xFFFF); // store as unsigned 0..65535
                    }
                }
                sb.append("]");
                if (z < bd.pointBrights.length - 1) sb.append(',');
                sb.append('\n');
            }
        }
        sb.append("  ],\n");

        sb.append("  \"zoneBorderPoints\": [\n");
        if (bd.zoneBorderPoints != null && bd.zoneBorderPoints.length > 0) {
            for (int z = 0; z < bd.zoneBorderPoints.length; z++) {
                short[] zb = bd.zoneBorderPoints[z];
                sb.append("    [");
                if (zb != null) {
                    for (int i = 0; i < zb.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(zb[i]); // signed (point indices, peuvent etre <= 0)
                    }
                }
                sb.append("]");
                if (z < bd.zoneBorderPoints.length - 1) sb.append(',');
                sb.append('\n');
            }
        }
        sb.append("  ],\n");

        // Edges (session 99 : tous les champs incl. flags pour DoorRoutine et reversed walls)
        sb.append("  \"edges\": [\n");
        if (bd.edges != null) {
            for (int i = 0; i < bd.edges.length; i++) {
                ZEdge e = bd.edges[i]; if (e == null) continue;
                sb.append("    {\"id\":").append(i)
                  .append(",\"x\":").append(e.pos().xi())
                  .append(",\"z\":").append(e.pos().zi())
                  .append(",\"dx\":").append(e.len().xi())
                  .append(",\"dz\":").append(e.len().zi())
                  .append(",\"joinZone\":").append(e.joinZoneId())
                  // Session 99 : nouveaux champs (probablement utilises par DoorRoutine ASM)
                  .append(",\"word5\":").append(e.word5() & 0xFFFF)
                  .append(",\"byte12\":").append(e.byte12() & 0xFF)
                  .append(",\"byte13\":").append(e.byte13() & 0xFF)
                  .append(",\"flags\":").append(e.flags() & 0xFFFF)
                  .append("}");
                if (i < bd.edges.length - 1) sb.append(",");
                sb.append("\n");
            }
        }
        sb.append("  ],\n");

        // Zones (session 99 : tous les champs incl. upperFloor/Roof, water, telZone, echo, etc.)
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
                  // Session 99 : zones a 2 niveaux (mezzanines, ponts)
                  // Quand upperFloor < DISABLED_HEIGHT (5000), la zone a un etage haut
                  .append(",\"upperFloorH\":").append(z.upperFloorHeight())
                  .append(",\"upperRoofH\":").append(z.upperRoofHeight())
                  .append(",\"hasUpper\":").append(z.hasUpper())
                  // Session 99 : niveau d'eau (si > 0, zone aquatique avec animation)
                  .append(",\"water\":").append(ZoneData.heightOf(z.water))
                  .append(",\"brightness\":").append(z.brightness & 0x3F)
                  // Session 99 : luminosite etage haut
                  .append(",\"upperBrightness\":").append(z.upperBrightness & 0x3F)
                  // Session 99 : control point de patrouille des aliens dans cette zone
                  .append(",\"controlPoint\":").append(z.controlPoint & 0xFFFF)
                  // Session 99 : masque pour sons d'ambiance (BACKSFX dans newanims.s)
                  .append(",\"backSFXMask\":").append(z.backSFXMask & 0xFFFF)
                  // Session 99 : skybox visible depuis cette zone
                  .append(",\"drawBackdrop\":").append(z.drawBackdrop & 0xFF)
                  // Session 99 : reverb par zone (utilise par PlayEcho dans audio.s)
                  .append(",\"echo\":").append(z.echo & 0xFF)
                  // Session 99 : telep porteur - si telZone >= 0, entrer dans la zone
                  // teleporte vers (telX, telZ) dans telZone
                  .append(",\"telZone\":").append(z.telZone)
                  .append(",\"telX\":").append(z.telX)
                  .append(",\"telZ\":").append(z.telZ)
                  // Session 99 : sons de pas (index dans GLFT_FloorData)
                  .append(",\"floorNoise\":").append(z.floorNoise & 0xFFFF)
                  .append(",\"upperFloorNoise\":").append(z.upperFloorNoise & 0xFFFF)
                  .append(",\"floorTile\":").append(ft)
                  .append(",\"ceilTile\":").append(ct)
                  .append(",\"edgeIds\":[");
                for (int k = 0; k < z.edgeIds.length; k++) {
                    if (k > 0) sb.append(",");
                    sb.append(z.edgeIds[k] & 0xFFFF);
                }
                sb.append("]");
                // Session 99 : Potentially Visible Set - zones potentiellement visibles
                // depuis cette zone (utilise pour l'optimisation rendu)
                sb.append(",\"pvs\":[");
                if (z.pvsRecords != null) {
                    for (int k = 0; k < z.pvsRecords.length; k++) {
                        if (k > 0) sb.append(",");
                        ZPVSRecord r = z.pvsRecords[k];
                        sb.append("{\"zone\":").append(r.zoneId())
                          .append(",\"clip\":").append(r.clipId())
                          .append(",\"w2\":").append(r.word2() & 0xFFFF)
                          .append(",\"w3\":").append(r.word3() & 0xFFFF)
                          .append("}");
                    }
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
                  // Session 92 (fix 3) : masks Amiga par-mur pour le bon UV mapping.
                  //   wMask = textureWidth - 1   (ex. 127 pour tile de 128 px)
                  //   hMask = textureHeight - 1  (ex. 127 pour 128 px)
                  //   hShift = log2(textureHeight) (ex. 7 pour 128 px)
                  // Voir hireswall.s : draw_WallTextureWidthMask_w stocke wMask par mur.
                  .append(",\"wMask\":").append(e.wMask)
                  .append(",\"hMask\":").append(e.hMask)
                  .append(",\"hShift\":").append(e.hShift)
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

        // ── Lifts (zone_liftable.h - meme format que les portes) ───────────────
        //
        // Les lifts utilisent EXACTEMENT la meme structure de donnees que les portes :
        // ZLiftable (36 bytes) suivi de N ZDoorWall (10 bytes) termine par -1, liste
        // entiere terminee par 999. La seule difference est SEMANTIQUE :
        //   - Door  : le PLAFOND monte (entre zl_Bottom et zl_Top)
        //   - Lift  : le SOL monte (entre zl_Bottom et zl_Top)
        //
        // Voir LiftRoutine vs DoorRoutine dans newanims.s.
        //
        // Avant cette session, liftDataOffset etait lu mais JAMAIS UTILISE - les lifts
        // n'apparaissaient donc pas du tout dans le jeu. Maintenant on les exporte.
        sb.append(",\n  \"lifts\": [\n");
        boolean firstLift = true;
        if (liftDataOffset > 0 && liftDataOffset < graphRaw.length) {
            ByteBuffer lb = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
            lb.position(liftDataOffset);
            int ZLIFTABLE_SIZE = 36;
            while (lb.remaining() >= 2) {
                int marker = lb.getShort() & 0xFFFF;
                if (marker == 999 || marker == 0xFFFF) break;
                lb.position(lb.position() - 2);
                if (lb.remaining() < ZLIFTABLE_SIZE) break;
                int posStart = lb.position();
                short zl_Bottom       = lb.getShort();
                short zl_Top          = lb.getShort();
                short zl_OpeningSpeed = lb.getShort();
                short zl_ClosingSpeed = lb.getShort();
                short zl_OpenDuration = lb.getShort();
                short zl_OpeningSoundFX = lb.getShort();
                short zl_ClosingSoundFX = lb.getShort();
                short zl_OpenedSoundFX  = lb.getShort();
                short zl_ClosedSoundFX  = lb.getShort();
                lb.position(posStart + 30);
                short zl_ZoneID       = lb.getShort();
                lb.position(posStart + 34);
                int raiseCondition    = lb.get() & 0xFF;
                int lowerCondition    = lb.get() & 0xFF;
                java.util.List<int[]> walls = new java.util.ArrayList<>();
                while (lb.remaining() >= 2) {
                    short edgeId = lb.getShort();
                    if (edgeId < 0) break;
                    if (lb.remaining() < 8) break;
                    int graphicsOfs = lb.getInt();
                    int long1       = lb.getInt();
                    walls.add(new int[]{edgeId & 0xFFFF, graphicsOfs, long1});
                }
                if (zl_ZoneID < 0 || zl_ZoneID == 999) continue;
                if (!firstLift) sb.append(",\n");
                firstLift = false;
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

        // ── Switches (TLGT header +8) ─────────────────────────────────────────
        //
        // Session 99 : structure dechiffree depuis newanims.s::SwitchRoutine.
        //
        // SwitchT (14 bytes) :
        //   +0  WORD active        : -1 = inactif (slot vide), >=0 = actif
        //   +2  BYTE timerActive   : 0 = pas de timer en cours, !=0 = timer actif
        //   +3  BYTE timerCounter  : decrement par Anim_TempFrames_w*4 (auto-reset)
        //   +4  WORD pointIndex    : index dans Lvl_PointsPtr_l (position du switch)
        //   +6  LONG gfxOffset     : offset dans Lvl_GraphicsPtr (graphique du switch)
        //   +10 BYTE pressed       : 0/1 toggle (Plr active)
        //   +11 BYTE padding
        //   +12 WORD reserved
        //
        // Maximum 8 switches par niveau (d0 = #7 dans CheckSwitches).
        // Le bit Conditions affecte par switch d'index i (0..7) est : bit (11 - i)
        // (cf. asm `move.w #7,d3 ; sub.w d0,d3 ; addq #4,d3` -> 4 + (7-d0) = 11..4)
        //
        // Distance d'activation : 60 unites Amiga (= ~1.9 unites JME) au carre.
        sb.append(",\n  \"switchesDataOffset\": ").append(switchDataOffset);
        sb.append(",\n  \"switches\": [\n");
        boolean firstSwitch = true;
        if (switchDataOffset > 0 && switchDataOffset < graphRaw.length) {
            ByteBuffer sw = ByteBuffer.wrap(graphRaw).order(ByteOrder.BIG_ENDIAN);
            int SWITCH_SIZE = 14;
            int MAX_SWITCHES = 8;
            for (int i = 0; i < MAX_SWITCHES; i++) {
                int off = switchDataOffset + i * SWITCH_SIZE;
                if (off + SWITCH_SIZE > graphRaw.length) break;
                sw.position(off);
                short active     = sw.getShort();   // +0
                if (active < 0) continue;            // slot inactif
                int  timerActive = sw.get() & 0xFF;  // +2
                int  timerCount  = sw.get() & 0xFF;  // +3
                int  pointIndex  = sw.getShort() & 0xFFFF; // +4
                int  gfxOffset   = sw.getInt();      // +6
                int  pressed     = sw.get() & 0xFF;  // +10
                sw.get();                            // +11 padding
                int  reserved    = sw.getShort() & 0xFFFF; // +12

                int conditionBit = 11 - i;  // bit affecte dans Conditions

                if (!firstSwitch) sb.append(",\n");
                firstSwitch = false;
                sb.append("    {\"index\":").append(i)
                  .append(",\"active\":").append(active)
                  .append(",\"pointIndex\":").append(pointIndex)
                  .append(",\"gfxOffset\":").append(gfxOffset)
                  .append(",\"pressed\":").append(pressed)
                  .append(",\"timerActive\":").append(timerActive)
                  .append(",\"timerCount\":").append(timerCount)
                  .append(",\"reserved\":").append(reserved)
                  .append(",\"conditionBit\":").append(conditionBit)
                  .append("}");
            }
        }
        sb.append("\n  ]");

        // Construire la map zones pour lookup floorH par zoneId
        // yPos = zone.floorH = hauteur du sol de la zone de l'objet (valeur editeur, negative vers le haut)
        // En JME : jy = -zone.floorH / 32 + 0.5f
        java.util.Map<Integer, Integer> zoneFloorH = new java.util.HashMap<>();
        for (int zi = 0; zi < numZones; zi++) {
            if (zones[zi] != null)
                zoneFloorH.put((int)(zones[zi].zoneId & 0xFFFF),
                               zones[zi].floorHeight());
        }

        // Objets de niveau
        // xPos, zPos : depuis ObjectPoints[objPointIndex] - vraies coordonnees monde
        // yPos : calcule depuis zone.floorH (sol de la zone de l'objet)
        // TypeID : 0=alien 1=object 2=projectile(skip) 3=aux(skip) 4=plr1 5=plr2
        sb.append(",\n  \"objects\": [\n");
        boolean firstObj = true;
        if (bd.objects != null) {
            for (var obj : bd.objects) {
                if (obj == null) continue;
                if (obj.typeId() == LevelBinaryParser.OBJ_TYPE_PROJECTILE
                 || obj.typeId() == LevelBinaryParser.OBJ_TYPE_AUX) continue;
                // y = floorH de la zone de l'objet (valeur editeur, pour coherence avec
                // le systeme de coordonnees AB3D2 - meme valeur que zone.floorH)
                int floorH = zoneFloorH.getOrDefault(obj.zoneId(), 0);
                if (!firstObj) sb.append(",\n");
                firstObj = false;
                sb.append("    {")
                  .append("\"x\":").append(obj.xPos())
                  .append(",\"z\":").append(obj.zPos())
                  .append(",\"y\":").append(floorH)
                  .append(",\"zoneId\":").append(obj.zoneId())
                  .append(",\"typeId\":").append(obj.typeId())
                  .append(",\"defIndex\":").append(obj.defIndex())
                  .append(",\"isPolygon\":").append(obj.isPolygon() ? 1 : 0)
                  .append(",\"polyModelIndex\":").append(obj.polyModelIndex())
                  .append(",\"startAnim\":").append(obj.startAnim())
                  .append(",\"angle\":").append(obj.angle())
                  .append(",\"hitPoints\":").append(obj.hitPoints())
                  .append(",\"teamNumber\":").append(obj.teamNumber())
                  .append(",\"doorLocks\":").append(obj.doorLockFlags())
                  .append(",\"liftLocks\":").append(obj.liftLockFlags())
                  // Session 99 : index dans messages[] pour afficher le texte quand active
                  .append(",\"displayText\":").append(obj.displayText())
                  // Session 99 : control point cible (patrouille IA aliens)
                  .append(",\"targetCP\":").append(obj.targetCP())
                  .append("}");
            }
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    // ── Export definitions.json ─────────────────────────────────────────────────────

    /**
     * Exporte toutes les définitions statiques de TEST.LNK dans definitions.json.
     *
     * Ce fichier est généré UNE SEULE FOIS pendant convertLevels.
     * Il contient les définitions fixes du jeu (aliens, objets, WAD, sons).
     * Le LevelSceneBuilder le lit pour colorer/nommer correctement les objets.
     *
     * Structure :
     * {
     *   "aliens":  [ { "index":0, "name":"...", "gfxType":0, ... }, ... ],   // 20 entrees
     *   "objects": [ { "index":0, "name":"...", "behaviour":0, "gfxType":0, "wadIndex":0 }, ... ], // 30 entrees
     *   "wadFiles":    [ { "index":0, "path":"ab3:includes/alien2", "name":"alien2" }, ... ],  // 30 entrees
     *   "vectorFiles": [ { "index":0, "path":"ab3:vectobj/blaster", "name":"blaster" }, ... ], // 30 entrees
     *   "sfxFiles":    [ { "index":0, "path":"ab3:samples/scream.fib", "name":"scream" }, ... ] // 60 entrees
     * }
     *
     * Offsets TEST.LNK (leved303.amos) :
     *   $34D8 = AlienNames  (20 x 20 bytes)
     *   $3668 = AlienDefs   (20 x 42 bytes) : [gfxType:w, defaultBehaviour:w, reactionTime:w, ...]
     *   $57B0 = ObjectNames (30 x 20 bytes)
     *   $5A08 = ObjectDefs  (30 x 40 bytes) : [behaviour:w, gfxType:w, ...]
     *   $02C0 = ObjGfxNames (30 x 64 bytes) -> index WAD pour chaque def objet
     *   $13FE0= VectorNames (30 x 64 bytes)
     *   $0A40 = SFXNames    (60 x 64 bytes)
     */
    public static String exportDefinitions(LnkParser lnk) {
        byte[] data = lnk.getData();
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("{\n");

        // ── Aliens (20 définitions) ───────────────────────────────────────────────────
        // AlienT (defs.i, STRUCTURE AlienT,0 - 42 bytes) :
        //   +0  UWORD AlienT_GFXType_w         : 0=BITMAP, 1=VECTOR, 2-5=lightsourced palettes
        //   +2  UWORD AlienT_DefaultBehaviour_w : 0=prowl 1=fly
        //   +4  UWORD AlienT_ReactionTime_w
        //   +6  UWORD AlienT_DefaultSpeed_w
        //   +8  UWORD AlienT_ResponseBehaviour_w
        //   +10 UWORD AlienT_ResponseSpeed_w
        //   +12 UWORD AlienT_ResponseTimeout_w
        //   +14 UWORD AlienT_DamageToRetreat_w
        //   +16 UWORD AlienT_DamageToFollowup_w
        //   +18 UWORD AlienT_FollowupBehaviour_w
        //   +20 UWORD AlienT_FollowupSpeed_w
        //   +22 UWORD AlienT_FollowupTimeout_w
        //   +24 UWORD AlienT_RetreatBehaviour_w
        //   +26 UWORD AlienT_RetreatSpeed_w
        //   +28 UWORD AlienT_RetreatTimeout_w
        //   +30 UWORD AlienT_BulType_w
        //   +32 UWORD AlienT_HitPoints_w
        //   +34 UWORD AlienT_Height_w
        //   +36 UWORD AlienT_Girth_w
        //   +38 UWORD AlienT_SplatType_w
        //   +40 UWORD AlienT_Auxilliary_w
        sb.append("  \"aliens\": [\n");
        java.util.List<String> alienNames = lnk.getAlienNames();
        for (int i = 0; i < LnkParser.NUM_ALIENS; i++) {
            // Session 113 : on lit la STRUCTURE COMPLETE AlienT (21 UWORDs)
            // au lieu de seulement 4 champs. Les 16 nouveaux champs alimentent
            // la machine a etats AI Java (port de modules/ai.s::AI_MainRoutine).
            LnkParser.AlienDef def = lnk.getAlienDef(i);
            String name = def.name();
            if (name == null || name.isEmpty()) {
                name = i < alienNames.size() ? alienNames.get(i) : "";
            }
            if (i > 0) sb.append(",\n");
            sb.append("    {\"index\":").append(i)
              .append(",\"name\":\"").append(escape(name)).append('"')
              .append(",\"gfxType\":").append(def.gfxType())          // 0=BITMAP,1=VECTOR,2-5=lsrc
              // ── Modes & Speeds & Timeouts (machine a etats IA) ───────────
              .append(",\"defaultBehaviour\":").append(def.defaultBehaviour())
              .append(",\"defaultSpeed\":").append(def.defaultSpeed())
              .append(",\"reactionTime\":").append(def.reactionTime())
              .append(",\"responseBehaviour\":").append(def.responseBehaviour())
              .append(",\"responseSpeed\":").append(def.responseSpeed())
              .append(",\"responseTimeout\":").append(def.responseTimeout())
              .append(",\"damageToRetreat\":").append(def.damageToRetreat())
              .append(",\"damageToFollowup\":").append(def.damageToFollowup())
              .append(",\"followupBehaviour\":").append(def.followupBehaviour())
              .append(",\"followupSpeed\":").append(def.followupSpeed())
              .append(",\"followupTimeout\":").append(def.followupTimeout())
              .append(",\"retreatBehaviour\":").append(def.retreatBehaviour())
              .append(",\"retreatSpeed\":").append(def.retreatSpeed())
              .append(",\"retreatTimeout\":").append(def.retreatTimeout())
              // ── Combat & Identite ───────────────────────────────
              .append(",\"bulType\":").append(def.bulType())
              .append(",\"hitPoints\":").append(def.hitPoints())
              .append(",\"height\":").append(def.height())
              .append(",\"girth\":").append(def.girth())
              .append(",\"splatType\":").append(def.splatType())
              .append(",\"auxilliary\":").append(def.auxilliary())
              .append("}");
        }
        sb.append("\n  ],\n");

        // ── Objets (30 définitions) ────────────────────────────────────────────────
        // ODefT (defs.i, STRUCTURE ODefT - 40 bytes) :
        //   +0  UWORD ODefT_Behaviour_w  : 0=COLLECTABLE, 1=ACTIVATABLE, 2=DESTRUCTABLE, 3=DECORATION
        //   +2  UWORD ODefT_GFXType_w    : 0=BITMAP(WAD), 1=VECTOR(polygon), 2=GLARE
        //   +4  UWORD ODefT_ActiveTimeout_w
        //   +6  UWORD ODefT_HitPoints_w
        //   +8  UWORD ODefT_ExplosiveForce_w
        //   +10 UWORD ODefT_Impassible_w
        //   +12 UWORD ODefT_DefaultAnimLen_w
        //   +14 UWORD ODefT_CollideRadius_w
        //   +16 UWORD ODefT_CollideHeight_w
        //   +18 UWORD ODefT_FloorCeiling_w  : 0=floor, 1=ceiling
        //   +20 UWORD ODefT_LockToWall_w
        //   +22 UWORD ODefT_ActiveAnimLen_w
        //   +24 UWORD ODefT_SFX_w
        //   +26..39 : (padding / unused)
        //
        // GLFT_ObjGfxNames_l ($2C0) : pour chaque def objet, le nom du fichier WAD
        // -> l'index dans cette table = le "wadIndex" qu'on stocke ici
        sb.append("  \"objects\": [\n");
        java.util.List<String> objNames  = lnk.getObjectNames();
        java.util.List<String> wadNames  = lnk.getObjGfxNames();
        for (int i = 0; i < LnkParser.NUM_OBJECTS; i++) {
            String name = i < objNames.size() ? objNames.get(i) : "";
            int defOfs = LnkParser.OFS_OBJECT_DEFS + i * 40;
            int behaviour  = (defOfs + 2  <= data.length) ? readShortBE(data, defOfs)      : 0;
            int gfxType    = (defOfs + 4  <= data.length) ? readShortBE(data, defOfs + 2)  : 0;
            int hitPoints  = (defOfs + 8  <= data.length) ? readShortBE(data, defOfs + 6)  : 0;
            int colRadius  = (defOfs + 16 <= data.length) ? readShortBE(data, defOfs + 14) : 0;
            int colHeight  = (defOfs + 18 <= data.length) ? readShortBE(data, defOfs + 16) : 0;
            int floorCeil  = (defOfs + 20 <= data.length) ? readShortBE(data, defOfs + 18) : 0;
            int sfx        = (defOfs + 26 <= data.length) ? readShortBE(data, defOfs + 24) : -1;
            // wadIndex : pour gfxType=BITMAP, l'index dans GLFT_ObjGfxNames_l
            // Dans le code AMOS, la table $2C0 EST indexée par le même index que ODefT.
            // Donc wadIndex = i (identique) mais on stocke le nom pour lisibilité.
            //
            // Session 111 fix : pour les BITMAP, byte 0 de l'anim entry peut REDIRIGER
            // vers une autre WAD (ex. ShotgunShells defIdx=2 mais gfxIndex=1 -> PICKUPS
            // au lieu de BIGBULLET). On lit ce gfxIndex et on utilise SON wadName.
            // Pour les VECTOR (gfxType=1) : pas de redirection, on garde l'index de
            // la def (gfxIndex est lu mais ne sert qu'au debug).
            int gfxIndex = lnk.getObjectDefaultGfxIndex(i);
            String wadPath, wadName;
            if (gfxType == 0 && gfxIndex >= 0 && gfxIndex < wadNames.size()) {
                // BITMAP : utiliser le wadName du gfxIndex pour permettre la redirection
                wadPath = wadNames.get(gfxIndex);
                wadName = LnkParser.extractFileName(wadPath);
            } else {
                // VECTOR ou index invalide : utiliser le wadName de la def elle-meme
                wadPath = (i < wadNames.size()) ? wadNames.get(i) : "";
                wadName = LnkParser.extractFileName(wadPath);
            }
            // Session 111 : frame index a utiliser au repos pour cet objet.
            // Lit GLFT_ObjectDefAnims_l[defIdx][frame=0][byte=1]. Pour les BITMAP
            // c'est le bon _f<N>.png a charger ; sans ce champ, on charge toujours
            // _f0.png ce qui donne la mauvaise image quand un WAD est partage
            // entre plusieurs objets (ex. PICKUPS partage entre Medipac et
            // ShotgunShells avec frame 5).
            int defFrame = lnk.getObjectDefaultFrameIndex(i);
            // Session 112 : tables d'inventaire donne par la collecte de l'objet.
            // ammoGive = [Health, JetpackFuel, Ammo[0]..Ammo[19]] (22 WORDs)
            // gunGive  = [Shield,  JetPack,     Weapons[0]..Weapons[9]] (12 WORDs)
            // Source ASM : GLFT_AmmoGive_l et GLFT_GunGive_l, lus par Plr1_CollectItem
            // pour merger dans Plr1_Invetory_vw via Game_AddToInventory.
            int[] ammoGive = lnk.getAmmoGive(i);
            int[] gunGive  = lnk.getGunGive(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"index\":").append(i)
              .append(",\"name\":\"").append(escape(name)).append('"')
              .append(",\"behaviour\":").append(behaviour) // 0=COLL 1=ACTI 2=DEST 3=DECO
              .append(",\"gfxType\":").append(gfxType)    // 0=BITMAP 1=VECTOR 2=GLARE
              .append(",\"hitPoints\":").append(hitPoints)
              .append(",\"colRadius\":").append(colRadius)
              .append(",\"colHeight\":").append(colHeight)
              .append(",\"floorCeiling\":").append(floorCeil) // 0=sol 1=plafond
              .append(",\"sfx\":").append(sfx)
              .append(",\"wadName\":\"").append(escape(wadName)).append('"')
              .append(",\"gfxIndex\":").append(gfxIndex)   // session 111 : redirection WAD
              .append(",\"defFrame\":").append(defFrame)  // session 111
              .append(",\"ammoGive\":[");                 // session 112
            for (int k = 0; k < ammoGive.length; k++) {
                if (k > 0) sb.append(',');
                sb.append(ammoGive[k]);
            }
            sb.append("],\"gunGive\":[");
            for (int k = 0; k < gunGive.length; k++) {
                if (k > 0) sb.append(',');
                sb.append(gunGive[k]);
            }
            sb.append("]}");
        }
        sb.append("\n  ],\n");

        // ── Fichiers WAD (sprites bitmap) ───────────────────────────────────────
        sb.append("  \"wadFiles\": [\n");
        for (int i = 0; i < wadNames.size(); i++) {
            String path = wadNames.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"index\":").append(i)
              .append(",\"path\":\"").append(escape(path)).append('"')
              .append(",\"name\":\"").append(escape(LnkParser.extractFileName(path))).append('"')
              .append("}");
        }
        sb.append("\n  ],\n");

        // ── Fichiers Vector ───────────────────────────────────────────────────
        sb.append("  \"vectorFiles\": [\n");
        java.util.List<String> vecNames = lnk.getVectorNames();
        for (int i = 0; i < vecNames.size(); i++) {
            String path = vecNames.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"index\":").append(i)
              .append(",\"path\":\"").append(escape(path)).append('"')
              .append(",\"name\":\"").append(escape(LnkParser.extractFileName(path))).append('"')
              .append("}");
        }
        sb.append("\n  ],\n");

        // ── Fichiers SFX (60 entrées) ───────────────────────────────────────────
        sb.append("  \"sfxFiles\": [\n");
        java.util.List<String> sfxNames = lnk.getSfxNames();
        for (int i = 0; i < sfxNames.size(); i++) {
            String path = sfxNames.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\"index\":").append(i)
              .append(",\"path\":\"").append(escape(path)).append('"')
              .append(",\"name\":\"").append(escape(LnkParser.extractFileName(path))).append('"')
              .append("}");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    private static Path findLnkFile(Path srcRes) {
        for (Path p : java.util.List.of(
                srcRes.resolve("sounds/raw/TEST.LNK"),
                srcRes.resolve("sounds/raw/test.lnk"),
                srcRes.resolve("TEST.LNK"))) {
            if (java.nio.file.Files.exists(p)) return p;
        }
        return null;
    }

    private static int readShortBE(byte[] data, int off) {
        if (off + 2 > data.length) return 0;
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
