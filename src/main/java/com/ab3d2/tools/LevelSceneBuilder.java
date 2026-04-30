package com.ab3d2.tools;

import com.jme3.bounding.BoundingBox;
import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Outil de build : JSON de niveau -> .j3o JME dans assets/scenes/
 *
 * CORRESPONDANCE AVEC LevelED.txt (AMOS BASIC)
 *   ZWG(A,B,0) = ZWG  = fromTile = position X dans le strip de texture
 *                        -> JSON "texIndex" -> utilise pour decalage U
 *   ZWG(A,B,2) = ZWGC = chunk file = INDEX DU FICHIER .256wad
 *                        -> JSON "clipIdx"  -> utilise pour choisir la texture
 *
 * UV mapping :
 *   U_offset = fromTile * 16 / texWidth
 *   (fromTile*16 = offset pixel dans la colonne de texture, texWidth = largeur PNG)
 *
 * PREREQUIS : ./gradlew convertAssets convertLevels buildScenes
 * Normales murs : (-dz, 0, dx)/L = perp gauche = vers l'interieur
 * Lumieres : AmbientLight + PointLight zones (serialisees dans .j3o)
 */
public class LevelSceneBuilder {

    private static final float SCALE = 32f, TEX_V = 128f, TILE_SZ = 64f;
    private static final int   DOOR_FLOOR_THRESH = 8;
    // Un panneau de porte vrai : botH proche du sol ET hauteur >= 64 unites
    // En-dessous = mur de marche (step wall) ou linteau -> rendu en geometrie statique
    private static final int   DOOR_HEIGHT_MIN = 64;
    private static final int   BRIGHT_LIGHT_THRESHOLD = 40;
    private static final ColorRGBA SCENE_AMBIENT = new ColorRGBA(0.35f, 0.32f, 0.38f, 1f);

    record ZD(int floorH, int roofH, int brightness, int floorTile, int ceilTile, int[] edgeIds,
              int telZone, int telX, int telZ) {}
    record PolyData(float[] xz, float y) {}

    private final AssetManager am;
    private final int[] wallTexWidths  = new int[AssetConverter.NUM_WALL_TEX];
    private final int[] wallTexHeights = new int[AssetConverter.NUM_WALL_TEX];
    // Definitions chargees une seule fois depuis definitions.json (build-time)
    private ObjDef[]   objDefs;
    private AlienDef[] alienDefs;

    public LevelSceneBuilder(AssetManager am) {
        this.am = am;
        this.objDefs   = loadObjDefs();
        this.alienDefs = loadAlienDefs();
    }

    // Entry point Gradle
    public static void main(String[] args) throws Exception {
        String lv = args.length > 0 ? args[0] : "assets/levels";
        String sc = args.length > 1 ? args[1] : "assets/Scenes";  // JME convention : majuscule
        String as = args.length > 2 ? args[2] : "assets";
        Files.createDirectories(Path.of(sc));
        System.out.printf("=== LevelSceneBuilder ===%n  levels:%s  scenes:%s  assets:%s%n%n",lv,sc,as);
        AssetManager am = new DesktopAssetManager(true);
        am.registerLocator(as, FileLocator.class);
        LevelSceneBuilder builder = new LevelSceneBuilder(am);
        int ok=0,skip=0;
        for(char c='A';c<='P';c++){
            Path j=Path.of(lv,"level_"+c+".json");
            if(!Files.exists(j)){skip++;continue;}
            try{
                Node scene=builder.buildScene(String.valueOf(c),Files.readString(j));
                BinaryExporter.getInstance().save(scene,Path.of(sc,"scene_"+c+".j3o").toFile());
                System.out.printf("  OK  scene_%c.j3o%n",c);ok++;
            }catch(Exception e){
                System.out.printf("  ERR scene_%c : %s%n",c,e.getMessage());
                e.printStackTrace();skip++;
            }
        }
        System.out.printf("%nOK:%d  Skip:%d%n",ok,skip);
    }

    // Construction de la scene
    public Node buildScene(String levelId, String json) {
        Map<Integer,int[]>       pts      = parsePts(json);
        Map<Integer,int[]>       edges    = parseEdges(json);
        Map<Integer,int[]>       edgesFull= parseEdgesFull(json);  // pour matching edgeId precis
        Map<Integer,ZD>          zones    = parseZones(json);
        Map<Integer,List<int[]>> zw       = parseWalls(json);

        // Charger les materiaux et remplir wallTexWidths en meme temps
        Material[] wm = loadWallMats();
        Material[] fm = loadFloorMats();
        Material   fb = makeFallback();

        Node root  = new Node("scene_"+levelId);
        Node geo   = new Node("geometry");
        Node doors = new Node("doors");
        Node lifts = new Node("lifts");
        Node spawns= new Node("spawns");
        Node lights= new Node("lights");
        Node zns   = new Node("zones");
        Node items = new Node("items");  // objets/aliens/items de niveau
        root.attachChild(geo);root.attachChild(doors);root.attachChild(lifts);
        root.attachChild(spawns);root.attachChild(lights);root.attachChild(zns);
        root.attachChild(items);
        root.setUserData("levelId",levelId);
        // Session 110 : ID de la zone exit du niveau, expose pour ZoneTracker.
        int exitZoneId = parseExitZoneId(json);
        root.setUserData("exitZoneId", exitZoneId);
        addSpawns(root,json,spawns);
        addItems(json, items, fb, objDefs, alienDefs);

        // AmbientLight par defaut serialisee dans le .j3o
        root.addLight(new AmbientLight(SCENE_AMBIENT));

        // Accumulateurs geometrie
        // Quad mur : [x0,z0,x1,z1,yTop,yBot, uM, vM, uOffset]
        //                                                  ^ fromTile*16/texWidth
        List<List<float[]>>  wq=new ArrayList<>();
        List<List<PolyData>> fp=new ArrayList<>(),cp=new ArrayList<>();
        for(int i=0;i<AssetConverter.NUM_WALL_TEX;i++) wq.add(new ArrayList<>());
        for(int i=0;i<=AssetConverter.NUM_FLOOR_TEX;i++){fp.add(new ArrayList<>());cp.add(new ArrayList<>());}

        Map<String,DoorAccum> dg=new LinkedHashMap<>();
        // Charger les definitions de portes depuis le JSON
        // Cle = zoneId de la porte
        Map<Integer,int[]> doorZoneDefs = parseDoorDefs(json); // zoneId -> [bottom,top,openSpeed,closeSpeed,openDuration,raiseCondition,lowerCondition,...sfx]
        // SESSION 97 : on n'utilise PAS gfxOfs pour la texture.
        // Le gfxOfs du JSON est juste un byte-offset dans Lvl_GraphicsPtr (ASM)
        // pour que DoorRoutine sache ou patcher ZoneT_Roof_l a runtime.
        // La VRAIE texture porte est deja dans le clipIdx du wall couloir-vers-zone-porte.
        // On garde juste l'ensemble des edgeIds des ZDoorWalls pour les identifier.
        Map<Integer,Integer> doorEdgeGfx = parseDoorWallGfx(json); // edgeId -> gfxOfs (juste pour detection)
        // Mapping zoneId-porte -> liste des edgeIds qui sont des ZDoorWalls
        Map<Integer,Set<Integer>> doorZoneEdges = parseDoorZoneEdges(json);
        // SESSION 102 : ensemble GLOBAL des edges porteurs d'au moins un
        // ZDoorWall (toutes zones-portes confondues). Sert a skipper les
        // linteaux et step-walls qui partagent un edge avec un panneau de
        // porte etendu : ils seraient des doublons visuels au-dessus/en-dessous
        // du panneau plein.
        Set<Integer> doorEdgesGlobal = new HashSet<>();
        for (var dze : doorZoneEdges.values()) doorEdgesGlobal.addAll(dze);

        // Lifts (session 92 fix 4) : meme structure que les portes mais
        // c'est le SOL qui monte. La zone-lift NE doit PAS avoir de sol statique
        // genere - on cree un Mesh dynamique pour le LiftControl.
        Map<Integer,int[]> liftZoneDefs = parseLiftDefs(json);

        // SESSION 99 : recuperer les edgeIds des LIFT WALLS.
        // Ce sont les walls dans `lifts[].walls[].edgeId` que l'ASM rend
        // dynamiquement (visibles ou invisibles selon position du lift).
        //
        // SESSION 100 : la methode est ajoutee mais le skip dans la boucle
        // principale n'est PAS active pour l'instant. Activer ce skip ferait
        // disparaitre les walls couloir<->lift, creant des trous visibles
        // tant que le lift est en bas. La gestion correcte (rendu dynamique
        // des walls de lift comme l'ASM) sera implementee dans une session
        // ulterieure. Pour le moment on collecte juste l'info pour usage futur.
        Set<Integer> liftWallEdges = parseLiftWallEdges(json);
        // TODO(session future) : utiliser liftWallEdges.contains(eid) pour
        // skip les walls dynamiques du lift, et les rendre depuis le lift node
        // (similaire aux ZDoorWalls pour les portes).
        int wc=0,sp=0,sl=0;

        for(var ze:zones.entrySet()){
            int zid=ze.getKey(); ZD z=ze.getValue();
            float yF=-z.floorH()/SCALE,yR=-z.roofH()/SCALE;
            if(yF>yR){float t=yF;yF=yR;yR=t;}
            if(Math.abs(yR-yF)<0.01f) continue;

            Node zn=new Node("zone_"+zid);
            zn.setUserData("id",(int)zid);zn.setUserData("floorH",(int)z.floorH());
            zn.setUserData("roofH",(int)z.roofH());zn.setUserData("brightness",(int)z.brightness());
            // Session 110 : donnees de teleport et polygone XZ pour ZoneTracker.
            //   - telZone : -1 = pas de teleport ; >=0 = ID zone de destination
            //   - telX/telZ : position monde Amiga ou teleporter (si telZone >= 0)
            //   - floorXZ : polygone XZ de la zone (CSV de floats), utilise par
            //     ZoneTracker pour le test point-in-polygon "joueur dans zone ?"
            zn.setUserData("telZone", (int) z.telZone());
            zn.setUserData("telX",    (int) z.telX());
            zn.setUserData("telZ",    (int) z.telZ());
            // floorXZ : reconstruit depuis les edges de la zone, format CSV "x0,z0,x1,z1,..."
            // (l'ordre des edges definit le polygone). Le polygone est en coords JME
            // (= world Amiga / SCALE, avec Z negate comme dans le reste du builder).
            List<Float> zoneXZ = new ArrayList<>();
            for (int eid : z.edgeIds()) {
                int[] e = edges.get(eid);
                if (e == null) continue;
                zoneXZ.add(e[0] / SCALE);
                zoneXZ.add(-e[1] / SCALE);
            }
            if (zoneXZ.size() >= 6) {
                float[] zoneXZArr = new float[zoneXZ.size()];
                for (int k = 0; k < zoneXZArr.length; k++) zoneXZArr[k] = zoneXZ.get(k);
                zn.setUserData("floorXZ", floatArrayToCsv(zoneXZArr));
            }
            zns.attachChild(zn);

            collectHoriz(z.edgeIds(),edges,yF,fp.get(floorIdx(z.floorTile())+1));
            collectHoriz(z.edgeIds(),edges,yR,cp.get(floorIdx(z.ceilTile())+1));

            // Session 99 (refonte) : on GARDE le sol statique meme pour les lifts.
            // Avantages :
            //   - Au repos : la collision Bullet globale couvre la zone-lift -> joueur stable
            //   - Le sol dynamique du lift se superpose visuellement par-dessus (pas de z-fighting
            //     car les meshes sont identiques au repos, et le dynamique sera devant)
            //   - Quand le lift monte : on cache le sol statique via setCullHint dans GameAppState
            //     et on "pousse" le joueur Y manuellement (LiftControl.currentFloorYDelta)
            //
            // Avant (sess 92 fix 4) on retirait le sol statique pour les lifts -> creait un
            // trou dans la collision et le joueur tombait des qu'il bougeait.
            // [Code retire intentionnellement]

            // PointLight pour zones lumineuses
            if(z.brightness()>=BRIGHT_LIGHT_THRESHOLD){
                float[] c=zoneCentroid(z.edgeIds(),edges,yF+0.5f);
                float t=(z.brightness()-BRIGHT_LIGHT_THRESHOLD)/(float)(63-BRIGHT_LIGHT_THRESHOLD);
                PointLight pl=new PointLight();
                pl.setPosition(new Vector3f(c[0],c[1],c[2]));
                float r=0.8f+t*0.4f,g=0.7f+t*0.3f,b=0.5f+t*0.2f;
                pl.setColor(new ColorRGBA(r,g,b,1f).mult(t*1.5f+0.5f));
                pl.setRadius(20f+t*15f);
                Node proxy=new Node("light_"+zid);
                proxy.setLocalTranslation(c[0],c[1],c[2]);
                proxy.setUserData("zoneId",(int)zid);
                proxy.setUserData("intensity",(float)t);
                proxy.addLight(pl);
                lights.attachChild(proxy);
            }

            List<int[]> walls=zw.get(zid); if(walls==null) continue;
            for(int[] w:walls){
                // w[0]=leftPt  w[1]=rightPt  w[2]=texIndex(fromTile, ZWG(A,B,0))
                // w[3]=topH    w[4]=botH      w[5]=otherZone  w[6]=clipIdx(ZWGC)  w[7]=yOffset
                int lpi=w[0],rpi=w[1];
                int fromTile=w[2]; // ZWG(A,B,0) = decalage U dans la texture
                int topH=w[3],botH=w[4],oz=w[5];
                int clipIdx=w.length>6?w[6]:w[2]; // ZWGC = vrai index fichier .256wad
                int wallYOffset = w.length>7?w[7]:0; // VO = decalage Y texture (porte = utilise pour alignement)
                // Session 92 (fix 3) : masks Amiga par-mur (vrai tileWidth/tileHeight)
                int wMaskByte = w.length>8?w[8]:0;  // textureWidth  - 1
                int hMaskByte = w.length>9?w[9]:0;  // textureHeight - 1

                if((fromTile&0x8000)!=0){sp++;continue;}
                if(topH==botH) continue;
                float wallH=(float)Math.abs(topH-botH);
                if(wallH>8192f||Math.abs(topH)>16384f) continue;
                int[] L=pts.get(lpi),R=pts.get(rpi); if(L==null||R==null) continue;
                float x0=L[0]/SCALE,z0=-L[1]/SCALE,x1=R[0]/SCALE,z1=-R[1]/SCALE;
                float yTop=-topH/SCALE,yBot=-botH/SCALE;
                if(Math.abs(yTop-yBot)<0.01f) continue;

                // SESSION 95 : detection de porte uniquement pour les ZDoorWalls
                //
                // Les vrais panneaux de porte sont rendus depuis les COULOIRS voisins :
                //   - Un wall de zone 4 (couloir) avec oz=5 (zone-porte) referencant edge 18 ou 25
                //     -> c'est un ZDoorWall, on le rend comme panneau de porte avec texture hazard
                //   - Un wall de zone 14 (couloir) avec oz=5 referencant edge 23 ou 62
                //     -> idem, ZDoorWall avec texture hazard
                //
                // SESSION 96 : on ne rajoute PAS de DoorAccum.segs ici. La construction
                // du cube de porte est faite separement apres la boucle, en parcourant
                // les edges de la zone-porte directement. Mais on collecte ici la TEXTURE
                // (texIdx + yOffset) du premier ZDoorWall rencontre, pour l'utiliser sur
                // toutes les faces du cube.
                // SESSION 97 : detection ZDoorWall = wall dont otherZone est une zone-porte
                // ET dont l'edge est dans la liste des walls de cette zone-porte.
                //
                // Approche ASM-fidele : le ZDoorWall a deja sa propre texture (clipIdx)
                // dans le binaire de niveau. Pour zone 5, clipIdx=5 = wall_05_chevrondoor
                // (la texture jaune/noir hazard). On garde TOUTES les infos du wall et
                // on l'envoie dans DoorAccum pour rendu anime.
                //
                // Animation : le BAS du quad (botWallH) monte vers le haut (topWallH).
                // Quand state=0 : panneau plein du sol au plafond. Quand state=1 : panneau
                // reduit a 0 hauteur (bas = haut). En realite l'ASM modifie ZoneT_Roof_l
                // (le plafond de la zone-porte descend) mais visuellement c'est equivalent.
                if (oz != 0 && doorZoneDefs.containsKey(oz)) {
                    // Matching du segment a un edge de la zone-porte
                    int eid = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, z.edgeIds());
                    if (eid < 0) {
                        ZD zOther = zones.get(oz);
                        if (zOther != null)
                            eid = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, zOther.edgeIds());
                    }
                    Set<Integer> zoneEdges = doorZoneEdges.get(oz);
                    boolean isZDoorWall = eid >= 0 && zoneEdges != null && zoneEdges.contains(eid);                    if (isZDoorWall) {
                        // C'est un VRAI ZDoorWall.
                        // SESSION 102 : on etend le panneau a la hauteur COMPLETE du couloir
                        // (yF=sol couloir, yR=plafond couloir = la zone courante).
                        //
                        // Avant : le panneau utilisait yBot/yTop du wall, qui ne couvre
                        // souvent qu'une portion verticale de l'ouverture (linteau et
                        // step-wall sont des walls separes). Resultat : pendant l'animation
                        // on voyait le panneau monter MAIS le linteau/step restaient en
                        // place -> effet "decoupe" avec bandes noires entre les morceaux.
                        //
                        // Maintenant : panneau plein du sol au plafond. Quand ferme,
                        // il cache tous les autres walls de cet edge (linteau, step).
                        // Quand ouvert, l'ouverture est complete (sol au plafond), comme
                        // dans le jeu original.
                        //
                        // Effet "bloc plein qui glisse" assure.
                        int doorZoneId = oz;
                        String key = "door_" + doorZoneId;
                        int[] def = doorZoneDefs.get(doorZoneId);
                        float dAnimDist = def != null
                            ? Math.abs(def[1] - def[0]) / SCALE
                            : Math.abs(yR - yF);
                        final float fAnim = dAnimDist;
                        final float fSegBot = yF;  // sol du couloir (zone courante)
                        final float fSegTop = yR;  // plafond du couloir
                        // SESSION 103 : decalage du panneau le long de sa normale
                        // interieure (perpendiculaire a l'edge, pointe vers le
                        // couloir courant). Comme il y a 2 ZDoorWalls par edge (un
                        // dans chaque couloir voisin) avec normales opposees, on
                        // obtient au final 2 faces paralleles separees de 2*epsilon.
                        // La porte a alors une epaisseur visible ("fond solide")
                        // au lieu d'etre un simple quad mince qui n'avait pas de
                        // back face -- c'etait visible quand le joueur regardait
                        // sous un angle ou s'arretait pres de la porte fermee.
                        // epsilon = 0.08 JME ~= 2.5 unites Amiga ~= 8cm.
                        float dxN = x1 - x0, dzN = z1 - z0;
                        float Ln = (float) Math.sqrt(dxN*dxN + dzN*dzN);
                        final float DOOR_OFFSET = 0.08f;
                        float ofsX = (Ln > 1e-5f) ? -dzN / Ln * DOOR_OFFSET : 0f;
                        float ofsZ = (Ln > 1e-5f) ?  dxN / Ln * DOOR_OFFSET : 0f;
                        // ATTENTION : ne pas dedupliquer ! Le meme edge a 2 ZDoorWalls
                        // (1 par cote, dans les 2 zones voisines), et les 2 doivent etre rendus
                        // pour que la porte soit visible des 2 cotes du couloir.
                        // Mais pour eviter le z-fighting, on les rend a positions identiques
                        // -> en pratique JME les superpose et on les voit avec FaceCullMode.Off.
                        dg.computeIfAbsent(key, k -> new DoorAccum(fSegTop, fSegBot, fAnim, doorZoneId))
                          .addSeg(x0 + ofsX, z0 + ofsZ, x1 + ofsX, z1 + ofsZ,
                                  clipIdx, fromTile, wallYOffset,
                                  wMaskByte, hMaskByte, fSegBot, fSegTop,
                                  z.ceilTile());
                        continue; // ne pas rendre comme mur normal
                    }
                    // sinon : wall ordinaire avec otherZone != 0 (passage simple) -> rendu normal
                }

                // SESSION 102 : skip les linteaux et step-walls partageant un edge
                // avec un panneau de porte etendu. Sans ce skip, ils apparaitraient
                // par-dessus/en-dessous du panneau plein et casseraient l'effet
                // "bloc plein" (en plus d'etre des doublons coutant des draw calls).
                {
                    int wallEid2 = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, z.edgeIds());
                    if (wallEid2 >= 0 && doorEdgesGlobal.contains(wallEid2)) {
                        continue;
                    }
                }

