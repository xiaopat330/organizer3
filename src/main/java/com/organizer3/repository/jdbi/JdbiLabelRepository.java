package com.organizer3.repository.jdbi;

import com.organizer3.model.Label;
import com.organizer3.repository.LabelRepository;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.organizer3.repository.LabelRepository.LabelSearchResult;

@RequiredArgsConstructor
public class JdbiLabelRepository implements LabelRepository {

    private static final RowMapper<Label> MAPPER = (rs, ctx) -> {
        String tagsCsv = rs.getString("tags_concat");
        List<String> tags = (tagsCsv != null && !tagsCsv.isEmpty())
                ? Arrays.asList(tagsCsv.split(","))
                : List.of();
        return new Label(
                rs.getString("code"),
                rs.getString("label_name"),
                rs.getString("company"),
                rs.getString("description"),
                rs.getString("company_description"),
                rs.getString("company_specialty"),
                rs.getString("company_founded"),
                rs.getString("company_status"),
                rs.getString("company_parent"),
                tags
        );
    };

    private final Jdbi jdbi;

    @Override
    public Map<String, Label> findAllAsMap() {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT l.code, l.label_name, l.company, l.description,
                               l.company_description, l.company_specialty,
                               l.company_founded, l.company_status, l.company_parent,
                               GROUP_CONCAT(lt.tag, ',') AS tags_concat
                        FROM labels l
                        LEFT JOIN label_tags lt ON lt.label_code = l.code
                        GROUP BY l.code, l.label_name, l.company, l.description,
                                 l.company_description, l.company_specialty,
                                 l.company_founded, l.company_status, l.company_parent
                        """)
                        .map(MAPPER)
                        .list()
        ).stream().collect(Collectors.toMap(
                l -> l.code().toUpperCase(),
                l -> l,
                (a, b) -> a   // keep first on duplicate codes
        ));
    }

    @Override
    public List<LabelSearchResult> searchLabels(String query, boolean startsWith, int limit) {
        String pattern = startsWith ? query + "%" : "%" + query + "%";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT code, label_name, company FROM labels
                        WHERE label_name LIKE :pattern COLLATE NOCASE
                           OR code LIKE :pattern COLLATE NOCASE
                        ORDER BY label_name
                        LIMIT :limit
                        """)
                        .bind("pattern", pattern)
                        .bind("limit", limit)
                        .map((rs, ctx) -> new LabelSearchResult(
                                rs.getString("code"),
                                rs.getString("label_name"),
                                rs.getString("company")))
                        .list()
        );
    }

    @Override
    public List<String> searchCompanies(String query, boolean startsWith, int limit) {
        String pattern = startsWith ? query + "%" : "%" + query + "%";
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT DISTINCT company FROM labels
                        WHERE company LIKE :pattern COLLATE NOCASE
                        ORDER BY company
                        LIMIT :limit
                        """)
                        .bind("pattern", pattern)
                        .bind("limit", limit)
                        .mapTo(String.class)
                        .list()
        );
    }
}
