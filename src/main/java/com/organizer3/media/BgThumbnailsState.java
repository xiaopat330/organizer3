package com.organizer3.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Persists the background-thumbnail worker's enable state across app restarts.
 *
 * <p>Why not {@code organizer-config.yaml}: per convention (see {@code feedback_data_dir}), the
 * YAML config is bootstrap-only — it tells the app how to start, not what state to remember.
 * User-toggleable runtime state lives under {@code dataDir} instead.
 *
 * <p>Behaviour on read: if the file is missing or unreadable, falls back to the YAML-configured
 * default, so first-run behavior is unchanged. On write: best-effort — a failed write logs an
 * error but does not throw; the worker flag in memory is still authoritative for this session.
 */
@Slf4j
public final class BgThumbnailsState {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path stateFile;

    public BgThumbnailsState(Path dataDir) {
        this.stateFile = dataDir.resolve("bg-thumbnails-state.json");
    }

    /** @return persisted enabled flag, or {@code null} if no state file exists. */
    public Boolean readEnabled() {
        if (!Files.isRegularFile(stateFile)) return null;
        try {
            Map<?, ?> parsed = JSON.readValue(stateFile.toFile(), Map.class);
            Object val = parsed.get("enabled");
            if (val instanceof Boolean b) return b;
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", stateFile, e.getMessage());
        }
        return null;
    }

    public void writeEnabled(boolean enabled) {
        try {
            Files.createDirectories(stateFile.getParent());
            JSON.writeValue(stateFile.toFile(), Map.of("enabled", enabled));
        } catch (IOException e) {
            log.error("Failed to write {}: {}", stateFile, e.getMessage());
        }
    }
}
