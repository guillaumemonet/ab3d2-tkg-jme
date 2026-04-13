package com.ab3d2.world;

import com.jme3.material.Material;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.util.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Système de portes dynamiques — JME scene graph (utilisé sans .j3o).
 *
 * Normale porte : (-wz, 0, wx)/L = perpendiculaire GAUCHE = vers l'intérieur.
 */
public class DoorSystem {

    private static final Logger log = LoggerFactory.getLogger(DoorSystem.class);

    public static final float TRIGGER_DIST        = 3.0f;
    public static final float COLLISION_THRESHOLD = 0.5f;
    public static final float OPEN_SPEED          = 1.5f;
    public static final float CLOSE_SPEED         = 0.8f;

    public record DoorSegment(float x0, float z0, float x1, float z1) {}

    public record DoorInfo(
        int               id,
        List<DoorSegment> segments,
        float             yTop,
        float             yBot,
        Material          mat
    ) {}

    private final List<DoorInfo> doors  = new ArrayList<>();
    private final List<Node>     nodes  = new ArrayList<>();
    private float[]              states = new float[0];
    private boolean              initialized = false;

    public void init(List<DoorInfo> doorList, Node rootNode) {
        doors.clear(); doors.addAll(doorList);
        nodes.clear();
        states = new float[doors.size()];
        for (int i = 0; i < doors.size(); i++) {
            states[i] = 0f;
            Node n = new Node("door_" + i);
            rebuildNode(n, doors.get(i), 0f);
            rootNode.attachChild(n);
            nodes.add(n);
        }
        initialized = true;
        log.info("DoorSystem : {} portes ({} segments)",
                 doors.size(), doors.stream().mapToInt(d -> d.segments().size()).sum());
    }

    public void update(float px, float pz, float tpf, WallCollision collision) {
        if (!initialized) return;
        for (int i = 0; i < doors.size(); i++) {
            DoorInfo d = doors.get(i);
            float prev = states[i];
            float minDist = Float.MAX_VALUE;
            for (DoorSegment s : d.segments())
                minDist = Math.min(minDist, distSeg(px,pz,s.x0(),s.z0(),s.x1(),s.z1()));
            if (minDist < TRIGGER_DIST) states[i] = Math.min(1f, states[i] + OPEN_SPEED * tpf);
            else                         states[i] = Math.max(0f, states[i] - CLOSE_SPEED * tpf);
            if (Math.abs(states[i] - prev) > 0.004f) {
                Node n = nodes.get(i); n.detachAllChildren();
                if (states[i] < 0.999f) rebuildNode(n, d, states[i]);
            }
            nodes.get(i).setCullHint(states[i]>=0.999f?Spatial.CullHint.Always:Spatial.CullHint.Dynamic);
            if (states[i] < COLLISION_THRESHOLD)
                for (DoorSegment s : d.segments())
                    collision.addSegment(s.x0(),s.z0(),s.x1(),s.z1());
        }
    }

    private static void rebuildNode(Node node, DoorInfo d, float open) {
        float fullH   = d.yTop() - d.yBot();
        float curYBot = d.yBot() + fullH * open;
        if (curYBot >= d.yTop() - 0.005f) return;
        for (int s=0; s<d.segments().size(); s++)
            node.attachChild(makeSegGeo("seg_"+s, d.segments().get(s), d.yTop(), curYBot, d.mat()));
    }

    private static Geometry makeSegGeo(String name, DoorSegment seg,
                                        float yTop, float yBot, Material mat) {
        float x0=seg.x0(), z0=seg.z0(), x1=seg.x1(), z1=seg.z1();
        float wx=x1-x0, wz=z1-z0;
        float len = Math.max((float)Math.sqrt(wx*wx+wz*wz), 1e-5f);
        float uMax = len * LevelGeometry.SCALE / 256f;
        float vMax = (yTop-yBot) * LevelGeometry.SCALE / 128f;
        // Normale INTÉRIEURE = perpendiculaire gauche = (-wz, 0, wx) / len
        float nx = -wz/len, nz = wx/len;

        Mesh mesh = new Mesh();
        mesh.setBuffer(Type.Position, 3, BufferUtils.createFloatBuffer(new float[]{
            x0,yBot,z0,  x1,yBot,z1,  x1,yTop,z1,  x0,yTop,z0
        }));
        mesh.setBuffer(Type.TexCoord, 2, BufferUtils.createFloatBuffer(new float[]{
            0f,0f,  uMax,0f,  uMax,vMax,  0f,vMax
        }));
        mesh.setBuffer(Type.Normal, 3, BufferUtils.createFloatBuffer(new float[]{
            nx,0f,nz,  nx,0f,nz,  nx,0f,nz,  nx,0f,nz
        }));
        mesh.setBuffer(Type.Index, 3, BufferUtils.createIntBuffer(new int[]{0,1,2, 0,2,3}));
        mesh.updateBound();
        Geometry geo = new Geometry(name, mesh);
        geo.setMaterial(mat);
        geo.setShadowMode(RenderQueue.ShadowMode.Receive);
        return geo;
    }

    private static float distSeg(float px,float pz,float ax,float az,float bx,float bz){
        float wx=bx-ax, wz=bz-az, l2=wx*wx+wz*wz;
        if(l2<1e-6f){float ex=px-ax,ez=pz-az;return(float)Math.sqrt(ex*ex+ez*ez);}
        float t=Math.max(0f,Math.min(1f,((px-ax)*wx+(pz-az)*wz)/l2));
        float cx=ax+t*wx,cz=az+t*wz,ex=px-cx,ez=pz-cz;
        return(float)Math.sqrt(ex*ex+ez*ez);
    }

    public int   doorCount()     { return doors.size(); }
    public float getState(int i) { return i<states.length?states[i]:0f; }

    public void destroy(Node rootNode) {
        for (Node n : nodes) rootNode.detachChild(n);
        nodes.clear(); doors.clear(); initialized=false;
    }
}
