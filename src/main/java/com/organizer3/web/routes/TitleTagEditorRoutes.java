package com.organizer3.web.routes;

import com.organizer3.db.TitleEffectiveTagsService;
import com.organizer3.model.Title;
import com.organizer3.repository.TitleRepository;
import io.javalin.Javalin;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Direct-tag editor for the title-detail screen.
 *
 * <ul>
 *   <li>{@code GET /api/titles/{code}/tag-state} — returns {@code { directTags, labelImpliedTags }}
 *       so the UI can render the modal with correct initial toggles (label-implied are shown as
 *       read-only red pills; direct are editable).</li>
 *   <li>{@code PUT /api/titles/{code}/tags} — body {@code { tags: [...] }}. Replaces the direct-tag
 *       set and rebuilds {@code title_effective_tags} from direct + label-implied.</li>
 * </ul>
 */
@Slf4j
public class TitleTagEditorRoutes {

    private final Jdbi jdbi;
    private final TitleRepository titleRepo;
    private final TitleEffectiveTagsService effectiveTags;

    public TitleTagEditorRoutes(Jdbi jdbi, TitleRepository titleRepo, TitleEffectiveTagsService effectiveTags) {
        this.jdbi = jdbi;
        this.titleRepo = titleRepo;
        this.effectiveTags = effectiveTags;
    }

    private List<String> enrichmentImpliedTagsFor(long titleId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT DISTINCT etd.curated_alias
                FROM title_enrichment_tags tet
                JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                WHERE tet.title_id = :id
                  AND etd.curated_alias IS NOT NULL
                  AND etd.curated_alias IN (SELECT name FROM tags)
                ORDER BY etd.curated_alias
                """)
                .bind("id", titleId)
                .mapTo(String.class).list());
    }

    public void register(Javalin app) {
        app.get("/api/titles/{code}/tag-state", ctx -> {
            Title t = titleRepo.findByCode(ctx.pathParam("code")).orElse(null);
            if (t == null) { ctx.status(404); return; }

            List<String> direct = jdbi.withHandle(h -> h.createQuery(
                    "SELECT tag FROM title_tags WHERE title_id = :id ORDER BY tag")
                    .bind("id", t.getId())
                    .mapTo(String.class).list());
            List<String> implied = (t.getLabel() == null || t.getLabel().isBlank())
                    ? List.of()
                    : jdbi.withHandle(h -> h.createQuery(
                        "SELECT tag FROM label_tags WHERE label_code = :label ORDER BY tag")
                        .bind("label", t.getLabel())
                        .mapTo(String.class).list());
            List<String> enrichmentImplied = enrichmentImpliedTagsFor(t.getId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", t.getCode());
            result.put("label", t.getLabel());
            result.put("directTags", direct);
            result.put("labelImpliedTags", implied);
            result.put("enrichmentImpliedTags", enrichmentImplied);
            ctx.json(result);
        });

        app.put("/api/titles/{code}/tags", ctx -> {
            Title t = titleRepo.findByCode(ctx.pathParam("code")).orElse(null);
            if (t == null) { ctx.status(404); return; }

            List<String> tags;
            try {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                Object raw = body.get("tags");
                if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("tags must be an array");
                // De-dupe + strip blanks, preserve stable order.
                var seen = new TreeSet<String>();
                tags = new ArrayList<>();
                for (Object o : list) {
                    if (o == null) continue;
                    String s = o.toString().trim();
                    if (s.isEmpty()) continue;
                    if (seen.add(s)) tags.add(s);
                }
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "Invalid body; expected { tags: [..] }"));
                return;
            }

            final List<String> tagsToSave = tags;
            long titleId = t.getId();
            jdbi.useTransaction(h -> {
                h.createUpdate("DELETE FROM title_tags WHERE title_id = :id")
                        .bind("id", titleId).execute();
                for (String tag : tagsToSave) {
                    h.createUpdate("INSERT OR IGNORE INTO title_tags (title_id, tag) VALUES (:id, :t)")
                            .bind("id", titleId).bind("t", tag).execute();
                }
            });
            // Effective-tag rebuild lives in TitleEffectiveTagsService (handles all three sources:
            // direct, label, enrichment). Synchronous so the response below sees the freshly-derived state.
            effectiveTags.recomputeForTitle(titleId);

            log.info("Title modified — code={} directTags={}", t.getCode(), tagsToSave);

            // Return the refreshed effective-tag view so the client can update without a re-fetch.
            List<String> implied = (t.getLabel() == null || t.getLabel().isBlank())
                    ? List.of()
                    : jdbi.withHandle(h -> h.createQuery(
                        "SELECT tag FROM label_tags WHERE label_code = :label ORDER BY tag")
                        .bind("label", t.getLabel())
                        .mapTo(String.class).list());
            List<String> enrichmentImplied = enrichmentImpliedTagsFor(titleId);
            var effective = new TreeSet<String>();
            effective.addAll(tagsToSave);
            effective.addAll(implied);
            effective.addAll(enrichmentImplied);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directTags", tagsToSave);
            result.put("labelImpliedTags", implied);
            result.put("enrichmentImpliedTags", enrichmentImplied);
            result.put("effectiveTags", new ArrayList<>(effective));
            ctx.json(result);
        });

        // Returns the raw javdb enrichment tags for a title, with their curated alias (if mapped).
        // Used by the title detail panel to render the "Javdb" tags row.
        app.get("/api/titles/{code}/enrichment-tags", ctx -> {
            Title t = titleRepo.findByCode(ctx.pathParam("code")).orElse(null);
            if (t == null) { ctx.status(404); return; }
            List<Map<String, Object>> tags = jdbi.withHandle(h -> h.createQuery("""
                    SELECT etd.name, etd.curated_alias
                    FROM title_enrichment_tags tet
                    JOIN enrichment_tag_definitions etd ON etd.id = tet.tag_id
                    WHERE tet.title_id = :id
                    ORDER BY etd.name
                    """)
                    .bind("id", t.getId())
                    .map((rs, rctx) -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("name", rs.getString("name"));
                        m.put("curatedAlias", rs.getString("curated_alias"));
                        return (Map<String, Object>) m;
                    })
                    .list());
            ctx.json(Map.of("isEnriched", !tags.isEmpty(), "tags", tags));
        });
    }
}
