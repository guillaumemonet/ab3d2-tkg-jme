package com.ab3d2.core.math;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;

/**
 * Tables mathématiques AB3D2 — SinCosTable (bigsine) + ConstantTable.
 *
 * sinw(angle) = SinCosTable[(angle & 8190) >> 1]
 * cosw(angle) = SinCosTable[((angle + 2048) & 8190) >> 1]
 *
 * sinf/cosf : angle 0..4095 → float [-1..1]
 */
public class Tables {

    private static final Logger log = LoggerFactory.getLogger(Tables.class);

    public static final int SINTAB_SIZE  = 8192;
    public static final int SINTAB_BYTES = SINTAB_SIZE * 2;
    public static final int CONST_SIZE   = 8192;
    public static final int SINE_QUARTER = SINTAB_SIZE / 4;

    private static short[] sinCosTable;
    private static int[]   constantTable;
    private static boolean initialized = false;

    public static void init(Path bigsinePath) throws IOException {
        initFromBytes(Files.readAllBytes(bigsinePath));
    }

    public static void initFromClasspath() throws IOException {
        try (InputStream is = Tables.class.getResourceAsStream("/bigsine")) {
            if (is == null) throw new IOException("/bigsine introuvable");
            initFromBytes(is.readAllBytes());
        }
    }

    public static void initFromBytes(byte[] data) {
        sinCosTable = new short[SINTAB_SIZE];
        if (data.length >= SINTAB_BYTES) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < SINTAB_SIZE; i++) sinCosTable[i] = buf.getShort();
            log.info("SinCosTable chargée ({} bytes)", SINTAB_BYTES);
        } else {
            log.warn("bigsine trop petit ({} bytes), table synthétique", data.length);
            for (int i = 0; i < SINTAB_SIZE; i++) {
                double a = 2.0 * Math.PI * 2.0 * i / SINTAB_SIZE;
                sinCosTable[i] = (short) Math.round(Math.sin(a) * 32767.0);
            }
        }
        buildConstantTable();
        initialized = true;
        log.info("Tables initialisées");
    }

    private static void buildConstantTable() {
        constantTable = new int[CONST_SIZE * 2];
        for (int k = 1; k <= CONST_SIZE; k++) {
            long c = (16384L * 64L) / k;
            long e = (64L * 64L * 65536L) / c;
            long d = (e * (c / 2 - 40L * 64L)) >> 6;
            constantTable[(k-1)*2]   = (int) e;
            constantTable[(k-1)*2+1] = (int) d;
        }
    }

    public static short sinw(int angle) {
        checkInit();
        return sinCosTable[(angle & (SINTAB_SIZE - 2)) >> 1];
    }

    public static short cosw(int angle) {
        checkInit();
        return sinCosTable[((angle + SINE_QUARTER) & (SINTAB_SIZE - 2)) >> 1];
    }

    public static float sinf(int angle) { return sinw(angle * 2) / 32767.0f; }
    public static float cosf(int angle) { return cosw(angle * 2) / 32767.0f; }

    public static int  constE(int k) { checkInit(); return (k>=1&&k<=CONST_SIZE)?constantTable[(k-1)*2]:0; }
    public static int  constD(int k) { checkInit(); return (k>=1&&k<=CONST_SIZE)?constantTable[(k-1)*2+1]:0; }

    public static boolean isInitialized() { return initialized; }

    private static void checkInit() {
        if (!initialized)
            throw new IllegalStateException("Tables.init() doit être appelé d'abord.");
    }
}
