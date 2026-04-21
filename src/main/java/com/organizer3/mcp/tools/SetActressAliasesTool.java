package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.model.ActressAlias;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Replace all aliases for an actress atomically, with conflict detection.
 *
 * <p>Conflicts (returns an error, no change made):
 * <ul>
 *   <li>A proposed alias is the canonical name of a different actress</li>
 *   <li>A proposed alias is already an alias owned by a different actress</li>
 * </ul>
 *
 * <p>Blank and duplicate entries are silently dropped before validation.
 *
 * <p>Defaults to {@code dryRun: true} — returns what would be set without committing.
 */
@Slf4j
public class SetActressAliasesTool implements Tool {

    private final ActressRepository actressRepo;

    public SetActressAliasesTool(ActressRepository actressRepo) {
        this.actressRepo = actressRepo;
    }

    @Override public String name()        { return "set_actress_aliases"; }
    @Override public String description() {
        return "Replace all aliases for an actress. Conflict-checks each proposed alias "
             + "(cannot be canonical name or existing alias of another actress). "
             + "Defaults to dryRun:true — returns the plan without committing.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("id",      "integer", "Actress id. Either this or 'name' is required.")
                .prop("name",    "string",  "Actress canonical or alias name. Either this or 'id' is required.")
                .propArray("aliases", "New alias list. Replaces all existing aliases. Pass [] to clear.")
                .prop("dryRun",  "boolean", "If true (default), return the plan without committing.", true)
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String nameArg = Schemas.optString(args, "name", null);
        long   idArg   = Schemas.optLong(args, "id", -1);
        boolean dryRun = Schemas.optBoolean(args, "dryRun", true);

        if ((nameArg == null || nameArg.isBlank()) && idArg < 0) {
            throw new IllegalArgumentException("Must provide either 'name' or 'id'");
        }

        JsonNode aliasesNode = args.get("aliases");
        if (aliasesNode == null || !aliasesNode.isArray()) {
            throw new IllegalArgumentException("'aliases' must be an array (may be empty)");
        }

        Actress actress = (idArg >= 0)
                ? actressRepo.findById(idArg).orElseThrow(
                        () -> new IllegalArgumentException("No actress with id " + idArg))
                : actressRepo.resolveByName(nameArg).orElseThrow(
                        () -> new IllegalArgumentException("No actress found for name '" + nameArg + "'"));

        // Collect, dedupe, trim the proposed aliases
        List<String> proposed = new ArrayList<>();
        for (JsonNode node : aliasesNode) {
            String v = node.asText("").trim();
            if (!v.isBlank() && !proposed.contains(v)) proposed.add(v);
        }

        // Conflict check
        List<String> conflicts = new ArrayList<>();
        for (String alias : proposed) {
            Optional<Actress> byCanonical = actressRepo.findByCanonicalName(alias);
            if (byCanonical.isPresent() && byCanonical.get().getId() != actress.getId()) {
                conflicts.add("'" + alias + "' is the canonical name of another actress ("
                        + byCanonical.get().getCanonicalName() + ")");
                continue;
            }
            Optional<Actress> byAlias = actressRepo.resolveByName(alias);
            if (byAlias.isPresent() && byAlias.get().getId() != actress.getId()) {
                conflicts.add("'" + alias + "' is already an alias for another actress ("
                        + byAlias.get().getCanonicalName() + ")");
            }
        }

        if (!conflicts.isEmpty()) {
            log.warn("MCP set_actress_aliases rejected — id={} name=\"{}\" conflicts={}",
                    actress.getId(), actress.getCanonicalName(), conflicts);
            return new Result(false, dryRun, actress.getId(), actress.getCanonicalName(),
                    currentAliases(actress.getId()), proposed, conflicts, "Conflict(s) detected — no change made");
        }

        List<String> before = currentAliases(actress.getId());

        if (!dryRun) {
            actressRepo.replaceAllAliases(actress.getId(), proposed);
            log.info("MCP set_actress_aliases committed — id={} name=\"{}\" before={} after={}",
                    actress.getId(), actress.getCanonicalName(), before, proposed);
        } else {
            log.info("MCP set_actress_aliases dry-run — id={} name=\"{}\" before={} proposed={}",
                    actress.getId(), actress.getCanonicalName(), before, proposed);
        }

        String summary = dryRun
                ? String.format("Would replace %d alias(es) with %d for '%s'",
                        before.size(), proposed.size(), actress.getCanonicalName())
                : String.format("Replaced %d alias(es) with %d for '%s'",
                        before.size(), proposed.size(), actress.getCanonicalName());

        return new Result(true, dryRun, actress.getId(), actress.getCanonicalName(),
                before, proposed, List.of(), summary);
    }

    private List<String> currentAliases(long actressId) {
        return actressRepo.findAliases(actressId).stream()
                .map(ActressAlias::aliasName)
                .sorted()
                .toList();
    }

    public record Result(
            boolean ok,
            boolean dryRun,
            long actressId,
            String canonicalName,
            List<String> aliasesBefore,
            List<String> aliasesAfter,
            List<String> conflicts,
            String summary
    ) {}
}
