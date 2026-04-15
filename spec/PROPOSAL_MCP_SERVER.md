# Proposal: Local MCP Server

> **Status: PROPOSAL — early design, not yet implemented**

Expose Organizer3's query and scan capabilities over the Model Context Protocol so that a local MCP client (Claude Desktop, Claude Code, or any MCP-aware agent) can investigate the JAV library and help diagnose data-quality issues — misspelled actresses, bad aliases, mislabeled titles, orphaned folders — by combining DB queries with live filesystem scans.

---

## 1. Motivation

The JAV collection is large, messy, and accumulates damage faster than it can be manually audited:

- Actress names drift across titles — typos, transliteration variants (`Yua Mikami` / `Mikami Yua` / `三上悠亜`), bad OCR, half-captured aliases.
- Title folders sometimes disagree with their codes or with each other across volumes.
- Files accumulate in unexpected places: loose videos, stray covers, empty folders, codes that don't parse.

The web UI and shell are good for browsing known-good data, but they're not shaped for *"find the weird stuff so we can fix it"*. The agent workflow I want is:

> Me: "Are there obvious actress-name duplicates I should clean up?"
> Agent: runs a name-similarity tool, returns a ranked list, maybe pulls filesystem samples for the top suspects, proposes merges.

> Me: "Why does this title look off?"
> Agent: fetches the DB record, lists the folder on disk, compares, tells me what mismatches.

An MCP server is the right shape for that: structured tools the agent can call itself, compact JSON back, no screen-scraping.

The target is not a remote API. The server runs inside the already-running app, speaks MCP over a local Unix socket, and is intended for the user's own local agents. No authentication, no network exposure.

---

## 2. Scope

Phase 1 is **purely read-only, diagnostic-oriented**. Every tool returns structured JSON shaped for agent reasoning — enough context to decide what to ask next, not just a single record. No mutations.

Tools are grouped by what kind of question they help answer.

### 2.1 Lookup — "what does the DB say about X?"

| Tool | Returns |
|---|---|
| `lookup_actress` | Actress + all aliases + favorite flag + title count + per-volume breakdown |
| `lookup_title` | Title + code + name + all locations (volume + path) + actresses + tags + video file list |
| `search_actresses` | Fuzzy name/alias matches, paged, with hit-field annotations (matched on primary vs alias) |
| `search_titles` | Fuzzy code/name matches, paged |
| `list_titles_for_actress` | All titles featuring an actress, with volume + path |
| `list_actresses_for_title` | All actresses credited on a title |

### 2.2 Name-quality — "what looks wrong with actress names?"

| Tool | Returns |
|---|---|
| `find_similar_actresses` | Pairs of actresses whose names are within edit distance N (configurable), grouped by similarity band. Designed to surface typo duplicates. Includes title counts on each side so the agent can judge which is canonical. |
| `find_name_order_variants` | Pairs of actresses whose names are identical under **token-set equality** — i.e. the set of whitespace-separated tokens matches after case-folding and diacritic stripping. Catches surname/given-name inversions (`Yua Mikami` ↔ `Mikami Yua`), extra middle tokens, and spacing differences that edit distance misses. Also checks token-set equality against all aliases, so an inverted spelling that's already an alias of someone else surfaces too. |
| `find_unaliased_variants` | Titles whose on-disk folder credits a name string that does not match any known actress or alias — candidates for new aliases or typo fixes. Uses both edit distance **and** token-set matching to compute `closestKnown`, so an inverted folder spelling still finds its canonical actress. |
| `find_orphan_actresses` | Actresses with zero titles, or with titles only on unmounted volumes. |
| `find_single_title_actresses` | Actresses with exactly one title — cheap heuristic for "probably a misspelling of someone else" |
| `list_aliases` | All aliases in the DB, paged, with their actress id. Useful for bulk review. |

### 2.3 Title-anomaly — "what looks wrong with titles?"

| Tool | Returns |
|---|---|
| `find_titles_without_actresses` | Titles with no actress credits |
| `find_cross_volume_duplicates` | Titles present in more than one location, with per-location metadata (path, mtime, file count, total size) so the agent can recommend which to keep |
| `find_code_mismatches` | Titles where the folder name doesn't round-trip through the code parser, or where the parsed code disagrees with the stored code |
| `find_unparseable_folders` | Top-level folders under a volume's title root that don't parse as a title code |
| `find_suspect_credits` | Titles (typically compilations) where one credit looks out-of-place compared to the others. Returns titles with ≥N actress credits where at least one credit is suspect — either unresolved to any actress or resolved to a low-title-count actress whose name is close (edit-distance or token-set) to an actress that **co-occurs with the other credits on this title in other titles**. The co-occurrence signal is what makes compilation typos stand out: if three of four credits regularly appear together and the fourth is a 1-title dangling spelling close to a fifth actress who's in the same co-occurrence cluster, that's a strong typo signal. Each row returns the title code, the suspect credit, the proposed canonical actress, and the evidence (shared-title count between the proposed canonical and the other credits). |

