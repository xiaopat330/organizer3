package com.organizer3.utilities.health.checks;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Titles whose expected cover file doesn't exist under the covers directory. "Titles without
 * covers" is surface-only: the fix is typically re-running {@code sync covers} for that
 * label, which is a Volumes-screen action. Phase 1 just reports.
 *
 * <p>Checks only titles with a non-null label and baseCode — others can't have an expected
 * cover path to begin with. A title whose cover lives on an unmounted volume will show up
 * here until that volume is resynced; that's accepted Phase 1 behavior (the cover is
 * actually missing from local cache).
 */
public final class TitlesWithoutCoversCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final TitleRepository titles;
    private final CoverPath coverPath;

    public TitlesWithoutCoversCheck(TitleRepository titles, CoverPath coverPath) {
        this.titles = titles;
        this.coverPath = coverPath;
    }

    @Override public String id() { return "titles_without_covers"; }
    @Override public String label() { return "Titles without covers"; }
    @Override public String description() {
        return "Titles whose expected cover image file is not present in the local covers cache.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        int total = 0;
        List<Finding> sample = new ArrayList<>();
        // Paged walk keeps memory bounded even for large libraries. We don't need to keep the
        // non-matching titles; only the first SAMPLE_LIMIT missing-cover titles contribute to
        // the sample, and the total is incremented regardless.
        final int page = 500;
        int offset = 0;
        while (true) {
            List<Title> batch = titles.findLibraryPaged(null, null, List.of(), List.of(),
                    "productCode", true, page, offset);
            if (batch.isEmpty()) break;
            for (Title t : batch) {
                if (t.getLabel() == null || t.getBaseCode() == null) continue;
                if (coverPath.find(t).isEmpty()) {
                    total++;
                    if (sample.size() < SAMPLE_LIMIT) {
                        sample.add(new Finding(
                                String.valueOf(t.getId()),
                                t.getCode(),
                                "expected: covers/" + t.getLabel().toUpperCase()
                                        + "/" + t.getBaseCode() + ".jpg"));
                    }
                }
            }
            if (batch.size() < page) break;
            offset += page;
        }
        return new CheckResult(total, sample);
    }
}
