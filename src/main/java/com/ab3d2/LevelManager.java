package com.ab3d2;

import com.ab3d2.core.level.GraphicsBinaryParser;
import com.ab3d2.core.level.LevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache et chargement des niveaux A-P.
 * Ressources attendues : resources/levels/LEVEL_X/twolev.bin + twolev.graph.bin
 */
public class LevelManager {

    private static final Logger log = LoggerFactory.getLogger(LevelManager.class);

    private final Path levelsRoot;
    private final GraphicsBinaryParser assembler = new GraphicsBinaryParser();
    private final Map<String, LevelData> cache = new HashMap<>();

    public LevelManager(Path assetsRoot) {
        this.levelsRoot = assetsRoot.resolve("levels");
        log.info("LevelManager root: {}", levelsRoot.toAbsolutePath());
    }

    public LevelData load(String levelLetter) throws IOException {
        String key = levelLetter.toUpperCase();
        if (cache.containsKey(key)) return cache.get(key);

        Path levelDir = levelsRoot.resolve("LEVEL_" + key);
        if (!Files.isDirectory(levelDir))
            throw new IOException("Répertoire niveau introuvable : " + levelDir);

        Path binPath   = levelDir.resolve("twolev.bin");
        Path graphPath = levelDir.resolve("twolev.graph.bin");
        if (!Files.exists(binPath))   throw new IOException("twolev.bin manquant : "       + binPath);
        if (!Files.exists(graphPath)) throw new IOException("twolev.graph.bin manquant : " + graphPath);

        log.info("Chargement LEVEL_{}", key);
        LevelData data = assembler.load(binPath, graphPath, key);
        cache.put(key, data);
        log.info("LEVEL_{} chargé : {}", key, data);
        return data;
    }

    public LevelData getCached(String levelLetter) { return cache.get(levelLetter.toUpperCase()); }
    public void clearCache() { cache.clear(); }

    public String[] listAvailableLevels() {
        if (!Files.isDirectory(levelsRoot)) return new String[0];
        try (var s = Files.list(levelsRoot)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("LEVEL_"))
                    .map(n -> n.substring(6))
                    .sorted()
                    .toArray(String[]::new);
        } catch (IOException e) { return new String[0]; }
    }

    public Path getLevelsRoot() { return levelsRoot; }
}
