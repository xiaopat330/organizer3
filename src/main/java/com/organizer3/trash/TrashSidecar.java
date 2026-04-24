package com.organizer3.trash;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Sidecar DTO for a trashed item — the JSON file co-located with the trashed item in {@code _trash/}.
 *
 * <p>{@code scheduledDeletionAt} is absent from newly-trashed sidecars and is only populated when
 * the user explicitly schedules the item for permanent deletion.
 *
 * <p>Unknown JSON fields are silently ignored so future sidecar versions remain readable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrashSidecar(
        String originalPath,
        String trashedAt,
        String volumeId,
        String reason,
        String scheduledDeletionAt,
        String lastDeletionAttempt,   // nullable — absent when no failure
        String lastDeletionError      // nullable — absent when no failure
) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static TrashSidecar read(VolumeFileSystem fs, Path sidecarPath) throws IOException {
        try (InputStream in = fs.openFile(sidecarPath)) {
            return JSON.readValue(in, TrashSidecar.class);
        }
    }

    public void write(VolumeFileSystem fs, Path sidecarPath) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(this);
        fs.writeFile(sidecarPath, bytes);
    }

    public TrashSidecar withScheduledDeletionAt(Instant t) {
        String ts = t == null ? null : DateTimeFormatter.ISO_INSTANT.format(t);
        return new TrashSidecar(originalPath, trashedAt, volumeId, reason, ts,
                lastDeletionAttempt, lastDeletionError);
    }

    public TrashSidecar withDeletionFailure(Instant attemptedAt, String error) {
        String ts = DateTimeFormatter.ISO_INSTANT.format(attemptedAt);
        String truncated = error != null && error.length() > 200 ? error.substring(0, 200) : error;
        return new TrashSidecar(originalPath, trashedAt, volumeId, reason,
                scheduledDeletionAt, ts, truncated);
    }
}
