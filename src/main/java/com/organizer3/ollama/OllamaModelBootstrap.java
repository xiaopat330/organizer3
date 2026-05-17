package com.organizer3.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ensures Ollama models required by the AI picker assist feature are present locally.
 *
 * <p>On demand (typically at app start, or when mode flips from off→anything), checks
 * {@code /api/tags} and pulls any missing models via streaming {@code /api/pull}. Surfaces
 * progress and outcomes to SLF4J at INFO so they appear in the in-app logs viewer.
 *
 * <p>Failure-tolerant by design: every error path returns {@code false} and logs WARN —
 * never throws. Callers can treat a {@code false} result as "AI assist unavailable."
 *
 * <p>HTTP and JSON conventions match {@link com.organizer3.translation.ollama.HttpOllamaAdapter}
 * (injected {@link HttpClient}, internal {@link ObjectMapper}).
 *
 * <p><b>Streaming vs buffered:</b> we use buffered body reads (matching the existing
 * {@code HttpOllamaAdapter#ensureModel} approach) and split the response by newlines. This
 * keeps the implementation simple and easy to mock in tests. True line-by-line streaming
 * is an optimisation we can adopt later if needed.
 */
public class OllamaModelBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelBootstrap.class);

    private static final Duration TAGS_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(30);

    private final HttpClient http;
    private final String baseUrl;
    private final ObjectMapper json;

    public OllamaModelBootstrap(HttpClient httpClient, String ollamaBaseUrl) {
        this(httpClient, ollamaBaseUrl, new ObjectMapper());
    }

    /** Package-visible for tests that want to inject a shared ObjectMapper. */
    OllamaModelBootstrap(HttpClient httpClient, String ollamaBaseUrl, ObjectMapper json) {
        this.http = httpClient;
        this.baseUrl = ollamaBaseUrl.replaceAll("/$", "");
        this.json = json;
    }

    /**
     * @return true if every requested model is present locally (already installed or pulled
     *         successfully); false if Ollama is unreachable, the API returned an error,
     *         or any pull failed to complete.
     */
    public boolean ensureModelsReady(List<String> models) {
        if (models == null || models.isEmpty()) {
            return true;
        }

        Set<String> present = listLocalModels();
        if (present == null) {
            // listLocalModels logs the WARN; treat as unavailable
            return false;
        }

        boolean allReady = true;
        for (String requested : models) {
            if (requested == null || requested.isBlank()) continue;
            if (isPresent(present, requested)) {
                log.debug("[ai-assist] model {} already present", requested);
                continue;
            }
            log.info("[ai-assist] pulling model {}...", requested);
            boolean ok = pullModel(requested);
            if (ok) {
                log.info("[ai-assist] pulled {}", requested);
                present.add(requested);
                present.add(stripLatest(requested));
            } else {
                allReady = false;
            }
        }
        return allReady;
    }

    /**
     * @return set of locally-installed model names (both raw and {@code :latest}-stripped
     *         variants included), or null if Ollama is unreachable / returned an error.
     */
    private Set<String> listLocalModels() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(TAGS_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] interrupted while contacting Ollama at {} — AI assist unavailable", baseUrl);
            return null;
        } catch (IOException e) {
            log.warn("[ai-assist] Ollama unreachable at {} — AI assist unavailable ({})", baseUrl, e.getMessage());
            return null;
        }

        if (resp.statusCode() / 100 != 2) {
            log.warn("[ai-assist] /api/tags returned HTTP {} — body: {}",
                    resp.statusCode(), excerpt(resp.body()));
            return null;
        }

        Set<String> result = new HashSet<>();
        try {
            JsonNode root = json.readTree(resp.body());
            JsonNode arr = root.path("models");
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    String name = m.path("name").asText("");
                    if (!name.isEmpty()) {
                        result.add(name);
                        result.add(stripLatest(name));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[ai-assist] failed to parse /api/tags response: {}", e.getMessage());
            return null;
        }
        return result;
    }

    private boolean pullModel(String modelName) {
        ObjectNode body = json.createObjectNode();
        body.put("name", modelName);
        body.put("stream", true);

        String bodyStr;
        try {
            bodyStr = json.writeValueAsString(body);
        } catch (IOException e) {
            log.warn("[ai-assist] failed to serialise pull request for {}: {}", modelName, e.getMessage());
            return false;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/pull"))
                .header("Content-Type", "application/json")
                .timeout(PULL_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ai-assist] interrupted while pulling {} — abort", modelName);
            return false;
        } catch (IOException e) {
            log.warn("[ai-assist] connection error while pulling {}: {}", modelName, e.getMessage());
            return false;
        }

        if (resp.statusCode() / 100 != 2) {
            log.warn("[ai-assist] /api/pull for {} returned HTTP {} — body: {}",
                    modelName, resp.statusCode(), excerpt(resp.body()));
            return false;
        }

        return consumePullStream(modelName, resp.body());
    }

    /**
     * Reads the newline-delimited JSON stream body. Returns true only if a terminal
     * {@code {"status":"success"}} line is observed. Progress is logged at INFO every
     * ~10% advancement to keep the logs viewer informative without flooding.
     */
    private boolean consumePullStream(String modelName, String body) {
        boolean sawSuccess = false;
        int lastLoggedPct = -10; // ensures first >=0 sample logs
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node;
                try {
                    node = json.readTree(line);
                } catch (IOException parseErr) {
                    log.warn("[ai-assist] could not parse pull stream line for {} (skipping): {}",
                            modelName, parseErr.getMessage());
                    continue;
                }
                String status = node.path("status").asText("");
                if ("success".equalsIgnoreCase(status)) {
                    sawSuccess = true;
                    continue;
                }
                long completed = node.path("completed").asLong(0);
                long total = node.path("total").asLong(0);
                if (total > 0 && completed > 0) {
                    int pct = (int) ((completed * 100L) / total);
                    if (pct >= lastLoggedPct + 10 || pct == 100) {
                        log.info("[ai-assist] pulling {} — {}% ({} / {})",
                                modelName, pct, completed, total);
                        lastLoggedPct = pct;
                    }
                } else if (!status.isBlank()) {
                    log.debug("[ai-assist] pull {} status: {}", modelName, status);
                }
            }
        } catch (IOException e) {
            log.warn("[ai-assist] pull stream for {} dropped mid-flight: {}", modelName, e.getMessage());
            return false;
        }
        if (!sawSuccess) {
            log.warn("[ai-assist] pull for {} ended without terminal success status", modelName);
        }
        return sawSuccess;
    }

    private static boolean isPresent(Set<String> present, String requested) {
        if (present.contains(requested)) return true;
        // Try with :latest suffix
        if (!requested.contains(":") && present.contains(requested + ":latest")) return true;
        // Try without :latest suffix
        return present.contains(stripLatest(requested));
    }

    private static String stripLatest(String name) {
        if (name.endsWith(":latest")) {
            return name.substring(0, name.length() - ":latest".length());
        }
        return name;
    }

    private static String excerpt(String body) {
        if (body == null) return "";
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(truncated)";
    }
}
