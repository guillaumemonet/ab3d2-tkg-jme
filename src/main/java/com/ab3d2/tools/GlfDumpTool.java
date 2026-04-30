package com.ab3d2.tools;

import com.ab3d2.combat.BulletDef;
import com.ab3d2.combat.GlfDatabase;
import com.ab3d2.combat.ShootDef;

import java.nio.file.Path;

/**
 * Outil de diagnostic : affiche le contenu du GLF (TEST.LNK).
 *
 * <p>Sert a valider que les offsets des structures ShootDefs/BulletDefs sont
 * correctement calcules. Affiche les noms des armes/bullets + les parametres
 * de chacun pour reperer les incoherences (valeurs aberrantes, noms tronques,
 * etc.).</p>
 *
 * <p>Usage : <code>./gradlew dumpGlf</code></p>
 */
public class GlfDumpTool {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "src/main/resources/TEST.LNK";

        System.out.println("=== GLF Dump: " + path + " ===");
        GlfDatabase glf = GlfDatabase.load(Path.of(path));
        System.out.printf("  Taille fichier: %d bytes%n%n", glf.getSize());

        // ── Guns ───────────────────────────────────────────────────────────
        System.out.println("=== GUNS (GLFT_GunNames + GLFT_ShootDefs) ===");
        System.out.println(" # | GunName              | bulType | delay | bulCnt | sfx | -> BulletName");
        System.out.println("---+----------------------+---------+-------+--------+-----+--------------");
        for (int i = 0; i < ShootDef.COUNT; i++) {
            ShootDef sd = glf.getShootDef(i);
            String gunName = glf.getGunName(i);
            String bulName = (sd.bulletType() < BulletDef.COUNT)
                ? glf.getBulletName(sd.bulletType()) : "???";
            System.out.printf("%2d | %-20s | %7d | %5d | %6d | %3d | %s%n",
                i, truncate(gunName, 20),
                sd.bulletType(), sd.delay(), sd.bulletCount(), sd.sfx(),
                bulName);
        }

        // ── Bullets ────────────────────────────────────────────────────────
        System.out.println();
        System.out.println("=== BULLETS (GLFT_BulletNames + GLFT_BulletDefs) ===");
        System.out.println(" # | BulletName           | hScan | grav  | life  | dmg | xplF | spd | aFr | pFr | gT | iGT");
        System.out.println("---+----------------------+-------+-------+-------+-----+------+-----+-----+-----+----+----");
        for (int i = 0; i < BulletDef.COUNT; i++) {
            BulletDef bd = glf.getBulletDef(i);
            String name = glf.getBulletName(i);
            System.out.printf("%2d | %-20s | %5d | %5d | %5d | %3d | %4d | %3d | %3d | %3d | %2d | %2d%n",
                i, truncate(name, 20),
                bd.isHitScan(), bd.gravity(), bd.lifetime(),
                bd.hitDamage(), bd.explosiveForce(), bd.speed(),
                bd.animFrames(), bd.popFrames(),
                bd.graphicType(), bd.impactGraphicType());
        }

        // ── Details par arme du player ─────────────────────────────────────
        System.out.println();
        System.out.println("=== DETAILS PAR ARME DU PLAYER ===");
        for (int i = 0; i < ShootDef.COUNT; i++) {
            ShootDef sd = glf.getShootDef(i);
            if (sd.bulletType() >= BulletDef.COUNT) continue;
            BulletDef bd = glf.getBulletDef(sd.bulletType());
            System.out.printf("%n[%d] %s  ->  bullet[%d] %s%n",
                i, glf.getGunName(i), sd.bulletType(), glf.getBulletName(sd.bulletType()));
            System.out.printf("    Cooldown: %d frames  |  Bullets/tir: %d  |  SFX: #%d%n",
                sd.delay(), sd.bulletCount(), sd.sfx());
            System.out.printf("    HitScan: %s  |  Gravity: %d  |  Lifetime: %s%n",
                bd.isHitScanBullet() ? "OUI" : "non",
                bd.gravity(),
                bd.lifetime() < 0 ? "infini" : bd.lifetime() + " frames");
            System.out.printf("    Damage: %d  |  Explosion: %s  |  Speed factor: %d  (vitesse = dir * 2^%d)%n",
                bd.hitDamage(),
                bd.isExplosive() ? String.valueOf(bd.explosiveForce()) : "non",
                bd.speed(), bd.speed());
            System.out.printf("    Anim: %d frames  |  Pop: %d frames  |  GfxType: %d  |  ImpactGfxType: %d%n",
                bd.animFrames(), bd.popFrames(),
                bd.graphicType(), bd.impactGraphicType());
            System.out.printf("    SFX Impact: #%d  |  SFX Bounce: #%d%n",
                bd.impactSfx(), bd.bounceSfx());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
