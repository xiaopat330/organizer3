package com.organizer3.utilities.actress;

import com.organizer3.enrichment.ActressYaml;
import com.organizer3.enrichment.ActressYamlLoader;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only catalog for the Actress Data screen's left pane and detail view. Enumerates actress
 * YAMLs on the classpath, peeks at each for a lightweight summary, and pairs that with the DB's
 * view of whether the actress has already been loaded.
 *
 * <p>Parsing 43 YAMLs on demand is cheap (sub-second), so the catalog does not cache — each
 * {@link #list} call re-reads. If the YAML count grows by an order of magnitude this becomes a
 * candidate for caching by mtime / file hash.
 */
@Slf4j
public final class ActressYamlCatalogService {

    private final ActressYamlLoader loader;
    private final ActressRepository actressRepo;

    public ActressYamlCatalogService(ActressYamlLoader loader, ActressRepository actressRepo) {
        this.loader = loader;
        this.actressRepo = actressRepo;
    }

    /** List every discoverable YAML with enough fields for the left-pane row + right-pane detail. */
    public List<Entry> list() throws IOException {
        List<String> slugs = loader.listSlugs();
        List<Entry> out = new ArrayList<>(slugs.size());
        for (String slug : slugs) {
            Entry entry = entryFor(slug);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    /** Detail for one slug; returns empty if the YAML is missing. */
    public Optional<Entry> find(String slug) throws IOException {
        return Optional.ofNullable(entryFor(slug));
    }

    private Entry entryFor(String slug) {
        ActressYaml yaml;
        try {
            yaml = loader.peek(slug);
        } catch (IOException e) {
            log.warn("Failed to parse actress YAML '{}': {}", slug, e.getMessage());
            return null;
        }
        if (yaml == null) return null;

        ActressYaml.Profile profile = yaml.profile();
        String canonicalName = (profile != null && profile.name() != null)
                ? profile.name().toCanonicalName()
                : slug;

        boolean loaded = actressRepo.findByCanonicalName(canonicalName).isPresent();
        int portfolioSize = yaml.portfolio() != null ? yaml.portfolio().size() : 0;

        return new Entry(
                slug,
                canonicalName,
                loaded,
                portfolioSize,
                summarize(profile));
    }

    private static ProfileSummary summarize(ActressYaml.Profile profile) {
        if (profile == null) return ProfileSummary.empty();
        String dob = profile.dateOfBirth() != null ? profile.dateOfBirth().toString() : null;
        Integer heightCm = profile.heightCm();
        String active = null;
        if (profile.activeFrom() != null || profile.activeTo() != null) {
            active = (profile.activeFrom() != null ? profile.activeFrom().toString() : "—")
                    + " → "
                    + (profile.activeTo()   != null ? profile.activeTo().toString()   : "present");
        }
        List<String> primaryStudios = new ArrayList<>();
        if (profile.primaryStudios() != null) {
            profile.primaryStudios().forEach(s -> { if (s.name() != null) primaryStudios.add(s.name()); });
        }
        return new ProfileSummary(dob, heightCm, active, primaryStudios);
    }

    public record Entry(String slug, String canonicalName, boolean loaded,
                        int portfolioSize, ProfileSummary profile) {}

    public record ProfileSummary(String dateOfBirth, Integer heightCm,
                                 String activeYears, List<String> primaryStudios) {
        public static ProfileSummary empty() { return new ProfileSummary(null, null, null, List.of()); }
    }
}
