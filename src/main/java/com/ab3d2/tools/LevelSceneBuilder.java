package com.ab3d2.tools;

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

    record ZD(int floorH, int roofH, int brightness, int floorTile, int ceilTile, int[] edgeIds) {}
    record PolyData(float[] xz, float y) {}

    private final AssetManager am;
    // Largeurs reelles des textures murs (lues depuis l'image chargee)
    // Utilisees pour calculer le decalage U du fromTile
    private final int[] wallTexWidths = new int[AssetConverter.NUM_WALL_TEX];

    public LevelSceneBuilder(AssetManager am) { this.am = am; }

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
        Map<Integer,int[]>       pts   = parsePts(json);
        Map<Integer,int[]>       edges = parseEdges(json);
        Map<Integer,ZD>          zones = parseZones(json);
        Map<Integer,List<int[]>> zw    = parseWalls(json);

        // Charger les materiaux et remplir wallTexWidths en meme temps
        Material[] wm = loadWallMats();
        Material[] fm = loadFloorMats();
        Material   fb = makeFallback();

        Node root  = new Node("scene_"+levelId);
        Node geo   = new Node("geometry");
        Node doors = new Node("doors");
        Node spawns= new Node("spawns");
        Node lights= new Node("lights");
        Node zns   = new Node("zones");
        Node items = new Node("items");  // objets/aliens/items de niveau
        root.attachChild(geo);root.attachChild(doors);root.attachChild(spawns);
        root.attachChild(lights);root.attachChild(zns);root.attachChild(items);
        root.setUserData("levelId",levelId);
        addSpawns(root,json,spawns);
        addItems(json,items,fb);

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
        // Charger les definitions de portes depuis le JSON (nouvelles donnees graph.bin)
        // Cle = zoneId de la porte
        Map<Integer,int[]> doorZoneDefs = parseDoorDefs(json); // zoneId -> [bottom,top,openSpeed,closeSpeed,openDuration,raiseCondition,lowerCondition]
        int wc=0,sp=0,sl=0;

        for(var ze:zones.entrySet()){
            int zid=ze.getKey(); ZD z=ze.getValue();
            float yF=-z.floorH()/SCALE,yR=-z.roofH()/SCALE;
            if(yF>yR){float t=yF;yF=yR;yR=t;}
            if(Math.abs(yR-yF)<0.01f) continue;

            Node zn=new Node("zone_"+zid);
            zn.setUserData("id",(int)zid);zn.setUserData("floorH",(int)z.floorH());
            zn.setUserData("roofH",(int)z.roofH());zn.setUserData("brightness",(int)z.brightness());
            zns.attachChild(zn);

            collectHoriz(z.edgeIds(),edges,yF,fp.get(floorIdx(z.floorTile())+1));
            collectHoriz(z.edgeIds(),edges,yR,cp.get(floorIdx(z.ceilTile())+1));

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
                // w[3]=topH    w[4]=botH      w[5]=otherZone  w[6]=clipIdx(ZWGC)
                int lpi=w[0],rpi=w[1];
                int fromTile=w[2]; // ZWG(A,B,0) = decalage U dans la texture
                int topH=w[3],botH=w[4],oz=w[5];
                int clipIdx=w.length>6?w[6]:w[2]; // ZWGC = vrai index fichier .256wad

                if((fromTile&0x8000)!=0){sp++;continue;}
                if(topH==botH) continue;
                float wallH=(float)Math.abs(topH-botH);
                if(wallH>8192f||Math.abs(topH)>16384f) continue;
                int[] L=pts.get(lpi),R=pts.get(rpi); if(L==null||R==null) continue;
                float x0=L[0]/SCALE,z0=-L[1]/SCALE,x1=R[0]/SCALE,z1=-R[1]/SCALE;
                float yTop=-topH/SCALE,yBot=-botH/SCALE;
                if(Math.abs(yTop-yBot)<0.01f) continue;

                if(oz!=0){
                    int wallHeight = Math.abs(topH - botH);
                    // Porte reelle : la zone courante OU la zone adjacente est une zone-porte connue
                    boolean zidIsDoor = doorZoneDefs.containsKey(zid);
                    boolean ozIsDoor  = doorZoneDefs.containsKey(oz);
                    if((zidIsDoor||ozIsDoor) && Math.abs(botH) <= DOOR_FLOOR_THRESH && wallHeight >= DOOR_HEIGHT_MIN){
                        // Grouper par ZONE-PORTE (pas par paire), pour que tous les pans bougent ensemble
                        int doorZoneId = zidIsDoor ? zid : oz;
                        String key = "door_"+doorZoneId;
                        int ft=fromTile;
                        dg.computeIfAbsent(key, k->{
                            int[] def = doorZoneDefs.get(doorZoneId);
                            // zl_Bottom (def[0]) = hauteur sol en unites editeur -> yBot en JME
                            // zl_Top    (def[1]) = hauteur plafond             -> yTop en JME
                            // Conversion : JME_Y = -editeurH / SCALE
                            float dYBot = def!=null ? -def[0]/SCALE : yBot;
                            float dYTop = def!=null ? -def[1]/SCALE : yTop;
                            // Garantir yTop > yBot (hauteurs AB3D2 sont inversees)
                            if (dYBot > dYTop) { float tmp=dYBot; dYBot=dYTop; dYTop=tmp; }
                            return new DoorAccum(clipIdx,ft,dYTop,dYBot,doorZoneId);
                        }).addSeg(x0,z0,x1,z1);
                        continue;
                    } else if(Math.abs(botH) <= DOOR_FLOOR_THRESH && wallHeight >= DOOR_HEIGHT_MIN){
                        // Porte non repertoriee dans doorZoneDefs : fallback ancienne methode
                        int za=Math.min(zid,oz),zb=Math.max(zid,oz);
                        int ft=fromTile;
                        dg.computeIfAbsent(za+"_"+zb, k->new DoorAccum(clipIdx,ft,yTop,yBot,-1))
                          .addSeg(x0,z0,x1,z1);
                        continue;
                    }
                    // Sinon : step wall ou linteau -> geometrie normale
                }

                float dx=R[0]-L[0],dz=R[1]-L[1];
                float len=(float)Math.sqrt(dx*dx+dz*dz);
                // Bucket par clipIdx = ZWGC = vrai index texture .256wad
                int bk=(clipIdx>=0&&clipIdx<AssetConverter.NUM_WALL_TEX)?clipIdx:0;
                // U_offset = fromTile * 16 / texWidth
                // (decalage pixel dans la texture = fromTile * 16 dans le format AMOS)
                float texW=(float)wallTexWidths[bk]; // largeur reelle du PNG
                float uOffset=(texW>0)?(fromTile*16f/texW):0f;
                float uMax=len/texW; // UV length = wallLen / texWidth
                wq.get(bk).add(new float[]{x0,z0,x1,z1,yTop,yBot,uMax,wallH/TEX_V,uOffset});
                wc++;
            }
        }

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
            // yTop et yBot sont deja en coordonnees JME (calcules depuis bottom/top du ZLiftable)
            dn.setUserData("yTop",(float)acc.yTop);
            dn.setUserData("yBot",(float)acc.yBot);
            dn.setUserData("doorZoneId",(int)doorZoneId);
            // Conditions d'ouverture/fermeture pour le runtime
            if(doorZoneId >= 0 && doorZoneDefs.containsKey(doorZoneId)) {
                int[] def = doorZoneDefs.get(doorZoneId);
                dn.setUserData("openDuration",   (int)def[4]);
                dn.setUserData("raiseCondition",(int)def[5]);
                dn.setUserData("lowerCondition",(int)def[6]);
                if (def.length > 7) dn.setUserData("openSfx",   (int)def[7]);
                if (def.length > 8) dn.setUserData("closeSfx",  (int)def[8]);
                if (def.length > 9) dn.setUserData("openedSfx", (int)def[9]);
                if (def.length >10) dn.setUserData("closedSfx", (int)def[10]);
            }
            Material mat=(acc.texIdx>=0&&acc.texIdx<wm.length&&wm[acc.texIdx]!=null)?wm[acc.texIdx]:fb;
            float texW = (acc.texIdx>=0&&acc.texIdx<wallTexWidths.length) ? (float)wallTexWidths[acc.texIdx] : 256f;
            int si=0;
            for(float[] seg:acc.segs){
                Geometry sg=makeDoorSegGeo("seg_"+si,seg[0],seg[1],seg[2],seg[3],
                                           acc.yTop,acc.yBot,mat,texW,acc.fromTile);
                sg.setUserData("x0",(float)seg[0]);sg.setUserData("z0",(float)seg[1]);
                sg.setUserData("x1",(float)seg[2]);sg.setUserData("z1",(float)seg[3]);
                dn.attachChild(sg);si++;
            }
            doors.attachChild(dn);
        }
        System.out.printf("  [%s] murs:%d portails:%d linteaux:%d portes:%d lights:%d%n",
            levelId,wc,sp,sl,dg.size(),lights.getQuantity());
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
     * UV identique aux murs : V=0 en bas, V=vM en haut.
     */
    private Geometry makeDoorSegGeo(String name,float x0,float z0,float x1,float z1,
                                     float yT,float yB,Material mat,float texW,int fromTile){
        float dx=x1-x0,dz=z1-z0,L=Math.max((float)Math.sqrt(dx*dx+dz*dz),1e-5f);
        float nx=-dz/L,nz=dx/L;
        float uOffset = (texW>0) ? (fromTile*16f/texW) : 0f;
        float uM      = L/Math.max(texW,1f);
        float vM      = (yT-yB)*SCALE/TEX_V;
        // UV identique aux murs : BL/BR = V=0 (bas), TL/TR = V=vM (haut)
        float[] pos={x0,yB,z0, x1,yB,z1, x1,yT,z1, x0,yT,z0};
        float[] uv ={uOffset,0f, uOffset+uM,0f, uOffset+uM,vM, uOffset,vM};
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
        return g;
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

    /**
     * Place des marqueurs (cubes colores) pour chaque objet/alien du niveau.
     *
     * Couleurs par type (voir LevelBinaryParser pour les constantes) :
     *   TypeID=0 ALIEN      -> rouge vif
     *   TypeID=1 OBJECT :
     *     defIndex behaviour COLLECTABLE  -> vert  (health, ammo, armes, cles)
     *     defIndex behaviour ACTIVATABLE  -> jaune (switch, terminal)
     *     defIndex behaviour DESTRUCTABLE -> orange(baril, caisse)
     *     defIndex behaviour DECORATION   -> gris  (lampe, deco)
     *   TypeID=4/5 PLAYER   -> bleu ciel
     *
     * UserData stockes pour le runtime GameAppState :
     *   typeId    : 0=alien 1=objet 4=plr1 5=plr2
     *   defIndex  : EntT_Type_b (alien def 0-19 OU objet def 0-29)
     *   startAnim : EntT_Timer1_w (frame de depart)
     *   angle     : EntT_CurrentAngle_w (0-8191)
     *   hitPoints, teamNumber, doorLocks, liftLocks
     */
    private void addItems(String json, Node itemsNode, Material fb) {
        // Palette de couleurs selon le catalogue TypeID/behaviour
        ColorRGBA[] cols = {
            new ColorRGBA(1f,  0.15f,0.15f,1f), // 0: ALIEN       -> rouge
            new ColorRGBA(0.1f,0.9f, 0.1f, 1f), // 1: COLLECTABLE -> vert
            new ColorRGBA(1f,  1f,   0.1f, 1f), // 2: ACTIVATABLE -> jaune
            new ColorRGBA(1f,  0.5f, 0.0f, 1f), // 3: DESTRUCTABLE-> orange
            new ColorRGBA(0.5f,0.5f, 0.5f, 1f), // 4: DECORATION  -> gris
            new ColorRGBA(0.3f,0.6f, 1f,   1f), // 5: PLAYER      -> bleu ciel
            new ColorRGBA(0.8f,0.3f, 0.8f, 1f), // 6: inconnu     -> violet
        };
        Material[] mats = new Material[cols.length];
        for (int i = 0; i < cols.length; i++) {
            mats[i] = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            mats[i].setColor("Color", cols[i]);
        }

        List<String> objs = elems(json, "objects");
        int countAlien=0, countObj=0, countPlr=0;
        for (String s : objs) {
            JsonObj o = parseObj(s);
            int x          = o.i("x");
            int z          = o.i("z");
            int y          = o.i("y");
            int typeId     = o.i("typeId");
            int defIndex   = o.i("defIndex");   // EntT_Type_b : alien def ou objet def
            int startAnim  = o.i("startAnim");  // EntT_Timer1_w
            int angle      = o.i("angle");      // EntT_CurrentAngle_w (0-8191)
            int hitPoints  = o.i("hitPoints");
            int teamNumber = o.i("teamNumber");
            int doorLocks  = o.i("doorLocks");
            int liftLocks  = o.i("liftLocks");
            int zoneId     = o.i("zoneId");

            // Conversion coordonnees Amiga -> JME
            // ObjT : XPos, ZPos, YPos en unites Amiga (1 unite = 1/32 m)
            // JME  : x = worldX/32  z = -worldZ/32  y = -worldY/32
            // +0.5f pour poser le cube sur le sol (demi-hauteur du cube)
            float jx =  x / SCALE;
            float jy = -y / SCALE + 0.5f;
            float jz = -z / SCALE;

            // Taille du marqueur selon le type
            float sz = (typeId == 0) ? 0.3f : 0.2f; // aliens un peu plus grands
            com.jme3.scene.shape.Box box = new com.jme3.scene.shape.Box(sz, sz, sz);
            Geometry g = new Geometry("obj_t" + typeId + "_d" + defIndex + "_" + zoneId, box);

            // Couleur selon TypeID
            // NOTE : pour TypeID=1, le behaviour reel (COLLECTABLE etc.) est dans
            //        GLFT_ObjectDefs[defIndex].ODefT_Behaviour_w - on ne l'a pas
            //        dans le JSON pour l'instant, donc on code par defIndex:
            //        defIndex < 6  -> collectibles courants
            //        defIndex 6-10 -> activatables
            //        defIndex >10  -> decorations / divers
            // Amelioration future : charger TEST.LNK pour avoir le vrai behaviour.
            int matIdx = switch (typeId) {
                case 0  -> 0; // ALIEN
                case 1  -> (defIndex < 6) ? 1 :  // COLLECTABLE (pickups standard)
                           (defIndex < 11) ? 2 :  // ACTIVATABLE (switches)
                           4;                      // DECORATION
                case 4, 5 -> 5; // PLAYER1 / PLAYER2
                default   -> 6;
            };
            g.setMaterial(mats[matIdx]);
            g.setLocalTranslation(jx, jy, jz);

            // UserData pour GameAppState (runtime)
            g.setUserData("typeId",     typeId);
            g.setUserData("defIndex",   defIndex);
            g.setUserData("startAnim",  startAnim);
            g.setUserData("angle",      angle);
            g.setUserData("hitPoints",  hitPoints);
            g.setUserData("teamNumber", teamNumber);
            g.setUserData("doorLocks",  doorLocks);
            g.setUserData("liftLocks",  liftLocks);
            g.setUserData("zoneId",     zoneId);
            g.setUserData("worldX",     x);
            g.setUserData("worldZ",     z);
            g.setUserData("worldY",     y);

            itemsNode.attachChild(g);
            if (typeId == 0)        countAlien++;
            else if (typeId == 1)   countObj++;
            else                    countPlr++;
        }
        System.out.printf("  [items] aliens:%d objets:%d joueurs:%d%n",
            countAlien, countObj, countPlr);
    }

    /**
     * Parse les definitions de portes depuis le JSON.
     * Retourne une map zoneId -> [bottom, top, openSpeed, closeSpeed, openDuration, raiseCondition, lowerCondition]
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
            // Lire la largeur reelle depuis l'image JME chargee
            wallTexWidths[i]=256; // defaut
            if(m[i]!=null){
                try{
                    var tp=m[i].getTextureParam("DiffuseMap");
                    if(tp!=null&&tp.getTextureValue()!=null){
                        int w=tp.getTextureValue().getImage().getWidth();
                        if(w>0) wallTexWidths[i]=w;
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
    private Map<Integer,ZD> parseZones(String json){Map<Integer,ZD> m=new LinkedHashMap<>();for(String s:elems(json,"zones")){if(s.trim().equals("null"))continue;JsonObj z=parseObj(s);m.put(z.i("id"),new ZD(z.i("floorH"),z.i("roofH"),z.i("brightness"),z.i("floorTile"),z.i("ceilTile"),z.iArr("edgeIds")));}return m;}

    /**
     * Parse les murs.
     * w[2] = texIndex = fromTile = ZWG(A,B,0) -> decalage U
     * w[6] = clipIdx  = ZWGC    = ZWG(A,B,2) -> index fichier texture
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
                    w.i("clipIdx")
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
    private static float[] toFA(List<Float> l){float[] a=new float[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}
    private static int[] toIA(List<Integer> l){int[] a=new int[l.size()];for(int i=0;i<a.length;i++)a[i]=l.get(i);return a;}
    private List<String> elems(String j,String k){JsonArr a=parseRoot(j,k);return a!=null?a.elems:List.of();}
    private List<String> arrElems(String s){return new JsonArr(s).elems;}
    private JsonArr parseRoot(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('[',ki+k.length()+2),b=mbr(j,a,'[',']');return(a<0||b<0)?null:new JsonArr(j.substring(a+1,b));}
    private String extractObj(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('{',ki+k.length()+2),b=mbr(j,a,'{','}');return(a<0||b<0)?null:j.substring(a+1,b);}
    private String extractField(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int col=j.indexOf(':',ki);if(col<0)return null;int ob=j.indexOf('{',col);if(ob<0)return null;int cb=mbr(j,ob,'{','}');if(cb<0)return null;return j.substring(ob,cb+1);}
    static JsonObj parseObj(String s){Map<String,String> f=new LinkedHashMap<>();int st=s.indexOf('{');if(st<0)return new JsonObj(f);int en=mbr(s,st,'{','}');if(en<0)en=s.length()-1;String in=s.substring(st+1,en);int pos=0;while(pos<in.length()){int q1=in.indexOf('"',pos);if(q1<0)break;int q2=in.indexOf('"',q1+1);if(q2<0)break;String k=in.substring(q1+1,q2);int co=in.indexOf(':',q2+1);if(co<0)break;int vs=co+1;while(vs<in.length()&&in.charAt(vs)==' ')vs++;String val;char fc=vs<in.length()?in.charAt(vs):0;if(fc=='{'||fc=='['){char cl=fc=='{'?'}':']';int ve=mbr(in,vs,fc,cl);val=in.substring(vs,ve+1);pos=ve+1;}else if(fc=='"'){int ve=in.indexOf('"',vs+1);val=in.substring(vs+1,ve);pos=ve+1;}else{int ve=vs;while(ve<in.length()&&in.charAt(ve)!=','&&in.charAt(ve)!='}')ve++;val=in.substring(vs,ve).trim();pos=ve;}f.put(k,val);int cm=in.indexOf(',',pos);pos=cm>=0?cm+1:in.length();}return new JsonObj(f);}
    static int mbr(String s,int st,char op,char cl){int d=0;for(int i=st;i<s.length();i++){char c=s.charAt(i);if(c==op)d++;if(c==cl){d--;if(d==0)return i;}}return -1;}
    static class JsonObj{final Map<String,String> f;JsonObj(Map<String,String> f){this.f=f;}int i(String k){try{return Integer.parseInt(f.getOrDefault(k,"0").trim());}catch(NumberFormatException e){return 0;}}int[] iArr(String k){String v=f.get(k);if(v==null||!v.startsWith("["))return new int[0];List<Integer> r=new ArrayList<>();for(String s:new JsonArr(v.substring(1,v.lastIndexOf(']'))).elems)try{r.add(Integer.parseInt(s.trim()));}catch(Exception ex){}return r.stream().mapToInt(Integer::intValue).toArray();}}
    static class JsonArr{final List<String> elems=new ArrayList<>();JsonArr(String in){int d=0,st=0;for(int i=0;i<in.length();i++){char c=in.charAt(i);if(c=='{'||c=='[')d++;else if(c=='}'||c==']')d--;else if(c==','&&d==0){String el=in.substring(st,i).trim();if(!el.isEmpty())elems.add(el);st=i+1;}}String last=in.substring(st).trim();if(!last.isEmpty())elems.add(last);}}
    private static class DoorAccum{
        final int texIdx;
        final int fromTile;  // ZWG(A,B,0) pour le decalage U
        final float yTop,yBot;
        final int doorZoneId; // -1 si zone non connue
        final List<float[]> segs=new ArrayList<>();
        DoorAccum(int ti,int ft,float yt,float yb,int dzid){texIdx=ti;fromTile=ft;yTop=yt;yBot=yb;doorZoneId=dzid;}
        void addSeg(float x0,float z0,float x1,float z1){for(float[] s:segs)if(Math.abs(s[0]-x0)<0.01f&&Math.abs(s[1]-z0)<0.01f&&Math.abs(s[2]-x1)<0.01f&&Math.abs(s[3]-z1)<0.01f)return;segs.add(new float[]{x0,z0,x1,z1});}}
}
