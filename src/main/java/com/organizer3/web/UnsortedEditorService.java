package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.ActressRepository.FederatedActressResult;
import com.organizer3.repository.UnsortedEditorRepository;
import com.organizer3.repository.UnsortedEditorRepository.EligibleTitle;
import com.organizer3.repository.UnsortedEditorRepository.TitleDetail;
import com.organizer3.smb.SmbConnectionFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Service layer for the Title Editor. Aggregates repository calls and adds
 * derived data (cover-present, complete status, typeahead shaping) that is not
 * worth pushing into persistence-layer queries.
 *
 * @deprecated The direct-write editor pathway (UnsortedEditor) is superseded by
 *   Draft Mode (Phase 4). This service still handles the no-draft editor's
 *   actress search, queue listing, and direct-save flows. Orderly retirement
 *   is tracked as a follow-up; see spec/PROPOSAL_DRAFT_MODE.md §11.6.
 */
@Deprecated
@SuppressWarnings("DeprecatedIsStillUsed")
@Slf4j
public class UnsortedEditorService {

    private final UnsortedEditorRepository repo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final SmbConnectionFactory smbFactory;
    private final String unsortedVolumeId;
    private final String unsortedSmbBasePath;
    private final Map<String, String> volumeSmbPaths;
    private final TitleFolderRenamer renamer;

    public UnsortedEditorService(UnsortedEditorRepository repo, ActressRepository actressRepo,
                                 CoverPath coverPath, SmbConnectionFactory smbFactory,
                                 String unsortedVolumeId, String unsortedSmbBasePath,
                                 Map<String, String> volumeSmbPaths,
                                 TitleFolderRenamer renamer) {
        this.repo = repo;
        this.actressRepo = actressRepo;
        this.coverPath = coverPath;
        this.smbFactory = smbFactory;
        this.unsortedVolumeId = unsortedVolumeId;
        this.unsortedSmbBasePath = unsortedSmbBasePath;
        this.volumeSmbPaths = volumeSmbPaths;
        this.renamer = renamer;
    }

    public String volumeId() {
        return unsortedVolumeId;
    }

    // ── List / detail ────────────────────────────────────────────────────

    public record EligibleListRow(
            long titleId,
            String code,
            String folderName,
            int actressCount,
            boolean hasCover,
            boolean complete,
            boolean processed
    ) {}

    public List<EligibleListRow> listEligible() {
        List<EligibleTitle> base = repo.listEligible(unsortedVolumeId);
        List<EligibleListRow> out = new ArrayList<>(base.size());
        for (EligibleTitle e : base) {
            boolean hasCover = coverExists(e.label(), e.baseCode());
            boolean complete = hasCover && e.actressCount() > 0;
            boolean processed = e.curatedAt() != null;
            out.add(new EligibleListRow(
                    e.titleId(), e.code(), e.folderName(),
                    e.actressCount(), hasCover, complete, processed));
        }
        return out;
    }

    public Optional<TitleDetailView> findEligibleById(long titleId) {
        return repo.findEligibleById(titleId, unsortedVolumeId)
                .map(detail -> {
                    String coverFile = coverFilename(detail.label(), detail.baseCode());
                    String descriptor = extractDescriptor(detail.folderName(), detail.code());
                    List<UnsortedEditorRepository.OtherLocation> others =
                            repo.findOtherLocations(titleId, unsortedVolumeId, detail.folderPath());
                    List<OtherLocationView> otherViews = others.stream()
                            .map(loc -> {
                                String base = volumeSmbPaths == null ? null : volumeSmbPaths.get(loc.volumeId());
                                String nasPath = base != null ? base + loc.path() : null;
                                return new OtherLocationView(loc.volumeId(), loc.path(), nasPath);
                            })
                            .toList();
                    List<String> directTags       = repo.findDirectTags(titleId);
                    List<String> labelImpliedTags = repo.findLabelTags(detail.label());
                    boolean processed = detail.curatedAt() != null;
                    String folderNasPath = unsortedSmbBasePath != null
                            ? unsortedSmbBasePath + detail.folderPath()
                            : null;
                    return new TitleDetailView(detail, coverFile != null, coverFile, descriptor,
                            !others.isEmpty(), otherViews, directTags, labelImpliedTags, processed,
                            folderNasPath);
                });
    }

