package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;

import java.util.List;

/**
 * Returns a curated, agent-oriented summary of the main domain entities and how they
 * relate. This is deliberately higher-level than {@code sql_schema}: it surfaces the
 * conceptual model (what's an "alias"? what's a "title location"?) rather than raw DDL.
 *
 * <p>The content is hand-maintained here rather than generated from the schema —
 * mechanical schema is what {@code sql_schema} exists for.
 */
public class DescribeSchemaTool implements Tool {

    @Override public String name()        { return "describe_schema"; }
    @Override public String description() { return "High-level conceptual overview of domain entities (actresses, titles, aliases, volumes) and how they relate."; }
    @Override public JsonNode inputSchema() { return Schemas.empty(); }

    @Override
    public Object call(JsonNode args) {
        return new SchemaDoc(List.of(
                new Entity("actresses",
                        "One row per canonical performer. Names are stored given-name-first (e.g. 'Yua Mikami').",
                        List.of("id", "canonical_name", "stage_name", "tier", "favorite", "bookmark", "grade", "rejected")),
                new Entity("actress_aliases",
                        "Many-to-one alias names mapped to an actress id. Used to resolve alternate spellings, transliterations, and kanji readings.",
                        List.of("actress_id", "alias_name")),
                new Entity("titles",
                        "One row per title (JAV release). The product code (e.g. 'MIDE-123') is the primary user-facing identifier. A title may have one filing actress via actress_id, plus many credited actresses via title_actresses.",
                        List.of("id", "code", "label", "base_code", "name", "actress_id", "release_date")),
                new Entity("title_locations",
                        "Per-(title, volume) row recording where a title physically lives on disk. A title can appear on multiple volumes — each location is a separate row.",
                        List.of("title_id", "volume_id", "path")),
                new Entity("title_actresses",
                        "Many-to-many join of titles to actresses. Credits beyond the single filing actress.",
                        List.of("title_id", "actress_id")),
                new Entity("title_tags",
                        "Many-to-many tags applied to titles (genre, era, collection, etc.).",
                        List.of("title_id", "tag")),
                new Entity("videos",
                        "Physical video files found inside a title folder during sync.",
                        List.of("id", "title_id", "filename", "size_bytes", "duration_seconds")),
                new Entity("labels",
                        "Title label codes (e.g. 'MIDE') mapped to company and studio group.",
                        List.of("code", "company", "studio_group")),
                new Entity("volumes",
                        "Mount targets discovered during sync. The configured set lives in organizer-config.yaml; this table caches what was last seen."
                        , List.of("id", "last_synced_at"))
        ), List.of(
                "An actress is resolved from any name via ActressRepository.resolveByName — this checks actress_aliases first, then canonical_name.",
                "A title's 'actresses' are the union of {actress_id} ∪ {title_actresses.actress_id for this title}.",
                "A title with a single filing actress has that actress in actress_id; compilations populate only title_actresses.",
                "Title product code = label + '-' + base_code, e.g. MIDE-123 = label 'MIDE' + base_code '123'."
        ));
    }

    public record SchemaDoc(List<Entity> entities, List<String> notes) {}
    public record Entity(String table, String description, List<String> keyColumns) {}
}
