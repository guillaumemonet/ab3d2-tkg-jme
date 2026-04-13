package com.ab3d2.world;

import com.ab3d2.tools.AssetConverter;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Géométrie 3D du niveau — JME scene graph (fallback si .j3o absent).
 *
 * Normales :
 *   Murs   : (-dz, 0, dx)/L  = perpendiculaire GAUCHE = vers l'INTÉRIEUR de la zone
 *   Sol    : (0, +1, 0)
 *   Plafond: (0, -1, 0)
 *
 * Matériaux : FaceCullMode.Off + Ambient 0.5 sur tous.
 */
public class LevelGeometry {

    private static final Logger log = LoggerFactory.getLogger(LevelGeometry.class);

    public static final float SCALE    = 32f;
    private static final float TEX_U   = 256f;
    private static final float TEX_V   = 128f;
    private static final float TILE_SZ = 64f;
    private static final int   DOOR_FLOOR_THRESH = 8;

    private final Node levelNode = new Node("level");
    private final List<float[]>             wallSegments = new ArrayList<>();
    private final List<DoorSystem.DoorInfo> doors        = new ArrayList<>();

    private final Material[] wallMats  = new Material[AssetConverter.NUM_WALL_TEX];
    private final Material[] floorMats = new Material[AssetConverter.NUM_FLOOR_TEX];
    private Material fallbackMat;
    private AssetManager assetManager;

    // ── Construction ──────────────────────────────────────────────────────────

    public void build(String levelId, Path jmeAssetsRoot, AssetManager am) {
        this.assetManager = am;
        log.info("LevelGeometry.build() LEVEL_{}", levelId);
        loadMaterials();
        Path jp = jmeAssetsRoot.resolve("levels/level_" + levelId + ".json");
        if (!Files.exists(jp)) { log.error("JSON absent → ./gradlew convertLevels"); return; }
        try { buildFromJson(Files.readString(jp)); }
        catch (Exception e) { log.error("JSON : {}", e.getMessage(), e); }
    }

    private void loadMaterials() {
        fallbackMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        fallbackMat.setColor("Color", new ColorRGBA(1f, 0f, 1f, 1f));
        fallbackMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        for (int i = 0; i < AssetConverter.NUM_WALL_TEX; i++)
            wallMats[i] = loadTexturedMat(String.format("Textures/walls/wall_%02d_%s.png",
                                           i, AssetConverter.WALL_NAMES[i]));
        for (int i = 0; i < AssetConverter.NUM_FLOOR_TEX; i++)
            floorMats[i] = loadTexturedMat(String.format("Textures/floors/floor_%02d.png", i+1));
    }

    private Material loadTexturedMat(String assetPath) {
        try {
            Texture tex = assetManager.loadTexture(assetPath);
            tex.setWrap(Texture.WrapMode.Repeat);
            tex.setMagFilter(Texture.MagFilter.Nearest);
            tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            mat.setTexture("DiffuseMap", tex);
            mat.setColor("Ambient", new ColorRGBA(0.5f, 0.5f, 0.5f, 1f));
            mat.setColor("Diffuse", ColorRGBA.White);
            // Double-sided : murs vus des deux côtés dans un donjon
            mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            return mat;
        } catch (Exception e) {
            return fallbackMat;
        }
    }

    // ── JSON → Scene graph ────────────────────────────────────────────────────

