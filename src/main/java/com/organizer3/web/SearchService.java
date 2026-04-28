package com.organizer3.web;

import com.organizer3.avstars.repository.AvActressRepository;
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

    private static final int MAX_ACTRESS_RESULTS    = 5;
    private static final int MAX_TITLE_RESULTS      = 3;
    private static final int MAX_LABEL_RESULTS      = 2;
    private static final int MAX_COMPANY_RESULTS    = 2;
    private static final int MAX_AV_ACTRESS_RESULTS = 5;

    private final ActressRepository   actressRepo;
    private final TitleRepository     titleRepo;
    private final LabelRepository     labelRepo;
    private final CoverPath           coverPath;
    private final AvActressRepository avActressRepo;

    /**
     * Run the federated search and return a grouped result map suitable for JSON serialisation.
     *
     * @param query      the user's search string (non-blank, already trimmed)
     * @param startsWith {@code true} for prefix matching, {@code false} for contains matching
     * @param includeAv  {@code true} to include AV actress results
     */
    public Map<String, Object> search(String query, boolean startsWith, boolean includeAv) {
        return search(query, startsWith, includeAv, false);
    }

    /**
     * Variant that, when {@code includeSparse} is true, uses {@code searchForEditor} for
     * actresses so performers with fewer than 2 titles are included. Used by the alias
     * editor, which must be able to find any actress that could block an alias edit.
     */
    public Map<String, Object> search(String query, boolean startsWith, boolean includeAv, boolean includeSparse) {
        var rawActresses = includeSparse
                ? actressRepo.searchForEditor(query, startsWith, MAX_ACTRESS_RESULTS)
                : actressRepo.searchForFederated(query, startsWith, MAX_ACTRESS_RESULTS);
        List<Map<String, Object>> actresses = rawActresses.stream()
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

        List<Map<String, Object>> avActresses = includeAv
                ? avActressRepo.searchForFederated(query, MAX_AV_ACTRESS_RESULTS)
                        .stream().map(this::toAvActressMap).toList()
                : List.of();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actresses",   actresses);
        result.put("titles",      titles);
        result.put("labels",      labels);
        result.put("companies",   companies);
        result.put("avActresses", avActresses);
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

        // Prefer the local actress profile avatar when available; fall back to a cover.
        String coverUrl = null;
        if (r.localAvatarPath() != null && !r.localAvatarPath().isBlank()) {
            coverUrl = "/" + r.localAvatarPath();
        } else if (r.coverCandidates() != null) {
            for (String candidate : r.coverCandidates().split("\\|")) {
                int colon = candidate.indexOf(':');
                if (colon < 0) continue;
                String label    = candidate.substring(0, colon);
                String baseCode = candidate.substring(colon + 1);
                Title synth = Title.builder().label(label).baseCode(baseCode).build();
                coverUrl = coverPath.find(synth)
                        .map(p -> "/covers/" + label.toUpperCase() + "/" + p.getFileName())
                        .orElse(null);
                if (coverUrl != null) break;
            }
        }
        m.put("coverUrl", coverUrl);
        return m;
    }

    private Map<String, Object> toAvActressMap(AvActressRepository.FederatedAvActressResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         r.id());
        m.put("stageName",  r.stageName());
        m.put("videoCount", r.videoCount());
        String headshotUrl = r.headshotPath() != null
                ? "/api/av/headshots/" + java.nio.file.Path.of(r.headshotPath()).getFileName()
                : null;
        m.put("headshotUrl", headshotUrl);
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
