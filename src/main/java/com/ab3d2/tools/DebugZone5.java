// Script de debug pour extraire les walls de la zone 5
// Lance avec: java -cp build/classes/java/main com.ab3d2.tools.DebugZone5
package com.ab3d2.tools;

import java.io.*;
import java.nio.file.*;

public class DebugZone5 {
    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of("C:\\Users\\guill\\Documents\\NetBeansProjects\\ab3d2-tkg-jme\\assets/levels/level_A.json"));
        // Trouver "5":[ dans walls
        int wallsIdx = json.indexOf("\"walls\"");
        if (wallsIdx < 0) { System.out.println("walls section not found"); return; }
        // Chercher "5":[
        int z5Idx = json.indexOf("\"5\":[", wallsIdx);
        if (z5Idx < 0) { System.out.println("zone 5 not found in walls"); return; }
        // Trouver le ] de fermeture
        int start = z5Idx;
        int end = json.indexOf("]", z5Idx);
        // Skip nested braces
        int depth = 0;
        for (int i = start + 5; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                if (depth == 0) { end = i; break; }
                depth--;
            }
        }
        System.out.println("=== Walls de la zone 5 (zone-porte) ===");
        System.out.println(json.substring(z5Idx, end + 1));
    }
}