    private void buildFromJson(String json) {
        Map<Integer,int[]> pts   = new LinkedHashMap<>();
        for (String s : elems(json,"points")) { JsonObj p=obj(s); pts.put(p.i("id"),new int[]{p.i("x"),p.i("z")}); }

        Map<Integer,int[]> edges = new LinkedHashMap<>();
        for (String s : elems(json,"edges")) { JsonObj e=obj(s); edges.put(e.i("id"),new int[]{e.i("x"),e.i("z")}); }

        Map<Integer,int[]>   zFC    = new LinkedHashMap<>();
        Map<Integer,int[]>   zEids  = new LinkedHashMap<>();
        Map<Integer,Integer> zFTile = new LinkedHashMap<>();
        Map<Integer,Integer> zCTile = new LinkedHashMap<>();
        for (String s : elems(json,"zones")) {
            if (s.trim().equals("null")) continue;
            JsonObj z=obj(s); int id=z.i("id");
            zFC.put(id,   new int[]{z.i("floorH"),z.i("roofH")});
            zEids.put(id, z.iArr("edgeIds"));
            zFTile.put(id,z.i("floorTile"));
            zCTile.put(id,z.i("ceilTile"));
        }

        Map<Integer,List<int[]>> zWalls = new LinkedHashMap<>();
        String wj = extractObj(json,"walls");
        if (wj!=null) { int pos=0; while(pos<wj.length()) {
            int q1=wj.indexOf('"',pos); if(q1<0) break;
            int q2=wj.indexOf('"',q1+1); if(q2<0) break;
            String key=wj.substring(q1+1,q2); int zid;
            try { zid=Integer.parseInt(key); } catch(NumberFormatException e){pos=q2+1;continue;}
            int a1=wj.indexOf('[',q2),a2=mbr(wj,a1,'[',']'); if(a1<0||a2<0) break;
            List<int[]> wl=new ArrayList<>();
            for (String ws : arr(wj.substring(a1+1,a2))) {
                JsonObj w=obj(ws);
                wl.add(new int[]{w.i("leftPt"),w.i("rightPt"),w.i("texIndex"),
                                 w.i("topWallH"),w.i("botWallH"),w.i("otherZone")});
            }
            zWalls.put(zid,wl); pos=a2+1;
        }}

        Map<Integer, List<float[]>> wallVerts  = new HashMap<>();
        Map<Integer, List<float[]>> floorVerts = new HashMap<>();
        Map<Integer, List<float[]>> ceilVerts  = new HashMap<>();
        Map<String,  DoorAccum>     doorGroups = new LinkedHashMap<>();
        int wCount=0, skipPortal=0, skipLintel=0;

        for (Map.Entry<Integer,int[]> ze : zFC.entrySet()) {
            int zid=ze.getKey(); int[] fc=ze.getValue();
            int[] eids=zEids.get(zid);
            float yF=-fc[0]/SCALE, yR=-fc[1]/SCALE;
            if(yF>yR){float t=yF;yF=yR;yR=t;}
            if(Math.abs(yR-yF)<0.01f) continue;

            int fbt=floorIdx(zFTile.getOrDefault(zid,-1));
            int cbt=floorIdx(zCTile.getOrDefault(zid,-1));
            if (eids!=null) {
                collectHorizPoly(eids, edges, yF, fbt,  floorVerts);
                collectHorizPoly(eids, edges, yR, cbt,  ceilVerts);
            }

            List<int[]> walls=zWalls.get(zid); if(walls==null) continue;
            for (int[] w : walls) {
                int lpi=w[0],rpi=w[1],ti=w[2],topH=w[3],botH=w[4];
                int otherZone = w.length>5 ? w[5] : 0;
                if ((ti & 0x8000) != 0) { skipPortal++; continue; }
                if (topH==botH) continue;
                float wallH=(float)Math.abs(topH-botH);
                if (wallH>8192f || Math.abs(topH)>16384f) continue;
                int[] L=pts.get(lpi), R=pts.get(rpi); if(L==null||R==null) continue;
                float x0=L[0]/SCALE, z0=-L[1]/SCALE, x1=R[0]/SCALE, z1=-R[1]/SCALE;
                float yTop=-topH/SCALE, yBot=-botH/SCALE;
                if(Math.abs(yTop-yBot)<0.01f) continue;

                if (otherZone != 0) {
                    if (Math.abs(botH) > DOOR_FLOOR_THRESH) {
                        skipLintel++;
                    } else {
                        int za=Math.min(zid,otherZone), zb=Math.max(zid,otherZone);
                        String gk = za+"_"+zb;
                        DoorAccum acc = doorGroups.computeIfAbsent(gk,
                            k -> new DoorAccum(ti, yTop, yBot, new ArrayList<>()));
                        DoorSystem.DoorSegment ns = new DoorSystem.DoorSegment(x0,z0,x1,z1);
                        boolean dup=false;
                        for (var s:acc.segments){
                            if(Math.abs(s.x0()-x0)<0.01f&&Math.abs(s.z0()-z0)<0.01f&&Math.abs(s.x1()-x1)<0.01f&&Math.abs(s.z1()-z1)<0.01f){dup=true;break;}
                            if(Math.abs(s.x0()-x1)<0.01f&&Math.abs(s.z0()-z1)<0.01f&&Math.abs(s.x1()-x0)<0.01f&&Math.abs(s.z1()-z0)<0.01f){dup=true;break;}
                        }
                        if(!dup) acc.segments.add(ns);
                        continue;
                    }
                }

                float dx=R[0]-L[0], dz=R[1]-L[1], len=(float)Math.sqrt(dx*dx+dz*dz);
                float uM=len/TEX_U, vM=wallH/TEX_V;
                int bk=(ti>=0&&ti<AssetConverter.NUM_WALL_TEX)?ti:0;
                wallVerts.computeIfAbsent(bk, k->new ArrayList<>())
                         .add(new float[]{x0,z0, x1,z1, yTop, yBot, uM, vM});
                wallSegments.add(new float[]{x0,z0,x1,z1});
                wCount++;
            }
        }

        for (Map.Entry<Integer,List<float[]>> e : wallVerts.entrySet()) {
            Geometry geo = buildWallGeometry("walls_" + e.getKey(), e.getValue());
            geo.setMaterial((e.getKey()<wallMats.length&&wallMats[e.getKey()]!=null)?wallMats[e.getKey()]:fallbackMat);
            levelNode.attachChild(geo);
        }
        for (Map.Entry<Integer,List<float[]>> e : floorVerts.entrySet()) {
            Geometry geo = buildHorizGeometry("floor_" + e.getKey(), e.getValue(), true);
            geo.setMaterial((e.getKey()>0&&e.getKey()-1<floorMats.length&&floorMats[e.getKey()-1]!=null)?floorMats[e.getKey()-1]:fallbackMat);
            levelNode.attachChild(geo);
        }
        for (Map.Entry<Integer,List<float[]>> e : ceilVerts.entrySet()) {
            Geometry geo = buildHorizGeometry("ceil_" + e.getKey(), e.getValue(), false);
            geo.setMaterial((e.getKey()>0&&e.getKey()-1<floorMats.length&&floorMats[e.getKey()-1]!=null)?floorMats[e.getKey()-1]:fallbackMat);
            levelNode.attachChild(geo);
        }
        for (Map.Entry<String,DoorAccum> de : doorGroups.entrySet()) {
            DoorAccum acc = de.getValue(); if(acc.segments.isEmpty()) continue;
            Material mat = (acc.texIdx>=0&&acc.texIdx<wallMats.length&&wallMats[acc.texIdx]!=null)?wallMats[acc.texIdx]:fallbackMat;
            doors.add(new DoorSystem.DoorInfo(doors.size(), acc.segments, acc.yTop, acc.yBot, mat));
        }
        log.info("LEVEL : {} murs, {} portails, {} linteaux, {} portes", wCount, skipPortal, skipLintel, doors.size());
    }