    public record OtherLocationView(String volumeId, String path, String nasPath) {}

    public record TitleDetailView(TitleDetail detail, boolean hasCover, String coverFilename,
                                  String descriptor, boolean duplicate,
                                  List<OtherLocationView> otherLocations,
                                  List<String> directTags, List<String> labelImpliedTags,
                                  boolean processed, String folderNasPath) {}

    /**
     * Pull the folder-name descriptor (e.g. "Demosaiced") out of a basename like
     * {@code "Nao Wakana - Demosaiced (ABP-527)"}. Returns empty string when there is no
     * {@code " - "} separator before the code. The prefix (actress / title stub) is discarded
     * — we only keep the text that would sit after the primary actress on a rewrite.
     *
     * <p>Delegates to {@link TitleFolderRenamer#extractDescriptor} — single source of truth.
     */
    static String extractDescriptor(String folderName, String code) {
        return TitleFolderRenamer.extractDescriptor(folderName, code);
    }

    private boolean coverExists(String label, String baseCode) {
        return coverFilename(label, baseCode) != null;
    }

    private String coverFilename(String label, String baseCode) {
        if (label == null || baseCode == null) return null;
        Title t = Title.builder().code(baseCode).label(label).baseCode(baseCode).build();
        return coverPath.find(t)
                .map(p -> p.getFileName().toString())
                .orElse(null);
    }

    // ── Typeahead ────────────────────────────────────────────────────────

    /** Typeahead row shaped for the editor's overlay — mirrors the federated-search fields. */
    public record ActressSearchRow(
            long id,
            String canonicalName,
            String stageName,
            /** Non-null when the match was via an alias rather than the canonical name. */
            String matchedAlias,
            String tier,
            String grade,
            boolean favorite,
            boolean bookmark,
            int titleCount,
            /** Local URL to one of the actress's title covers, or null if none cached. */
            String coverUrl
    ) {}

    public List<ActressSearchRow> searchActresses(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<FederatedActressResult> raw = actressRepo.searchForEditor(query, false, limit);
        return raw.stream()
                .map(r -> new ActressSearchRow(
                        r.id(), r.canonicalName(), r.stageName(), r.matchedAlias(),
                        r.tier(), r.grade(), r.favorite(), r.bookmark(),
                        r.titleCount(), resolveCoverUrl(r.coverCandidates())))
                .toList();
    }

    private String resolveCoverUrl(String coverCandidates) {
        if (coverCandidates == null || coverCandidates.isBlank()) return null;
        for (String candidate : coverCandidates.split("\\|")) {
            int colon = candidate.indexOf(':');
            if (colon < 0) continue;
            String label    = candidate.substring(0, colon);
            String baseCode = candidate.substring(colon + 1);
            Title synth = Title.builder().label(label).baseCode(baseCode).build();
            Optional<String> url = coverPath.find(synth)
                    .map(p -> "/covers/" + label.toUpperCase() + "/" + p.getFileName());
            if (url.isPresent()) return url.get();
        }
        return null;
    }

    // ── Save actress assignments (transactional, with inline create) ────

    /**
     * Individual actress entry in a save request. Exactly one of {@code id} or
     * {@code newName} must be non-null.
     */
    public record ActressEntry(Long id, String newName) {
        public boolean isDraft() { return id == null && newName != null && !newName.isBlank(); }
        public boolean isExisting() { return id != null; }
    }

    public record SaveResult(List<Long> actressIds, long primaryActressId,
                             String folderPath, boolean folderRenamed) {}