### 2.4 Filesystem scan — "what's actually on disk?"

These go through `VolumeFileSystem` against a mounted volume and are the agent's eyes when the DB isn't enough.

| Tool | Returns |
|---|---|
| `list_directory` | Non-recursive listing of a volume-relative path: entries with name, isDir, size, mtime |
| `walk_directory` | Recursive listing, capped at N entries, with a truncation flag if the cap was hit |
| `stat_path` | exists / isDir / size / mtime for a single path |
| `read_text_file` | First N KB of a small text file (nfo, txt, json). Refuses binary extensions and anything over a size cap. |
| `sample_title_folder` | Convenience: given a title code, returns the listing of its folder on each volume where it lives, plus parsed video metadata if present |

Filesystem tools take a `volumeId` and a volume-relative path. They never see absolute host paths. All paths are returned volume-relative.

### 2.5 SQL — "let me ask a question the canned tools don't cover"

The canned anomaly tools cover the common cases. The long tail — cross-tab counts, ad-hoc filters, joins the agent dreams up — is better served by letting the agent write SQL directly against the SQLite DB.

| Tool | Returns |
|---|---|
| `sql_query` | Executes a single read-only SQL statement and returns rows as JSON |
| `sql_tables` | Lists all tables in the DB with row counts |
| `sql_schema` | Returns the CREATE TABLE statement (or column list) for a named table |

**Safety constraints on `sql_query`** — enforced before the statement reaches the driver:

- Opened on a **read-only** SQLite connection (`mode=ro`). Writes fail at the driver layer even if the statement parses.
- Only a single statement per call. No `;`-chained statements.
- Leading keyword must be `SELECT`, `WITH`, `EXPLAIN`, or `PRAGMA` (and `PRAGMA` is further restricted to an allowlist: `table_info`, `table_list`, `index_list`, `foreign_key_list`).
- Hard caps: `LIMIT` auto-appended if missing (default 500, max 5000), query timeout 10 seconds.
- Result size cap: JSON response truncated at ~1 MB with a `truncated: true` flag.

The read-only connection is the primary safety net — the other checks are ergonomic (stop the agent from accidentally pulling 2M rows) rather than security-critical. Writes cannot happen through this tool by construction.

This tool intentionally exposes raw table and column names, which is why `sql_schema` and `sql_tables` exist — the agent should introspect first, then write the query, rather than guessing.

### 2.6 Context — "what's the state of the world?"

| Tool | Returns |
|---|---|
| `list_volumes` | Configured volumes + mount status + title root path |
| `get_stats` | Counts of titles / actresses / videos / aliases / favorites, per volume and overall |
| `describe_schema` | High-level summary of entities and their fields, written for agent orientation. Complements `sql_schema` which returns raw DDL. |

### Out of scope (Phase 1)

- Any mutation — no mount/unmount, no sync trigger, no DB writes, no file moves. Those are later phases.
- Media streaming — covers / thumbnails / video are still served by the existing web server on localhost. Tools that reference media return a `http://localhost:<port>/...` URL the agent can fetch if needed, rather than piping bytes through MCP.
- Remote access — no TCP binding by default, no auth.

---

## 3. Architecture

### 3.1 Embedding model

**In-process.** The MCP server is started from `Application.java` alongside the web server and shell, shares the same repositories, and lives exactly as long as the app does. When the app shuts down, the MCP server shuts down with it. No standalone launcher, no coordination with a running shell — there is only one process.

The MCP server holds **two** JDBC handles to the SQLite DB: the shared app `Jdbi` (used by all tools that go through repositories) and a **second read-only handle** opened with `mode=ro` in the JDBC URL, dedicated to the `sql_query` tool. SQLite WAL permits concurrent readers alongside the app's writer, and the `mode=ro` flag guarantees raw SQL cannot mutate even if the statement filter is bypassed.

Consequence: the MCP client cannot spawn the server on demand the way Claude Desktop spawns stdio subprocesses. The client connects to an already-running instance. That drives the transport choice.

### 3.2 Transport

