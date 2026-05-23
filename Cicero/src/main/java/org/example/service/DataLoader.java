package org.example.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A utility class for loading data resources like JSON files from the classpath.
 * It includes fallback mechanisms to prevent crashes if a resource is missing or corrupt.
 */
public class DataLoader {

    private static final Logger logger = LoggerFactory.getLogger(DataLoader.class);

    /**
     * Loads the champion class mappings from the "/champion_classes.json" resource file.
     *
     * @return A Map where the key is the champion name (lowercase) and the value is the champion's class.
     *         Returns an empty map if the file cannot be loaded or parsed.
     */
    public static Map<String, String> loadChampionClasses() {
        try (InputStream is = DataLoader.class.getResourceAsStream("/champion_classes.json")) {
            if (is == null) {
                logger.error("Cannot find resource: /champion_classes.json. Champion classes will not be loaded.");
                return Collections.emptyMap();
            }
            String jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(jsonText);
            Map<String, String> championClasses = new HashMap<>();

            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String className = keys.next();
                JSONArray champions = json.getJSONArray(className);
                for (int i = 0; i < champions.length(); i++) {
                    String championName = champions.getString(i);
                    championClasses.put(championName.toLowerCase(), className);
                }
            }
            logger.info("Successfully loaded {} champion class mappings.", championClasses.size());
            return championClasses;
        } catch (Exception e) {
            logger.error("Failed to load or parse /champion_classes.json. Using empty map as fallback.", e);
            return Collections.emptyMap(); // Fallback to an empty map
        }
    }

    /**
     * Loads the scoring benchmarks from the "/benchmarks.json" resource file.
     *
     * @return A JSONObject containing the benchmark data.
     *         Returns an empty JSONObject if the file cannot be loaded or parsed.
     */
    public static JSONObject loadBenchmarks() {
        try (InputStream is = DataLoader.class.getResourceAsStream("/benchmarks.json")) {
            if (is == null) {
                logger.error("Cannot find resource: /benchmarks.json. Scoring will use default benchmark values.");
                return new JSONObject(); // Fallback to an empty JSON object
            }
            String jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            logger.info("Successfully loaded /benchmarks.json.");
            return new JSONObject(jsonText);
        } catch (Exception e) {
            logger.error("Failed to load or parse /benchmarks.json. Using empty JSON as fallback.", e);
            return new JSONObject(); // Fallback to an empty JSON object
        }
    }
}
