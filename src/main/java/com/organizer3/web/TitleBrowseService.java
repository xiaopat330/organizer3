package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.TitleRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Read-only query service backing the browse home page.
 */
public class TitleBrowseService {

    static final int MAX_LIMIT = 72;

    private final TitleRepository titleRepo;
    private final ActressRepository actressRepo;
    private final CoverPath coverPath;

    public TitleBrowseService(TitleRepository titleRepo, ActressRepository actressRepo, CoverPath coverPath) {
        this.titleRepo = titleRepo;
        this.actressRepo = actressRepo;
        this.coverPath = coverPath;
    }

    /**
     * Returns at most {@code limit} titles starting at {@code offset}, ordered newest-first.
     * Hard-capped at {@link #MAX_LIMIT} total regardless of requested limit.
     */
    public List<TitleSummary> findRecent(int offset, int limit) {
        limit = Math.min(limit, MAX_LIMIT);
        List<Title> titles = titleRepo.findRecent(limit, offset);

        Map<Long, String> actressNames = titles.stream()
                .map(Title::actressId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> actressRepo.findById(id).map(Actress::getCanonicalName).orElse(null)
                ));

        return titles.stream()
                .map(t -> new TitleSummary(
                        t.code(),
                        t.baseCode(),
                        t.label(),
                        t.actressId() != null ? actressNames.get(t.actressId()) : null,
                        t.addedDate() != null ? t.addedDate().toString() : null,
                        coverPath.find(t)
                                .map(p -> "/covers/" + t.label().toUpperCase() + "/" + p.getFileName())
                                .orElse(null)
                ))
                .toList();
    }
}