**Unix domain socket** under `dataDir/mcp.sock`. The app binds it on startup and unlinks it on shutdown. MCP clients that support socket transport connect directly; clients that only speak stdio (Claude Desktop today) use a tiny `socat`-style bridge script as their `command`:

```json
{
  "mcpServers": {
    "organizer3": {
      "command": "socat",
      "args": ["-", "UNIX-CONNECT:/Users/pyoung/.organizer3/mcp.sock"]
    }
  }
}
```

The bridge is one line of config; the app doesn't ship it. If the app isn't running, the client gets a connection error — that's the intended behavior.

A TCP fallback on `127.0.0.1:<port>` is available as a second transport mode for clients that can't do either socket or bridge. Off by default.

### 3.3 Code layout

```
com.organizer3.mcp
  McpServer.java         entry point, stdio loop
  ToolRegistry.java      registers tools, dispatches calls
  tools/
    LookupActressTool.java
    SearchTitlesTool.java
    ...
  Schemas.java           JSON schema fragments per tool
```

Each tool is a small class that takes its dependencies (repositories, services) via constructor and exposes `name()`, `schema()`, `call(JsonNode args)`. Mirrors the `Command` pattern already used in `com.organizer3.command`.

### 3.4 Library choice

Use the **official Java MCP SDK** (`io.modelcontextprotocol:mcp`) if versions are stable enough; otherwise hand-roll the JSON-RPC loop — the protocol is small and the existing Jackson dependency covers serialization. Decide at implementation time.

---

## 4. Tool shape

Tools return compact JSON, not prose. Shape mirrors repository records where possible. Pagination is explicit (`limit` + `offset` + `total`). Every anomaly tool returns enough context per row that the agent doesn't need a follow-up call for every candidate.

### Example: `find_similar_actresses`

Input:
```json
{ "maxDistance": 2, "limit": 50 }
```

Output:
```json
{
  "total": 37,
  "pairs": [
    {
      "distance": 1,
      "a": { "id": 142, "name": "Yua Mikami", "titleCount": 87, "favorite": true },
      "b": { "id": 903, "name": "Yua Mikamii", "titleCount": 1, "favorite": false },
      "sharedAliases": [],
      "hint": "b has 1 title, likely typo of a"
    }
  ]
}
```

### Example: `sample_title_folder`

Input:
```json
{ "code": "MIDE-123" }
```

Output:
```json
{
  "code": "MIDE-123",
  "dbRecord": { "name": "...", "actresses": ["Yua Mikami"] },
  "locations": [
    {
      "volumeId": "a",
      "path": "stars/popular/MIDE-123",
      "mtime": "2025-08-14",
      "entries": [
        { "name": "MIDE-123.mp4", "size": 1843200000, "isDir": false },
        { "name": "cover.jpg",    "size":     412883, "isDir": false }
      ]
    }
  ]
}
```

### Example: `find_unaliased_variants`

Input:
```json
{ "limit": 20 }
```

Output:
```json
{
  "total": 58,
  "results": [
    {
      "candidateName": "Yua Mikamii",
      "occurrences": 1,
      "titleCodes": ["MIDE-456"],
      "closestKnown": { "id": 142, "name": "Yua Mikami", "distance": 1 }
    }
  ]
}
```

### Example: `sql_query`

Input:
```json
{
  "sql": "SELECT a.primary_name, COUNT(*) AS n FROM actresses a JOIN title_actresses ta ON ta.actress_id = a.id GROUP BY a.id HAVING n = 1 ORDER BY a.primary_name LIMIT 10"
}
```

Output:
```json
{
  "columns": ["primary_name", "n"],
  "rows": [
    ["Yua Mikamii", 1],
    ["Yua Mikame",  1]
  ],
  "rowCount": 2,
  "truncated": false,
  "elapsedMs": 12
}
```

---

## 5. Config

A new top-level `mcp:` block in `organizer-config.yaml`:

```yaml
mcp:
  enabled: true
  transport: socket       # socket | tcp
  socketPath: mcp.sock    # relative to dataDir; used when transport=socket
  tcpPort: 0              # used when transport=tcp; 0 = disabled
  allowMutations: false   # master switch for Phase 2 tools
```

The MCP subsystem is skipped on app start if `enabled: false`. Phase 2 tools are hidden from the tool list when `allowMutations: false`.

---

## 6. Client setup

The app must be running for the MCP server to be reachable. See §3.2 for the `socat` bridge snippet that lets stdio-only clients talk to the socket. Clients with native Unix-socket or TCP transport connect directly.