    // ── Mesh builders ─────────────────────────────────────────────────────────

    private Geometry buildWallGeometry(String name, List<float[]> quads) {
        int n=quads.size();
        float[] pos=new float[n*12], uv=new float[n*8], nor=new float[n*12]; int[] idx=new int[n*6];
        int vi=0,ui=0,ni=0,ii=0;
        for (float[] q : quads) {
            float x0=q[0],z0=q[1],x1=q[2],z1=q[3],yTop=q[4],yBot=q[5],uM=q[6],vM=q[7];
            float dx=x1-x0, dz=z1-z0, L=(float)Math.sqrt(dx*dx+dz*dz);
            // Normale INTÉRIEURE = perpendiculaire gauche = (-dz, 0, dx)/L
            float nx=(L>0)?-dz/L:0f, nz=(L>0)?dx/L:1f;
            int base=vi/3;
            pos[vi++]=x0;pos[vi++]=yBot;pos[vi++]=z0; pos[vi++]=x1;pos[vi++]=yBot;pos[vi++]=z1;
            pos[vi++]=x1;pos[vi++]=yTop;pos[vi++]=z1; pos[vi++]=x0;pos[vi++]=yTop;pos[vi++]=z0;
            uv[ui++]=0f;uv[ui++]=0f; uv[ui++]=uM;uv[ui++]=0f; uv[ui++]=uM;uv[ui++]=vM; uv[ui++]=0f;uv[ui++]=vM;
            for(int k=0;k<4;k++){nor[ni++]=nx;nor[ni++]=0f;nor[ni++]=nz;}
            idx[ii++]=base;idx[ii++]=base+1;idx[ii++]=base+2; idx[ii++]=base;idx[ii++]=base+2;idx[ii++]=base+3;
        }
        return makeGeometry(name, pos, uv, nor, idx);
    }

