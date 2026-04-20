package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.ActressRepository.FederatedActressResult;
import com.organizer3.repository.UnsortedEditorRepository;
import com.organizer3.repository.UnsortedEditorRepository.EligibleTitle;
import com.organizer3.repository.UnsortedEditorRepository.TitleDetail;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service layer for the Title Editor. Aggregates repository calls and adds
 * derived data (cover-present, complete status, typeahead shaping) that is not
 * worth pushing into persistence-layer queries.
 */
@RequiredArgsConstructor
public class UnsortedEditorService {

    private final UnsortedEditorRepository repo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final String unsortedVolumeId;

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
                    return new TitleDetailView(detail, coverFile != null, coverFile);
                });
    }

    public record TitleDetailView(TitleDetail detail, boolean hasCover, String coverFilename) {}

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

    public record SaveResult(List<Long> actressIds, long primaryActressId) {}

    /**
     * Apply a full actress-list replacement, creating any draft actresses inline in the
     * same transaction. Throws {@link IllegalArgumentException} for invalid payloads
     * (empty list, primary not in list, blank draft name).
     */
    public SaveResult replaceActresses(long titleId, List<ActressEntry> entries, ActressEntry primary) {
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

        return repo.inTransaction(h -> {
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
            return new SaveResult(ids, primaryId);
        });
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
