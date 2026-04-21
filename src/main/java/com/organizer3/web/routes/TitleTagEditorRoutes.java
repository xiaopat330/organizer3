package com.organizer3.web.routes;

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

    public TitleTagEditorRoutes(Jdbi jdbi, TitleRepository titleRepo) {
        this.jdbi = jdbi;
        this.titleRepo = titleRepo;
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

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", t.getCode());
            result.put("label", t.getLabel());
            result.put("directTags", direct);
            result.put("labelImpliedTags", implied);
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
                // Rebuild denormalized effective tags: direct + label-implied.
                h.createUpdate("DELETE FROM title_effective_tags WHERE title_id = :id")
                        .bind("id", titleId).execute();
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                        SELECT :id, tag, 'direct' FROM title_tags WHERE title_id = :id
                        """).bind("id", titleId).execute();
                h.createUpdate("""
                        INSERT OR IGNORE INTO title_effective_tags (title_id, tag, source)
                        SELECT t.id, lt.tag, 'label'
                        FROM titles t
                        JOIN label_tags lt ON lt.label_code = t.label
                        WHERE t.id = :id AND t.label IS NOT NULL AND t.label != ''
                        """).bind("id", titleId).execute();
            });

            log.info("Title tags updated — code={} directTags={}", t.getCode(), tagsToSave);

            // Return the refreshed effective-tag view so the client can update without a re-fetch.
            List<String> implied = (t.getLabel() == null || t.getLabel().isBlank())
                    ? List.of()
                    : jdbi.withHandle(h -> h.createQuery(
                        "SELECT tag FROM label_tags WHERE label_code = :label ORDER BY tag")
                        .bind("label", t.getLabel())
                        .mapTo(String.class).list());
            var effective = new TreeSet<String>();
            effective.addAll(tagsToSave);
            effective.addAll(implied);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("directTags", tagsToSave);
            result.put("labelImpliedTags", implied);
            result.put("effectiveTags", new ArrayList<>(effective));
            ctx.json(result);
        });
    }
}
