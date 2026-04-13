package com.ab3d2.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Convertit les samples audio Amiga en fichiers WAV pour JME.
 *
 * Formats supportes :
 *   - Raw PCM 8-bit signe (fichiers sans extension, ex: "door", "dooropen")
 *   - IFF 8SVX (ex: "SPLASH.IFF") : format IFF Amiga avec chunk VHDR + BODY
 *
 * Pourquoi pas FFmpeg ?
 *   Les samples Amiga sont du PCM 8-bit brut. Il suffit d'ajouter un header
 *   RIFF WAV standard. Pas de decompression necessaire pour les fichiers raw.
 *   JME3 (jme3-jogg) supporte WAV nativement.
 *
 * Frequences d'echantillonnage typiques :
 *   8363 Hz = tuning standard Amiga (periode 428)
 *   11025 Hz = frequence utilisee par beaucoup de jeux Amiga pour les SFX
 *   22050 Hz = haute qualite (rare sur Amiga 500)
 *
 * Les fichiers de ce projet n'ont pas de header indiquant leur frequence.
 * On utilise 11025 Hz par defaut (bonne approximation pour AB3D2).
 *
 * Usage Gradle : ./gradlew convertSounds
 */
public class SoundConverter {

    private static final int DEFAULT_SAMPLE_RATE = 11025;  // Hz
    private static final int CHANNELS = 1;                  // mono
    private static final int BITS_PER_SAMPLE = 8;          // 8-bit

    // Noms des fichiers raw a convertir (sans extension)
    // Priorite : newdoor > door > door01 > dooropen > liftnoise
    private static final String[] RAW_SFX_FILES = {
        "door", "door01", "dooropen", "newdoor", "liftnoise",
        "Switch", "Collect", "Teleport",
        "footclank", "footclop", "Footstep", "FootStep2", "FootStep3",
        "BOOM", "Shot", "shotgun", "silenced",
        "Shoot.dm",   // delta-mod - on essaie quand meme
        "Growl", "howl1", "howl2", "Hiss",
        "BIGSCREAM", "Scream", "LowScream",
        "marinedie", "marinelazer",
        "halfwormdie", "halfwormhowl", "halfwormpain",
        "SplatPop", "Splotch", "splash",
        "pant", "noammo", "whoosh"
    };

    public static void main(String[] args) throws Exception {
        String srcDir  = args.length > 0 ? args[0] : "src/main/resources/sounds/raw";
        String destDir = args.length > 1 ? args[1] : "assets/Sounds/sfx";

        Path src  = Path.of(srcDir);
        Path dest = Path.of(destDir);
        Files.createDirectories(dest);

        System.out.println("=== SoundConverter ===");
        System.out.println("Source : " + src);
        System.out.println("Dest   : " + dest);
        System.out.println();

        int ok = 0, skip = 0, fail = 0;

        // Convertir les fichiers raw connus
        for (String name : RAW_SFX_FILES) {
            Path inFile = src.resolve(name);
            if (!Files.exists(inFile)) continue;

            String outName = sanitizeName(name) + ".wav";
            Path outFile   = dest.resolve(outName);
            try {
                byte[] raw = Files.readAllBytes(inFile);
                byte[] pcm = tryExtractPCM(raw, name);
                if (pcm == null || pcm.length == 0) { skip++; continue; }
                writeWav(pcm, outFile, DEFAULT_SAMPLE_RATE);
                System.out.printf("  OK   %-30s -> %s  (%d bytes PCM)%n", name, outName, pcm.length);
                ok++;
            } catch (Exception e) {
                System.out.printf("  FAIL %-30s : %s%n", name, e.getMessage());
                fail++;
            }
        }

        // Convertir aussi les fichiers .IFF
        try (var stream = Files.newDirectoryStream(src, "*.IFF")) {
            for (Path iff : stream) {
                String outName = sanitizeName(iff.getFileName().toString().replace(".IFF","").replace(".iff","")) + ".wav";
                Path outFile   = dest.resolve(outName);
                try {
                    byte[] raw = Files.readAllBytes(iff);
                    byte[] pcm = parseIFF8SVX(raw);
                    if (pcm == null || pcm.length == 0) { skip++; continue; }
                    writeWav(pcm, outFile, DEFAULT_SAMPLE_RATE);
                    System.out.printf("  OK   %-30s -> %s  (%d bytes PCM)%n", iff.getFileName(), outName, pcm.length);
                    ok++;
                } catch (Exception e) {
                    System.out.printf("  FAIL %-30s : %s%n", iff.getFileName(), e.getMessage());
                    fail++;
                }
            }
        } catch (Exception ignored) {}

        System.out.printf("%nOK: %d  Skip: %d  Fail: %d%n", ok, skip, fail);
    }