    private void collectHorizPoly(int[] eids, Map<Integer,int[]> edges, float y,
                                   int matBucket, Map<Integer,List<float[]>> map) {
        int bucket = matBucket + 1;
        List<float[]> pts = map.computeIfAbsent(bucket, k -> new ArrayList<>());
        for (int eid : eids) {
            int[] e = edges.get(eid); if(e==null) continue;
            pts.add(new float[]{e[0]/SCALE, -e[1]/SCALE, y});
        }
        pts.add(null);  // marqueur fin de polygone
    }

    private Geometry buildHorizGeometry(String name, List<float[]> data, boolean isFloor) {
        List<Float>   pos=new ArrayList<>(), uvl=new ArrayList<>(), nor=new ArrayList<>();
        List<Integer> idx=new ArrayList<>();
        float ny = isFloor ? 1f : -1f;
        List<float[]> poly = new ArrayList<>();

        for (float[] pt : data) {
            if (pt == null) {
                if (poly.size() >= 3) {
                    float cx=0,cz=0;
                    for(float[] p:poly){cx+=p[0];cz+=p[1];} cx/=poly.size();cz/=poly.size();
                    float y = poly.get(0)[2];
                    int base = pos.size()/3;
                    pos.add(cx);pos.add(y);pos.add(cz); uvl.add(cx*(SCALE/TILE_SZ));uvl.add(-cz*(SCALE/TILE_SZ)); nor.add(0f);nor.add(ny);nor.add(0f);
                    for(float[] p:poly){pos.add(p[0]);pos.add(y);pos.add(p[1]);uvl.add(p[0]*(SCALE/TILE_SZ));uvl.add(-p[1]*(SCALE/TILE_SZ));nor.add(0f);nor.add(ny);nor.add(0f);}
                    int n=poly.size();
                    // Éventail — FaceCullMode.Off compense les inversements dus au flip Z
                    for(int i=0;i<n;i++){int next=(i+1)%n+1;idx.add(base);idx.add(base+i+1);idx.add(base+next);}
                }
                poly.clear();
            } else {
                poly.add(pt);
            }
        }
        float[] pa=new float[pos.size()]; for(int i=0;i<pa.length;i++) pa[i]=pos.get(i);
        float[] ua=new float[uvl.size()]; for(int i=0;i<ua.length;i++) ua[i]=uvl.get(i);
        float[] na=new float[nor.size()]; for(int i=0;i<na.length;i++) na[i]=nor.get(i);
        int[]   ia=new int[idx.size()];   for(int i=0;i<ia.length;i++) ia[i]=idx.get(i);
        return makeGeometry(name, pa, ua, na, ia);
    }

