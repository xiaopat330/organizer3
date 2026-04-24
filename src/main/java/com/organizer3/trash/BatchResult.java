package com.organizer3.trash;

import java.nio.file.Path;
import java.util.List;

/** Result of a bulk schedule-for-deletion or restore operation. */
public record BatchResult(int successes, List<FailureDetail> failures) {

    public record FailureDetail(Path sidecarPath, String reason) {}

    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
