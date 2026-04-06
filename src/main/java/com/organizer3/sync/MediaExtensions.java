package com.organizer3.sync;

import java.nio.file.Path;
import java.util.Set;

/**
 * Known video file extensions. Used during filesystem scans to identify video files.
 */
public final class MediaExtensions {

    public static final Set<String> VIDEO = Set.of(
            "mkv", "mp4", "avi", "mov", "wmv",
            "mpg", "mpeg", "m4v", "m2ts", "ts",
            "rmvb", "divx", "asf", "wma", "wm"
    );

    public static boolean isVideo(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && VIDEO.contains(name.substring(dot + 1));
    }

    private MediaExtensions() {}
}
