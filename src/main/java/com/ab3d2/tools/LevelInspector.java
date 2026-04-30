package com.ab3d2.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Affiche un rapport synthétique des données enrichies dans level_*.json
 * (session 99). Liste les téléporteurs, zones à 2 niveaux, water zones,
 * control points, messages texte etc.
 *
 * Usage : ./gradlew levelInspect
 *         ./gradlew levelInspect -Plevel=A
 *
 * Note : ce tool fait du pattern matching sur le JSON. Il n'est pas robuste
 * a tous les formats mais suffit pour l'inspection rapide.
 */
public class LevelInspector {

    public static void main(String[] args) throws Exception {
        Path levelsDir = Path.of(args.length > 0 ? args[0]
            : "C:/Users/guill/Documents/NetBeansProjects/ab3d2-tkg-jme/assets/levels");
        String filter  = args.length > 1 ? args[1] : null;

        if (!Files.isDirectory(levelsDir)) {
            System.err.println("Repertoire introuvable : " + levelsDir);
            System.exit(1);
        }

        File[] files = levelsDir.toFile().listFiles((d,n) -> n.matches("level_[A-P]\\.json"));
        if (files == null || files.length == 0) {
            System.err.println("Aucun level_*.json trouve dans " + levelsDir);
            System.exit(1);
        }
        Arrays.sort(files);

        for (File f : files) {
            String levelId = f.getName().replace("level_","").replace(".json","");
            if (filter != null && !filter.equalsIgnoreCase(levelId)) continue;
            inspect(levelId, Files.readString(f.toPath()));
        }
    }

    private static void inspect(String levelId, String json) {
        System.out.println("\n========================================================");
        System.out.println("  LEVEL " + levelId);
        System.out.println("========================================================");

        // Messages texte
        inspectMessages(json);

        // Control points
        inspectControlPoints(json);

        // Zones (telep, upper, water)
        inspectZonesSpecial(json);

        // Doors/Lifts/Switches
        inspectInteractive(json);

        // Stats globales
        inspectStats(json);
    }