    /**
     * Apply a full actress-list replacement, creating any draft actresses inline in the
     * same transaction. Throws {@link IllegalArgumentException} for invalid payloads
     * (empty list, primary not in list, blank draft name).
     */
    public SaveResult replaceActresses(long titleId, List<ActressEntry> entries, ActressEntry primary) {
        return replaceActresses(titleId, entries, primary, null);
    }

    public SaveResult replaceActresses(long titleId, List<ActressEntry> entries, ActressEntry primary,
                                       String descriptor) {
        return replaceActresses(titleId, entries, primary, descriptor, null);
    }

    /**
     * Replace the user-directed tag set for the title. Idempotent; safe to call with the
     * unchanged set. Rebuilds {@code title_effective_tags} (direct ∪ label-implied).
     */
    public void replaceTags(long titleId, List<String> tags) {
        List<String> normalized = tags == null ? List.of() : tags;
        log.info("TitleEditor: replacing tags — titleId={} tags={}", titleId, normalized);
        repo.replaceTags(titleId, normalized);
    }

    /**
     * Save flow for titles that are duplicates of an already-registered entry: the
     * actress assignment (global per code) and cover cache stay untouched. The only
     * mutation is the folder rename, using the existing {@code titles.actress_id}
     * as primary and the supplied descriptor for uniqueness.
     */
    public SaveResult saveDuplicateRename(long titleId, String descriptor) {
        final String validatedDescriptor = validateDescriptor(descriptor);
        log.info("TitleEditor: duplicate-rename start — titleId={} descriptor=\"{}\"",
                titleId, validatedDescriptor);
        if (!repo.hasLocationInVolume(titleId, unsortedVolumeId)) {
            throw new IllegalStateException("Title is no longer in the unsorted volume");
        }
        Long existingPrimary = repo.inTransaction(h ->
                h.createQuery("SELECT actress_id FROM titles WHERE id = :id")
                        .bind("id", titleId)
                        .mapTo(Long.class)
                        .findFirst()
                        .orElse(null));
        if (existingPrimary == null) {
            throw new IllegalStateException("Duplicate title has no primary actress on record");
        }
        SaveResult stub = new SaveResult(List.of(), existingPrimary, null, false);
        SaveResult renamed = renameFolderIfNeeded(titleId, stub, validatedDescriptor);
        placeCoverFromCache(titleId, renamed.folderPath());
        return renamed;
    }

