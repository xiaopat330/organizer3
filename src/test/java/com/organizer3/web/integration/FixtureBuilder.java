package com.organizer3.web.integration;

import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.model.TitleLocation;
import com.organizer3.repository.jdbi.JdbiTitleTagRepository;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fluent fixture builder for {@link IntegrationTestHarness}. Lets a test
 * assemble only the entities it needs, in declaration order, without the
 * giant {@code seedRich()} one-size-fits-all.
 *
 * <p>All operations are eager: each method persists on call, so references
 * by handle (e.g. {@code .actress("aya")} inside a later {@code .title(...)})
 * require the referenced entity to already be configured earlier in the
 * chain. Effective-tag recomputation happens automatically after any
 * tag-related write so inherited tags surface through the list endpoints.
 *
 * <p>Terminal {@link #build()} returns an immutable {@link Fixtures} lookup
 * keyed by handle for assertions in test bodies.
 */
public final class FixtureBuilder {

    private final IntegrationTestHarness h;
    private final TitleEffectiveTagsService effectiveTags;
    private final JdbiTitleTagRepository tagRepo;

    private final Map<String, Actress> actresses = new HashMap<>();
    private final Map<String, Title>   titles    = new HashMap<>();

    FixtureBuilder(IntegrationTestHarness h) {
        this.h = h;
        this.effectiveTags = new TitleEffectiveTagsService(h.jdbi());
        this.tagRepo = new JdbiTitleTagRepository(h.jdbi());
    }

    // ── Labels & tag master ───────────────────────────────────────────

    public FixtureBuilder label(String code, String company) {
        h.jdbi().useHandle(handle -> handle.createUpdate(
                "INSERT INTO labels (code, label_name, company) VALUES (:c, :n, :co)")
                .bind("c", code).bind("n", code + " Label").bind("co", company)
                .execute());
        return this;
    }

    public FixtureBuilder tag(String name, String category) {
        h.jdbi().useHandle(handle -> handle.createUpdate(
                "INSERT OR IGNORE INTO tags (name, category) VALUES (:n, :c)")
                .bind("n", name).bind("c", category)
                .execute());
        return this;
    }

    /**
     * Attaches a tag to a label so every title under that label inherits it
     * via {@code title_effective_tags}. Recomputes effective tags for all
     * already-seeded titles whose label matches.
     */
    public FixtureBuilder labelTag(String labelCode, String tagName) {
        h.jdbi().useHandle(handle -> handle.createUpdate(
                "INSERT OR IGNORE INTO label_tags (label_code, tag) VALUES (:c, :t)")
                .bind("c", labelCode).bind("t", tagName)
                .execute());
        for (Title t : titles.values()) {
            if (labelCode.equals(t.getLabel())) {
                effectiveTags.recomputeForTitle(t.getId());
            }
        }
        return this;
    }

    // ── Actresses ─────────────────────────────────────────────────────

    public FixtureBuilder actress(String handle, Consumer<ActressSpec> configure) {
        ActressSpec spec = new ActressSpec();
        configure.accept(spec);

        Actress saved = h.actressRepo.save(Actress.builder()
                .canonicalName(spec.canonical != null ? spec.canonical : handle)
                .tier(spec.tier)
                .firstSeenAt(spec.firstSeen)
                .build());

        if (spec.favorite) h.actressRepo.toggleFavorite(saved.getId(), true);
        if (spec.bookmark) h.actressRepo.toggleBookmark(saved.getId(), true);
        if (spec.rejected) h.actressRepo.toggleRejected(saved.getId(), true);

        actresses.put(handle, saved);
        return this;
    }

    // ── Titles ────────────────────────────────────────────────────────

    public FixtureBuilder title(String handle, Consumer<TitleSpec> configure) {
        TitleSpec spec = new TitleSpec();
        configure.accept(spec);
        if (spec.code == null) throw new IllegalStateException("title '" + handle + "' missing code");
        if (spec.actressHandle == null) throw new IllegalStateException("title '" + handle + "' missing actress");
        Actress a = requireActress(spec.actressHandle);

        Title saved = h.titleRepo.save(Title.builder()
                .code(spec.code)
                .baseCode(spec.baseCode != null ? spec.baseCode : spec.code.replace("-", "-0000"))
                .label(spec.label)
                .seqNum(spec.seqNum)
                .actressId(a.getId())
                .titleEnglish(spec.titleEnglish)
                .favorite(spec.favorite)
                .bookmark(spec.bookmark)
                .build());

        titles.put(handle, saved);
        // If the label already has inherited tags, propagate to this fresh title.
        effectiveTags.recomputeForTitle(saved.getId());
        return this;
    }

    public FixtureBuilder location(String titleHandle, String volumeId, String partitionId,
                                   String path, LocalDate date) {
        Title t = requireTitle(titleHandle);
        h.locationRepo.save(TitleLocation.builder()
                .titleId(t.getId())
                .volumeId(volumeId).partitionId(partitionId)
                .path(Path.of(path))
                .lastSeenAt(date).addedDate(date)
                .build());
        return this;
    }

    public FixtureBuilder coStar(String titleHandle, String... additionalActressHandles) {
        Title t = requireTitle(titleHandle);
        List<Long> ids = new ArrayList<>();
        ids.add(t.getActressId());
        for (String handle : additionalActressHandles) {
            ids.add(requireActress(handle).getId());
        }
        h.titleActressRepo.linkAll(t.getId(), ids);
        return this;
    }

    /** Assigns direct title-level tags. Tag master rows must exist (via {@link #tag}). */
    public FixtureBuilder titleTags(String titleHandle, String... tags) {
        Title t = requireTitle(titleHandle);
        tagRepo.replaceTagsForTitle(t.getId(), List.of(tags));
        effectiveTags.recomputeForTitle(t.getId());
        return this;
    }

    // ── Visit / watch history ─────────────────────────────────────────

    public FixtureBuilder visit(String titleHandle) {
        h.titleRepo.recordVisit(requireTitle(titleHandle).getId());
        return this;
    }

    public FixtureBuilder visitActress(String actressHandle) {
        h.actressRepo.recordVisit(requireActress(actressHandle).getId());
        return this;
    }

    public FixtureBuilder watchHistory(String titleHandle, LocalDateTime at) {
        h.watchHistoryRepo.record(requireTitle(titleHandle).getCode(), at);
        return this;
    }

    // ── Terminal ──────────────────────────────────────────────────────

    public Fixtures build() {
        return new Fixtures(Map.copyOf(actresses), Map.copyOf(titles));
    }

    // ── Internals ─────────────────────────────────────────────────────

    private Actress requireActress(String handle) {
        Actress a = actresses.get(handle);
        if (a == null) throw new IllegalStateException("unknown actress handle: " + handle);
        return a;
    }

    private Title requireTitle(String handle) {
        Title t = titles.get(handle);
        if (t == null) throw new IllegalStateException("unknown title handle: " + handle);
        return t;
    }

    // ── Sub-builders ──────────────────────────────────────────────────

    public static final class ActressSpec {
        private String canonical;
        private Actress.Tier tier = Actress.Tier.POPULAR;
        private LocalDate firstSeen = LocalDate.of(2024, 1, 1);
        private boolean favorite, bookmark, rejected;

        public ActressSpec canonical(String n)    { this.canonical = n; return this; }
        public ActressSpec tier(Actress.Tier t)   { this.tier = t; return this; }
        public ActressSpec firstSeen(LocalDate d) { this.firstSeen = d; return this; }
        public ActressSpec favorite()             { this.favorite = true; return this; }
        public ActressSpec bookmark()             { this.bookmark = true; return this; }
        public ActressSpec rejected()             { this.rejected = true; return this; }
    }

    public static final class TitleSpec {
        private String code, baseCode, label, actressHandle, titleEnglish;
        private int seqNum = 1;
        private boolean favorite, bookmark;

        public TitleSpec code(String c)          { this.code = c; return this; }
        public TitleSpec baseCode(String c)      { this.baseCode = c; return this; }
        public TitleSpec label(String l)         { this.label = l; return this; }
        public TitleSpec seqNum(int n)           { this.seqNum = n; return this; }
        public TitleSpec actress(String handle)  { this.actressHandle = handle; return this; }
        public TitleSpec titleEnglish(String s)  { this.titleEnglish = s; return this; }
        public TitleSpec favorite()              { this.favorite = true; return this; }
        public TitleSpec bookmark()              { this.bookmark = true; return this; }
    }

    /** Immutable snapshot returned by {@link #build()}. */
    public record Fixtures(Map<String, Actress> actresses, Map<String, Title> titles) {
        public long actressId(String handle)     { return actresses.get(handle).getId(); }
        public Actress actress(String handle)    { return actresses.get(handle); }
        public String titleCode(String handle)   { return titles.get(handle).getCode(); }
        public Title title(String handle)        { return titles.get(handle); }
    }
}
