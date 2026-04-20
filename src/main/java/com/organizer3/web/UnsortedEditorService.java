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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for the Title Editor. Aggregates repository calls and adds
 * derived data (cover-present, complete status, typeahead shaping) that is not
 * worth pushing into persistence-layer queries.
 */
@Slf4j
public class UnsortedEditorService {

    private final UnsortedEditorRepository repo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final SmbConnectionFactory smbFactory;
    private final String unsortedVolumeId;

    public UnsortedEditorService(UnsortedEditorRepository repo, ActressRepository actressRepo,
                                 CoverPath coverPath, SmbConnectionFactory smbFactory,
                                 String unsortedVolumeId) {
        this.repo = repo;
        this.actressRepo = actressRepo;
        this.coverPath = coverPath;
        this.smbFactory = smbFactory;
        this.unsortedVolumeId = unsortedVolumeId;
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
            boolean complete
    ) {}

    public List<EligibleListRow> listEligible() {
        List<EligibleTitle> base = repo.listEligible(unsortedVolumeId);
        List<EligibleListRow> out = new ArrayList<>(base.size());
        for (EligibleTitle e : base) {
            boolean hasCover = coverExists(e.label(), e.baseCode());
            boolean complete = hasCover && e.actressCount() > 0;
            out.add(new EligibleListRow(
                    e.titleId(), e.code(), e.folderName(),
                    e.actressCount(), hasCover, complete));
        }
        return out;
    }

    public Optional<TitleDetailView> findEligibleById(long titleId) {
        return repo.findEligibleById(titleId, unsortedVolumeId)
                .map(detail -> {
                    String coverFile = coverFilename(detail.label(), detail.baseCode());
                    String descriptor = extractDescriptor(detail.folderName(), detail.code());
                    return new TitleDetailView(detail, coverFile != null, coverFile, descriptor);
                });
    }

    public record TitleDetailView(TitleDetail detail, boolean hasCover, String coverFilename, String descriptor) {}

    /**
     * Pull the folder-name descriptor (e.g. "Demosaiced") out of a basename like
     * {@code "Nao Wakana - Demosaiced (ABP-527)"}. Returns empty string when there is no
     * {@code " - "} separator before the code. The prefix (actress / title stub) is discarded
     * — we only keep the text that would sit after the primary actress on a rewrite.
     */
    static String extractDescriptor(String folderName, String code) {
        if (folderName == null || code == null) return "";
        String suffix = " (" + code + ")";
        if (!folderName.endsWith(suffix)) return "";
        String prefix = folderName.substring(0, folderName.length() - suffix.length());
        int sep = prefix.indexOf(" - ");
        if (sep < 0) return "";
        return prefix.substring(sep + 3).trim();
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

    /** Typeahead row annotated with the matched alias (if any) for UI transparency. */
    public record ActressSearchRow(
            long id,
            String canonicalName,
            String stageName,
            /** Non-null when the match was via an alias rather than the canonical name. */
            String matchedAlias
    ) {}

    public List<ActressSearchRow> searchActresses(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        List<FederatedActressResult> raw = actressRepo.searchForEditor(query, false, limit);
        return raw.stream()
                .map(r -> new ActressSearchRow(r.id(), r.canonicalName(), r.stageName(), r.matchedAlias()))
                .toList();
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

        // Folder rename (SMB + DB path rewrite) happens after the DB commit so we never
        // hold a SQLite lock across a network op. If the SMB rename fails, actresses are
        // still saved — callers see the failure via the returned error.
        return renameFolderIfNeeded(titleId, committed, descriptor);
    }

    /**
     * If the title's current folder name differs from the target pattern, perform the SMB
     * rename and update DB paths. Target pattern is
     * {@code "{PrimaryCanonical} - {Descriptor} ({code})"} when {@code descriptor} is non-blank,
     * otherwise {@code "{PrimaryCanonical} ({code})"}. Returns an updated {@link SaveResult}.
     */
    private SaveResult renameFolderIfNeeded(long titleId, SaveResult committed, String descriptor) {
        var detail = repo.findEligibleById(titleId, unsortedVolumeId).orElse(null);
        if (detail == null) return committed;  // race — can't rename
        String currentPath = detail.folderPath();
        String currentName = basename(currentPath);
        String primaryName = repo.findActressCanonicalName(committed.primaryActressId()).orElse(null);
        if (primaryName == null) return committed;

        String desc = descriptor == null ? "" : descriptor.trim();
        String base = desc.isEmpty()
                ? primaryName + " (" + detail.code() + ")"
                : primaryName + " - " + desc + " (" + detail.code() + ")";
        String targetName = sanitizeFolderName(base);
        if (targetName.equals(currentName)) {
            return new SaveResult(committed.actressIds(), committed.primaryActressId(), currentPath, false);
        }

        String parent = parentPath(currentPath);
        String newPath = parent.isEmpty() ? targetName : parent + "/" + targetName;

        try (SmbConnectionFactory.SmbShareHandle handle = smbFactory.open(unsortedVolumeId)) {
            VolumeFileSystem fs = handle.fileSystem();
            if (fs.exists(Path.of(newPath)) && !newPath.equalsIgnoreCase(currentPath)) {
                throw new IllegalStateException("Target folder already exists: " + newPath);
            }
            fs.rename(Path.of(currentPath), targetName);
            log.info("Renamed title {} folder: {} -> {}", titleId, currentPath, newPath);
        } catch (IOException e) {
            String rootCause = e.getCause() != null ? e.getCause().toString() : "(no cause)";
            log.warn("Folder rename failed for title {} ({} -> {}): {} / root: {}",
                    titleId, currentPath, newPath, e.getMessage(), rootCause);
            throw new RuntimeException("Folder rename failed: " + e.getMessage() + " / " + rootCause, e);
        }

        repo.renameFolderInDb(titleId, unsortedVolumeId, currentPath, newPath);
        return new SaveResult(committed.actressIds(), committed.primaryActressId(), newPath, true);
    }

    /** Strip filesystem-unsafe characters. Keeps letters, digits, spaces, parens, hyphens, dots, ampersands, apostrophes. */
    static String sanitizeFolderName(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        // Collapse runs of spaces and trim.
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private long createDraftOrReuse(org.jdbi.v3.core.Handle h, String name) {
        // If a canonical actress with that exact name already exists, reuse her id.
        Optional<Long> existing = h.createQuery(
                        "SELECT id FROM actresses WHERE canonical_name = :name COLLATE NOCASE")
                .bind("name", name)
                .mapTo(Long.class)
                .findFirst();
        if (existing.isPresent()) return existing.get();
        return repo.createDraftActress(h, name);
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
