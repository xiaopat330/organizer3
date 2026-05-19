package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.organizer3.mcp.Schemas;
import com.organizer3.mcp.Tool;
import com.organizer3.model.Actress;
import com.organizer3.repository.ActressRepository;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Jdbi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a new actress row in the DB, with optional stage name and aliases.
 *
 * <p>Errors cleanly if the canonical name already exists (case-insensitive), so callers
 * always know what id to use without risking silent duplicates.
 *
 * <p>The {@code first_seen_at} column is populated with {@link LocalDate#now()} — always
 * a plain date value to avoid the {@code DateTimeParseException} that {@code datetime('now')}
 * would cause when the JDBI mapper reads it back as a {@code LocalDate}.
 */
@Slf4j
public class CreateActressTool implements Tool {

    private final ActressRepository actressRepo;
    private final Jdbi jdbi;

    public CreateActressTool(ActressRepository actressRepo, Jdbi jdbi) {
        this.actressRepo = actressRepo;
        this.jdbi        = jdbi;
    }

    @Override public String name() { return "create_actress"; }

    @Override
    public String description() {
        return "Create a new actress row in the database. "
             + "Errors if the canonical name already exists (returns the existing id). "
             + "Optional stageName (kanji/kana) and aliases are inserted atomically.";
    }

    @Override
    public JsonNode inputSchema() {
        return Schemas.object()
                .prop("canonicalName", "string",  "Required. Romaji canonical name (e.g. \"Kana Mochizuki\").")
                .prop("stageName",     "string",  "Optional. Japanese kanji/kana stage name.")
                .prop("tier",          "string",  "Optional. LIBRARY (default), MINOR, POPULAR, SUPERSTAR, or GODDESS.")
                .propArray("aliases",             "Optional. Additional alias strings to insert into actress_aliases.")
                .require("canonicalName")
                .build();
    }

    @Override
    public Object call(JsonNode args) {
        String canonicalName = Schemas.requireString(args, "canonicalName").trim();
        String stageName     = Schemas.optString(args, "stageName", null);
        String tierStr       = Schemas.optString(args, "tier", null);

        if (canonicalName.isBlank()) {
            throw new IllegalArgumentException("canonicalName must not be blank");
        }

        // Parse tier
        Actress.Tier tier = Actress.Tier.LIBRARY;
        if (tierStr != null && !tierStr.isBlank()) {
            try {
                tier = Actress.Tier.valueOf(tierStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown tier '" + tierStr + "'. Valid values: LIBRARY, MINOR, POPULAR, SUPERSTAR, GODDESS");
            }
        }

        // Collect aliases
        List<String> aliases = new ArrayList<>();
        JsonNode aliasesNode = args.get("aliases");
        if (aliasesNode != null && aliasesNode.isArray()) {
            for (JsonNode node : aliasesNode) {
                String v = node.asText("").trim();
                if (!v.isBlank() && !aliases.contains(v)) {
                    aliases.add(v);
                }
            }
        }

        // Duplicate check — resolve by canonical name (case-insensitive)
        var existing = actressRepo.findByCanonicalName(canonicalName);
        if (existing.isPresent()) {
            Actress dup = existing.get();
            log.warn("create_actress rejected duplicate — canonicalName=\"{}\" existingId={}",
                    canonicalName, dup.getId());
            return new DuplicateResult(
                    dup.getId(),
                    dup.getCanonicalName(),
                    "Actress already exists with this canonical name. Use the existing id.");
        }

        // Build and save the actress
        Actress toInsert = Actress.builder()
                .canonicalName(canonicalName)
                .stageName(stageName)
                .tier(tier)
                .firstSeenAt(LocalDate.now())
                .build();

        Actress saved;
        if (aliases.isEmpty()) {
            saved = actressRepo.save(toInsert);
        } else {
            // Insert actress + aliases in one transaction
            final Actress[] savedHolder = new Actress[1];
            jdbi.useTransaction(h -> {
                savedHolder[0] = actressRepo.save(toInsert);
                for (String alias : aliases) {
                    actressRepo.addAlias(savedHolder[0].getId(), alias, h);
                }
            });
            saved = savedHolder[0];
        }

        log.info("create_actress ok — id={} canonicalName=\"{}\" tier={} stageName=\"{}\" aliases={}",
                saved.getId(), saved.getCanonicalName(), saved.getTier(),
                saved.getStageName(), aliases);

        return new Result(saved.getId(), saved.getCanonicalName(), saved.getTier().name(),
                saved.getStageName(), aliases, "created");
    }

    // ── result types ─────────────────────────────────────────────────────────

    public record Result(
            long   actressId,
            String canonicalName,
            String tier,
            String stageName,
            List<String> aliases,
            String status
    ) {}

    public record DuplicateResult(
            long   existingId,
            String existingCanonicalName,
            String error
    ) {}
}