No credentials, no URL. The server runs inside the already-configured app process.

---

## 7. Worked example: agent session

A concrete trace of what a Phase 1 diagnostic session looks like. The user's question at the top drives a short chain of tool calls; the agent reasons between them and comes back with a recommendation.

> **User:** Something feels off about my Yua Mikami titles — are there near-duplicate actress entries I should clean up?

**Step 1 — ground the query.** Agent looks up the canonical record first so later comparisons have an anchor.

```
call: lookup_actress { "name": "Yua Mikami" }
→ { "id": 142, "primaryName": "Yua Mikami",
    "aliases": ["三上悠亜", "Mikami Yua"], "titleCount": 87,
    "perVolume": { "a": 54, "b": 33 } }
```

**Step 2 — scan for similar names.** Edit-distance 2 is a reasonable net for typos.

```
call: find_similar_actresses { "maxDistance": 2, "limit": 200 }
→ { "total": 37, "pairs": [
    { "distance": 1,
      "a": { "id": 142, "name": "Yua Mikami",  "titleCount": 87 },
      "b": { "id": 903, "name": "Yua Mikamii", "titleCount":  1 } },
    { "distance": 2,
      "a": { "id": 142, "name": "Yua Mikami",  "titleCount": 87 },
      "b": { "id": 911, "name": "Yua Mikame",  "titleCount":  2 } },
    ... ] }
```

Two candidates look like typos of #142. The agent focuses on those.

**Step 3 — pull the evidence.** For each suspect, get the titles so the agent can judge whether they really are the same person.

```
call: list_titles_for_actress { "id": 903 }
→ { "titles": [ { "code": "MIDE-456", "name": "...",
                  "volumeId": "a", "path": "stars/popular/MIDE-456" } ] }

call: list_titles_for_actress { "id": 911 }
→ { "titles": [ { "code": "SSNI-222", ... }, { "code": "SSNI-289", ... } ] }
```

**Step 4 — check the folder.** The DB says the actress is "Yua Mikamii" but maybe the on-disk metadata already says "Yua Mikami" and the bad name crept in from sync parsing. Sample the folder on disk.

```
call: sample_title_folder { "code": "MIDE-456" }
→ { "locations": [ { "volumeId": "a", "path": "stars/popular/MIDE-456",
    "entries": [ { "name": "MIDE-456.nfo", ... },
                 { "name": "MIDE-456.mp4", ... } ] } ] }

call: read_text_file { "volumeId": "a",
                       "path": "stars/popular/MIDE-456/MIDE-456.nfo",
                       "maxBytes": 4096 }
→ "...&lt;actor&gt;&lt;name&gt;Yua Mikami&lt;/name&gt;&lt;/actor&gt;..."
```

The nfo agrees with the canonical spelling — the DB row for #903 came from a folder-name typo, not a real variant.

**Step 5 — answer the user.** Agent now has enough to report:

> You have two near-duplicate actress entries for **Yua Mikami** (#142):
> - **#903 "Yua Mikamii"** — 1 title (MIDE-456). NFO credits "Yua Mikami" — this is a typo row.
> - **#911 "Yua Mikame"** — 2 titles (SSNI-222, SSNI-289). Worth a closer look before merging — the names are one step further apart.
>
> In Phase 2 this ends in `merge_actresses { into: 142, from: 903 }`. For #911 I'd sample the folders first to confirm.

Total tool calls: 5. The agent never needed to open the web UI or run a shell command, and every call returned structured JSON the next step could feed on.

---

## 8. Phasing

1. **Phase 1 — diagnostic read-only.** All tools in §2. In-process server on Unix socket. Focus is answering investigative questions about the collection; no mutations of any kind.
2. **Phase 2 — scoped mutations.** Once the diagnostic loop is proven, add write tools that directly address what Phase 1 surfaced: `merge_actresses` (fold one into another, rewriting credits), `add_alias`, `rename_alias`, `set_favorite`, `tag_title`, plus `mount`/`unmount`/`sync`. Every mutating tool defaults to `dryRun: true` and returns the exact plan of DB changes before execution.
3. **Phase 3 — file operations.** Only after Trash is implemented (PROPOSAL_TRASH.md) so any destructive op has an undo path. Per-tool allowlist in config.

---

## 9. Non-goals

- Multi-user access, auth, TLS, remote exposure
- Replacing the shell or web UI — MCP is a third surface, not a replacement
- Streaming media through MCP — clients fetch media from the existing web server on `localhost`
- Exposing every shell command — only commands whose inputs and outputs are well-structured enough to be useful to an agent
