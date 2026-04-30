package com.ab3d2.core.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Charge la table des 20 {@link AlienDef} depuis {@code assets/levels/definitions.json}.
 *
 * <p>Le fichier est genere par {@link com.ab3d2.tools.LevelJsonExporter#exportDefinitions}
 * a partir du GLF (TEST.LNK) du jeu. On le parse ici avec un parseur regex maison
 * pour eviter d'introduire une dependance JSON, ce qui est coherent avec
 * {@code LevelSceneBuilder} qui utilise la meme approche.</p>
 *
 * <p>Si un champ est absent du JSON (ex. ancien dump avant session 113 sans les
 * 16 champs de comportement), il est lu comme 0. Les aliens charges depuis un
 * vieux dump auront alors des comportements "tous ProwlRandom / Charge / Pause"
 * mais le code ne crashera pas.</p>
 *
 * @since session 113
 */
public final class AlienDefLoader {

    private AlienDefLoader() {}

    /** Le tableau extrait : index 0..19, ou null si pas defini. */
    private static final Pattern ALIEN_BLOCK = Pattern.compile(
        "\\{\\s*\"index\"\\s*:\\s*(\\d+)([^}]*)\\}", Pattern.DOTALL);

    /** Une paire {@code "field":value} dans un bloc alien. */
    private static final Pattern FIELD_INT = Pattern.compile(
        "\"(\\w+)\"\\s*:\\s*(-?\\d+)");

    private static final Pattern FIELD_STR = Pattern.compile(
        "\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * Charge les 20 definitions d'aliens depuis le fichier JSON donne.
     *
     * @param definitionsJson chemin vers {@code definitions.json}
     * @return tableau de 20 entrees, certaines peuvent etre null si parse echoue
     * @throws IOException si le fichier ne peut etre lu
     */
    public static AlienDef[] load(Path definitionsJson) throws IOException {
        String text = Files.readString(definitionsJson);
        return parse(text);
    }

    /**
     * Parse un JSON deja en memoire. Utile pour tests et pour le mode classpath
     * (charger depuis un .jar).
     */
    public static AlienDef[] parse(String text) {
        AlienDef[] result = new AlienDef[20];

        // Trouver le bloc "aliens": [ ... ]
        int aliensStart = text.indexOf("\"aliens\"");
        if (aliensStart < 0) return result;
        int arrStart = text.indexOf('[', aliensStart);
        int arrEnd   = text.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return result;
        String alienSection = text.substring(arrStart + 1, arrEnd);

        // Iterer sur tous les blocs { "index":N, ... }
        Matcher m = ALIEN_BLOCK.matcher(alienSection);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx < 0 || idx >= result.length) continue;
            String body = m.group(2);
            result[idx] = parseAlien(idx, body);
        }
        return result;
    }

    private static AlienDef parseAlien(int idx, String body) {
        // Extraire tous les champs entiers
        java.util.Map<String, Integer> ints = new java.util.HashMap<>();
        Matcher mi = FIELD_INT.matcher(body);
        while (mi.find()) {
            ints.put(mi.group(1), Integer.parseInt(mi.group(2)));
        }
        // Extraire les champs string
        java.util.Map<String, String> strs = new java.util.HashMap<>();
        Matcher ms = FIELD_STR.matcher(body);
        while (ms.find()) {
            strs.put(ms.group(1), ms.group(2));
        }

        // Construire l'AlienDef avec valeurs par defaut a 0
        return new AlienDef(
            idx,
            strs.getOrDefault("name", "alien_" + idx),
            ints.getOrDefault("gfxType",          0),
            ints.getOrDefault("defaultBehaviour", 0),
            ints.getOrDefault("reactionTime",     50),  // 1 sec par defaut
            ints.getOrDefault("defaultSpeed",     16),
            ints.getOrDefault("responseBehaviour", 0),
            ints.getOrDefault("responseSpeed",    32),
            ints.getOrDefault("responseTimeout",  100),
            ints.getOrDefault("damageToRetreat",  255),
            ints.getOrDefault("damageToFollowup", 16),
            ints.getOrDefault("followupBehaviour", 0),
            ints.getOrDefault("followupSpeed",    16),
            ints.getOrDefault("followupTimeout",  50),
            ints.getOrDefault("retreatBehaviour", 0),
            ints.getOrDefault("retreatSpeed",     32),
            ints.getOrDefault("retreatTimeout",   50),
            ints.getOrDefault("bulType",          0),
            ints.getOrDefault("hitPoints",        2),
            ints.getOrDefault("height",           128),
            ints.getOrDefault("girth",            1),
            ints.getOrDefault("splatType",        0),
            ints.getOrDefault("auxilliary",       -1)
        );
    }

    /**
     * Charge depuis le classpath ({@code /levels/definitions.json}). Utilise par
     * les tests unitaires.
     */
    public static AlienDef[] loadFromClasspath(String resourcePath) throws IOException {
        try (var in = AlienDefLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Resource not found: " + resourcePath);
            byte[] bytes = in.readAllBytes();
            return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Retourne la liste non-null des AlienDef pour debug. */
    public static List<AlienDef> nonNull(AlienDef[] all) {
        List<AlienDef> out = new ArrayList<>();
        for (AlienDef d : all) if (d != null) out.add(d);
        return out;
    }
}
