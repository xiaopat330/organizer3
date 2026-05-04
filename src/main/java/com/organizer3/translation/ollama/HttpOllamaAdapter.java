package com.organizer3.translation.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Production {@link OllamaAdapter} backed by {@link java.net.http.HttpClient}.
 *
 * <p>Targets the Ollama REST API (default: {@code http://localhost:11434}).
 *
 * <p>Important: the {@code think: false} field is always injected at the <em>top level</em> of
 * the JSON payload, not inside {@code options}. This suppresses chain-of-thought output from
 * qwen3-family models that otherwise prepend thinking tokens before the actual translation.
 */
@Slf4j
public class HttpOllamaAdapter implements OllamaAdapter {

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;

    public HttpOllamaAdapter(String baseUrl, ObjectMapper json) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), json);
    }

    /** Package-visible for testing with a mock HTTP client. */
    HttpOllamaAdapter(String baseUrl, HttpClient http, ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
        this.json = json;
    }

    @Override
    public OllamaResponse generate(OllamaRequest req) {
        ObjectNode body = json.createObjectNode();
        body.put("model", req.modelId());
        body.put("prompt", req.prompt());
        if (req.systemMessage() != null) {
            body.put("system", req.systemMessage());
        }
        body.put("stream", false);
        // think: false is ALWAYS at top level — never in options — to suppress qwen3 CoT output
        body.put("think", false);

        if (req.options() != null && !req.options().isEmpty()) {
            ObjectNode opts = json.createObjectNode();
            for (Map.Entry<String, Object> e : req.options().entrySet()) {
                Object v = e.getValue();
                if (v instanceof Boolean b) opts.put(e.getKey(), b);
                else if (v instanceof Integer i) opts.put(e.getKey(), i);
                else if (v instanceof Long l) opts.put(e.getKey(), l);
                else if (v instanceof Double d) opts.put(e.getKey(), d);
                else if (v instanceof Float f) opts.put(e.getKey(), f);
                else opts.put(e.getKey(), String.valueOf(v));
            }
            body.set("options", opts);
        }

        String bodyStr;
        try {
            bodyStr = json.writeValueAsString(body);
        } catch (IOException e) {
            throw new OllamaException("Failed to serialise generate request", e);
        }

        Duration timeout = req.timeout() != null ? req.timeout() : Duration.ofMinutes(2);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        log.debug("ollama generate: model={} prompt_len={}", req.modelId(), req.prompt().length());
        HttpResponse<String> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("HTTP error calling /api/generate: " + e.getMessage(), e);
        }

        if (resp.statusCode() != 200) {
            throw new OllamaException("Ollama /api/generate returned HTTP " + resp.statusCode()
                    + ": " + resp.body());
        }

        try {
            JsonNode node = json.readTree(resp.body());
            String responseText = node.path("response").asText("");
            long totalDuration = node.path("total_duration").asLong(0);
            int promptEvalCount = node.path("prompt_eval_count").asInt(0);
            int evalCount = node.path("eval_count").asInt(0);
            long evalDuration = node.path("eval_duration").asLong(0);
            log.debug("ollama generate: model={} eval_count={} total_ms={}",
                    req.modelId(), evalCount, totalDuration / 1_000_000);
            return new OllamaResponse(responseText, totalDuration, promptEvalCount, evalCount, evalDuration);
        } catch (IOException e) {
            throw new OllamaException("Failed to parse /api/generate response", e);
        }
    }

    @Override
    public void generateStreaming(OllamaRequest req, Consumer<String> onToken) {
        throw new UnsupportedOperationException(
                "Streaming variant is deferred to a future phase. Use generate() instead.");
    }

    @Override
    public List<OllamaModel> listModels() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new OllamaException("Ollama /api/tags returned HTTP " + resp.statusCode());
            }
            JsonNode root = json.readTree(resp.body());
            JsonNode models = root.path("models");
            List<OllamaModel> result = new ArrayList<>();
            if (models.isArray()) {
                for (JsonNode m : models) {
                    result.add(new OllamaModel(m.path("name").asText(""), m.path("size").asLong(0)));
                }
            }
            return result;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Error calling /api/tags: " + e.getMessage(), e);
        }
    }

    @Override
    public void ensureModel(String modelId, Consumer<String> progress) {
        // First check if the model is already present
        List<OllamaModel> installed = listModels();
        boolean present = installed.stream().anyMatch(m -> m.name().equals(modelId));
        if (present) {
            log.debug("ensureModel: {} already installed", modelId);
            if (progress != null) progress.accept("Model " + modelId + " already installed.");
            return;
        }

        log.info("ensureModel: pulling {}", modelId);
        if (progress != null) progress.accept("Pulling " + modelId + "...");

        ObjectNode body = json.createObjectNode();
        body.put("model", modelId);
        body.put("stream", true);

        String bodyStr;
        try {
            bodyStr = json.writeValueAsString(body);
        } catch (IOException e) {
            throw new OllamaException("Failed to serialise pull request", e);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/pull"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(30))
                .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
                .build();

        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new OllamaException("Ollama /api/pull returned HTTP " + resp.statusCode());
            }
            // Streaming JSON lines — each line is a status update
            try (BufferedReader reader = new BufferedReader(new StringReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node = json.readTree(line);
                    String status = node.path("status").asText("");
                    if (progress != null && !status.isBlank()) {
                        progress.accept(status);
                    }
                }
            }
            log.info("ensureModel: {} pull complete", modelId);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Error pulling model " + modelId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("isHealthy: Ollama unreachable — {}", e.getMessage());
            return false;
        }
    }
}
