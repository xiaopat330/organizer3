package com.organizer3.utilities.health.checks;

import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.repository.ActressRepository;
import com.organizer3.utilities.health.LibraryHealthCheck;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Actress YAMLs on the classpath that haven't been loaded into the DB. Detected by walking
 * the list of discoverable slugs and probing {@link ActressRepository#resolveByName} — if
 * the slug doesn't resolve to an existing actress (canonical or alias), the YAML hasn't been
 * applied.
 *
 * <p>The slug-to-name mapping isn't formal, so a YAML can exist on disk for an actress who
 * is only findable by a different canonical_name than the slug. For Phase 1 we use
 * {@code loader.peek()} to read the YAML's nested profile.name and match on that too. If
 * neither matches, the YAML is reported as unloaded.
 */
@Slf4j
public final class UnloadedYamlsCheck implements LibraryHealthCheck {

    private static final int SAMPLE_LIMIT = 50;

    private final ActressYamlLoader loader;
    private final ActressRepository actresses;

    public UnloadedYamlsCheck(ActressYamlLoader loader, ActressRepository actresses) {
        this.loader = loader;
        this.actresses = actresses;
    }

    @Override public String id() { return "unloaded_yamls"; }
    @Override public String label() { return "Unloaded actress YAMLs"; }
    @Override public String description() {
        return "Actress YAML files on disk that have never been applied to the database.";
    }
    @Override public FixRouting fixRouting() { return FixRouting.ACTRESS_DATA_SCREEN; }

    @Override
    public CheckResult run() {
        List<String> slugs;
        try {
            slugs = loader.listSlugs();
        } catch (Exception e) {
            log.warn("Failed to enumerate actress YAMLs", e);
            return CheckResult.empty();
        }

        int total = 0;
        List<Finding> sample = new ArrayList<>();
        for (String slug : slugs) {
            if (isLoaded(slug)) continue;
            total++;
            if (sample.size() < SAMPLE_LIMIT) {
                sample.add(new Finding(slug, slug, "YAML on disk; no matching DB row"));
            }
        }
        return new CheckResult(total, sample);
    }

    private boolean isLoaded(String slug) {
        if (actresses.resolveByName(slug).isPresent()) return true;
        // Slug may not match the canonical name — probe the YAML's profile.name as a fallback.
        try {
            var peek = loader.peek(slug);
            if (peek == null || peek.profile() == null || peek.profile().name() == null) return false;
            var name = peek.profile().name();
            String canonical = name.toCanonicalName();
            if (canonical != null && actresses.resolveByName(canonical).isPresent()) return true;
            String stage = name.stageName();
            if (stage != null && actresses.resolveByName(stage).isPresent()) return true;
        } catch (Exception e) {
            log.debug("peek failed for {}", slug, e);
        }
        return false;
    }
}
