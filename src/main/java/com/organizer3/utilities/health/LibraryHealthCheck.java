package com.organizer3.utilities.health;

import java.util.List;

/**
 * A single diagnostic check. Each check reports a count and a capped sample of offenders.
 *
 * <p>Checks are intentionally small and self-contained — they own their SQL, filesystem scan,
 * or YAML lookup — so new checks can be added as drop-in classes. The set of checks is not
 * sealed; domain extension (e.g. AV stars library, future scrape-source checks) is expected.
 *
 * <p>Implementations must be safe to call from the task executor; they may touch the database
 * and filesystem but should not mutate state.
 */
public interface LibraryHealthCheck {

    /** Stable machine id used in routes and as a cache key. Must not change once released. */
    String id();

    /** Short human label shown in the check list. */
    String label();

    /** One-sentence description shown in the detail pane header. */
    String description();

    /**
     * Where the fix lives. {@link FixRouting#INLINE} means "this screen handles it" (Phase 2
     * for most checks); a {@link FixRouting#SCREEN} value points the UI at another Utilities
     * screen. {@link FixRouting#SURFACE_ONLY} means we only report and leave resolution to the
     * user (e.g. duplicates need human judgement).
     */
    FixRouting fixRouting();

    /** Run the check, returning the result with total count and a capped sample of rows. */
    CheckResult run();

    /** Per-check finding row. {@code id} is machine-stable; {@code label} / {@code detail} are human. */
    record Finding(String id, String label, String detail) {}

    /** Output of {@link #run()}: total is the authoritative count; rows is a capped preview. */
    record CheckResult(int total, List<Finding> rows) {
        public static CheckResult empty() { return new CheckResult(0, List.of()); }
    }

    /** Where the fix lives. The frontend uses this to decide what action button to render. */
    enum FixRouting { INLINE, SURFACE_ONLY, VOLUMES_SCREEN, ACTRESS_DATA_SCREEN }
}
