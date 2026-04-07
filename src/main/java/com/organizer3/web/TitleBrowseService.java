package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Label;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Read-only query service backing the browse home page.
 */
public class TitleBrowseService {

    static final int MAX_LIMIT = 500;

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;
    private final LabelRepository labelRepo;

    public TitleBrowseService(TitleRepository titleRepo, ActressRepository actressRepo,
                              CoverPath coverPath, LabelRepository labelRepo) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.coverPath = coverPath;
        this.labelRepo = labelRepo;
    }

    /**
     * Returns at most {@code limit} titles starting at {@code offset}, ordered newest-first.
     * Hard-capped at {@link #MAX_LIMIT} total regardless of requested limit.
     */
    public List<TitleSummary> findRecent(int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        return toSummaries(titleRepo.findRecent(limit, offset));
    }

    /**
     * Returns titles in the queue partition of the given volume, ordered newest-first.
     * Hard-capped at {@link #MAX_LIMIT} total regardless of requested limit.
     *
     * <p>Queue titles are synced without an actress_id. For each one, we attempt to infer
     * the actress by finding another title in the DB with the same base_code that has already
     * been attributed (e.g. a stars copy on another volume). Only exact base_code matches
     * are used — label-based guessing produced too many false positives.
     */
    public List<TitleSummary> findByVolumeQueue(String volumeId, int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        List<Title> titles = titleRepo.findByVolumeAndPartition(volumeId, "queue", limit, offset);

        // Infer actress for unattributed titles via exact base_code match only.
        Map<String, Long> actressIdByBaseCode = new HashMap<>();

        titles.stream()
                .filter(t -> t.getActressId() == null)
                .forEach(t -> {
                    if (t.getBaseCode() != null && !actressIdByBaseCode.containsKey(t.getBaseCode())) {
                        titleRepo.findByBaseCode(t.getBaseCode()).stream()
                                .filter(other -> other.getActressId() != null)
                                .findFirst()
                                .ifPresent(other -> actressIdByBaseCode.put(t.getBaseCode(), other.getActressId()));
                    }
                });

        if (!actressIdByBaseCode.isEmpty()) {
            titles = titles.stream()
                    .map(t -> {
                        if (t.getActressId() != null) return t;
                        Long inferred = t.getBaseCode() != null ? actressIdByBaseCode.get(t.getBaseCode()) : null;
                        return inferred != null ? t.toBuilder().actressId(inferred).build() : t;
                    })
                    .toList();
        }

        return toSummaries(titles);
    }

    private List<TitleSummary> toSummaries(List<Title> titles) {
        record ActressInfo(String name, String tier) {}

        Map<Long, ActressInfo> actressInfo = titles.stream()
                .map(Title::getActressId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> actressRepo.findById(id)
                                .map(a -> new ActressInfo(a.getCanonicalName(),
                                        a.getTier() != null ? a.getTier().name() : null))
                                .orElse(new ActressInfo(null, null))
                ));

        Map<String, Label> labelMap = labelRepo.findAllAsMap();

        return titles.stream()
                .map(t -> {
                    Label lbl = t.getLabel() != null ? labelMap.get(t.getLabel().toUpperCase()) : null;
                    String coverUrl = t.getLabel() != null
                            ? coverPath.find(t)
                                    .map(p -> "/covers/" + t.getLabel().toUpperCase() + "/" + p.getFileName())
                                    .orElse(null)
                            : null;
                    ActressInfo ai = t.getActressId() != null ? actressInfo.get(t.getActressId()) : null;
                    return new TitleSummary(
                            t.getCode(),
                            t.getBaseCode(),
                            t.getLabel(),
                            t.getActressId(),
                            ai != null ? ai.name() : null,
                            ai != null ? ai.tier() : null,
                            t.getAddedDate() != null ? t.getAddedDate().toString() : null,
                            coverUrl,
                            lbl != null ? lbl.company() : null,
                            lbl != null ? lbl.labelName() : null,
                            t.getPath() != null ? t.getPath().toString() : null
                    );
                })
                .toList();
    }
}
