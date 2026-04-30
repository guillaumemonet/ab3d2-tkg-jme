package com.ab3d2.combat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Parser du Game Link File (TEST.LNK) d'AB3D2.
 *
 * <p>Le GLF contient toutes les definitions statiques du jeu (armes, bullets,
 * aliens, objets, niveaux, sons...). Il est charge une fois au demarrage et
 * reference via <code>GLF_DatabasePtr_l</code> dans l'ASM.</p>
 *
 * <p>Structure du GLF (d'apres defs.i, <code>STRUCTURE GLFT,64</code>) :</p>
 * <pre>
 *    0..63   : header (taille fixe, contenu exact TBD)
 *   64+      : GLFT_LevelNames_l     (16*40     = 640)
 *  704+      : GLFT_ObjGfxNames_l    (30*64    = 1920)
 * 2624+      : GLFT_SFXFilenames_l   (64*60    = 3840)
 * 6464+      : GLFT_FloorFilename_l  (64)
 * 6528+      : GLFT_TextureFilename_l(192)
 * 6720+      : GLFT_GunGFXFilename_l (64)
 * 6784+      : GLFT_StoryFilename_l  (64)
 * 6848+      : GLFT_BulletDefs_l     (20*300   = 6000)   &lt;-- BulletDefs ici
 *12848+      : GLFT_BulletNames_l    (20*20    =  400)
 *13248+      : GLFT_GunNames_l       (10*20    =  200)
 *13448+      : GLFT_ShootDefs_l      (10*8     =   80)   &lt;-- ShootDefs ici
 *13528+      : ... (AlienNames, AlienDefs, ObjectDefs, ...)
 * </pre>
 *
 * <p>Cette classe ne lit pour l'instant que les offsets pertinents pour le
 * combat (ShootDefs + BulletDefs). Les autres sections seront ajoutees au
 * fur et a mesure des besoins.</p>
 */
public class GlfDatabase {

    // Offsets dans le GLF, calcules a partir des tailles des structures
    // precedentes dans STRUCTURE GLFT de defs.i
    // (STRUCTURE GLFT,64 -> commence a offset 64)
    public static final int GLFT_HEADER_SIZE         =    64;
    public static final int GLFT_LEVEL_NAMES         =    64;  // 16*40
    public static final int GLFT_OBJ_GFX_NAMES       =   704;  // 30*64
    public static final int GLFT_SFX_FILENAMES       =  2624;  // 64*60
    public static final int GLFT_FLOOR_FILENAME      =  6464;  // 64
    public static final int GLFT_TEXTURE_FILENAME    =  6528;  // 192
    public static final int GLFT_GUN_GFX_FILENAME    =  6720;  // 64
    public static final int GLFT_STORY_FILENAME      =  6784;  // 64
    public static final int GLFT_BULLET_DEFS         =  6848;  // 20*300 = 6000
    public static final int GLFT_BULLET_NAMES        = 12848;  // 20*20  =  400
    public static final int GLFT_GUN_NAMES           = 13248;  // 10*20  =  200
    public static final int GLFT_SHOOT_DEFS          = 13448;  // 10*8   =   80

    public static final int NAME_LENGTH = 20;

    private final byte[]      rawData;
    private final BulletDef[] bulletDefs;
    private final ShootDef[]  shootDefs;
    private final String[]    bulletNames;
    private final String[]    gunNames;

    // ── Construction / chargement ──────────────────────────────────────────

    /**
     * Parse un GLF en memoire.
     *
     * @param data bytes bruts du fichier TEST.LNK
     */
    public GlfDatabase(byte[] data) {
        this.rawData     = Objects.requireNonNull(data);
        this.bulletDefs  = parseBulletDefs(data);
        this.shootDefs   = parseShootDefs(data);
        this.bulletNames = parseNames(data, GLFT_BULLET_NAMES, BulletDef.COUNT);
        this.gunNames    = parseNames(data, GLFT_GUN_NAMES,    ShootDef.COUNT);
    }

    /** Charge le GLF depuis un chemin disque. */
    public static GlfDatabase load(Path path) throws IOException {
        return new GlfDatabase(Files.readAllBytes(path));
    }

    /**
     * Charge le GLF depuis le classpath (resources).
     *
     * @param resourcePath chemin relatif dans le classpath, ex: "TEST.LNK"
     */
    public static GlfDatabase loadFromResource(String resourcePath) throws IOException {
        try (var in = GlfDatabase.class.getClassLoader()
                                       .getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource introuvable: " + resourcePath);
            }
            return new GlfDatabase(in.readAllBytes());
        }
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    private static ShootDef[] parseShootDefs(byte[] data) {
        ShootDef[] defs = new ShootDef[ShootDef.COUNT];
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < ShootDef.COUNT; i++) {
            int base = GLFT_SHOOT_DEFS + i * ShootDef.SIZE_BYTES;
            if (base + ShootDef.SIZE_BYTES > data.length) {
                // GLF tronque : met des defs a zero pour eviter NPE
                defs[i] = new ShootDef(0, 0, 0, 0);
                continue;
            }
            defs[i] = new ShootDef(
                b.getShort(base + ShootDef.OFS_BUL_TYPE)  & 0xFFFF,
                b.getShort(base + ShootDef.OFS_DELAY)     & 0xFFFF,
                b.getShort(base + ShootDef.OFS_BUL_COUNT) & 0xFFFF,
                b.getShort(base + ShootDef.OFS_SFX)       & 0xFFFF
            );
        }
        return defs;
    }

    private static BulletDef[] parseBulletDefs(byte[] data) {
        BulletDef[] defs = new BulletDef[BulletDef.COUNT];
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < BulletDef.COUNT; i++) {
            int base = GLFT_BULLET_DEFS + i * BulletDef.SIZE_BYTES;
            if (base + BulletDef.SIZE_BYTES > data.length) {
                defs[i] = new BulletDef(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    new byte[BulletDef.ANIM_DATA_SIZE], new byte[BulletDef.POP_DATA_SIZE]);
                continue;
            }

            byte[] animData = new byte[BulletDef.ANIM_DATA_SIZE];
            byte[] popData  = new byte[BulletDef.POP_DATA_SIZE];
            System.arraycopy(data, base + BulletDef.OFS_ANIM_DATA, animData, 0, BulletDef.ANIM_DATA_SIZE);
            System.arraycopy(data, base + BulletDef.OFS_POP_DATA,  popData,  0, BulletDef.POP_DATA_SIZE);

            defs[i] = new BulletDef(
                b.getInt(base + BulletDef.OFS_IS_HITSCAN),
                b.getInt(base + BulletDef.OFS_GRAVITY),
                b.getInt(base + BulletDef.OFS_LIFETIME),
                b.getInt(base + BulletDef.OFS_AMMO_IN_CLIP),
                b.getInt(base + BulletDef.OFS_BOUNCE_HORIZ),
                b.getInt(base + BulletDef.OFS_BOUNCE_VERT),
                b.getInt(base + BulletDef.OFS_HIT_DAMAGE),
                b.getInt(base + BulletDef.OFS_EXPLOSIVE_FORCE),
                b.getInt(base + BulletDef.OFS_SPEED),
                b.getInt(base + BulletDef.OFS_ANIM_FRAMES),
                b.getInt(base + BulletDef.OFS_POP_FRAMES),
                b.getInt(base + BulletDef.OFS_BOUNCE_SFX),
                b.getInt(base + BulletDef.OFS_IMPACT_SFX),
                b.getInt(base + BulletDef.OFS_GRAPHIC_TYPE),
                b.getInt(base + BulletDef.OFS_IMPACT_GFX_TYPE),
                animData,
                popData
            );
        }
        return defs;
    }

    private static String[] parseNames(byte[] data, int baseOffset, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            int base = baseOffset + i * NAME_LENGTH;
            if (base + NAME_LENGTH > data.length) {
                names[i] = "";
                continue;
            }
            names[i] = readNulTerminatedString(data, base, NAME_LENGTH);
        }
        return names;
    }

    /**
     * Lit une string C-style (termine par \0 ou par la fin du buffer alloue).
     */
    private static String readNulTerminatedString(byte[] data, int offset, int maxLen) {
        int end = offset;
        int limit = Math.min(offset + maxLen, data.length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset);
    }

    // ── Accesseurs ─────────────────────────────────────────────────────────

    public ShootDef  getShootDef (int gunIdx)    { return shootDefs[gunIdx];  }
    public BulletDef getBulletDef(int bulletIdx) { return bulletDefs[bulletIdx]; }
    public String    getBulletName(int bulletIdx){ return bulletNames[bulletIdx]; }
    public String    getGunName  (int gunIdx)    { return gunNames[gunIdx]; }
    public int       getSize()                   { return rawData.length; }
    public byte[]    getRawData()                { return rawData; }
}
