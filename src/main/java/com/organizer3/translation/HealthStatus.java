package com.organizer3.translation;

/**
 * Result of a translation health check.
 *
 * <p>Populated by {@link HealthGate} and exposed via {@code GET /api/translation/health}.
 *
 * @param ollamaReachable   true if the Ollama daemon responds to health pings
 * @param tier1ModelPresent true if the tier-1 model (e.g. gemma4:e4b) is listed in Ollama
 * @param latencyOk         true if recent p95 latency is within acceptable range (≤ 90s)
 * @param latencyP95Ms      p95 latency in milliseconds from recent cache rows; null if no data
 * @param overall           true if all checks pass (ollamaReachable &amp;&amp; tier1ModelPresent)
 * @param message           human-readable summary for the UI dot tooltip
 */
public record HealthStatus(
        boolean ollamaReachable,
        boolean tier1ModelPresent,
        boolean latencyOk,
        Long latencyP95Ms,
        boolean overall,
        String message
) {
    public static HealthStatus healthy(Long latencyP95Ms) {
        return new HealthStatus(true, true, true, latencyP95Ms, true, "OK");
    }

    public static HealthStatus ollamaDown() {
        return new HealthStatus(false, false, false, null, false, "Ollama daemon unreachable");
    }

    public static HealthStatus modelMissing(String modelId) {
        return new HealthStatus(true, false, false, null, false,
                "Tier-1 model not installed: " + modelId);
    }
}