    private static void inspectMessages(String json) {
        // Extrait section "messages": [...]
        Matcher m = Pattern.compile("\"messages\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) { System.out.println("[messages] section absente"); return; }
        String body = m.group(1);
        Matcher entries = Pattern.compile("\\{\"id\":(\\d+),\"text\":\"([^\"]*)\"\\}").matcher(body);
        int count = 0, nonEmpty = 0;
        StringBuilder lines = new StringBuilder();
        while (entries.find()) {
            count++;
            String txt = entries.group(2);
            if (!txt.isBlank()) {
                nonEmpty++;
                lines.append(String.format("  [%s] %.80s%n", entries.group(1),
                    txt.length() > 80 ? txt.substring(0,77)+"..." : txt));
            }
        }
        System.out.println("\n--- MESSAGES TEXTE (" + nonEmpty + "/" + count + " non vides) ---");
        if (nonEmpty > 0) System.out.print(lines);
        else System.out.println("  (aucun message non vide)");
    }

    private static void inspectControlPoints(String json) {
        Matcher m = Pattern.compile("\"controlPoints\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) { System.out.println("[controlPoints] section absente"); return; }
        Matcher entries = Pattern.compile("\\{\"id\":\\d+").matcher(m.group(1));
        int n = 0;
        while (entries.find()) n++;
        System.out.println("\n--- CONTROL POINTS : " + n + " ---");
    }

    private static void inspectZonesSpecial(String json) {
        // Extrait section "zones": [...]
        Matcher m = Pattern.compile("\"zones\"\\s*:\\s*\\[(.*?)\\n  \\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) { System.out.println("[zones] section absente"); return; }
        String body = m.group(1);

        // Pour chaque zone, on extrait id + floorH + roofH + telZone + hasUpper + water
        Matcher zone = Pattern.compile(
            "\\{\"id\":(\\d+),\"floorH\":(-?\\d+),\"roofH\":(-?\\d+).*?" +
            "\"hasUpper\":(true|false).*?" +
            "\"water\":(-?\\d+).*?" +
            "\"echo\":(\\d+).*?" +
            "\"telZone\":(-?\\d+),\"telX\":(-?\\d+),\"telZ\":(-?\\d+)" +
            ".*?\\}", Pattern.DOTALL).matcher(body);

        List<String> teleps = new ArrayList<>();
        List<String> uppers = new ArrayList<>();
        List<String> waters = new ArrayList<>();
        Map<Integer,Integer> echoCount = new TreeMap<>();
        int total = 0;
        while (zone.find()) {
            total++;
            int id = Integer.parseInt(zone.group(1));
            int floorH = Integer.parseInt(zone.group(2));
            int roofH = Integer.parseInt(zone.group(3));
            boolean upper = zone.group(4).equals("true");
            int water = Integer.parseInt(zone.group(5));
            int echo = Integer.parseInt(zone.group(6));
            int telZone = Integer.parseInt(zone.group(7));
            int telX = Integer.parseInt(zone.group(8));
            int telZ = Integer.parseInt(zone.group(9));

            if (telZone >= 0 && telZone != id) {
                teleps.add(String.format("  zone %3d -> zone %d (%d, %d)", id, telZone, telX, telZ));
            }
            if (upper) {
                uppers.add(String.format("  zone %3d : floor=%d roof=%d upper visible", id, floorH, roofH));
            }
            // Water visible : water < floorH (au-dessus du sol en convention editeur)
            // Plus eleve dans le monde = valeur plus PETITE en editeur
            if (water < floorH) {
                waters.add(String.format(
                    "  zone %3d : water=%d (au dessus floorH=%d) -> eau visible",
                    id, water, floorH));
            }
            if (echo > 0) echoCount.merge(echo, 1, Integer::sum);
        }
        System.out.println("\n--- ZONES (total : " + total + ") ---");

        System.out.println("  TELEPORTEURS  : " + teleps.size());
        teleps.forEach(System.out::println);

        System.out.println("  ZONES 2 NIV   : " + uppers.size());
        uppers.forEach(System.out::println);

        System.out.println("  ZONES W/ EAU  : " + waters.size() + " (water visible)");
        waters.forEach(System.out::println);

        if (!echoCount.isEmpty()) {
            System.out.println("  ECHO (reverb) : " + echoCount.values().stream().mapToInt(Integer::intValue).sum() + " zones");
            echoCount.forEach((level, count) ->
                System.out.printf("    echo level %d : %d zones%n", level, count));
        }
    }

    private static void inspectInteractive(String json) {
        int doors = countSection(json, "doors");
        int lifts = countSection(json, "lifts");

        // Switch offset
        Matcher m = Pattern.compile("\"switchesDataOffset\"\\s*:\\s*(\\d+)").matcher(json);
        int swOfs = m.find() ? Integer.parseInt(m.group(1)) : -1;

        // Switches actifs
        Matcher swSec = Pattern.compile("\"switches\"\\s*:\\s*\\[(.*?)\\n  \\]", Pattern.DOTALL).matcher(json);
        int swCount = 0;
        StringBuilder swDetails = new StringBuilder();
        if (swSec.find()) {
            Matcher swEntries = Pattern.compile(
                "\\{\"index\":(\\d+),\"active\":(-?\\d+),\"pointIndex\":(\\d+)," +
                "\"gfxOffset\":(-?\\d+),\"pressed\":(\\d+).*?\"conditionBit\":(\\d+)\\}"
            ).matcher(swSec.group(1));
            while (swEntries.find()) {
                swCount++;
                swDetails.append(String.format(
                    "    [#%s] active=%s point=%s gfxOfs=%s pressed=%s -> bit %s of Conditions%n",
                    swEntries.group(1), swEntries.group(2), swEntries.group(3),
                    swEntries.group(4), swEntries.group(5), swEntries.group(6)));
            }
        }

        System.out.println("\n--- INTERACTIF ---");
        System.out.println("  Doors    : " + doors);
        System.out.println("  Lifts    : " + lifts);
        System.out.println("  Switches : " + swCount + " actifs (data @ offset " + swOfs + ")");
        if (swCount > 0) System.out.print(swDetails);
    }

    private static int countSection(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\\[(.*?)\\n  \\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) return 0;
        return (int) Pattern.compile("\\{\"zoneId\"").matcher(m.group(1)).results().count();
    }

    private static void inspectStats(String json) {
        int points = (int) Pattern.compile("\"points\":\\[\\s*\\{\\s*\"id\"").matcher(json).results().count();
        // Plutot que de chercher "points":[\s*\{ qui ne marche pas, on compte les ids dans la section points
        int npoints = countIds(json, "points");
        int nedges  = countIds(json, "edges");
        int nobjs   = countObjects(json);

        System.out.println("\n--- STATS ---");
        System.out.println("  Points  : " + npoints);
        System.out.println("  Edges   : " + nedges);
        System.out.println("  Objects : " + nobjs);
    }

    private static int countIds(String json, String section) {
        Matcher m = Pattern.compile("\"" + section + "\"\\s*:\\s*\\[(.*?)\\n  \\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) return 0;
        return (int) Pattern.compile("\"id\":\\d+").matcher(m.group(1)).results().count();
    }

    private static int countObjects(String json) {
        Matcher m = Pattern.compile("\"objects\"\\s*:\\s*\\[(.*?)\\n  \\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) return 0;
        return (int) Pattern.compile("\"x\":-?\\d+,\"z\"").matcher(m.group(1)).results().count();
    }
}