    /**
     * Copy the cached cover image into the title's folder on the unsorted volume as
     * {@code {baseCode}.{ext}}. Used by the duplicate-save path — the canonical Title
     * already has a cached cover, so we plant it in this physical copy's folder so the
     * folder looks the same as every other structured title.
     *
     * <p>Best-effort: failures are logged but do not fail the save. The rename is the
     * durable mutation; the cover can be healed later by {@code sync covers}.
     */
    private void placeCoverFromCache(long titleId, String folderPath) {
        if (folderPath == null) return;
        var detail = repo.findEligibleById(titleId, unsortedVolumeId).orElse(null);
        if (detail == null) return;
        Title synth = Title.builder().label(detail.label()).baseCode(detail.baseCode()).build();
        Optional<java.nio.file.Path> cached = coverPath.find(synth);
        if (cached.isEmpty()) {
            log.info("TitleEditor: no cached cover to place — titleId={} label={} baseCode={}",
                    titleId, detail.label(), detail.baseCode());
            return;
        }
        java.nio.file.Path cachedPath = cached.get();
        String filename = cachedPath.getFileName().toString();
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(cachedPath);
            try (SmbConnectionFactory.SmbShareHandle handle = smbFactory.open(unsortedVolumeId)) {
                VolumeFileSystem fs = handle.fileSystem();
                java.nio.file.Path target = java.nio.file.Path.of(folderPath, filename);
                fs.writeFile(target, bytes);
                log.info("FS mutation [TitleEditor.placeCover]: volume={} titleId={} target={} bytes={}",
                        unsortedVolumeId, titleId, target, bytes.length);
            }
        } catch (IOException e) {
            log.warn("TitleEditor: failed to place cover for titleId={}: {}", titleId, e.getMessage());
        }
    }

    /**
     * Descriptor character allowlist: ASCII letters/digits, space, and the punctuation
     * {@code _ @ # = + , ;}. The hyphen is reserved as the {@code actress - descriptor}
     * delimiter; any filesystem-unsafe character ({@code / \ : * ? " < > |}) is excluded.
     */
    private static final Pattern DESCRIPTOR_ALLOWED = Pattern.compile("^[A-Za-z0-9 _@#=+,;]*$");

    /** Validate and normalize a descriptor. Returns empty string when input is null/blank. */
    public static String validateDescriptor(String descriptor) {
        if (descriptor == null) return "";
        String trimmed = descriptor.trim();
        if (trimmed.isEmpty()) return "";
        if (!DESCRIPTOR_ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Descriptor may only contain letters, digits, spaces, and _ @ # = + , ; "
                            + "(no hyphens or filesystem-reserved characters)");
        }
        return trimmed;
    }

    public SaveResult replaceActresses(long titleId, List<ActressEntry> entries, ActressEntry primary,
                                       String descriptor, List<String> tags) {
        // Fail fast before any DB writes.
        final String validatedDescriptor = validateDescriptor(descriptor);
        int draftCount = (int) (entries == null ? 0 : entries.stream().filter(e -> e != null && e.id() == null).count());
        int existingCount = (entries == null ? 0 : entries.size()) - draftCount;
        log.info("TitleEditor: replaceActresses start — titleId={} existing={} drafts={} primary={} descriptor=\"{}\" tags={}",
                titleId, existingCount, draftCount,
                primary == null ? "null" : (primary.id() != null ? ("id=" + primary.id()) : ("new=\"" + primary.newName() + "\"")),
                validatedDescriptor, tags == null ? null : tags);
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Actress list must not be empty");
        }
        if (primary == null || (primary.id == null && (primary.newName == null || primary.newName.isBlank()))) {
            throw new IllegalArgumentException("Primary actress is required");
        }
        if (!repo.hasLocationInVolume(titleId, unsortedVolumeId)) {
            throw new IllegalStateException("Title is no longer in the unsorted volume");
        }

        // Validate each entry
        for (ActressEntry e : entries) {
            if (e == null) throw new IllegalArgumentException("Null actress entry");
            boolean hasId = e.id != null;
            boolean hasName = e.newName != null && !e.newName.isBlank();
            if (hasId == hasName) {
                throw new IllegalArgumentException("Each actress entry must have exactly one of id or newName");
            }
        }

        // Sentinel guard: a sentinel (Amateur/Various/Unknown) must be the sole cast member.
        // New entries (id==null) are never sentinels — only check existing ids.
        List<Long> existingIds = entries.stream()
                .filter(e -> e.id() != null)
                .map(ActressEntry::id)
                .toList();
        java.util.Set<Long> sentinelIds = actressRepo.findSentinelIds(existingIds);
        int sentinelCount = sentinelIds.size();
        int totalEntries = entries.size();
        if (sentinelCount > 1 || (sentinelCount == 1 && totalEntries > 1)) {
            throw new IllegalArgumentException(
                    "A placeholder (Amateur/Various/Unknown) must be the only cast member.");
        }

        SaveResult committed = repo.inTransaction(h -> {
            // Resolve draft actresses first (create) so we know their ids before link.
            List<Long> ids = new ArrayList<>(entries.size());
            Long primaryId = null;
            for (ActressEntry e : entries) {
                long resolved;
                if (e.isExisting()) {
                    resolved = e.id();
                } else {
                    resolved = createDraftOrReuse(h, e.newName().trim());
                }
                ids.add(resolved);
                if (matchesPrimary(e, primary)) primaryId = resolved;
            }
            if (primaryId == null) {
                throw new IllegalArgumentException("Primary actress is not in the list");
            }
            repo.replaceActressesInTx(h, titleId, ids, primaryId);
            return new SaveResult(ids, primaryId, null, false);
        });
        log.info("TitleEditor: replaceActresses committed — titleId={} actressIds={} primaryId={}",
                titleId, committed.actressIds(), committed.primaryActressId());

        // Mark the staging-volume location as curated. Runs in its own transaction immediately
        // after the actress save commits so curated_at is durable even if the rename later fails.
        repo.markCurated(titleId, unsortedVolumeId, Instant.now().toString());

        // Replace tags (own transaction + effective-tag rebuild). Safe to run after the
        // actress save since it's scoped to the title row and doesn't depend on SMB.
        if (tags != null) replaceTags(titleId, tags);

        // Folder rename (SMB + DB path rewrite) happens after the DB commit so we never
        // hold a SQLite lock across a network op. If the SMB rename fails, actresses are
        // still saved — callers see the failure via the returned error.
        return renameFolderIfNeeded(titleId, committed, validatedDescriptor);
    }

    /**
     * If the title's current folder name differs from the target pattern, perform the SMB
     * rename and update DB paths. Target pattern is
     * {@code "{PrimaryCanonical} - {Descriptor} ({code})"} when {@code descriptor} is non-blank,
     * otherwise {@code "{PrimaryCanonical} ({code})"}. Returns an updated {@link SaveResult}.
     *
     * <p>Delegates to {@link TitleFolderRenamer#renameIfNeeded} which is the single source of
     * truth for folder-name construction, sanitization, collision detection, and the load-bearing
     * dual {@code title_locations.path} + {@code videos.path} rewrite.
     */
    private SaveResult renameFolderIfNeeded(long titleId, SaveResult committed, String descriptor) {
        var detail = repo.findEligibleById(titleId, unsortedVolumeId).orElse(null);
        if (detail == null) return committed;  // race — can't rename

        String primaryName = repo.findActressCanonicalName(committed.primaryActressId()).orElse(null);

        TitleFolderRenamer.RenameOutcome outcome =
                renamer.renameIfNeeded(titleId, primaryName, descriptor, detail.code());
        String effectivePath = outcome.newPath() != null ? outcome.newPath()
                : detail.folderPath();
        return new SaveResult(committed.actressIds(), committed.primaryActressId(),
                effectivePath, outcome.renamed());
    }

    /**
     * Strip filesystem-unsafe characters.
     * Delegates to {@link TitleFolderRenamer#sanitizeFolderName} — single source of truth.
     */
    static String sanitizeFolderName(String raw) {
        return TitleFolderRenamer.sanitizeFolderName(raw);
    }

    private long createDraftOrReuse(org.jdbi.v3.core.Handle h, String name) {
        // If a canonical actress with that exact name already exists, reuse her id.
        Optional<Long> existing = h.createQuery(
                        "SELECT id FROM actresses WHERE canonical_name = :name COLLATE NOCASE")
                .bind("name", name)
                .mapTo(Long.class)
                .findFirst();
        if (existing.isPresent()) return existing.get();
        long newId = repo.createDraftActress(h, name);
        log.info("TitleEditor: created draft actress — id={} name=\"{}\"", newId, name);
        return newId;
    }

    private static boolean matchesPrimary(ActressEntry e, ActressEntry primary) {
        if (e.isExisting() && primary.isExisting()) {
            return e.id().equals(primary.id());
        }
        if (e.isDraft() && primary.isDraft()) {
            return e.newName().trim().equalsIgnoreCase(primary.newName().trim());
        }
        // Mixed (draft vs existing id) — match by normalized name if primary carries a name AND id null.
        return false;
    }
}
