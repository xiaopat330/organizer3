package com.organizer3.utilities.health.checks;

import com.organizer3.repository.AttributionFindingsRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Library health check that surfaces open attribution audit findings.
 *
 * <p>Each finding represents an actress with a detected cast-mismatch or suspect-credit
 * signal that has not been suppressed or resolved.
 */
public final class AttributionFindingsCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 20;

    private final AttributionFindingsRepository repo;

    public AttributionFindingsCheck(AttributionFindingsRepository repo) {
        this.repo = repo;
    }

    @Override public String id()          { return "attribution_findings"; }
    @Override public String label()       { return "Open attribution findings"; }
    @Override public String description() {
        return "Actress-level cast-mismatch or suspect-credit findings that have not been suppressed.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.SURFACE_ONLY; }

    @Override
    public CheckResult run() {
        int total = repo.count("open");
        List<AttributionFindingsRepository.Finding> sample = repo.list("open", SAMPLE_LIMIT);
        List<Finding> findings = new ArrayList<>();
        for (AttributionFindingsRepository.Finding f : sample) {
            findings.add(new Finding(
                    f.actressId() + ":" + f.findingClass(),
                    "Actress " + f.actressId() + " [" + f.findingClass() + "]",
                    "metric=" + (f.metric() != null ? String.format("%.2f", f.metric()) : "n/a")));
        }
        return new CheckResult(total, findings);
    }
}