    // ── Extraction PCM ────────────────────────────────────────────────────────

    /**
     * Tente d'extraire les donnees PCM depuis un fichier audio Amiga.
     * Detecte automatiquement le format.
     */
    private static byte[] tryExtractPCM(byte[] raw, String name) {
        if (raw.length < 4) return null;

        // IFF : commence par "FORM"
        if (raw[0]=='F' && raw[1]=='O' && raw[2]=='R' && raw[3]=='M') {
            return parseIFF8SVX(raw);
        }

        // Delta-mod (.dm) : format de compression Amiga
        // On ne supporte pas la decompression delta-mod, on skip
        if (name.endsWith(".dm")) return null;

        // Sinon : raw PCM 8-bit signe (le plus courant dans ce projet)
        return raw;
    }

    /**
     * Parse un fichier IFF 8SVX (format Amiga standard pour samples mono).
     * Structure : FORM [size] 8SVX
     *               VHDR [26] { oneShotLen, repeatLen, samplesPerHiCycle, samplesPerSec,
     *                           ctOctave, sCompression, volume }
     *               BODY [size] { samples... }
     */
    private static byte[] parseIFF8SVX(byte[] raw) {
        if (raw.length < 12) return null;
        if (raw[0]!='F'||raw[1]!='O'||raw[2]!='R'||raw[3]!='M') return null;
        if (raw[8]!='8'||raw[9]!='S'||raw[10]!='V'||raw[11]!='X') {
            // Essayer quand meme - peut-etre un autre type IFF
            // On cherche juste le chunk BODY
        }

        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        bb.position(12); // apres FORM + size + type

        while (bb.remaining() >= 8) {
            byte[] tag = new byte[4];
            bb.get(tag);
            int chunkSize = bb.getInt();
            if (chunkSize < 0 || chunkSize > raw.length) break;

            String tagStr = new String(tag);
            if ("BODY".equals(tagStr)) {
                byte[] body = new byte[chunkSize];
                bb.get(body);
                return body;
            }
            // Skip ce chunk (aligne sur 2 bytes)
            int skip = chunkSize + (chunkSize & 1);
            if (bb.position() + skip > bb.limit()) break;
            bb.position(bb.position() + skip);
        }
        return null;
    }

    // ── Ecriture WAV ──────────────────────────────────────────────────────────

    /**
     * Ecrit un fichier WAV (RIFF) depuis des donnees PCM 8-bit signe.
     *
     * ATTENTION : le format WAV standard attend du PCM 8-bit NON-signe (0..255).
     * Les samples Amiga sont signes (-128..127). On convertit en ajoutant 128.
     */
    private static void writeWav(byte[] pcmSigned, Path out, int sampleRate) throws IOException {
        // Convertir signed 8-bit -> unsigned 8-bit (convention WAV)
        byte[] pcm = new byte[pcmSigned.length];
        for (int i = 0; i < pcmSigned.length; i++) {
            pcm[i] = (byte)((pcmSigned[i] & 0xFF) ^ 0x80); // signed -> unsigned
        }

        int dataSize   = pcm.length;
        int byteRate   = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buf.put(new byte[]{'R','I','F','F'});
        buf.putInt(36 + dataSize);     // total size - 8
        buf.put(new byte[]{'W','A','V','E'});

        // fmt chunk
        buf.put(new byte[]{'f','m','t',' '});
        buf.putInt(16);                // chunk size
        buf.putShort((short)1);        // PCM = 1
        buf.putShort((short)CHANNELS);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short)blockAlign);
        buf.putShort((short)BITS_PER_SAMPLE);

        // data chunk
        buf.put(new byte[]{'d','a','t','a'});
        buf.putInt(dataSize);
        buf.put(pcm);

        Files.write(out, buf.array());
    }

    // ── Utilitaires ───────────────────────────────────────────────────────────

    /** Transforme un nom de fichier Amiga en nom de fichier valide. */
    private static String sanitizeName(String name) {
        return name.toLowerCase()
                   .replace(" ", "_")
                   .replace("!", "")
                   .replace(".", "_")
                   .replaceAll("[^a-z0-9_]", "");
    }
}