                // SESSION 108 : skip les walls couloir<->lift (= ouvertures de la
                // cabine). Le JSON declare ces edges dans `lifts[].walls[].edgeId`
                // (collectes dans liftWallEdges). Sans ce skip, ces walls sont rendus
                // comme des panneaux pleins avec la texture chevron (clipIdx=5), ce
                // qui cache l'entree/sortie du lift quand on s'approche en couloir.
                //
                // Note ASM-fidele : dans newanims.s::LiftRoutine, ces walls sont
                // rendus DYNAMIQUEMENT (visibles selon position du lift). Pour la
                // version Java, on les retire purement et simplement -- les sides
                // de la cabine (lift_side_*) gerent deja le rendu interieur.
                {
                    int wallEidLift = findEdgeIdForSegment(lpi, rpi, pts, edgesFull, z.edgeIds());
                    if (wallEidLift >= 0 && liftWallEdges.contains(wallEidLift)) {
                        continue;
                    }
                }

                float dx=R[0]-L[0],dz=R[1]-L[1];
                float len=(float)Math.sqrt(dx*dx+dz*dz);
                // Bucket par clipIdx = ZWGC = vrai index texture .256wad
                int bk=(clipIdx>=0&&clipIdx<AssetConverter.NUM_WALL_TEX)?clipIdx:0;

                // Session 92 (fix 2) : mapping UV conforme a l'ASM Amiga.
                //
                // L'ASM (hiresgourwall.s) applique un AND mask de tileWidth-1 sur
                // la colonne de sampling, PUIS ajoute fromTile*16 comme offset
                // pixel absolu. Concretement, fromTile selectionne UNE tile de
                // tileWidth pixels (typiquement 128) dans la texture, et le wrap
                // horizontal se fait dans cette tile seulement - pas sur la
                // texture entiere.
                //
                // Exemple : hullmetal 256x128 contient 2 tiles de 128x128 :
                //   fromTile=0 -> tile gauche (colonnes 0-127)
                //   fromTile=8 -> tile droite (colonnes 128-255)
                //
                // Pour un mur plus long que tileWidth, l'Amiga repete la MEME tile
                // (via le mask), il ne deborde pas sur la tile suivante.
                //
                // Comme JME WrapMode.Repeat wrappe sur la texture entiere (pas une
                // sous-region), on decoupe le mur en N sous-quads de longueur
                // <=tileWidth, chacun avec un UV borne a [uOffset, uOffset + tileUvWidth].
                // Session 92 (fix 3) : utiliser les masks Amiga par-mur.
                //
                // L'ASM (hireswall.s) lit chaque mur du fichier .lvl avec un
                // <code>wMask</code> et <code>hMask</code> stockes comme bytes :
                //   wMask = textureWidth - 1   (ex. 127 pour une tile de 128 px)
                //   hMask = textureHeight - 1  (ex. 127 pour 128 px de haut)
                //
                // Ces valeurs sont les VRAIES dimensions de la tile logique de
                // sampling, baked-in dans le binaire du niveau par le LevelED.
                // Avant fix 3, on devinait empiriquement (deduceTileWidth) ;
                // maintenant on utilise la valeur exacte du jeu.
                //
                // Fallback : si le JSON ne contient pas wMask/hMask (anciens
                // exports), on retombe sur deduceTileWidth + texH.
                float texW=(float)wallTexWidths[bk];
                float texH=(float)wallTexHeights[bk];
                int   tileWidth  = (wMaskByte > 0) ? (wMaskByte + 1) : deduceTileWidth((int)texW);
                int   tileHeight = (hMaskByte > 0) ? (hMaskByte + 1) : (int)texH;
                float uOffset    = (texW>0) ? (fromTile*16f/texW) : 0f;
                float tileUvWidth = (texW>0) ? tileWidth/texW   : 1f; // largeur UV d'une tile
                // V repeat sur la hauteur tile reelle (pas texH si tile != texH)
                float segVMax    = (tileHeight>0) ? wallH/tileHeight : wallH/TEX_V;

