package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.model.Title;
import com.organizer3.repository.ActressRepository;
import com.organizer3.repository.LabelRepository;
import com.organizer3.repository.TitleRepository;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backing service for the federated search overlay.
 *
 * <p>Queries all four entity groups — actresses, titles, labels, companies — in a single
 * call and returns a grouped response. Result limits are hardcoded here to keep
 * OrganizerConfig unchanged (changes to that record break many test constructors).
 */
@RequiredArgsConstructor
public class SearchService {

    private static final int MAX_ACTRESS_RESULTS = 5;
    private static final int MAX_TITLE_RESULTS   = 3;
    private static final int MAX_LABEL_RESULTS   = 2;
    private static final int MAX_COMPANY_RESULTS = 2;

    private final ActressRepository actressRepo;
    private final TitleRepository   titleRepo;
    private final LabelRepository   labelRepo;
    private final CoverPath         coverPath;

    /**
     * Run the federated search and return a grouped result map suitable for JSON serialisation.
     *
     * @param query      the user's search string (non-blank, already trimmed)
     * @param startsWith {@code true} for prefix matching, {@code false} for contains matching
     */
    public Map<String, Object> search(String query, boolean startsWith) {
        List<Map<String, Object>> actresses = actressRepo
                .searchForFederated(query, startsWith, MAX_ACTRESS_RESULTS)
                .stream()
                .map(this::toActressMap)
                .toList();

        List<Map<String, Object>> titles = titleRepo
                .searchByTitleName(query, startsWith, MAX_TITLE_RESULTS)
                .stream()
                .map(this::toTitleMap)
                .toList();

        List<Map<String, Object>> labels = labelRepo
                .searchLabels(query, startsWith, MAX_LABEL_RESULTS)
                .stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code",      l.code());
                    m.put("labelName", l.labelName());
                    m.put("company",   l.company());
                    return m;
                })
                .toList();

        List<String> companies = labelRepo.searchCompanies(query, startsWith, MAX_COMPANY_RESULTS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actresses", actresses);
        result.put("titles",    titles);
        result.put("labels",    labels);
        result.put("companies", companies);
        return result;
    }

    private Map<String, Object> toActressMap(ActressRepository.FederatedActressResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.id());
        m.put("canonicalName", r.canonicalName());
        m.put("stageName",     r.stageName());
        m.put("tier",          r.tier());
        m.put("grade",         r.grade());
        m.put("favorite",      r.favorite());
        m.put("bookmark",      r.bookmark());
        m.put("matchedAlias",  r.matchedAlias());
        m.put("titleCount",    r.titleCount());

        // Resolve cover URL — coverLabel and coverBaseCode come from the same DB row,
        // so CoverPath.find() looks in the right label directory.
        String coverUrl = null;
        if (r.coverLabel() != null && r.coverBaseCode() != null) {
            Title synth = Title.builder()
                    .label(r.coverLabel())
                    .baseCode(r.coverBaseCode())
                    .build();
            coverUrl = coverPath.find(synth)
                    .map(p -> "/covers/" + r.coverLabel().toUpperCase() + "/" + p.getFileName())
                    .orElse(null);
        }
        m.put("coverUrl", coverUrl);
        return m;
    }

    /**
     * Search titles by code prefix for the partial product-code shortcut.
     * Returns up to {@code limit} results; caller passes 11 and checks size > 10 to suppress display.
     */
    public List<Map<String, Object>> searchByCodePrefix(String prefix, int limit) {
        return titleRepo.searchByCodePrefix(prefix, limit)
                .stream()
                .map(this::toTitleMap)
                .toList();
    }

    private Map<String, Object> toTitleMap(TitleRepository.FederatedTitleResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            r.id());
        m.put("code",          r.code());
        m.put("titleOriginal", r.titleOriginal());
        m.put("titleEnglish",  r.titleEnglish());
        m.put("label",         r.label());
        m.put("releaseDate",   r.releaseDate());
        m.put("actressId",     r.actressId());
        m.put("actressName",   r.actressName());
        m.put("favorite",      r.favorite());
        m.put("bookmark",      r.bookmark());

        String coverUrl = null;
        if (r.label() != null && r.baseCode() != null) {
            Title synth = Title.builder()
                    .label(r.label())
                    .baseCode(r.baseCode())
                    .build();
            coverUrl = coverPath.find(synth)
                    .map(p -> "/covers/" + r.label().toUpperCase() + "/" + p.getFileName())
                    .orElse(null);
        }
        m.put("coverUrl", coverUrl);
        return m;
    }
}
