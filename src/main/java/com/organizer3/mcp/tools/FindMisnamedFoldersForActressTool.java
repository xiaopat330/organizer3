package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * For a single actress, return every title location whose on-disk path doesn't
 * contain the actress's canonical name. Reports which of her aliases (if any) is
 * present in the path, so the user can tell the difference between:
 * <ul>
 *   <li>A merged-away name that now needs a rename (alias matches path)</li>
 *   <li>A historical name someone once used that should still be renamed to
 *       canonical (alias matches path — same intent, different origin)</li>
 *   <li>A folder with an unexpected name unrelated to any known alias (alias null)</li>
 * </ul>
 */
public class FindMisnamedFoldersForActressTool implements Tool {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT     = 5000;

    private final Jdbi jdbi;
    private final ActressRepository actressRepo;

    public FindMisnamedFoldersForActressTool(Jdbi jdbi, ActressRepository actressRepo) {
        this.jdbi = jdbi;
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "find_misnamed_folders_for_actress"; }
    @Override public String description() {
        return "Return every title location for one actress where the on-disk path doesn't contain her "
             + "canonical name. Flags the matched alias (if any) so merged-away typos are distinguishable "
             + "from historical-alias folders.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("actress_id", "integer", "Actress id to inspect. Either this or 'name' is required.")
                .prop("name",       "string",  "Canonical name or alias to resolve. Either this or 'actress_id' is required.")
                .prop("limit",      "integer", "Maximum rows to return. Default 500, max 5000.", DEFAULT_LIMIT)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        long idArg    = Schemas.optLong(args, "actress_id", -1);
        String nameArg = Schemas.optString(args, "name", null);
        int limit = Math.max(1, Math.min(Schemas.optInt(args, "limit", DEFAULT_LIMIT), MAX_LIMIT));

        if (idArg < 0 && (nameArg == null || nameArg.isBlank())) {
            throw new IllegalArgumentException("Must provide either 'actress_id' or 'name'");
        }

        Actress actress = (idArg >= 0
                ? actressRepo.findById(idArg)
                : actressRepo.resolveByName(nameArg))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No actress found for " + (idArg >= 0 ? "id=" + idArg : "name=" + nameArg)));

        List<String> aliases = actressRepo.findAliases(actress.getId()).stream()
                .map(ActressAlias::aliasName).toList();

        return jdbi.withHandle(h -> {
            List<Row> rows = new ArrayList<>();
            h.createQuery("""
                    SELECT t.code, tl.volume_id, tl.path
                    FROM titles t
                    JOIN title_locations tl ON tl.title_id = t.id
                    WHERE t.actress_id = :aid
                      AND instr(LOWER(tl.path), LOWER(:canonical)) = 0
                    ORDER BY tl.volume_id, t.code
                    LIMIT :lim
                    """)
                    .bind("aid", actress.getId())
                    .bind("canonical", actress.getCanonicalName())
                    .bind("lim", limit)
                    .map((rs, ctx) -> {
                        String path = rs.getString("path");
                        String matched = findMatchedAlias(path, aliases);
                        return new Row(
                                rs.getString("code"),
                                rs.getString("volume_id"),
                                path,
                                matched);
                    })
                    .forEach(rows::add);
            return new Result(actress.getId(), actress.getCanonicalName(), aliases,
                    rows.size(), rows);
        });
    }

    private static String findMatchedAlias(String path, List<String> aliases) {
        if (path == null) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()
                    && lower.contains(alias.toLowerCase(Locale.ROOT))) {
                return alias;
            }
        }
        return null;
    }

    public record Row(String titleCode, String volumeId, String path, String matchedAlias) {}
    public record Result(long actressId, String canonicalName, List<String> knownAliases,
                         int count, List<Row> misnamedLocations) {}
}