                int numRepeats = Math.max(1, (int) Math.ceil(len / tileWidth));
                for (int rep = 0; rep < numRepeats; rep++) {
                    float segStart = Math.min(1f, (rep * tileWidth) / len);
                    float segEnd   = Math.min(1f, ((rep+1) * tileWidth) / len);
                    float segLen   = (segEnd - segStart) * len;
                    // Points monde du sous-quad
                    float sx0 = x0 + (x1-x0) * segStart;
                    float sz0 = z0 + (z1-z0) * segStart;
                    float sx1 = x0 + (x1-x0) * segEnd;
                    float sz1 = z0 + (z1-z0) * segEnd;
                    // UV : pour ce segment, on affiche la tile du debut (uOffset)
                    // jusqu'a uOffset + (segLen/tileWidth)*tileUvWidth.
                    // (segLen/tileWidth) est dans [0,1] et vaut 1 pour les segs complets.
                    float segUvMax = (segLen / tileWidth) * tileUvWidth;
                    wq.get(bk).add(new float[]{sx0,sz0,sx1,sz1,yTop,yBot,
                        segUvMax, segVMax, uOffset});
                    wc++;
                }
            }
        }

        // SESSION 97 : plus de construction de cube. Les ZDoorWalls sont collectes
        // dans la boucle principale ci-dessus avec leur vraie texture (clipIdx).

        // Geometrie murs
        for(int i=0;i<AssetConverter.NUM_WALL_TEX;i++){
            if(wq.get(i).isEmpty()) continue;
            Geometry g=buildWallGeo("walls_"+i,wq.get(i));
            g.setMaterial(wm[i]!=null?wm[i]:fb); geo.attachChild(g);
        }
        // Sols et plafonds
        for(int i=0;i<=AssetConverter.NUM_FLOOR_TEX;i++){
            if(!fp.get(i).isEmpty()){Geometry g=buildHorizGeo("floor_"+i,fp.get(i),true);g.setMaterial(i>0&&fm[i-1]!=null?fm[i-1]:fb);geo.attachChild(g);}
            if(!cp.get(i).isEmpty()){Geometry g=buildHorizGeo("ceil_"+i,cp.get(i),false);g.setMaterial(i>0&&fm[i-1]!=null?fm[i-1]:fb);geo.attachChild(g);}
        }

        // Portes
        for(var de:dg.entrySet()){
            DoorAccum acc=de.getValue(); if(acc.segs.isEmpty()) continue;
            int doorZoneId = acc.doorZoneId;
            Node dn=new Node("door_"+de.getKey());
            // yTop et yBot : extremes du panneau de porte FERMEE (en coords JME)
            // animDist : distance JME de translation pour ouvrir le panneau
            //   (=  |zl_Top - zl_Bottom| / SCALE, en unites JME)
            dn.setUserData("yTop",(float)acc.yTop);
            dn.setUserData("yBot",(float)acc.yBot);
            dn.setUserData("animDist",(float)acc.animDist);
            dn.setUserData("doorZoneId",(int)doorZoneId);
            // Conditions d'ouverture/fermeture + vitesses pour le runtime
            if(doorZoneId >= 0 && doorZoneDefs.containsKey(doorZoneId)) {
                int[] def = doorZoneDefs.get(doorZoneId);
                // def = [bottom,top,openSpeed,closeSpeed,openDuration,raiseCond,lowerCond, 4x sfx]
                // zl_Bottom / zl_Top : hauteurs editeur (unites Amiga, pour conversion vitesse)
                dn.setUserData("zlBottom",       (int)def[0]);
                dn.setUserData("zlTop",          (int)def[1]);
                dn.setUserData("openSpeed",      (int)def[2]);   // zl_OpeningSpeed (Amiga-units/frame@25fps)
                dn.setUserData("closeSpeed",     (int)def[3]);   // zl_ClosingSpeed
                dn.setUserData("openDuration",   (int)def[4]);
                dn.setUserData("raiseCondition",(int)def[5]);
                dn.setUserData("lowerCondition",(int)def[6]);
                if (def.length > 7) dn.setUserData("openSfx",   (int)def[7]);
                if (def.length > 8) dn.setUserData("closeSfx",  (int)def[8]);
                if (def.length > 9) dn.setUserData("openedSfx", (int)def[9]);
                if (def.length >10) dn.setUserData("closedSfx", (int)def[10]);
            }
            // SESSION 101 : fusion des ZDoorWalls par edge.
            //
            // Probleme observe : pour une porte, le binaire peut definir plusieurs
            // ZDoorWalls sur le MEME edge XZ mais a des hauteurs differentes (par
            // exemple panneau bas + linteau, separes par un wall de transition).
            // Rendre chaque seg independamment cree un panneau "decoupe" avec des
            // bandes noires entre les morceaux et un effet de glissement incoherent.
            //
            // Solution : grouper les segs par position XZ (= meme edge couloir),
            // puis creer UN SEUL quad par groupe couvrant min(yBot)..max(yTop).
            // Texture/UV prises du seg avec le yBot le plus bas (ancre sol :
            // son yOffset est calibre pour ce yBot, on conserve l'alignement).
            //
            // Resultat : un panneau plein qui glisse vers le haut d'un seul tenant.
            // C'est l'effet "bloc plein" attendu par l'ASM original (qui rendait
            // tous les ZDoorWalls dans la meme passe scanline et ne laissait
            // jamais voir le gap).
            Map<String, List<float[]>> bySpan = new LinkedHashMap<>();
            for (float[] seg : acc.segs) {
                String key = String.format(java.util.Locale.US,
                    "%.3f_%.3f_%.3f_%.3f", seg[0], seg[1], seg[2], seg[3]);
                bySpan.computeIfAbsent(key, k -> new ArrayList<>()).add(seg);
            }
            int si=0;
            for (var spanEntry : bySpan.entrySet()) {
                List<float[]> group = spanEntry.getValue();
                // Identifier le seg-ancre = celui avec le yBot le plus bas.
                // Sa texture, ses UV et son yOffset sont cales sur ce yBot,
                // on les reutilise pour le quad fusionne.
                float[] anchor = group.get(0);
                float groupYBot = anchor[7];
                float groupYTop = anchor.length > 10 ? anchor[10] : acc.yTop;
                for (float[] s : group) {
                    if (s[7] < groupYBot) {
                        groupYBot = s[7];
                        anchor = s;
                    }
                    float st = (s.length > 10) ? s[10] : acc.yTop;
                    if (st > groupYTop) groupYTop = st;
                }
                // seg = [x0, z0, x1, z1, texIdx, fromTile, yOffset, segYBot, wMask, hMask, segYTop]
                int segTexIdx  = (int) anchor[4];
                int segFromTile= (int) anchor[5];
                int segYOffset = (int) anchor[6];
                int segWMask   = anchor.length>8 ? (int) anchor[8] : 0;
                int segHMask   = anchor.length>9 ? (int) anchor[9] : 0;
                Material mat = (segTexIdx>=0 && segTexIdx<wm.length && wm[segTexIdx]!=null) ? wm[segTexIdx] : fb;
                float texW   = (segTexIdx>=0 && segTexIdx<wallTexWidths.length)  ? (float)wallTexWidths[segTexIdx]  : 256f;
                float texH   = (segTexIdx>=0 && segTexIdx<wallTexHeights.length) ? (float)wallTexHeights[segTexIdx] : 128f;
                int tileW = (segWMask > 0) ? (segWMask + 1) : deduceTileWidth((int)texW);
                int tileH = (segHMask > 0) ? (segHMask + 1) : (int)texH;
                Geometry sg = makeDoorSegGeo("seg_"+si, anchor[0], anchor[1], anchor[2], anchor[3],
                                              groupYTop, groupYBot, mat, texW, texH,
                                              tileW, tileH, segFromTile, segYOffset);
                sg.setUserData("x0",(float)anchor[0]); sg.setUserData("z0",(float)anchor[1]);
                sg.setUserData("x1",(float)anchor[2]); sg.setUserData("z1",(float)anchor[3]);
                sg.setUserData("yBotSeg", (float)groupYBot);
                sg.setUserData("yTopSeg", (float)groupYTop);
                sg.setUserData("animDist", (float)acc.animDist);
                sg.setUserData("faceType", "front");
                dn.attachChild(sg);

                si++;
            }

            // SESSION 106 : plafond mobile de la zone-porte (UN seul quad
            // polygonal partage par tous les pans, au lieu de N caps par pan
            // de la session 105 - desormais retires).
            //
            // Au repos (state=0) : plafond AU SOL (acc.yBot = sol couloir).
            // Pendant l'ouverture : plafond MONTE en synchro avec les pans.
            // A state=1 : plafond a sa hauteur normale (acc.yTop = plafond couloir).
            //
            // C'est l'approche ASM-fidele : dans newanims.s::DoorRoutine,
            // ZoneT_Roof_l de la zone-porte est patche pour suivre le mouvement
            // des ZDoorWalls. La zone-porte est aplatie au repos (plafond=sol)
            // et "s'eleve" au fur et a mesure que la porte s'ouvre.
            //
            // Resultat visuel : les N pans verticaux ont un fond commun (= ce
            // plafond mobile). Plus de cube ouvert au-dessus, plus de feuille
            // de papier - on voit un vrai volume avec un plafond solide.
            ZD doorZone = zones.get(doorZoneId);
            if (doorZone != null) {
                List<Float> roofCoords = new ArrayList<>();
                for (int eid : doorZone.edgeIds()) {
                    int[] e = edges.get(eid);
                    if (e == null) continue;
                    roofCoords.add(e[0] / SCALE);
                    roofCoords.add(-e[1] / SCALE);
                }
                if (roofCoords.size() >= 6) {  // >= 3 points pour un polygone
                    int dCeilTile = doorZone.ceilTile();
                    int dCeilIdx  = floorIdx(dCeilTile);
                    Material roofMat = (dCeilIdx >= 0 && dCeilIdx < AssetConverter.NUM_FLOOR_TEX
                                        && fm[dCeilIdx] != null) ? fm[dCeilIdx] : fb;

                    float[] roofXZ = new float[roofCoords.size()];
                    for (int i = 0; i < roofXZ.length; i++) roofXZ[i] = roofCoords.get(i);

                    // SESSION 107 : utiliser le floorH/roofH PROPRES de la
                    // zone-porte (pas acc.yBot/yTop qui viennent du couloir).
                    // Cas porte rouge zone 132 : son plafond est plus bas que
                    // celui du couloir, le plafond mobile doit s'arreter a
                    // doorZone.roofH(), pas a corridorRoof.
                    float doorYBot = -doorZone.floorH() / SCALE;
                    float doorYTop = -doorZone.roofH()  / SCALE;

                    // Plafond initialement au SOL de la zone-porte, normale -Y
                    // (visible depuis en bas = depuis le sol du couloir). Anime
                    // par DoorControl via faceType="bottom" -> setLocalTranslation Y.
                    Geometry roofGeo = makePolyCapGeo("door_"+doorZoneId+"_roof",
                        roofXZ, doorYBot, roofMat, false);
                    roofGeo.setUserData("faceType", "bottom");
                    roofGeo.setUserData("yBotSeg", doorYBot);
                    roofGeo.setUserData("yTopSeg", doorYTop);
                    roofGeo.setUserData("animDist", (float) acc.animDist);
                    dn.attachChild(roofGeo);
                }
            }

            doors.attachChild(dn);
        }

        // Lifts (session 92 fix 4) : creation des nodes lift_* avec un sol dynamique.
        //
        // Pour chaque zone marquee comme lift, on cree un Node lift_<zoneId>
        // contenant un Geometry de sol (polygone de la zone) avec basePos en
        // UserData (positions de base des vertex au repos = lift en bas).
        // LiftControl translate ces positions en Y selon state.
        //
        // floorXZ = polygone XZ de la zone (pour point-in-polygon dans LiftControl).
        int liftCount = 0;
        for (var le : liftZoneDefs.entrySet()) {
            int zid = le.getKey();
            int[] def = le.getValue();
            ZD z = zones.get(zid);
            if (z == null) continue;

            // Polygone XZ depuis les edges de la zone
            // Session 102 : on garde aussi les edgeIds en parallele pour
            // pouvoir skipper les sides correspondant aux ouvertures du lift
            // (passages couloir<->cabine, declares dans lifts[].walls[]).
            List<Float> coords = new ArrayList<>();
            List<Integer> sideEdgeIds = new ArrayList<>();
            for (int eid : z.edgeIds()) {
                int[] e = edges.get(eid);
                if (e == null) continue;
                coords.add(e[0] / SCALE);
                coords.add(-e[1] / SCALE);
                sideEdgeIds.add(eid);
            }
            if (coords.size() < 6) continue; // moins de 3 points : pas un polygone

            float[] xz = new float[coords.size()];
            for (int i = 0; i < xz.length; i++) xz[i] = coords.get(i);
            int n = xz.length / 2;

            // Conversion bottom/top Amiga -> JME
            // SESSION 99 (correction lift zone 104) :
            //
            // Pour un lift, la convention bottom/top n'est PAS la meme que pour les portes.
            // Pour une porte (DoorRoutine ASM) : top = plafond le plus haut, bottom = plus bas.
            // Pour un lift (LiftRoutine ASM) : bottom et top decrivent la PLAGE TOTALE de
            // mouvement du sol, mais l'etat initial = floorH de la zone (sol au repos).
            //
            // Exemple zone 104 : floorH=344, bottom=1376, top=-64
            //   - Au repos le sol est a floorH=344 (JME -10.75) - le joueur arrive ici
            //   - Le lift peut descendre jusqu'a bottom=1376 (JME -43) - mais raiseCond=1 PLAYER_TOUCH
            //     et lowerCond=1 NEVER, donc il ne descend jamais
            //   - Le lift peut monter jusqu'a top=-64 (JME +2) quand le joueur marche dessus
            //
            // Pour la course visible : on utilise (yRest, yTop) pour un lift qui monte,
            // ou (yBottom, yRest) pour un lift qui descend.
            float yRest = -z.floorH() / SCALE;       // sol au repos = floorH de la zone (JME)
            float yLowEdit  = -def[0] / SCALE;       // bottom Amiga -> JME (peut etre tres bas)
            float yHighEdit = -def[1] / SCALE;       // top Amiga -> JME (peut etre haut)

            // SESSION 108 : clamper yHigh/yLow aux floorH des zones voisines reliees
            // par un liftWall (= passages de la cabine). Sans ca, le lift peut
            // depasser le sol de la zone d'arrivee.
            //
            // Ex zone 104 : top=-64 (JME +2) mais zone 105 (arrivee haute)
            // a floorH=-8 (JME +0.25). Le lift montait 1.75 JME (~56 cm) au-dessus
            // du sol couloir, le joueur arrivait "perche".
            //
            // Avec ce fix : yHigh = min(yHighEdit, max floorH voisin) -> le lift
            // s'arrete pile au sol de la zone d'arrivee. yLow = max(yLowEdit, min
            // floorH voisin) -> il ne descend pas plus bas que le sol couloir bas.
            //
            // ASM-fidele ? Non - l'ASM clamp simplement a top/bottom. Mais visuellement
            // c'est plus naturel et le joueur n'a pas a "descendre une marche" a la
            // sortie du lift.
            float maxNeighborYFloor = Float.NEGATIVE_INFINITY;  // sol le plus haut en JME
            float minNeighborYFloor = Float.POSITIVE_INFINITY;  // sol le plus bas en JME
            List<int[]> liftWallsZone = zw.get(zid);
            if (liftWallsZone != null) {
                for (int[] w : liftWallsZone) {
                    int oz = w[5];  // otherZone
                    if (oz <= 0 || oz == zid) continue;
                    // Verifier que ce wall est bien sur un liftWall edge
                    int weid = findEdgeIdForSegment(w[0], w[1], pts, edgesFull, z.edgeIds());
                    if (weid < 0 || !liftWallEdges.contains(weid)) continue;
                    ZD nz = zones.get(oz);
                    if (nz == null) continue;
                    float yNeighborFloor = -nz.floorH() / SCALE;
                    if (yNeighborFloor > maxNeighborYFloor) maxNeighborYFloor = yNeighborFloor;
                    if (yNeighborFloor < minNeighborYFloor) minNeighborYFloor = yNeighborFloor;
                }
            }

            // Determiner sens du mouvement : on utilise raiseCondition pour savoir
            // si le lift monte (most common) ou descend.
            // Pour zone 104 : raiseCond=1 (PLAYER_TOUCH) -> monte quand on est dessus.
            // -> yLow = yRest (position courante au repos), yHigh = yHighEdit (vers le haut)
            float yLow, yHigh;
            if (yHighEdit > yRest) {
                // Le lift monte au-dessus de sa position de repos
                yLow  = yRest;
                yHigh = (maxNeighborYFloor > Float.NEGATIVE_INFINITY)
                        ? Math.min(yHighEdit, maxNeighborYFloor)
                        : yHighEdit;
            } else if (yLowEdit < yRest) {
                // Le lift descend en-dessous de sa position de repos
                yLow  = (minNeighborYFloor < Float.POSITIVE_INFINITY)
                        ? Math.max(yLowEdit, minNeighborYFloor)
                        : yLowEdit;
                yHigh = yRest;
            } else {
                // Cas par defaut : utiliser bottom/top bruts
                yLow  = Math.min(yLowEdit, yHighEdit);
                yHigh = Math.max(yLowEdit, yHighEdit);
            }

            // Construire mesh du sol (triangle fan depuis le centroide)
            float cx = 0, cz = 0;
            for (int i = 0; i < n; i++) { cx += xz[i*2]; cz += xz[i*2+1]; }
            cx /= n; cz /= n;

            int numVerts = n + 1; // centroide + N points
            float[] pos = new float[numVerts * 3];
            float[] uv  = new float[numVerts * 2];
            float[] nor = new float[numVerts * 3];
            int[]   idx = new int[n * 3];

            // Vertex 0 = centroide
            pos[0]=cx; pos[1]=yLow; pos[2]=cz;
            uv[0]=cx*(SCALE/TILE_SZ); uv[1]=-cz*(SCALE/TILE_SZ);
            nor[0]=0; nor[1]=1; nor[2]=0;
            // Vertices 1..N = bord
            for (int i = 0; i < n; i++) {
                int vi = (i+1) * 3;
                pos[vi]   = xz[i*2];
                pos[vi+1] = yLow;
                pos[vi+2] = xz[i*2+1];
                uv [(i+1)*2]   = xz[i*2]*(SCALE/TILE_SZ);
                uv [(i+1)*2+1] = -xz[i*2+1]*(SCALE/TILE_SZ);
                nor[vi]=0; nor[vi+1]=1; nor[vi+2]=0;
            }
            // Triangles fan
            for (int i = 0; i < n; i++) {
                int next = (i+1) % n + 1;
                idx[i*3]   = 0;
                idx[i*3+1] = i+1;
                idx[i*3+2] = next;
            }

            Mesh mesh = new Mesh();
            mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(pos));
            mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
            mesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(nor));
            mesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(idx));
            mesh.setDynamic();  // IMPORTANT pour que LiftControl puisse modifier le buffer
            mesh.updateBound();

            // Materiau du sol : meme texture que la zone (floorTile)
            Material liftMat = fb;
            int floorTileIdx = floorIdx(z.floorTile());
            if (floorTileIdx >= 0 && floorTileIdx < AssetConverter.NUM_FLOOR_TEX
                && fm[floorTileIdx] != null) {
                liftMat = fm[floorTileIdx];
            }

            Geometry liftFloorGeo = new Geometry("lift_floor_"+zid, mesh);
            liftFloorGeo.setMaterial(liftMat);
            // basePos = copie des positions de base (sol en BAS) pour LiftControl.
            // JME UserData ne supporte pas float[] - encodage en String CSV.
            liftFloorGeo.setUserData("basePos", floatArrayToCsv(pos));

            Node liftNode = new Node("lift_"+zid);
            liftNode.attachChild(liftFloorGeo);

            // SESSION 99 : ajouter les 4 "cotes" du lift (parois laterales).
            // Sans ca, le sol du lift flotte tout seul - on veut des panneaux
            // verticaux qui descendent du sol vers (yLow - sideHeight) pour
            // donner l'illusion d'une cabine d'ascenseur.
            //
            // Chaque cote = un quad vertical de hauteur 'sideHeight' :
            //   - top = yLow (= sol du lift au repos)
            //   - bottom = yLow - sideHeight (= dans le "trou" du lift en bas)
            //
            // Quand le sol monte (LocalTranslation), les cotes montent avec lui (ils
            // sont enfants du meme Node liftNode -> heritent la translation).
            //
            // Hauteur des cotes : (yHigh - yLow) + 1.0 unite de marge pour cacher
            // le sol bas pendant que le lift est entre les 2 etages.
            float sideHeight = (yHigh - yLow) + 1.0f;

            // SESSION 108b : texture des sides = celle du PREMIER WALL de la
            // zone-lift (= chevron clipIdx=5 pour zone 104). Avant on utilisait
            // liftMat (= texture du sol = floor_02 beige), ce qui ne ressemblait
            // pas du tout aux parois jaune/noir typiques des ascenseurs Alien
            // Breed. Toutes les zones-lifts du jeu ont leurs walls avec une
            // texture chevron specifique : on la reutilise ici.
            Material sideMat = liftMat;          // fallback
            float sideTileSize = TILE_SZ / SCALE;  // = 2.0 JME = 64 px (defaut)
            List<int[]> liftZoneWalls = zw.get(zid);
            if (liftZoneWalls != null && !liftZoneWalls.isEmpty()) {
                int[] firstWall = liftZoneWalls.get(0);
                int sideClipIdx = firstWall.length > 6 ? firstWall[6] : -1;
                if (sideClipIdx >= 0 && sideClipIdx < AssetConverter.NUM_WALL_TEX
                    && wm[sideClipIdx] != null) {
                    sideMat = wm[sideClipIdx];
                }
                // Tile size en JME : utiliser wMask du wall si dispo (= tile_pixels - 1).
                int wMaskByte = firstWall.length > 8 ? firstWall[8] : 0;
                int tileWpx   = (wMaskByte > 0) ? (wMaskByte + 1) : 128;
                sideTileSize  = tileWpx / SCALE;  // ex. 128 / 32 = 4 JME
            }
            //
            // Chaque cote est un quad SEPARE (pas combines) car on veut un mesh
            // propre par cote pour faciliter le debug eventuel.
            for (int i = 0; i < n; i++) {
                // SESSION 102 : skip les sides correspondant aux ouvertures de
                // la cabine du lift. Ces edges (declares dans lifts[].walls[])
                // sont les passages couloir <-> lift, ils doivent rester
                // visuellement OUVERTS pour que le joueur puisse entrer/sortir.
                // Sans ce skip on cree une cabine totalement fermee = on se
                // retrouve coince a l'arrivee.
                int sideEid = (i < sideEdgeIds.size()) ? sideEdgeIds.get(i) : -1;
                if (sideEid > 0 && liftWallEdges.contains(sideEid)) continue;
                int j = (i + 1) % n;
                float ax = xz[i*2],     az = xz[i*2+1];
                float bx = xz[j*2],     bz = xz[j*2+1];
                float dx2 = bx - ax,    dz2 = bz - az;
                float L2 = (float) Math.sqrt(dx2*dx2 + dz2*dz2);
                if (L2 < 1e-3f) continue;
                // Normale pointant vers l'EXTERIEUR du polygone (oppose centroide)
                // (pour un polygone CCW vu de dessus en JME : normale = (dz, 0, -dx)/L)
                float nx = dz2 / L2, nz = -dx2 / L2;
                // Verifier que la normale pointe vers l'exterieur en testant
                // si le centroide est de l'autre cote.
                float midX = (ax + bx) * 0.5f, midZ = (az + bz) * 0.5f;
                float toCx = cx - midX, toCz = cz - midZ;
                if (nx * toCx + nz * toCz > 0) { nx = -nx; nz = -nz; }

                // Quad vertical : 4 vertices (sol-bas, sol-haut sur 2 cotes)
                float yTopSide = yLow;
                float yBotSide = yLow - sideHeight;
                float[] sidePos = {
                    ax, yBotSide, az,   // BL
                    bx, yBotSide, bz,   // BR
                    bx, yTopSide, bz,   // TR
                    ax, yTopSide, az    // TL
                };
                // SESSION 108b : UV en tile-units (= JME / sideTileSize). Sans
                // normalisation la texture chevron etait etiree de plusieurs fois
                // sa taille reelle. Avec sideTileSize = 4 JME (tile 128px), un seg
                // L2=4 JME montre exactement 1 chevron en largeur, ~3 en hauteur.
                float[] sideUv = {
                    0f,             sideHeight / sideTileSize,  // BL
                    L2 / sideTileSize, sideHeight / sideTileSize,  // BR
                    L2 / sideTileSize, 0f,                          // TR
                    0f,             0f                           // TL
                };
                float[] sideNor = {
                    nx, 0f, nz, nx, 0f, nz, nx, 0f, nz, nx, 0f, nz
                };
                int[] sideIdx = {0, 1, 2, 0, 2, 3};

                Mesh sideMesh = new Mesh();
                sideMesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(sidePos));
                sideMesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(sideUv));
                sideMesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(sideNor));
                sideMesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(sideIdx));
                sideMesh.setStatic();
                sideMesh.updateBound();

                Geometry sideGeo = new Geometry("lift_side_" + zid + "_" + i, sideMesh);
                sideGeo.setMaterial(sideMat);
                liftNode.attachChild(sideGeo);
            }

            // Donnees pour LiftControl
            liftNode.setUserData("yBot", (float)yLow);
            liftNode.setUserData("yTop", (float)yHigh);
            liftNode.setUserData("liftZoneId",   (int)zid);
            liftNode.setUserData("zlBottom",     (int)def[0]);
            liftNode.setUserData("zlTop",        (int)def[1]);
            liftNode.setUserData("openSpeed",    (int)def[2]);
            liftNode.setUserData("closeSpeed",   (int)def[3]);
            liftNode.setUserData("openDuration", (int)def[4]);
            liftNode.setUserData("raiseCondition", (int)def[5]);
            liftNode.setUserData("lowerCondition", (int)def[6]);
            if (def.length > 7)  liftNode.setUserData("openSfx",   (int)def[7]);
            if (def.length > 8)  liftNode.setUserData("closeSfx",  (int)def[8]);
            if (def.length > 9)  liftNode.setUserData("openedSfx", (int)def[9]);
            if (def.length > 10) liftNode.setUserData("closedSfx", (int)def[10]);
            // floorXZ : polygone du sol pour point-in-polygon dans LiftControl.
            // JME UserData ne supporte pas float[] - encodage en String CSV.
            liftNode.setUserData("floorXZ", floatArrayToCsv(xz));

            lifts.attachChild(liftNode);
            liftCount++;
        }
        System.out.printf("  [%s] murs:%d portails:%d linteaux:%d portes:%d lifts:%d lights:%d%n",
            levelId,wc,sp,sl,dg.size(),liftCount,lights.getQuantity());
        return root;
    }

    // Mesh builders

    /**
     * Quads de murs.
     * q = [x0,z0, x1,z1, yTop,yBot, uMax, vMax, uOffset]
     *   uOffset = fromTile*16/texWidth (decalage horizontal dans la texture)
     *   Normale interieure = (-dz,0,dx)/L
     */
    private Geometry buildWallGeo(String name, List<float[]> quads){
        int n=quads.size();
        float[] pos=new float[n*12],uv=new float[n*8],nor=new float[n*12];
        int[] idx=new int[n*6];
        int vi=0,ui=0,ni=0,ii=0;
        for(float[] q:quads){
            float x0=q[0],z0=q[1],x1=q[2],z1=q[3],yT=q[4],yB=q[5],uM=q[6],vM=q[7],uOfs=q[8];
            float dx=x1-x0,dz=z1-z0,L=Math.max((float)Math.sqrt(dx*dx+dz*dz),1e-5f);
            float nx=-dz/L,nz=dx/L; // normale interieure
            int b=vi/3;
            pos[vi++]=x0;pos[vi++]=yB;pos[vi++]=z0; pos[vi++]=x1;pos[vi++]=yB;pos[vi++]=z1;
            pos[vi++]=x1;pos[vi++]=yT;pos[vi++]=z1; pos[vi++]=x0;pos[vi++]=yT;pos[vi++]=z0;
            // UV avec offset U (fromTile)
            uv[ui++]=uOfs;    uv[ui++]=0f;
            uv[ui++]=uOfs+uM; uv[ui++]=0f;
            uv[ui++]=uOfs+uM; uv[ui++]=vM;
            uv[ui++]=uOfs;    uv[ui++]=vM;
            for(int k=0;k<4;k++){nor[ni++]=nx;nor[ni++]=0f;nor[ni++]=nz;}
            idx[ii++]=b;idx[ii++]=b+1;idx[ii++]=b+2;idx[ii++]=b;idx[ii++]=b+2;idx[ii++]=b+3;
        }
        return makeGeo(name,pos,uv,nor,idx);
    }

    private void collectHoriz(int[] eids,Map<Integer,int[]> edges,float y,List<PolyData> target){
        List<Float> c=new ArrayList<>();
        for(int eid:eids){int[] e=edges.get(eid);if(e==null)continue;c.add(e[0]/SCALE);c.add(-e[1]/SCALE);}
        if(c.size()<6) return;
        float[] xz=new float[c.size()]; for(int i=0;i<xz.length;i++) xz[i]=c.get(i);
        target.add(new PolyData(xz,y));
    }

    /** Sol (ny=+1) / Plafond (ny=-1) */
    private Geometry buildHorizGeo(String name,List<PolyData> polys,boolean isFloor){
        List<Float> p=new ArrayList<>(),u=new ArrayList<>(),no=new ArrayList<>();
        List<Integer> ix=new ArrayList<>();
        float ny=isFloor?1f:-1f;
        for(PolyData pd:polys){
            float[] xz=pd.xz();float y=pd.y();int n=xz.length/2;if(n<3) continue;
            float cx=0,cz=0; for(int i=0;i<n;i++){cx+=xz[i*2];cz+=xz[i*2+1];}cx/=n;cz/=n;
            int b=p.size()/3;
            p.add(cx);p.add(y);p.add(cz);u.add(cx*(SCALE/TILE_SZ));u.add(-cz*(SCALE/TILE_SZ));no.add(0f);no.add(ny);no.add(0f);
            for(int i=0;i<n;i++){p.add(xz[i*2]);p.add(y);p.add(xz[i*2+1]);u.add(xz[i*2]*(SCALE/TILE_SZ));u.add(-xz[i*2+1]*(SCALE/TILE_SZ));no.add(0f);no.add(ny);no.add(0f);}
            for(int i=0;i<n;i++){int next=(i+1)%n+1;ix.add(b);ix.add(b+i+1);ix.add(b+next);}
        }
        return makeGeo(name,toFA(p),toFA(u),toFA(no),toIA(ix));
    }

    /**
     * Geometrie d'un segment de porte (DYNAMIC - buffers modifiables pour animation).
     *
     * <p>Session 92 (fix 3) : utilise <code>tileWidth</code> et <code>tileHeight</code>
     * (depuis les masks Amiga par-mur) au lieu de <code>texW</code> / <code>texH</code>.
     * Ainsi un mur de porte dont la texture contient plusieurs tiles juxtaposees
     * (ex. hullmetal 256x128 = 2 tiles 128x128) affiche bien UNE seule tile.</p>
     *
     * <p>UV mapping :</p>
     * <ul>
     *   <li>U : uOffset (= fromTile*16/texW) a gauche, uOffset+uM a droite</li>
     *   <li>V : vOffset (= yOffset/tileH) en bas, vOffset+vM en haut</li>
     *   <li>uM = wallLen / tileW (le mur repete la tile si plus long)</li>
     *   <li>vM = wallH / tileH</li>
     * </ul>
     *
     * <p>Le yOffset (VO = (-floor) & 0xFF) decale verticalement la texture
     * pour aligner l'image de porte selon la hauteur de la zone.</p>
     *
     * <p>NOTE : les portes ne sont PAS decoupees en sous-quads (contrairement aux murs)
     * car elles ont besoin d'un quad unique pour l'animation glissante. Si le panneau
     * est plus long que tileWidth, la texture deborde sur les tiles adjacentes du PNG -
     * mais en pratique les portes font wallLen <= tileWidth (= 128).</p>
     */
    private Geometry makeDoorSegGeo(String name,float x0,float z0,float x1,float z1,
                                     float yT,float yB,Material mat,
                                     float texW,float texH,
                                     float tileW,float tileH,
                                     int fromTile,int yOffset){
        float dx=x1-x0,dz=z1-z0,L=Math.max((float)Math.sqrt(dx*dx+dz*dz),1e-5f);
        float nx=-dz/L,nz=dx/L;
        // SESSION 97 fix : x0,x1,z0,z1 sont en unites JME (deja /SCALE).
        // Mais tileW/tileH sont en PIXELS. On reconvertit la longueur en pixels
        // pour calculer le UV correctement (= meme echelle que buildWallGeo).
        float L_pixels  = L * SCALE;
        float wallH_pixels = (yT - yB) * SCALE;
        // uOffset reste calcule sur texW (espace texture complet)
        float uOffset = (texW>0) ? (fromTile*16f/texW) : 0f;
        // V offset : yOffset normalise par tileH (en pixels)
        float vOffset = (tileH>0) ? yOffset / tileH : yOffset / TEX_V;
        // U max : longueur du mur (en pixels) / tileW (en pixels)
        float uM      = (tileW>0) ? L_pixels/tileW : L_pixels/Math.max(texW,1f);
        // Scaler en espace texture pour JME : uM_texture = uM_tile * tileW/texW
        float tileUvWidth = (texW>0) ? tileW/texW : 1f;
        float uM_jme = uM * tileUvWidth;
        // V max
        float vM      = (tileH>0) ? wallH_pixels/tileH : wallH_pixels/TEX_V;
        // UV identique aux murs : BL/BR = V=vOffset (bas), TL/TR = V=vOffset+vM (haut)
        float[] pos={x0,yB,z0, x1,yB,z1, x1,yT,z1, x0,yT,z0};
        float[] uv ={uOffset,vOffset,
                     uOffset+uM_jme,vOffset,
                     uOffset+uM_jme,vOffset+vM,
                     uOffset,vOffset+vM};
        float[] nor={nx,0f,nz, nx,0f,nz, nx,0f,nz, nx,0f,nz};
        // DYNAMIC : le buffer Position sera modifie par DoorControl chaque frame
        Mesh mesh=new Mesh();
        mesh.setBuffer(Type.Position,3,BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Normal,3,BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.Index,3,BufferUtils.createIntBuffer(new int[]{0,1,2,0,2,3}));
        mesh.setDynamic(); // IMPORTANT : permet updateMeshes() dans DoorControl
        mesh.updateBound();
        Geometry g=new Geometry(name,mesh);
        g.setMaterial(mat);
        // Donnees pour DoorControl : permet l'animation de texture qui glisse.
        // JME UserData ne supporte pas float[] - on encode en String CSV.
        g.setUserData("tileH", (float)tileH);
        g.setUserData("uvBase", floatArrayToCsv(uv));
        return g;
    }

    /**
     * Cap horizontal d'un cube de porte (top ou bottom).
     *
     * <p>Quad statique (positions fixees a la creation) en plan XZ. Le bottom
     * cap est anime au runtime via {@code setLocalTranslation} dans
     * {@code DoorControl} - les positions du mesh restent fixes mais le Y du
     * Geometry se decale pour suivre curYBot.</p>
     *
     * <p>UV mapping = coords XZ scaled (style sol/plafond), pour s'aligner
     * naturellement avec le plafond du couloir voisin.</p>
     *
     * @param x0,z0  front-left corner (cote couloir)
     * @param x1,z1  front-right corner
     * @param x2,z2  back-right corner (cote zone-porte)
     * @param x3,z3  back-left corner
     * @param y      hauteur du cap
     * @param mat    materiau (typiquement la texture du plafond)
     * @param normalUp true = normale +Y (top cap), false = normale -Y (bottom cap)
     */
    private Geometry makeCapGeo(String name,
                                 float x0, float z0,
                                 float x1, float z1,
                                 float x2, float z2,
                                 float x3, float z3,
                                 float y, Material mat,
                                 boolean normalUp) {
        float ny = normalUp ? 1f : -1f;
        float[] pos = {
            x0, y, z0,
            x1, y, z1,
            x2, y, z2,
            x3, y, z3
        };
        float uvScale = SCALE / TILE_SZ;
        float[] uv = {
            x0 * uvScale, -z0 * uvScale,
            x1 * uvScale, -z1 * uvScale,
            x2 * uvScale, -z2 * uvScale,
            x3 * uvScale, -z3 * uvScale
        };
        float[] nor = { 0f, ny, 0f, 0f, ny, 0f, 0f, ny, 0f, 0f, ny, 0f };
        // Index : ordre CCW vu depuis la direction de la normale.
        int[] idx = normalUp
            ? new int[]{0, 1, 2, 0, 2, 3}
            : new int[]{0, 2, 1, 0, 3, 2};
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(idx));
        mesh.setStatic();
        mesh.updateBound();
        Geometry g = new Geometry(name, mesh);
        g.setMaterial(mat);
        return g;
    }

    /**
     * Cap polygonal d'une zone-porte (session 106).
     *
     * <p>Quad triangle-fan couvrant tout le polygone XZ d'une zone-porte.
     * Sert de "plafond mobile" partage par tous les pans : au repos il est
     * au sol, pendant l'ouverture il monte en synchro avec les pans, atteint
     * sa position finale (plafond couloir) a state=1.</p>
     *
     * <p>Anime au runtime par {@code DoorControl} via
     * {@code setLocalTranslation(0, effectiveRise, 0)}. Les positions du mesh
     * restent fixes (initiales = au sol).</p>
     *
     * @param xz       polygone XZ (coords successives x0,z0,x1,z1,...)
     * @param y        hauteur initiale (= sol couloir)
     * @param mat      materiau (texture du plafond)
     * @param normalUp true = normale +Y (visible d'en haut), false = normale -Y
     */
    private Geometry makePolyCapGeo(String name, float[] xz, float y, Material mat, boolean normalUp) {
        int n = xz.length / 2;
        if (n < 3) return null;

        // Centroide pour le triangle fan
        float cx = 0, cz = 0;
        for (int i = 0; i < n; i++) { cx += xz[i*2]; cz += xz[i*2+1]; }
        cx /= n; cz /= n;

        int numVerts = n + 1;
        float[] pos = new float[numVerts * 3];
        float[] uv  = new float[numVerts * 2];
        float[] nor = new float[numVerts * 3];
        int[]   idx = new int[n * 3];

        float ny = normalUp ? 1f : -1f;
        float uvScale = SCALE / TILE_SZ;

        // Vertex 0 = centroide
        pos[0]=cx; pos[1]=y; pos[2]=cz;
        uv[0]=cx*uvScale; uv[1]=-cz*uvScale;
        nor[0]=0; nor[1]=ny; nor[2]=0;

        // Vertices 1..N = bord
        for (int i = 0; i < n; i++) {
            int vi = (i+1) * 3;
            pos[vi]   = xz[i*2];
            pos[vi+1] = y;
            pos[vi+2] = xz[i*2+1];
            uv[(i+1)*2]   = xz[i*2] * uvScale;
            uv[(i+1)*2+1] = -xz[i*2+1] * uvScale;
            nor[vi]=0; nor[vi+1]=ny; nor[vi+2]=0;
        }

        // Triangles fan (CCW pour normalUp, CW pour normalDown)
        for (int i = 0; i < n; i++) {
            int next = (i+1) % n + 1;
            if (normalUp) {
                idx[i*3]   = 0;
                idx[i*3+1] = i+1;
                idx[i*3+2] = next;
            } else {
                idx[i*3]   = 0;
                idx[i*3+1] = next;
                idx[i*3+2] = i+1;
            }
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Normal,   3, BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.Index,    3, BufferUtils.createIntBuffer(idx));
        mesh.setStatic();
        mesh.updateBound();
        Geometry g = new Geometry(name, mesh);
        g.setMaterial(mat);
        return g;
    }

    /** Encode un float[] en CSV pour stockage dans UserData (JME ne supporte pas float[]). */
    static String floatArrayToCsv(float[] arr) {
        StringBuilder sb = new StringBuilder(arr.length * 12);
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    /** Decode un CSV de floats - utilise par DoorControl/LiftControl pour relire les UserData. */
    public static float[] csvToFloatArray(String csv) {
        if (csv == null || csv.isEmpty()) return new float[0];
        String[] parts = csv.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Float.parseFloat(parts[i].trim()); }
            catch (NumberFormatException e) { result[i] = 0f; }
        }
        return result;
    }

    private Geometry makeGeo(String name,float[] pos,float[] uv,float[] nor,int[] idx){
        Mesh mesh=new Mesh();
        mesh.setBuffer(Type.Position,3,BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Normal,3,BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.Index,3,BufferUtils.createIntBuffer(idx));
        mesh.setStatic();mesh.updateBound();
        Geometry g=new Geometry(name,mesh);
        g.setShadowMode(RenderQueue.ShadowMode.Receive);
        return g;
    }

    // ── Constantes de rendu sprites ──────────────────────────────────────────
    /** Hauteur JME d'un sprite si colHeight=0 (unites Amiga -> JME). */
    private static final float DEFAULT_SPRITE_H = 1.5f;
    /** Dossier sprites generes par convertWads. */
    private static final String SPRITES_DIR  = "Textures/objects/";
    /** Dossier modeles vectobj generes par convertVectObj. */
    private static final String VECTOBJ_DIR  = "Scenes/vectobj/";

    /**
     * Table GLFT_VectorNames_l dans l'ordre exact du LNK file (depuis LevelED.txt).
     * Index = polyModelIndex (WORD +8 dans le binaire objet).
     * Ce tableau est valide pour les 22 entrees non-vides (les 8 restantes sont vides).
     */
    private static final String[] VECTOR_NAMES_TABLE = {
        "generator",       //  0
        "switch",          //  1
        "passkey",         //  2
        "blaster",         //  3
        "shotgun",         //  4
        "plink",           //  5
        "plasmagun",       //  6
        "glarebox",        //  7
        "ventfan",         //  8
        "snake",           //  9
        "rifle",           // 10
        "passkey",         // 11 (alias de 2)
        "grenadelauncher", // 12
        "wasp",            // 13
        "mantis",          // 14
        "crab",            // 15
        "passkey",         // 16 (alias de 2)
        "rocketlauncher",  // 17
        "scenery",         // 18
        "laser",           // 19
        "jetpack",         // 20
        "charger",         // 21
    };

    /**
     * Mapping nom d'objet (definitions.json) -> fichier vectobj.
     * Utilise UNIQUEMENT en fallback si polyModelIndex n'est pas disponible.
     */
    private static final Map<String,String> OBJECT_NAME_TO_VECTOBJ = Map.ofEntries(
        Map.entry("Computer",          "computer"),
        Map.entry("Plasmagun",         "plasmagun"),
        Map.entry("Ventfan",           "ventfan"),
        Map.entry("Shotgun",           "shotgun"),
        Map.entry("Generator",         "generator"),
        Map.entry("Passkey",           "passkey"),
        Map.entry("Assault Rifle",     "rifle"),
        Map.entry("KnifeSwitch",       "switch"),
        Map.entry("Grenade Launcher",  "grenadelauncher"),
        Map.entry("Jetpack",           "jetpack"),
        Map.entry("Charger",           "charger"),
        Map.entry("Blaster",           "blaster"),
        Map.entry("Rocket Launcher",   "rocketlauncher"),
        Map.entry("Bounce Lazer",      "laser"),
        Map.entry("Indicator",         "scenery"),
        Map.entry("glarebox",          "glarebox"),
        Map.entry("Lamp",              "glarebox")
    );

    /**
     * Mapping nom d'alien (definitions.json) -> fichier vectobj.
     * Pour les aliens avec gfxType=1 (modeles polygonaux).
     */
    private static final Map<String,String> ALIEN_NAME_TO_VECTOBJ = Map.of(
        "SnakeScanner",  "snake",
        "Wasp Boss",     "wasp",
        "Mantis Boss",   "mantis",
        "Crab Boss",     "crab"
    );

    /** Noms des fichiers WAD pour aliens selon gfxType (0-4). */
    // Mapping gfxType alien -> nom WAD (depuis media/includes et media/hqn)
    // gfxType 0 = alien2  (alien classique : Red Alien)
    // gfxType 1 = worm    (snake/wasp/crab)
    // gfxType 2 = robotright (droid/priest/big insect)
    // gfxType 3 = guard   (guard/'ard guard/triclaw/insect boss)
    // gfxType 4 = insect  (insect alien/ashnarg/well ard guard)
    private static final String[] ALIEN_WAD_BY_GFXTYPE = {
        "alien2",      // 0: Red Alien, standard
        "worm",        // 1: SnakeScanner, Wasp Boss, Mantis Boss, Crab Boss
        "robotright",  // 2: Droid, AlienPriest, BigInsect, Tough Triclaw
        "guard",       // 3: Guard, 'Ard Guard, Triclaw, Insect Boss, Player
        "insect",      // 4: well ard guard, Ashnarg, Insectalien, Player2
    };

    /**
     * Place les sprites billboard pour chaque objet/alien du niveau.
     *
     * Sprite pipeline :
     *  1. Chercher assets/Textures/objects/{wadName}/{wadName}_f0.png (converti par convertWads)
     *  2. Si trouve : billboard JME (Quad + BillboardControl, matiere Unshaded + alpha)
     *  3. Si absent : cube colore (fallback lisible en attendant)
     *
     * Conversion coordonnees (identique murs/zones) :
     *   jme.x =  worldX / 32
     *   jme.z = -worldZ / 32   (flip !)
     *   jme.y = -floorH / 32 + spriteHalfH  (centre du sprite au-dessus du sol)
     */
    private void addItems(String json, Node itemsNode, Material fb,
                          ObjDef[] objDefs, AlienDef[] alienDefs) {
        // Couleurs fallback (cubes colores si sprite absent)
        ColorRGBA[] cols = {
            new ColorRGBA(1f,  0.15f,0.15f,1f), // 0: ALIEN       -> rouge
            new ColorRGBA(0.1f,0.9f, 0.1f, 1f), // 1: COLLECTABLE -> vert
            new ColorRGBA(1f,  1f,   0.1f, 1f), // 2: ACTIVATABLE -> jaune
            new ColorRGBA(1f,  0.5f, 0.0f, 1f), // 3: DESTRUCTABLE-> orange
            new ColorRGBA(0.5f,0.5f, 0.5f, 1f), // 4: DECORATION  -> gris
            new ColorRGBA(0.3f,0.6f, 1f,   1f), // 5: PLAYER      -> bleu ciel
            new ColorRGBA(0.8f,0.3f, 0.8f, 1f), // 6: inconnu     -> violet
        };
        Material[] fallbackMats = new Material[cols.length];
        for (int i = 0; i < cols.length; i++) {
            fallbackMats[i] = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            fallbackMats[i].setColor("Color", cols[i]);
        }

        List<String> objs = elems(json, "objects");
        int countAlien=0, countObj=0, countPlr=0, countSprite=0;
        for (String s : objs) {
            JsonObj o = parseObj(s);
            int x          = o.i("x");
            int z          = o.i("z");
            int y          = o.i("y");      // zone.floorH
            int typeId     = o.i("typeId");
            int defIndex   = o.i("defIndex");
            int startAnim  = o.i("startAnim");
            int angle      = o.i("angle");
            int hitPoints  = o.i("hitPoints");
            int teamNumber = o.i("teamNumber");
            int doorLocks  = o.i("doorLocks");
            int liftLocks  = o.i("liftLocks");
            int zoneId     = o.i("zoneId");

            float jx = x / SCALE;
            float jz = -z / SCALE;   // flip Z

            // Determiner nom WAD + behaviour + infos def
            String wadName   = null;
            int    behaviour = -1;
            String defName   = "?";
            int    colHeight = 0;   // hauteur collision (unites Amiga)
            int    gfxType   = 0;
            int    defFrame  = 0;   // session 111 : sprite frame index dans le WAD
            boolean isGlare  = false;
            boolean isCeiling= false;

            if (typeId == 1 && objDefs != null && defIndex < objDefs.length) {
                ObjDef def = objDefs[defIndex];
                behaviour = def.behaviour();
                defName   = def.name();
                colHeight = def.colHeight();
                gfxType   = def.gfxType();
                wadName   = def.wadName().isEmpty() ? null : def.wadName().toLowerCase();
                defFrame  = def.defFrame();   // session 111
                isGlare   = (gfxType == 2); // GLARE
                isCeiling = (def.floorCeiling() == 1);
            } else if (typeId == 0 && alienDefs != null && defIndex < alienDefs.length) {
                AlienDef adef = alienDefs[defIndex];
                defName  = adef.name();
                gfxType  = adef.gfxType();
                colHeight = adef.height();
                // Mapping gfxType -> WAD file pour aliens
                if (gfxType >= 0 && gfxType < ALIEN_WAD_BY_GFXTYPE.length)
                    wadName = ALIEN_WAD_BY_GFXTYPE[gfxType];
                else
                    wadName = "alien2";
            } else if (typeId == 4 || typeId == 5) {
                defName = typeId == 4 ? "Player1" : "Player2";
                wadName = "guard"; // sprite joueur de remplacement
            }

            // Hauteur sprite en unites JME
            float spriteH = (colHeight > 0) ? colHeight / SCALE : DEFAULT_SPRITE_H;
            // Sprites glare et decorations plafond sont plus petits
            if (isGlare)   spriteH = Math.min(spriteH, 0.5f);
            if (isCeiling) spriteH = Math.min(spriteH, 0.8f);

            // Position Y : sol zone + demi-hauteur sprite
            float jy = -y / SCALE + spriteH * 0.5f;
            // Objets plafond : accroches en haut
            if (isCeiling) {
                float jyRoof = jy + 2.0f; // approximation plafond
                jy = jyRoof - spriteH * 0.5f;
            }

            // Couleur fallback
            int matIdx = switch (typeId) {
                case 0     -> 0; // ALIEN
                case 1     -> switch (behaviour) {
                    case 0 -> 1;  // COLLECTABLE
                    case 1 -> 2;  // ACTIVATABLE
                    case 2 -> 3;  // DESTRUCTABLE
                    case 3 -> 4;  // DECORATION
                    default -> 6;
                };
                case 4, 5  -> 5; // PLAYER
                default    -> 6;
            };

            // ── Nom du modele vectobj ──────────────────────────────────────────────
            // Priorite 1 : polyModelIndex direct depuis le binaire (WORD +8, marqueur 0xFF +6)
            // Priorite 2 : fallback par nom si gfxType=1 dans definitions.json
            String vectName = null;
            boolean isPolygon = o.i("isPolygon") != 0;
            int polyModelIndex = o.i("polyModelIndex");

            if (isPolygon && polyModelIndex >= 0 && polyModelIndex < VECTOR_NAMES_TABLE.length) {
                vectName = VECTOR_NAMES_TABLE[polyModelIndex];
            } else if (typeId == 1 && gfxType == 1) {
                vectName = OBJECT_NAME_TO_VECTOBJ.get(defName); // fallback par nom
            } else if (typeId == 0 && gfxType == 1) {
                vectName = ALIEN_NAME_TO_VECTOBJ.get(defName);  // fallback aliens
            }

            String nodeName = typeId + "/" + defName + "/z" + zoneId;
            Node spriteNode = new Node(nodeName);
            spriteNode.setLocalTranslation(jx, jy, jz);

            // 1. Essayer modele vectobj (gfxType=1)
            // 2. Essayer sprite bitmap (gfxType=0) avec defFrame (session 111)
            // 3. Fallback cube colore
            Node vectNode  = tryLoadVectObj(vectName, spriteH);
            Geometry geom  = (vectNode == null) ? tryLoadSprite(wadName, defFrame, spriteH, nodeName) : null;

            if (vectNode != null) {
                spriteNode.attachChild(vectNode);
                countSprite++;
            } else if (geom != null) {
                spriteNode.attachChild(geom);
                // BillboardControl : le sprite tourne toujours face a la camera
                com.jme3.scene.control.BillboardControl bc =
                    new com.jme3.scene.control.BillboardControl();
                bc.setAlignment(com.jme3.scene.control.BillboardControl.Alignment.Camera);
                spriteNode.addControl(bc);
                countSprite++;
            } else {
                // Fallback : cube colore
                float sz = (typeId == 0) ? 0.3f : 0.2f;
                com.jme3.scene.shape.Box box = new com.jme3.scene.shape.Box(sz, sz, sz);
                Geometry boxGeom = new Geometry(nodeName, box);
                boxGeom.setMaterial(fallbackMats[matIdx]);
                spriteNode.attachChild(boxGeom);
            }

            // UserData pour le runtime
            spriteNode.setUserData("typeId",     typeId);
            spriteNode.setUserData("defIndex",   defIndex);
            spriteNode.setUserData("defName",    defName);
            spriteNode.setUserData("behaviour",  behaviour);
            spriteNode.setUserData("startAnim",  startAnim);
            spriteNode.setUserData("angle",      angle);
            spriteNode.setUserData("hitPoints",  hitPoints);
            spriteNode.setUserData("teamNumber", teamNumber);
            spriteNode.setUserData("doorLocks",  doorLocks);
            spriteNode.setUserData("liftLocks",  liftLocks);
            spriteNode.setUserData("zoneId",     zoneId);
            spriteNode.setUserData("worldX",     x);
            spriteNode.setUserData("worldZ",     z);
            spriteNode.setUserData("worldY",     y);
            spriteNode.setUserData("wadName",    wadName != null ? wadName : "");
            // Session 112 : exposer colRadius et colHeight (unites Amiga) pour
            // permettre au PickupSystem de tester la collision joueur<->item.
            spriteNode.setUserData("colRadius",  colHeight > 0 ? 0 : 0); // sera ecrase ci-dessous
            // Recuperer colRadius depuis la def (typeId=1 obj) ou laisser 0 sinon
            if (typeId == 1 && objDefs != null && defIndex < objDefs.length) {
                spriteNode.setUserData("colRadius", (int) objDefs[defIndex].colRadius());
                spriteNode.setUserData("colHeight", (int) objDefs[defIndex].colHeight());
            } else {
                spriteNode.setUserData("colRadius", 0);
                spriteNode.setUserData("colHeight", 0);
            }

            itemsNode.attachChild(spriteNode);
            if      (typeId == 0) countAlien++;
            else if (typeId == 1) countObj++;
            else                  countPlr++;
        }
        System.out.printf("  [items] aliens:%d objets:%d joueurs:%d  sprites:%d fallback:%d%n",
            countAlien, countObj, countPlr, countSprite,
            countAlien+countObj+countPlr-countSprite);
    }

    /**
     * Charge le sprite PNG d'un objet comme Quad texture + alpha.
     * Retourne null si le sprite n'existe pas (fallback cube colore).
     *
     * <p>Le PNG est cherche dans :
     *   {@code assets/Textures/objects/{wadName}/{wadName}_f{defFrame}.png}.
     *   Avec fallback sur {@code _f0.png} si absent.</p>
     *
     * <p>Session 111 : ajout du parametre {@code defFrame}. Avant on chargeait
     * toujours {@code _f0.png}, ce qui donnait la mauvaise image quand un WAD
     * etait partage entre plusieurs objets (ex. PLasmaclip dont le WAD
     * &quot;KEYS&quot; est aussi celui de la Passkey, mais a une frame differente).
     * La valeur {@code defFrame} provient de
     * {@code GLFT_ObjectDefAnims_l[defIdx][frame=0][byte=0]}, exposee dans
     * {@code definitions.json} via {@link LnkParser#getObjectDefaultFrameIndex}.</p>
     *
     * <p>Pour les aliens et les players (typeId != 1), {@code defFrame=0} (= comportement
     * legacy) car AlienDef n'a pas de table d'anim equivalente cote Java pour le moment.</p>
     *
     * <p>Le Quad est centre a l'origine du noeud parent.
     * Le ratio largeur/hauteur est conserve depuis l'image.</p>
     *
     * <p>Session 92 : materiau Lighting.j3md (avant : Unshaded). Les sprites
     * reagissent maintenant au lighting JME. On garde <code>FaceCullMode.Off</code>
     * + normales orientees vers +Z (face a la camera par defaut) qui combine avec
     * le <code>BillboardControl</code> fait tourner le quad vers la camera.</p>
     */
    private Geometry tryLoadSprite(String wadName, int defFrame, float targetHeight, String geoName) {
        if (wadName == null || wadName.isEmpty()) return null;
        // Session 111 : tenter d'abord _f<defFrame>.png, puis _f0.png en fallback.
        // Defaut explicite (defFrame<=0) : on saute direct au _f0.png.
        int[] framesToTry = (defFrame > 0) ? new int[]{defFrame, 0} : new int[]{0};
        // Essayer minuscules d'abord (sortie WadConverter), puis majuscules (anciens dossiers)
        String[] names = { wadName.toLowerCase(), wadName.toUpperCase() };
        for (String name : names) {
            for (int frame : framesToTry) {
                String path = SPRITES_DIR + name + "/" + name + "_f" + frame + ".png";
                try {
                    com.jme3.texture.Texture tex = am.loadTexture(path);
                    if (tex == null) continue;
                    int imgW = tex.getImage().getWidth();
                    int imgH = tex.getImage().getHeight();
                    float ratio = (imgH > 0 && imgW > 0) ? (float)imgW / imgH : 1f;
                    float spriteW = targetHeight * ratio;
                    float spriteH = targetHeight;

                    com.jme3.scene.shape.Quad quad = new com.jme3.scene.shape.Quad(spriteW, spriteH);
                    Geometry g = new Geometry(geoName + "_sprite", quad);

                    // Session 92 : Lighting.j3md au lieu de Unshaded, pour que le
                    // sprite reagisse a l'eclairage de la scene (ambient +
                    // pointlight + headlight).
                    Material mat = new Material(am, "Common/MatDefs/Light/Lighting.j3md");
                    tex.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
                    tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
                    tex.setMinFilter(com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
                    mat.setTexture("DiffuseMap", tex);
                    mat.setBoolean("UseMaterialColors", true);
                    mat.setColor("Ambient", new ColorRGBA(0.6f, 0.6f, 0.6f, 1f));  // sprites legerement eclaires
                    mat.setColor("Diffuse", ColorRGBA.White);
                    mat.setFloat("AlphaDiscardThreshold", 0.5f);
                    mat.getAdditionalRenderState().setBlendMode(
                        com.jme3.material.RenderState.BlendMode.Alpha);
                    mat.getAdditionalRenderState().setDepthWrite(false);
                    // FaceCullMode.Off car le BillboardControl peut orienter le quad
                    // soit recto soit verso selon l'angle de la camera.
                    mat.getAdditionalRenderState().setFaceCullMode(
                        com.jme3.material.RenderState.FaceCullMode.Off);
                    g.setMaterial(mat);
                    g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
                    g.setLocalTranslation(-spriteW * 0.5f, -spriteH * 0.5f, 0f);
                    return g;  // succes
                } catch (Exception e) {
                    // Frame absente : on essaie la suivante (fallback _f0). On ne logge
                    // que si la derniere tentative echoue (path matchera names[last] + _f0).
                    if (frame == 0 && name.equals(names[names.length - 1])) {
                        System.out.printf("  SPRITE ERR [%s]: %s%n", path, e.getMessage());
                    }
                }
            }
        }
        return null;
    }

    /**
     * Charge un modele vectobj (.j3o) genere par convertVectObj.
     * Retourne un Node ou null si absent.
     *
     * Scale : les modeles vectobj utilisent des coordonnees en unites SBP (/256 de l'Amiga).
     * La taille typique d'un objet est 1-4 unites JME apres le scale du convertisseur.
     * On applique un scale supplementaire pour que les objets aient une taille raisonnable.
     */
    private Node tryLoadVectObj(String vectName, float targetH) {
        if (vectName == null || vectName.isEmpty()) return null;
        String path = VECTOBJ_DIR + vectName.toLowerCase() + ".j3o";
        try {
            Spatial model = am.loadModel(path);
            if (model == null) return null;

            // Scale pour que le modele ait la hauteur cible
            // Les modeles SBP ont des coordonnees en unites /256, et le convertisseur
            // utilise SCALE=128, donc 1 unite JME = 256/128 = 2 Amiga-units/128 = ~0.016m
            // targetH est en unites JME (colHeight/32). On veut le modele a cette taille.
            BoundingBox bb = (BoundingBox)
                model.getWorldBound();
            if (bb != null) {
                float modelH = bb.getYExtent() * 2.0f;
                if (modelH > 0.001f) {
                    float s = (targetH > 0 ? targetH : DEFAULT_SPRITE_H) / modelH;
                    model.setLocalScale(s);
                }
            }

            // Centrer le modele : base au sol
            if (bb != null) {
                float cy = bb.getCenter().y * model.getLocalScale().y;
                float ext = bb.getYExtent() * model.getLocalScale().y;
                model.setLocalTranslation(0f, -cy + ext - targetH * 0.5f, 0f);
            }

            Node wrapper = new Node("vectobj_" + vectName);
            wrapper.attachChild(model);
            return wrapper;
        } catch (Exception e) {
            // Modele absent = fallback cube
            return null;
        }
    }
    /**
     * Pour chaque porte (zoneId), extrait le texIndex et yOffset du PREMIER
     * ZDoorWall. Ces valeurs proviennent de zdw_GraphicsOffset :
     *   texIndex = (gfxOfs >> 16) & 0xFFFF
     *   yOffset  = gfxOfs & 0xFF
     * Voir zone_liftable.h.
     *
     * Retourne zoneId -> [texIndex, yOffset]
     */
    private Map<Integer,int[]> parseDoorFirstGfx(String json) {
        Map<Integer,int[]> m = new LinkedHashMap<>();
        List<String> doorElems = elems(json, "doors");
        for (String s : doorElems) {
            JsonObj d = parseObj(s);
            int zoneId = d.i("zoneId");
            if (zoneId < 0) continue;
            int wi = s.indexOf("\"walls\"");
            if (wi < 0) continue;
            int ab = s.indexOf('[', wi);
            int ae = mbr(s, ab, '[', ']');
            if (ab < 0 || ae < 0) continue;
            String wallsJson = s.substring(ab + 1, ae);
            List<String> wallElems = new JsonArr(wallsJson).elems;
            if (wallElems.isEmpty()) continue;
            // Prendre le premier ZDoorWall
            JsonObj w = parseObj(wallElems.get(0));
            int gfxOfs = w.i("gfxOfs");
            int texIndex = (gfxOfs >>> 16) & 0xFFFF;
            int yOffset  = gfxOfs & 0xFF;
            m.put(zoneId, new int[]{texIndex, yOffset});
        }
        return m;
    }

    /**
     * Parse les definitions de portes depuis le JSON.
     * Retourne une map zoneId -> [bottom, top, openSpeed, closeSpeed, openDuration, raiseCondition, lowerCondition, ...sfx]
     * Les hauteurs bottom/top sont en unites editeur (valeur brute).
     */
    private Map<Integer,int[]> parseDoorDefs(String json) {
        Map<Integer,int[]> map = new LinkedHashMap<>();
        List<String> doorElems = elems(json, "doors");
        for (String s : doorElems) {
            JsonObj d = parseObj(s);
            int zoneId  = d.i("zoneId");
            int bottom  = d.i("bottom");
            int top     = d.i("top");
            int openSpd = d.i("openSpeed");
            int clsSpd  = d.i("closeSpeed");
            int dur     = d.i("openDuration");
            int raiseCond = d.i("raiseCondition");
            int lowerCond = d.i("lowerCondition");
            if (zoneId >= 0)
                map.put(zoneId, new int[]{bottom, top, openSpd, clsSpd, dur, raiseCond, lowerCond,
                                          d.i("openSfx"), d.i("closeSfx"),
                                          d.i("openedSfx"), d.i("closedSfx")});
        }
        System.out.printf("  [doors] %d zones-portes parsees%n", map.size());
        return map;
    }

    /**
     * Set des edgeIds qui sont des LIFT WALLS (session 99/100).
     *
     * <p>Parcourt le tableau {@code lifts[].walls[]} du JSON et collecte tous
     * les {@code edgeId} declares. Ces edges correspondent aux ouvertures
     * d'entree/sortie du lift (passages couloir <-> cabine). Dans l'ASM original,
     * ces walls sont rendus dynamiquement par {@code LiftRoutine} : visibles
     * quand le lift est a leur niveau, invisibles sinon.</p>
     *
     * <p>Cas du level A zone 104 : edges 500, 510 (les 2 portes de la cabine).</p>
     *
     * @return ensemble des edgeIds, vide si aucun lift n'a de walls declares
     */
    private Set<Integer> parseLiftWallEdges(String json) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String s : elems(json, "lifts")) {
            int wi = s.indexOf("\"walls\"");
            if (wi < 0) continue;
            int ab = s.indexOf('[', wi);
            int ae = mbr(s, ab, '[', ']');
            if (ab < 0 || ae < 0) continue;
            String wallsJson = s.substring(ab + 1, ae);
            for (String ws : new JsonArr(wallsJson).elems) {
                JsonObj w = parseObj(ws);
                int eid = w.i("edgeId");
                if (eid > 0) result.add(eid);
            }
        }
        return result;
    }

    /**
     * Parse les definitions de lifts depuis le JSON (session 92 fix 4).
     * Format identique a parseDoorDefs : zoneId -> [bottom, top, openSpeed,
     * closeSpeed, openDuration, raiseCondition, lowerCondition, ...sfx].
     *
     * <p>Difference semantique avec les portes : pour un lift, c'est le SOL
     * qui monte entre {@code bottom} et {@code top} (pas le plafond).</p>
     */
    private Map<Integer,int[]> parseLiftDefs(String json) {
        Map<Integer,int[]> map = new LinkedHashMap<>();
        List<String> liftElems = elems(json, "lifts");
        for (String s : liftElems) {
            JsonObj d = parseObj(s);
            int zoneId  = d.i("zoneId");
            int bottom  = d.i("bottom");
            int top     = d.i("top");
            int openSpd = d.i("openSpeed");
            int clsSpd  = d.i("closeSpeed");
            int dur     = d.i("openDuration");
            int raiseCond = d.i("raiseCondition");
            int lowerCond = d.i("lowerCondition");
            if (zoneId >= 0)
                map.put(zoneId, new int[]{bottom, top, openSpd, clsSpd, dur, raiseCond, lowerCond,
                                          d.i("openSfx"), d.i("closeSfx"),
                                          d.i("openedSfx"), d.i("closedSfx")});
        }
        if (!map.isEmpty())
            System.out.printf("  [lifts] %d zones-lifts parsees%n", map.size());
        return map;
    }

    /**
     * Mapping zoneId-porte -> Set des edgeIds qui sont des ZDoorWalls.
     * Utilise pour identifier rapidement quels walls de couloir sont des portes.
     */
    private Map<Integer,Set<Integer>> parseDoorZoneEdges(String json) {
        Map<Integer,Set<Integer>> m = new LinkedHashMap<>();
        List<String> doorElems = elems(json, "doors");
        for (String s : doorElems) {
            JsonObj d = parseObj(s);
            int zoneId = d.i("zoneId");
            if (zoneId < 0) continue;
            int wi = s.indexOf("\"walls\"");
            if (wi < 0) continue;
            int ab = s.indexOf('[', wi);
            int ae = mbr(s, ab, '[', ']');
            if (ab < 0 || ae < 0) continue;
            String wallsJson = s.substring(ab + 1, ae);
            Set<Integer> edges = new LinkedHashSet<>();
            for (String ws : new JsonArr(wallsJson).elems) {
                JsonObj w = parseObj(ws);
                edges.add(w.i("edgeId"));
            }
            m.put(zoneId, edges);
        }
        return m;
    }

    /**
     * Parse la sous-liste walls de chaque porte (edgeId -> gfxOfs).
     * Le gfxOfs encode, comme pour les murs : HIGH SHORT = texIndex, LOW BYTE = yOffset.
     * Voir zone_liftable.h ZDoorWall.zdw_GraphicsOffset.
     */
    private Map<Integer,Integer> parseDoorWallGfx(String json) {
        Map<Integer,Integer> m = new LinkedHashMap<>();
        List<String> doorElems = elems(json, "doors");
        for (String s : doorElems) {
            // Recuperer le tableau walls dans la porte
            int wi = s.indexOf("\"walls\"");
            if (wi < 0) continue;
            int ab = s.indexOf('[', wi);
            int ae = mbr(s, ab, '[', ']');
            if (ab < 0 || ae < 0) continue;
            String wallsJson = s.substring(ab + 1, ae);
            for (String ws : new JsonArr(wallsJson).elems) {
                JsonObj w = parseObj(ws);
                int edgeId = w.i("edgeId");
                int gfxOfs = w.i("gfxOfs");
                // Conserver si entree inexistante, sinon garder la premiere
                m.putIfAbsent(edgeId, gfxOfs);
            }
        }
        return m;
    }

    // Spawns
    private void addSpawns(Node root,String json,Node spawnNode){
        String p1s=extractField(json,"player1"),p2s=extractField(json,"player2");
        if(p1s!=null){addSpawn(spawnNode,"player1",p1s);JsonObj p1=parseObj(p1s);root.setUserData("p1X",(float)(p1.i("worldX")/SCALE));root.setUserData("p1Z",(float)(-p1.i("worldZ")/SCALE));root.setUserData("p1Zone",(int)p1.i("zoneId"));}
        if(p2s!=null) addSpawn(spawnNode,"player2",p2s);
    }
    private void addSpawn(Node parent,String name,String obj){
        JsonObj p=parseObj(obj);Node n=new Node(name);
        n.setLocalTranslation(p.i("worldX")/SCALE,2f,-p.i("worldZ")/SCALE);
        n.setUserData("worldX",(float)p.i("worldX"));n.setUserData("worldZ",(float)p.i("worldZ"));n.setUserData("zoneId",(int)p.i("zoneId"));
        parent.attachChild(n);
    }
    private float[] zoneCentroid(int[] eids,Map<Integer,int[]> edges,float y){
        float cx=0,cz=0;int n=0;
        for(int eid:eids){int[] e=edges.get(eid);if(e==null)continue;cx+=e[0]/SCALE;cz+=-e[1]/SCALE;n++;}
        return n==0?new float[]{0,y,0}:new float[]{cx/n,y,cz/n};
    }

    // Materiaux
    private Material[] loadWallMats(){
        Material[] m=new Material[AssetConverter.NUM_WALL_TEX];
        for(int i=0;i<m.length;i++){
            String path=String.format("Textures/walls/wall_%02d_%s.png",i,AssetConverter.WALL_NAMES[i]);
            m[i]=texMat(path);
            // Lire les dimensions reelles depuis l'image JME chargee
            wallTexWidths[i]  = 256;  // defaut
            wallTexHeights[i] = 128;  // defaut (TEX_V legacy)
            if(m[i]!=null){
                try{
                    var tp=m[i].getTextureParam("DiffuseMap");
                    if(tp!=null&&tp.getTextureValue()!=null){
                        int w=tp.getTextureValue().getImage().getWidth();
                        int h=tp.getTextureValue().getImage().getHeight();
                        if(w>0) wallTexWidths[i]  = w;
                        if(h>0) wallTexHeights[i] = h;
                    }
                }catch(Exception ignored){}
            }
        }
        return m;
    }
    private Material[] loadFloorMats(){
        Material[] m=new Material[AssetConverter.NUM_FLOOR_TEX];
        for(int i=0;i<m.length;i++) m[i]=texMat(String.format("Textures/floors/floor_%02d.png",i+1));
        return m;
    }

    /**
     * Materiau Lighting.j3md texture.
     * FaceCullMode.Off - visible des deux cotes.
     * Ambient 0.5 - zones sombres partiellement visibles.
     */
    private Material texMat(String path){
        try{
            Texture t=am.loadTexture(path);
            t.setWrap(Texture.WrapMode.Repeat);
            t.setMagFilter(Texture.MagFilter.Nearest);
            t.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            Material mat=new Material(am,"Common/MatDefs/Light/Lighting.j3md");
            mat.setTexture("DiffuseMap",t);
            mat.setBoolean("UseMaterialColors", true);  // obligatoire pour que Ambient/Diffuse soient pris en compte
            mat.setColor("Ambient",new ColorRGBA(0.5f,0.5f,0.5f,1f));
            mat.setColor("Diffuse",ColorRGBA.White);
            mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            return mat;
        }catch(Exception e){
            System.out.printf("  WARN texture introuvable : %s%n",path);
            return null;
        }
    }

    private Material makeFallback(){
        Material m=new Material(am,"Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color",new ColorRGBA(1f,0f,1f,1f));
        m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return m;
    }

    // JSON parser
    private Map<Integer,int[]> parsePts(String json){Map<Integer,int[]> m=new LinkedHashMap<>();for(String s:elems(json,"points")){JsonObj p=parseObj(s);m.put(p.i("id"),new int[]{p.i("x"),p.i("z")});}return m;}
    private Map<Integer,int[]> parseEdges(String json){Map<Integer,int[]> m=new LinkedHashMap<>();for(String s:elems(json,"edges")){JsonObj e=parseObj(s);m.put(e.i("id"),new int[]{e.i("x"),e.i("z")});}return m;}

    /**
     * Parse les edges complets avec pos+len : id -> [x, z, dx, dz].
     * pos = (x,z) : point de depart de l'edge
     * len = (dx,dz) : vecteur direction vers le point d'arrivee
     * Donc l'edge va de (x,z) a (x+dx, z+dz).
     */
    private Map<Integer,int[]> parseEdgesFull(String json) {
        Map<Integer,int[]> m = new LinkedHashMap<>();
        for (String s : elems(json, "edges")) {
            JsonObj e = parseObj(s);
            m.put(e.i("id"), new int[]{e.i("x"), e.i("z"), e.i("dx"), e.i("dz")});
        }
        return m;
    }

    /**
     * Recupere le joinZone d'un edge donne (parcours direct du JSON).
     * Utilise une seule fois pour la construction du cube de porte (session 96).
     * Retourne -1 si non trouve ou si l'edge a joinZone=-1.
     */
    private int parseEdgeJoinZone(String json, int targetEid) {
        for (String s : elems(json, "edges")) {
            JsonObj e = parseObj(s);
            if (e.i("id") == targetEid) {
                return e.i("joinZone");
            }
        }
        return -1;
    }

    /**
     * Retrouve l'edgeId correspondant a un segment (leftPt -> rightPt).
     * Pour chaque edge de la liste zoneEdges, teste si l'edge va de leftPt a rightPt
     * OU de rightPt a leftPt (edge partage entre 2 zones, sens oppose).
     *
     * Tolerance coordonnees : 2 unites editeur (les points sont souvent snappes sur grille).
     * Retourne -1 si aucun edge ne correspond.
     */
    private int findEdgeIdForSegment(int leftPtId, int rightPtId,
                                      Map<Integer,int[]> pts,
                                      Map<Integer,int[]> edgesFull,
                                      int[] zoneEdgeIds) {
        int[] L = pts.get(leftPtId);
        int[] R = pts.get(rightPtId);
        if (L == null || R == null) return -1;
        for (int eid : zoneEdgeIds) {
            int[] e = edgesFull.get(eid);
            if (e == null) continue;
            int ex0 = e[0], ez0 = e[1];
            int ex1 = e[0] + e[2], ez1 = e[1] + e[3];
            // Cas 1 : edge oriente leftPt -> rightPt
            if (near(ex0, ez0, L[0], L[1]) && near(ex1, ez1, R[0], R[1])) return eid;
            // Cas 2 : edge oriente rightPt -> leftPt (inversion possible)
            if (near(ex0, ez0, R[0], R[1]) && near(ex1, ez1, L[0], L[1])) return eid;
        }
        return -1;
    }

    private static boolean near(int x1, int z1, int x2, int z2) {
        return Math.abs(x1 - x2) <= 2 && Math.abs(z1 - z2) <= 2;
    }
    private Map<Integer,ZD> parseZones(String json){Map<Integer,ZD> m=new LinkedHashMap<>();for(String s:elems(json,"zones")){if(s.trim().equals("null"))continue;JsonObj z=parseObj(s);m.put(z.i("id"),new ZD(z.i("floorH"),z.i("roofH"),z.i("brightness"),z.i("floorTile"),z.i("ceilTile"),z.iArr("edgeIds"),z.i("telZone"),z.i("telX"),z.i("telZ")));}return m;}

    /**
     * Parse {@code exitZoneId} au top-level du JSON. Session 110.
     *
     * <p>Cherche {@code "exitZoneId": N} hors de tout objet imbrique. Retourne
     * -1 si absent (anciens JSON pre-session 110).</p>
     */
    private int parseExitZoneId(String json) {
        int ki = json.indexOf("\"exitZoneId\"");
        if (ki < 0) return -1;
        int co = json.indexOf(':', ki);
        if (co < 0) return -1;
        int vs = co + 1;
        while (vs < json.length() && (json.charAt(vs)==' '||json.charAt(vs)=='\t')) vs++;
        int ve = vs;
        while (ve < json.length() && json.charAt(ve)!=','&&json.charAt(ve)!='}'&&json.charAt(ve)!='\n') ve++;
        try {
            return Integer.parseInt(json.substring(vs, ve).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Parse les murs.
     * w[2] = texIndex = fromTile = ZWG(A,B,0) -> decalage U
     * w[6] = clipIdx  = ZWGC    = ZWG(A,B,2) -> index fichier texture
     * w[7] = yOffset  = VO      = decalage Y texture (0-255)
     * w[8] = wMask    = textureWidth - 1  (tile width Amiga, ex. 127 pour 128px)
     * w[9] = hMask    = textureHeight - 1 (ex. 127 pour 128px)
     */
    private Map<Integer,List<int[]>> parseWalls(String json){
        Map<Integer,List<int[]>> m=new LinkedHashMap<>();
        String wj=extractObj(json,"walls");if(wj==null)return m;
        int pos=0;
        while(pos<wj.length()){
            int q1=wj.indexOf('"',pos);if(q1<0)break;
            int q2=wj.indexOf('"',q1+1);if(q2<0)break;
            String key=wj.substring(q1+1,q2);int zid;
            try{zid=Integer.parseInt(key);}catch(NumberFormatException e){pos=q2+1;continue;}
            int a1=wj.indexOf('[',q2),a2=mbr(wj,a1,'[',']');if(a1<0||a2<0)break;
            List<int[]> wl=new ArrayList<>();
            for(String ws:arrElems(wj.substring(a1+1,a2))){
                JsonObj w=parseObj(ws);
                wl.add(new int[]{
                    w.i("leftPt"),w.i("rightPt"),w.i("texIndex"),
                    w.i("topWallH"),w.i("botWallH"),w.i("otherZone"),
                    w.i("clipIdx"),w.i("yOffset"),
                    w.i("wMask"),w.i("hMask")
                });
            }
            m.put(zid,wl);pos=a2+1;
        }
        return m;
    }

    /**
     * Convertit la valeur whichTile binaire en index de dalle 0-15.
     *
     * Format memoire AMOS floortile (depuis _FLOOR2SCREEN) :
     *   S = ADR + (NR mod 4) + ((NR/4) and 3)*256
     * Donc offset = (NR mod 4) + (NR/4)*256
     * Inverse : NR = (W >> 8) * 4 + (W & 3)
     *
     * Verification avec les valeurs de level_A.json :
     *   W=513 (0x201) -> (2)*4 + 1 = 9  -> floor_10.png
     *   W=257 (0x101) -> (1)*4 + 1 = 5  -> floor_06.png
     *   W=769 (0x301) -> (3)*4 + 1 = 13 -> floor_14.png
     *   W=1   (0x001) -> (0)*4 + 1 = 1  -> floor_02.png
     *   W=0   (0x000) -> (0)*4 + 0 = 0  -> floor_01.png
     *   W=259 (0x103) -> (1)*4 + 3 = 7  -> floor_08.png
     */
    static int floorIdx(int wt){
        if(wt<0) return -1;
        int nr = (wt>>8)*4 + (wt&3);
        return(nr>=0&&nr<=15)?nr:-1;
    }

    /**
     * Deduit la largeur de tile logique d'une texture de mur.
     *
     * <p>Dans le jeu original, <code>draw_WallTextureWidthMask_w</code> sert a
     * wrapper le sampling U par une puissance de 2. Les textures de plus de
     * 128 px de large contiennent en general plusieurs tiles de 128 juxtaposees,
     * selectionnees via <code>fromTile</code>. Les petites textures (&lt;=128 px)
     * sont elles-memes une unique tile.</p>
     *
     * <p>Regle empirique : tileWidth = 128 si texW &gt;= 128, sinon tileWidth = texW.
     * Cela correspond aux patterns observes dans les fichiers <code>.256wad</code>.</p>
     */
    static int deduceTileWidth(int texW) {
        if (texW >= 128) return 128;
        return texW;
    }
    private static float[] toFA(List<Float> l){float[] a=new float[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}
    private static int[] toIA(List<Integer> l){int[] a=new int[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}
    private List<String> elems(String j,String k){JsonArr a=parseRoot(j,k);return a!=null?a.elems:List.of();}
    private List<String> arrElems(String s){return new JsonArr(s).elems;}
    private JsonArr parseRoot(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('[',ki+k.length()+2),b=mbr(j,a,'[',']');return(a<0||b<0)?null:new JsonArr(j.substring(a+1,b));}
    private String extractObj(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('{',ki+k.length()+2),b=mbr(j,a,'{','}');return(a<0||b<0)?null:j.substring(a+1,b);}
    private String extractField(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int col=j.indexOf(':',ki);if(col<0)return null;int ob=j.indexOf('{',col);if(ob<0)return null;int cb=mbr(j,ob,'{','}');if(cb<0)return null;return j.substring(ob,cb+1);}
    static JsonObj parseObj(String s){Map<String,String> f=new LinkedHashMap<>();int st=s.indexOf('{');if(st<0)return new JsonObj(f);int en=mbr(s,st,'{','}');if(en<0)en=s.length()-1;String in=s.substring(st+1,en);int pos=0;while(pos<in.length()){int q1=in.indexOf('"',pos);if(q1<0)break;int q2=in.indexOf('"',q1+1);if(q2<0)break;String k=in.substring(q1+1,q2);int co=in.indexOf(':',q2+1);if(co<0)break;int vs=co+1;while(vs<in.length()&&in.charAt(vs)==' ')vs++;String val;char fc=vs<in.length()?in.charAt(vs):0;if(fc=='{'||fc=='['){char cl=fc=='{'?'}':']';int ve=mbr(in,vs,fc,cl);val=in.substring(vs,ve+1);pos=ve+1;}else if(fc=='"'){int ve=in.indexOf('"',vs+1);val=in.substring(vs+1,ve);pos=ve+1;}else{int ve=vs;while(ve<in.length()&&in.charAt(ve)!=','&&in.charAt(ve)!='}')ve++;val=in.substring(vs,ve).trim();pos=ve;}f.put(k,val);int cm=in.indexOf(',',pos);pos=cm>=0?cm+1:in.length();}return new JsonObj(f);}
    static int mbr(String s,int st,char op,char cl){int d=0;for(int i=st;i<s.length();i++){char c=s.charAt(i);if(c==op)d++;if(c==cl){d--;if(d==0)return i;}}return -1;}
    static class JsonObj{final Map<String,String> f;JsonObj(Map<String,String> f){this.f=f;}int i(String k){try{return Integer.parseInt(f.getOrDefault(k,"0").trim());}catch(NumberFormatException e){return 0;}}String f(String k){return f.getOrDefault(k,"").trim();}int[] iArr(String k){String v=f.get(k);if(v==null||!v.startsWith("["))return new int[0];List<Integer> r=new ArrayList<>();for(String s:new JsonArr(v.substring(1,v.lastIndexOf(']'))).elems)try{r.add(Integer.parseInt(s.trim()));}catch(Exception ex){}return r.stream().mapToInt(Integer::intValue).toArray();}}
    static class JsonArr{final List<String> elems=new ArrayList<>();JsonArr(String in){int d=0,st=0;for(int i=0;i<in.length();i++){char c=in.charAt(i);if(c=='{'||c=='[')d++;else if(c=='}'||c==']')d--;else if(c==','&&d==0){String el=in.substring(st,i).trim();if(!el.isEmpty())elems.add(el);st=i+1;}}String last=in.substring(st).trim();if(!last.isEmpty())elems.add(last);}}

    // ── Définitions statiques depuis definitions.json ──────────────────────────────────
    /**
     * Definition d'un objet. Session 111 : ajoute {@code defFrame} = sprite frame
     * index a charger au repos depuis le WAD (lu dans GLFT_ObjectDefAnims_l).
     * Sans ce champ, on chargeait toujours la frame 0 du WAD ce qui donnait
     * la mauvaise image pour les objets dont le WAD est partage entre plusieurs.
     */
    record ObjDef(int index, String name, int behaviour, int gfxType,
                  int hitPoints, int colRadius, int colHeight,
                  int floorCeiling, int sfx, String wadName, int defFrame) {}
    record AlienDef(int index, String name, int gfxType, int hitPoints, int height) {}

    private ObjDef[] loadObjDefs() {
        Path p = Path.of("assets/levels/definitions.json");
        if (!Files.exists(p)) { System.out.println("  INFO definitions.json absent"); return null; }
        try {
            String json = Files.readString(p);
            List<String> items = elems(json, "objects");
            ObjDef[] defs = new ObjDef[30];
            for (String s : items) {
                JsonObj o = parseObj(s);
                int idx = o.i("index");
                if (idx >= 0 && idx < 30)
                    defs[idx] = new ObjDef(idx, o.f("name"), o.i("behaviour"), o.i("gfxType"),
                        o.i("hitPoints"), o.i("colRadius"), o.i("colHeight"),
                        o.i("floorCeiling"), o.i("sfx"), o.f("wadName"),
                        o.i("defFrame"));   // session 111 : frame index pour _fN.png
            }
            for (int i = 0; i < 30; i++)
                if (defs[i] == null) defs[i] = new ObjDef(i,"?",3,0,0,0,0,0,-1,"",0);
            System.out.printf("  definitions.json: %d objets, %d aliens%n", items.size(),
                elems(json,"aliens").size());
            return defs;
        } catch (Exception e) { System.out.println("  WARN defs: "+e.getMessage()); return null; }
    }

    private AlienDef[] loadAlienDefs() {
        Path p = Path.of("assets/levels/definitions.json");
        if (!Files.exists(p)) return null;
        try {
            String json = Files.readString(p);
            List<String> items = elems(json, "aliens");
            AlienDef[] defs = new AlienDef[20];
            for (String s : items) {
                JsonObj o = parseObj(s);
                int idx = o.i("index");
                if (idx >= 0 && idx < 20)
                    defs[idx] = new AlienDef(idx, o.f("name"), o.i("gfxType"),
                        o.i("hitPoints"), o.i("height"));
            }
            for (int i = 0; i < 20; i++)
                if (defs[i] == null) defs[i] = new AlienDef(i,"?",0,0,0);
            return defs;
        } catch (Exception e) { return null; }
    }

    /**
     * Accumulateur pour une porte (zoneId).
     * Chaque segment a ses propres donnees texture, issues de son ZDoorWall via edgeId.
     *
     * Segment : [x0, z0, x1, z1, texIdx, fromTile, yOffset, isInsideView, wMask, hMask]
     *            ^^^^^^^^^^^^^^^  geometrie monde
     *                             ^^^^^^^^^^^^^^^^^^^^^^^^ donnees texture par segment
     *                                                       ^^^^^^^^^^^^^ 1.0f si vue depuis zone-porte
     *                                                                      ^^^^^^^^^^^^ session 92 fix 3 : tile masks Amiga
     *
     * Priorite de texture : quand deux vues (interieure depuis zone-porte, exterieure
     * depuis zone voisine) decrivent le meme segment, la vue interieure gagne car elle
     * porte la texture de porte (les murs cote zone-porte contiennent le graphisme
     * ecrit dans le graph.bin pour la porte).
     */
    /**
     * Accumulateur pour une porte (zoneId).
     * Chaque segment a ses propres donnees texture, issues de son ZDoorWall via edgeId.
     *
     * Segment : [x0, z0, x1, z1, texIdx, fromTile, yOffset, segYBot, wMask, hMask, segYTop]
     *            ^^^^^^^^^^^^^^^  geometrie monde
     *                             ^^^^^^^^^^^^^^^^^^^^^^^^ donnees texture par segment
     *                                                       ^^^^^^^ session 93 : yBot par segment
     *                                                                ^^^^^^^^^^^^ session 92 fix 3 : tile masks Amiga
     *                                                                              ^^^^^^^ session 93 : yTop par segment
     *
     * yBot et yTop : geometrie du panneau ferme = MEME que le mur voisin (topWallH/botWallH).
     * animDist (commun) : distance JME de translation pour ouvrir = |zl_Top - zl_Bottom|/SCALE.
     */
    private static class DoorAccum{
        final float yTop;     // top du premier segment (informatif)
        final float yBot;     // bot du premier segment (informatif)
        final float animDist; // distance JME de translation pour s'ouvrir (commun)
        final int doorZoneId; // -1 si zone non connue
        final List<float[]> segs = new ArrayList<>();
        DoorAccum(float yt, float yb, float ad, int dzid) {
            yTop = yt; yBot = yb; animDist = ad; doorZoneId = dzid;
        }
        /**
         * Ajoute un segment avec ses propres donnees texture, son propre yBot et son propre yTop.
         * SESSION 97 : on accepte tous les segments (meme geometrie identique pour les 2 faces
         * d'un edge avec FaceCullMode.Off, on peut superposer).
         */
        void addSeg(float x0, float z0, float x1, float z1,
                    int texIdx, int fromTile, int yOffset,
                    int wMask, int hMask, float segYBot, float segYTop,
                    int ceilTile) {
            segs.add(new float[]{x0, z0, x1, z1, texIdx, fromTile, yOffset,
                                 segYBot, wMask, hMask, segYTop, ceilTile});
        }
    }
}
