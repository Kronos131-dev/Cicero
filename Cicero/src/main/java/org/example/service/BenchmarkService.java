package org.example.service;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class BenchmarkService {

    private JSONObject benchmarksCache;

    public BenchmarkService() {
        loadBenchmarks();
    }

    /**
     * Charge le fichier JSON en mémoire depuis le dossier src/main/resources au démarrage du bot.
     */
    private void loadBenchmarks() {
        // Le ClassLoader va chercher directement à la racine du dossier resources/
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("benchmarks.json")) {
            if (is == null) {
                System.err.println("❌ Fichier benchmarks.json introuvable dans le dossier resources ! Le ScoreCalculator risque de planter.");
                this.benchmarksCache = new JSONObject();
                return;
            }

            // Lecture du flux de données (InputStream) vers une String
            try (Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name())) {
                String content = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                this.benchmarksCache = new JSONObject(content);
                System.out.println("✅ Benchmarks chargés avec succès en mémoire !");
            }
        } catch (Exception e) {
            System.err.println("❌ Erreur lors de la lecture des benchmarks : " + e.getMessage());
            this.benchmarksCache = new JSONObject();
        }
    }

    /**
     * Retourne l'objet JSON contenant toutes les statistiques de référence par Élo.
     */
    public JSONObject getBenchmarks() {
        if (benchmarksCache == null || benchmarksCache.isEmpty()) {
            loadBenchmarks();
        }
        return benchmarksCache;
    }
}