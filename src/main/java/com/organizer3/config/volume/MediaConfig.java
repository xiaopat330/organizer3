package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Media file extension classification — which extensions count as video vs. cover
 * files. Used by the organize pipeline to decide which files inside a title folder
 * to move, rename, or restructure. Bound from the {@code media:} block in
 * {@code organizer-config.yaml}.
 *
 * <p>All comparisons against these lists are case-insensitive; the config values
 * should be lowercase.
 *
 * <p>Defaults are populated from the legacy Organizer v2 configuration; they can
 * be overridden or extended per-environment.
 */
public record MediaConfig(
        @JsonProperty("videoExtensions") List<String> videoExtensions,
        @JsonProperty("coverExtensions") List<String> coverExtensions
) {

    public static final List<String> DEFAULT_VIDEO_EXTENSIONS = List.of(
            "asf", "wma", "wmv", "wm",
            "avi",
            "mpg", "mpeg",
            "mkv",
            "mov",
            "rmvb",
            "mp4", "m4v", "mp4v",
            "m2ts", "ts",
            "divx"
    );

    public static final List<String> DEFAULT_COVER_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");

    public static final MediaConfig DEFAULTS =
            new MediaConfig(DEFAULT_VIDEO_EXTENSIONS, DEFAULT_COVER_EXTENSIONS);

    public List<String> effectiveVideoExtensions() {
        return videoExtensions != null ? videoExtensions : DEFAULT_VIDEO_EXTENSIONS;
    }

    public List<String> effectiveCoverExtensions() {
        return coverExtensions != null ? coverExtensions : DEFAULT_COVER_EXTENSIONS;
    }
}
