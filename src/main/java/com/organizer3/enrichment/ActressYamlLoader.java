package com.organizer3.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.organizer3.enrichment.plan.ActressChange;
import com.organizer3.enrichment.plan.ActressYamlPlan;
import com.organizer3.enrichment.plan.FieldChange;
import com.organizer3.enrichment.plan.PortfolioChange;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;
import com.organizer3.repository.TitleTagRepository;
import com.organizer3.sync.TitleCodeParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

/**
 * Loads actress YAML files from {@code resources/actresses/} and applies them to the database.
 *
 * <p>All enrichment fields are overwritten unconditionally — YAML is authoritative.
 * Operational fields (tier, favorite, bookmark, grade, rejected) are never touched.
 *
 * <p>Portfolio entries are applied to matching titles (looked up by code). Titles that
 * do not yet exist in the DB are created with minimal metadata and associated with the actress.
 * Tags are always replaced atomically per title.
 */
@Slf4j
@RequiredArgsConstructor
public class ActressYamlLoader {

    private static final String RESOURCE_PREFIX = "actresses/";

    private final ActressRepository actressRepo;
    private final TitleRepository titleRepo;
    private final TitleTagRepository tagRepo;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final TitleCodeParser codeParser = new TitleCodeParser();

    /**
     * Load a single actress YAML by romanized name slug (e.g. "nana_ogura").
     * Matches the filename {@code actresses/<slug>.yaml} on the classpath.
     *
     * @return a summary of what was applied
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException if no matching resource is found
     */
    public LoadResult loadOne(String slug) throws IOException {
        String resourcePath = RESOURCE_PREFIX + slug + ".yaml";
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalArgumentException("No actress YAML found at classpath:" + resourcePath);
        }
        ActressYaml yaml = parseYaml(stream);
        return apply(slug, yaml);
    }

    /**
     * Load all actress YAML files under {@code resources/actresses/} on the classpath.
     *
     * @return one {@link LoadResult} per file processed
     */
    public List<LoadResult> loadAll() throws IOException {
        List<LoadResult> results = new ArrayList<>();
        URL dirUrl = getClass().getClassLoader().getResource(RESOURCE_PREFIX);
        if (dirUrl == null) {
            log.warn("No actresses/ directory found on classpath");
            return results;
        }

        for (String slug : discoverSlugs(dirUrl)) {
            try {
                results.add(loadOne(slug));
            } catch (Exception e) {
                log.error("Failed to load actress YAML for slug '{}': {}", slug, e.getMessage());
            }
        }
        return results;
    }

    /**
     * List every actress slug discoverable on the classpath under {@code actresses/}. Returns an
     * empty list (not an exception) if the directory is missing, so callers can treat a fresh
     * repo without YAMLs as a normal state. Used by the Utilities catalog service for the UI list.
     */
    public List<String> listSlugs() throws IOException {
        URL dirUrl = getClass().getClassLoader().getResource(RESOURCE_PREFIX);
        if (dirUrl == null) return List.of();
        return discoverSlugs(dirUrl);
    }

    /**
     * Parse a YAML for inspection without applying it to the DB. Returns {@code null} if no
     * matching resource exists. Distinct from {@link #loadOne} in that it never writes.
     */
    public ActressYaml peek(String slug) throws IOException {
        String resourcePath = RESOURCE_PREFIX + slug + ".yaml";
        InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (stream == null) return null;
        return parseYaml(stream);
    }

    /**
     * Compute what would change if this slug's YAML were applied to the DB right now. Purely
     * read-only — no writes. The result drives the visualize-then-confirm UI so the user can
     * see the diff before committing. {@code loadOne} is unchanged; this is a parallel read path.
     *
     * @throws IllegalArgumentException if the YAML resource is missing
     */
    public ActressYamlPlan plan(String slug) throws IOException {
        ActressYaml yaml = peek(slug);
        if (yaml == null) {
            throw new IllegalArgumentException("No actress YAML found at classpath:" + RESOURCE_PREFIX + slug + ".yaml");
        }
        return buildPlan(slug, yaml);
    }

    /**
     * Apply only the grade field from a YAML's portfolio entries to existing DB titles.
     *
     * <p>Lighter-weight than {@link #loadOne}: does not create stubs, does not touch
     * title_original / title_english / release_date / notes / tags. Skips entries with no grade
     * or whose code is not yet in the DB (counted as {@code missingTitle}). Repository call
     * skips titles with {@code grade_source = 'manual'}; YAML wins over enrichment and refreshes
     * existing 'ai' grades.
     *
     * <p>Use this to bring DB grades back in sync after new titles have been imported by volume
     * sync since the last full {@link #loadOne}.
     */
    public SyncGradesResult syncGradesFromYaml(String slug) throws IOException {
        ActressYaml data = peek(slug);
        if (data == null) {
            throw new IllegalArgumentException("No actress YAML found at classpath:" + RESOURCE_PREFIX + slug + ".yaml");
        }
        if (data.portfolio() == null) {
            return new SyncGradesResult(slug, 0, 0, 0, 0);
        }

        int scanned = 0;
        int written = 0;
        int missingTitle = 0;
        int noGrade = 0;

        for (ActressYaml.PortfolioEntry entry : data.portfolio()) {
            if (entry.code() == null || entry.code().isBlank()) continue;
            scanned++;

            if (entry.grade() == null || entry.grade().isBlank()) {
                noGrade++;
                continue;
            }

            Actress.Grade grade;
            try {
                grade = Actress.Grade.fromDisplay(entry.grade().trim());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown grade '{}' for code {} in {}, skipping", entry.grade(), entry.code(), slug);
                noGrade++;
                continue;
            }

            String code = entry.code().trim();
            Optional<Title> existing = titleRepo.findByCode(code);
            if (existing.isEmpty()) {
                missingTitle++;
                continue;
            }

            titleRepo.setGradeFromYaml(existing.get().getId(), grade);
            written++;
        }

        log.info("sync-grades [{}]: scanned={} written={} missingTitle={} noGrade={}",
                slug, scanned, written, missingTitle, noGrade);
        return new SyncGradesResult(slug, scanned, written, missingTitle, noGrade);
    }

    private List<String> discoverSlugs(URL dirUrl) throws IOException {
        String protocol = dirUrl.getProtocol();
        if ("file".equals(protocol)) {
            try {
                try (var paths = Files.list(Path.of(dirUrl.toURI()))) {
                    return paths
                            .map(p -> p.getFileName().toString())
                            .filter(name -> name.endsWith(".yaml") && !name.startsWith("test_"))
                            .map(name -> name.replace(".yaml", ""))
                            .sorted()
                            .toList();
                }
            } catch (Exception e) {
                throw new IOException("Failed to list actresses directory: " + e.getMessage(), e);
            }
        } else if ("jar".equals(protocol)) {
            String jarPath = dirUrl.getPath();
            String jarFilePart = jarPath.substring(0, jarPath.indexOf('!'));
            String dirPrefix = RESOURCE_PREFIX;
            List<String> slugs = new ArrayList<>();
            try (JarFile jar = new JarFile(new File(new URI(jarFilePart)))) {
                jar.entries().asIterator().forEachRemaining(entry -> {
                    String name = entry.getName();
                    if (name.startsWith(dirPrefix) && name.endsWith(".yaml") && !entry.isDirectory()) {
                        String filename = name.substring(name.lastIndexOf('/') + 1);
                        if (!filename.startsWith("test_")) {
                            slugs.add(filename.replace(".yaml", ""));
                        }
                    }
                });
            } catch (Exception e) {
                throw new IOException("Failed to enumerate JAR entries: " + e.getMessage(), e);
            }
            slugs.sort(String::compareTo);
            return slugs;
        } else {
            throw new IOException("Unsupported classpath URL protocol '" + protocol + "' for actresses directory");
        }
    }

    private ActressYaml parseYaml(InputStream stream) throws IOException {
        return yaml.readValue(stream, ActressYaml.class);
    }

    private LoadResult apply(String slug, ActressYaml data) {
        ActressYaml.Profile profile = data.profile();
        String canonicalName = profile.name() != null ? profile.name().toCanonicalName() : slug;

        // Resolve actress — try canonical name, then stage name
        Optional<Actress> found = actressRepo.resolveByName(canonicalName);
        if (found.isEmpty() && profile.name() != null && profile.name().stageName() != null) {
            found = actressRepo.resolveByName(profile.name().stageName());
        }

        final Actress actress;
        if (found.isPresent()) {
            actress = found.get();
            log.info("Enriching actress: {} (id={})", actress.getCanonicalName(), actress.getId());
        } else {
            // Create a minimal actress record — sync hasn't seen her yet
            actress = actressRepo.save(Actress.builder()
                    .canonicalName(canonicalName)
                    .stageName(profile.name() != null ? profile.name().stageName() : null)
                    .tier(Actress.Tier.LIBRARY)
                    .firstSeenAt(LocalDate.now())
                    .build());
            log.info("Created new actress from YAML: {} (id={})", actress.getCanonicalName(), actress.getId());
        }

        // Update profile enrichment fields
        ActressYaml.Measurements m = profile.measurements();
        actressRepo.updateProfile(
                actress.getId(),
                profile.name() != null ? profile.name().stageName() : null,
                parseDate(profile.dateOfBirth()),
                profile.birthplace(),
                profile.bloodType(),
                profile.heightCm(),
                m != null ? m.bust() : null,
                m != null ? m.waist() : null,
                m != null ? m.hip() : null,
                profile.cup(),
                parseDate(profile.activeFrom()),
                parseDate(profile.activeTo()),
                profile.biography(),
                profile.legacy()
        );

        // Apply extended profile fields (reading, retirement, alternate names, studios, awards).
        actressRepo.updateExtendedProfile(
                actress.getId(),
                profile.name() != null ? profile.name().reading() : null,
                parseDate(profile.retirementAnnounced()),
                toAlternateNames(profile.name() != null ? profile.name().alternateNames() : null),
                toStudioTenures(profile.primaryStudios()),
                toAwards(profile.awards())
        );

        // Apply portfolio entries
        int titlesCreated = 0;
        int titlesEnriched = 0;
        List<String> unresolved = new ArrayList<>();

        if (data.portfolio() != null) {
            for (ActressYaml.PortfolioEntry entry : data.portfolio()) {
                if (entry.code() == null || entry.code().isBlank()) continue;

                String code = entry.code().trim();
                Optional<Title> existing = titleRepo.findByCode(code);

                final long titleId;
                if (existing.isPresent()) {
                    titleId = existing.get().getId();
                    titlesEnriched++;
                } else {
                    // Create a minimal title stub so enrichment data is not lost
                    TitleCodeParser.ParsedCode parsed = codeParser.parse(code);
                    Title stub = titleRepo.save(Title.builder()
                            .code(code)
                            .baseCode(parsed != null ? parsed.baseCode() : code)
                            .label(entry.label())
                            .seqNum(parsed != null ? parsed.seqNum() : null)
                            .actressId(actress.getId())
                            .build());
                    titleId = stub.getId();
                    titlesCreated++;
                }

                // Enrich title metadata
                Actress.Grade grade = null;
                if (entry.grade() != null && !entry.grade().isBlank()) {
                    try {
                        grade = Actress.Grade.fromDisplay(entry.grade().trim());
                    } catch (IllegalArgumentException e) {
                        log.warn("Unknown grade '{}' for code {}, skipping", entry.grade(), code);
                    }
                }

                String titleOriginal = entry.title() != null ? entry.title().original() : null;
                String titleEnglish = entry.title() != null ? entry.title().english() : null;

                titleRepo.enrichTitle(titleId, titleOriginal, titleEnglish,
                        parseDate(entry.date()), entry.notes(), grade);

                // Replace tags
                List<String> tags = entry.tags() != null ? entry.tags() : List.of();
                tagRepo.replaceTagsForTitle(titleId, tags);
            }
        }

        return new LoadResult(actress.getCanonicalName(), actress.getId(), titlesCreated, titlesEnriched, unresolved);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<com.organizer3.model.Actress.AlternateName> toAlternateNames(
            List<ActressYaml.AlternateName> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(a -> a != null && a.name() != null && !a.name().isBlank())
                .map(a -> new com.organizer3.model.Actress.AlternateName(a.name(), a.note()))
                .toList();
    }

    private static List<com.organizer3.model.Actress.StudioTenure> toStudioTenures(
            List<ActressYaml.Studio> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(s -> s != null && (s.name() != null || s.company() != null))
                .map(s -> new com.organizer3.model.Actress.StudioTenure(
                        s.name(), s.company(),
                        parseDate(s.from()), parseDate(s.to()),
                        s.role()))
                .toList();
    }

    private static List<com.organizer3.model.Actress.Award> toAwards(List<ActressYaml.Award> yaml) {
        if (yaml == null || yaml.isEmpty()) return List.of();
        return yaml.stream()
                .filter(a -> a != null && (a.event() != null || a.category() != null))
                .map(a -> new com.organizer3.model.Actress.Award(a.event(), a.year(), a.category()))
                .toList();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Read-only diff planner (used by the Utilities visualize pane)
    // ─────────────────────────────────────────────────────────────────────

    private ActressYamlPlan buildPlan(String slug, ActressYaml data) {
        ActressYaml.Profile profile = data.profile();
        String canonicalName = (profile != null && profile.name() != null)
                ? profile.name().toCanonicalName() : slug;

        Optional<Actress> found = actressRepo.resolveByName(canonicalName);
        if (found.isEmpty() && profile != null && profile.name() != null && profile.name().stageName() != null) {
            found = actressRepo.resolveByName(profile.name().stageName());
        }

        ActressChange actressChange = found.isPresent()
                ? planActressUpdate(found.get(), profile)
                : planActressCreate(canonicalName, profile);

        List<PortfolioChange> portfolio = new ArrayList<>();
        int titlesCreate = 0, titlesEnrich = 0, titlesNoop = 0, fieldChanges = 0, tagsAdded = 0, tagsRemoved = 0;
        if (data.portfolio() != null) {
            for (ActressYaml.PortfolioEntry entry : data.portfolio()) {
                if (entry.code() == null || entry.code().isBlank()) continue;
                String code = entry.code().trim();
                PortfolioChange pc = planPortfolioEntry(code, entry);
                portfolio.add(pc);
                if (pc instanceof PortfolioChange.CreateTitle) {
                    titlesCreate++;
                } else if (pc instanceof PortfolioChange.EnrichTitle et) {
                    if (et.isNoop()) titlesNoop++; else titlesEnrich++;
                    fieldChanges += et.fields().size();
                    tagsAdded    += et.tagsAdded().size();
                    tagsRemoved  += et.tagsRemoved().size();
                }
            }
        }

        int actressFieldCount = (actressChange instanceof ActressChange.Update u)
                ? u.fields().size()
                : (actressChange instanceof ActressChange.Create c ? c.fields().size() : 0);
        int actressChanged = (actressChange instanceof ActressChange.Create) ? 1
                : (actressChange instanceof ActressChange.Update u && !u.fields().isEmpty()) ? 1 : 0;
        fieldChanges += actressFieldCount;

        ActressYamlPlan.Summary summary = new ActressYamlPlan.Summary(
                actressChanged, titlesCreate, titlesEnrich, titlesNoop,
                fieldChanges, tagsAdded, tagsRemoved);

        return new ActressYamlPlan(slug, actressChange, portfolio, List.of(), summary);
    }

    private ActressChange planActressCreate(String canonicalName, ActressYaml.Profile profile) {
        List<FieldChange> fields = new ArrayList<>();
        if (profile != null) collectActressFieldChanges(null, profile, fields);
        return new ActressChange.Create(canonicalName, fields);
    }

    private ActressChange planActressUpdate(Actress existing, ActressYaml.Profile profile) {
        List<FieldChange> fields = new ArrayList<>();
        if (profile != null) collectActressFieldChanges(existing, profile, fields);
        return new ActressChange.Update(existing.getId(), existing.getCanonicalName(), fields);
    }

    /** Emit a FieldChange for each actress enrichment field where YAML differs from current DB state. */
    private static void collectActressFieldChanges(
            Actress current, ActressYaml.Profile profile, List<FieldChange> out) {
        ActressYaml.Name name = profile.name();
        ActressYaml.Measurements m = profile.measurements();
        String currentStageName     = current != null ? current.getStageName() : null;
        String currentNameReading   = current != null ? current.getNameReading() : null;
        LocalDate currentDob        = current != null ? current.getDateOfBirth() : null;
        String currentBirthplace    = current != null ? current.getBirthplace() : null;
        String currentBloodType     = current != null ? current.getBloodType() : null;
        Integer currentHeightCm     = current != null ? current.getHeightCm() : null;
        Integer currentBust         = current != null ? current.getBust() : null;
        Integer currentWaist        = current != null ? current.getWaist() : null;
        Integer currentHip          = current != null ? current.getHip() : null;
        String currentCup           = current != null ? current.getCup() : null;
        LocalDate currentActiveFrom = current != null ? current.getActiveFrom() : null;
        LocalDate currentActiveTo   = current != null ? current.getActiveTo() : null;
        LocalDate currentRetired    = current != null ? current.getRetirementAnnounced() : null;
        String currentBio           = current != null ? current.getBiography() : null;
        String currentLegacy        = current != null ? current.getLegacy() : null;
        List<Actress.AlternateName> currentAlts    = current != null ? current.getAlternateNames() : null;
        List<Actress.StudioTenure>  currentStudios = current != null ? current.getPrimaryStudios() : null;
        List<Actress.Award>         currentAwards  = current != null ? current.getAwards() : null;

        diff(out, "stageName",            currentStageName,   name != null ? name.stageName() : null);
        diff(out, "nameReading",          currentNameReading, name != null ? name.reading()   : null);
        diff(out, "dateOfBirth",          currentDob,         parseDate(profile.dateOfBirth()));
        diff(out, "birthplace",           currentBirthplace,  profile.birthplace());
        diff(out, "bloodType",            currentBloodType,   profile.bloodType());
        diff(out, "heightCm",             currentHeightCm,    profile.heightCm());
        diff(out, "bust",                 currentBust,        m != null ? m.bust()  : null);
        diff(out, "waist",                currentWaist,       m != null ? m.waist() : null);
        diff(out, "hip",                  currentHip,         m != null ? m.hip()   : null);
        diff(out, "cup",                  currentCup,         profile.cup());
        diff(out, "activeFrom",           currentActiveFrom,  parseDate(profile.activeFrom()));
        diff(out, "activeTo",             currentActiveTo,    parseDate(profile.activeTo()));
        diff(out, "retirementAnnounced",  currentRetired,     parseDate(profile.retirementAnnounced()));
        diff(out, "biography",            currentBio,         profile.biography());
        diff(out, "legacy",               currentLegacy,      profile.legacy());
        diffList(out, "alternateNames", currentAlts, toAlternateNames(name != null ? name.alternateNames() : null));
        diffList(out, "primaryStudios", currentStudios, toStudioTenures(profile.primaryStudios()));
        diffList(out, "awards",         currentAwards, toAwards(profile.awards()));
    }

    private PortfolioChange planPortfolioEntry(String code, ActressYaml.PortfolioEntry entry) {
        Optional<Title> existing = titleRepo.findByCode(code);
        String titleOriginal = entry.title() != null ? entry.title().original() : null;
        String titleEnglish  = entry.title() != null ? entry.title().english() : null;
        LocalDate releaseDate = parseDate(entry.date());
        String notes = entry.notes();
        String grade = entry.grade() != null ? entry.grade().trim() : null;
        List<String> yamlTags = entry.tags() != null ? entry.tags() : List.of();

        if (existing.isEmpty()) {
            return new PortfolioChange.CreateTitle(code, titleOriginal, titleEnglish,
                    releaseDate != null ? releaseDate.toString() : null, notes, grade, yamlTags);
        }

        Title t = existing.get();
        List<FieldChange> fields = new ArrayList<>();
        diff(fields, "titleOriginal", t.getTitleOriginal(), titleOriginal);
        diff(fields, "titleEnglish",  t.getTitleEnglish(),  titleEnglish);
        diff(fields, "releaseDate",   t.getReleaseDate(),   releaseDate);
        diff(fields, "notes",         t.getNotes(),         notes);
        diff(fields, "grade",         t.getGrade() != null ? t.getGrade().display : null, grade);

        List<String> currentTags = tagRepo.findTagsForTitle(t.getId());
        Set<String> currentSet = new HashSet<>(currentTags);
        Set<String> yamlSet    = new HashSet<>(yamlTags);
        List<String> added   = new ArrayList<>(new TreeSet<>(difference(yamlSet, currentSet)));
        List<String> removed = new ArrayList<>(new TreeSet<>(difference(currentSet, yamlSet)));
        List<String> unchanged = new ArrayList<>(new TreeSet<>(intersection(currentSet, yamlSet)));

        return new PortfolioChange.EnrichTitle(t.getId(), code, fields, added, removed, unchanged);
    }

    private static void diff(List<FieldChange> out, String field, Object oldValue, Object newValue) {
        if (oldValue == null && newValue == null) return;
        // Treat empty string / empty list as null for comparison; the loader treats them identically.
        Object a = normalize(oldValue);
        Object b = normalize(newValue);
        if (Objects.equals(a, b)) return;
        out.add(new FieldChange(field, a, b));
    }

    /** List diff: both null / both empty counts as no change. */
    private static <T> void diffList(List<FieldChange> out, String field, List<T> oldValue, List<T> newValue) {
        boolean oldEmpty = oldValue == null || oldValue.isEmpty();
        boolean newEmpty = newValue == null || newValue.isEmpty();
        if (oldEmpty && newEmpty) return;
        if (!Objects.equals(oldValue, newValue)) {
            out.add(new FieldChange(field,
                    oldEmpty ? null : oldValue,
                    newEmpty ? null : newValue));
        }
    }

    private static Object normalize(Object v) {
        if (v instanceof String s && s.isEmpty()) return null;
        if (v instanceof List<?> l && l.isEmpty()) return null;
        return v;
    }

    private static <T> Set<T> difference(Set<T> a, Set<T> b) {
        Set<T> r = new HashSet<>(a); r.removeAll(b); return r;
    }
    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> r = new HashSet<>(a); r.retainAll(b); return r;
    }

    /**
     * Summary of what was applied for one actress YAML load.
     */
    public record LoadResult(
            String canonicalName,
            long actressId,
            int titlesCreated,
            int titlesEnriched,
            List<String> unresolvedCodes
    ) {}

    /**
     * Summary of a grade-only YAML→DB sync. {@code missingTitle} counts portfolio entries
     * whose code has no row in the {@code titles} table; {@code noGrade} counts entries
     * with no grade in the YAML (or an unparseable grade value).
     */
    public record SyncGradesResult(
            String slug,
            int scanned,
            int written,
            int missingTitle,
            int noGrade
    ) {}
}
