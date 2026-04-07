package com.organizer3.web;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists discovered Japanese stage names to a YAML backup file alongside the database.
 *
 * <p>Format: one entry per line, {@code canonical_name: stage name}. Entries are keyed
 * by the romanized canonical name with spaces replaced by underscores (lower-cased).
 * This backup survives DB drops — on rebuild, the saved names can be re-imported.
 */
@Slf4j
public class StageNameBackupFile {

    private final Path filePath;

    public StageNameBackupFile(Path filePath) {
        this.filePath = filePath;
    }

    /**
     * Saves or updates the stage name for the given actress.
     * Reads the current file, updates the entry, and writes it back atomically.
     */
    public synchronized void save(String canonicalName, String stageName) {
        String key = toKey(canonicalName);
        try {
            Map<String, String> entries = readAll();
            entries.put(key, stageName);
            writeAll(entries);
            log.debug("Stage name backup updated: {} → {}", key, stageName);
        } catch (IOException e) {
            log.warn("Failed to save stage name backup for '{}': {}", canonicalName, e.getMessage());
        }
    }

    private Map<String, String> readAll() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        if (!Files.exists(filePath)) return entries;
        for (String line : Files.readAllLines(filePath)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String k = line.substring(0, colon).trim();
            String v = line.substring(colon + 2).trim();
            entries.put(k, v);
        }
        return entries;
    }

    private void writeAll(Map<String, String> entries) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Stage names backup — key: stage_name");
        for (var entry : entries.entrySet()) {
            lines.add(entry.getKey() + ": " + entry.getValue());
        }
        Files.write(filePath, lines);
    }

    static String toKey(String canonicalName) {
        return canonicalName.toLowerCase().replace(' ', '_');
    }
}