    private Geometry makeGeometry(String name, float[] pos, float[] uv, float[] nor, int[] idx) {
        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position,3,BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(Type.TexCoord,2,BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(Type.Normal,  3,BufferUtils.createFloatBuffer(nor));
        mesh.setBuffer(Type.Index,   3,BufferUtils.createIntBuffer(idx));
        mesh.updateBound(); mesh.setStatic();
        Geometry geo = new Geometry(name, mesh);
        geo.setShadowMode(RenderQueue.ShadowMode.Receive);
        return geo;
    }

    static int floorIdx(int wt) {
        if(wt<0) return -1;
        int C=wt&3, s=(wt>>2)/64, N=s*2+C+5;
        return (N>=1&&N<=16) ? N-1 : -1;
    }

    public Node                      getLevelNode()    { return levelNode; }
    public List<float[]>             getWallSegments() { return wallSegments; }
    public List<DoorSystem.DoorInfo> getDoors()        { return doors; }
    public void destroy()                              { levelNode.detachAllChildren(); }

    // ── JSON parser ───────────────────────────────────────────────────────────

    private List<String> elems(String j,String k){JsonArr a=parseRoot(j,k);return a!=null?a.elements:List.of();}
    private List<String> arr(String inner){return new JsonArr(inner).elements;}
    private JsonArr parseRoot(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('[',ki+k.length()+2),b=mbr(j,a,'[',']');return(a<0||b<0)?null:new JsonArr(j.substring(a+1,b));}
    private String extractObj(String j,String k){int ki=j.indexOf('"'+k+'"');if(ki<0)return null;int a=j.indexOf('{',ki+k.length()+2),b=mbr(j,a,'{','}');return(a<0||b<0)?null:j.substring(a+1,b);}
    static JsonObj obj(String s){Map<String,String> f=new LinkedHashMap<>();int st=s.indexOf('{');if(st<0)return new JsonObj(f);int en=mbr(s,st,'{','}');if(en<0)en=s.length()-1;String in=s.substring(st+1,en);int pos=0;while(pos<in.length()){int q1=in.indexOf('"',pos);if(q1<0)break;int q2=in.indexOf('"',q1+1);if(q2<0)break;String k=in.substring(q1+1,q2);int co=in.indexOf(':',q2+1);if(co<0)break;int vs=co+1;while(vs<in.length()&&in.charAt(vs)==' ')vs++;String val;char fc=vs<in.length()?in.charAt(vs):0;if(fc=='{'||fc=='['){char cl=fc=='{'?'}':']';int ve=mbr(in,vs,fc,cl);val=in.substring(vs,ve+1);pos=ve+1;}else if(fc=='"'){int ve=in.indexOf('"',vs+1);val=in.substring(vs+1,ve);pos=ve+1;}else{int ve=vs;while(ve<in.length()&&in.charAt(ve)!=','&&in.charAt(ve)!='}')ve++;val=in.substring(vs,ve).trim();pos=ve;}f.put(k,val);int cm=in.indexOf(',',pos);pos=cm>=0?cm+1:in.length();}return new JsonObj(f);}
    static int mbr(String s,int st,char op,char cl){int d=0;for(int i=st;i<s.length();i++){char c=s.charAt(i);if(c==op)d++;if(c==cl){d--;if(d==0)return i;}}return -1;}
    static class JsonObj{final Map<String,String> f;JsonObj(Map<String,String> f){this.f=f;}int i(String k){try{return Integer.parseInt(f.getOrDefault(k,"0").trim());}catch(NumberFormatException e){return 0;}}int[] iArr(String k){String v=f.get(k);if(v==null||!v.startsWith("["))return new int[0];List<Integer> r=new ArrayList<>();for(String s:new JsonArr(v.substring(1,v.lastIndexOf(']'))).elements)try{r.add(Integer.parseInt(s.trim()));}catch(Exception ignored){}return r.stream().mapToInt(Integer::intValue).toArray();}}
    static class JsonArr{final List<String> elements=new ArrayList<>();JsonArr(String in){int d=0,st=0;for(int i=0;i<in.length();i++){char c=in.charAt(i);if(c=='{'||c=='[')d++;else if(c=='}'||c==']')d--;else if(c==','&&d==0){String el=in.substring(st,i).trim();if(!el.isEmpty())elements.add(el);st=i+1;}}String last=in.substring(st).trim();if(!last.isEmpty())elements.add(last);}}

    private static class DoorAccum {
        int texIdx; float yTop, yBot;
        List<DoorSystem.DoorSegment> segments;
        DoorAccum(int ti, float yt, float yb, List<DoorSystem.DoorSegment> s){
            texIdx=ti; yTop=yt; yBot=yb; segments=s;
        }
    }
}
