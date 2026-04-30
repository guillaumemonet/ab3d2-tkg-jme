package com.ab3d2.tools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Outil de diagnostic bas niveau : dump hexa brut des 10 ShootDefs + 10 GunNames.
 *
 * <p>Sert a verifier ce qu'il y a REELLEMENT aux offsets GLFT_ShootDefs_l et
 * GLFT_GunNames_l, sans passer par notre parser (pour detecter un offset
 * incorrect ou des donnees inattendues).</p>
 *
 * <p>Usage : {@code ./gradlew dumpGlfRaw}</p>
 */
public class GlfRawGunDump {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "src/main/resources/TEST.LNK";
        byte[] data = Files.readAllBytes(Path.of(path));
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Offsets connus
        int GLFT_GUN_NAMES   = 13248;  // 10 * 20 = 200 bytes
        int GLFT_SHOOT_DEFS  = 13448;  // 10 * 8  = 80 bytes

        System.out.println("=== Dump brut GLF ===");
        System.out.printf("Fichier : %s (%d bytes)%n%n", path, data.length);

        // ── 10 noms d'armes (20 bytes chacun, NUL-terminated) ───────────────
        System.out.println("=== GLFT_GunNames_l @ offset 13248 (10 * 20 bytes) ===");
        for (int i = 0; i < 10; i++) {
            int off = GLFT_GUN_NAMES + i * 20;
            byte[] nameBytes = new byte[20];
            System.arraycopy(data, off, nameBytes, 0, 20);
            String name = readCString(nameBytes);
            System.out.printf("  Gun %d @ 0x%04X : \"%s\"  |  hex: %s%n",
                i, off, name, hexDump(nameBytes));
        }

        // ── 10 ShootDefs (8 bytes chacun : bulType, delay, count, sfx) ──────
        System.out.println();
        System.out.println("=== GLFT_ShootDefs_l @ offset 13448 (10 * 8 bytes) ===");
        System.out.println(" Gun | Offset  | bulType | delay | count | sfx | hex");
        System.out.println("-----+---------+---------+-------+-------+-----+------------------");
        for (int i = 0; i < 10; i++) {
            int off = GLFT_SHOOT_DEFS + i * 8;
            int bulType = buf.getShort(off)     & 0xFFFF;
            int delay   = buf.getShort(off + 2) & 0xFFFF;
            int count   = buf.getShort(off + 4) & 0xFFFF;
            int sfx     = buf.getShort(off + 6) & 0xFFFF;
            byte[] raw  = new byte[8];
            System.arraycopy(data, off, raw, 0, 8);
            System.out.printf("%4d | 0x%04X  | %7d | %5d | %5d | %3d | %s%n",
                i, off, bulType, delay, count, sfx, hexDump(raw));
        }

        // ── 20 noms de bullets (20 bytes chacun) ────────────────────────────
        int GLFT_BULLET_NAMES = 12848;
        System.out.println();
        System.out.println("=== GLFT_BulletNames_l @ offset 12848 (20 * 20 bytes) ===");
        for (int i = 0; i < 20; i++) {
            int off = GLFT_BULLET_NAMES + i * 20;
            byte[] nameBytes = new byte[20];
            System.arraycopy(data, off, nameBytes, 0, 20);
            String name = readCString(nameBytes);
            System.out.printf("  Bullet %2d @ 0x%04X : \"%s\"%n", i, off, name);
        }
    }

    private static String readCString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, 0, end);
    }

    private static String hexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
