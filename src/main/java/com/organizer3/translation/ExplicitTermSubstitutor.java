package com.organizer3.translation;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Pre-substitutes known explicit Japanese terms with their English equivalents
 * before the prompt is sent to the LLM.
 *
 * <p>Both gemma4:e4b and qwen2.5:14b struggle with explicit JAV titles: gemma
 * outright refuses to translate them, and qwen silently drops the explicit
 * terms. Both outcomes get caught by SanitizationDetector and recorded as
 * permanent failures. This pre-substitution sidesteps both problems by
 * handing the model an input where the explicit kanji has already been
 * replaced with the English term we want to see in the output.
 *
 * <p><b>Vocabulary lives outside source.</b> The substitution map is loaded
 * from a per-machine properties file at app startup (default location:
 * {@code ~/.organizer3/explicit-substitutions.properties}). The file is
 * UTF-8 (Java 9+ default for {@link Properties#load(Reader)}); keys can be
 * literal kanji or backslash-u escapes. Comment lines start with
 * {@code #}. Order in the file does not matter — entries are sorted by key
 * length descending at load time so longest-match wins.
 *
 * <p>If the file is missing the substitutor is a no-op. The class never
 * carries a default vocabulary in source — the data is intentionally local
 * to the deployment.
 */
@Slf4j
public final class ExplicitTermSubstitutor {

    /**
     * Empty no-op substitutor. Returned by {@link #loadFromFile(Path)} when
     * the properties file is missing or unreadable.
     */
    public static final ExplicitTermSubstitutor EMPTY =
            new ExplicitTermSubstitutor(new LinkedHashMap<>());

    /** Substitutions ordered by key length descending (longest-match wins). */
    private final Map<String, String> substitutions;

    /** Visible for testing — most callers should use {@link #loadFromFile}. */
    public ExplicitTermSubstitutor(Map<String, String> substitutions) {
        // Re-order by key length descending so longest-match wins regardless
        // of the order entries appeared in the source file.
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        substitutions.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, String>>comparingInt(e -> e.getKey().length()).reversed())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        this.substitutions = ordered;
    }

    /**
     * Load substitutions from a properties file. Missing or unreadable file
     * returns {@link #EMPTY} (logs a warning). Empty file returns an empty
     * substitutor (logs an info).
     */
    public static ExplicitTermSubstitutor loadFromFile(Path path) {
        if (!Files.exists(path)) {
            log.warn("ExplicitTermSubstitutor: {} not found — sanitization-prone titles "
                    + "will fail as before. Create the file to enable substitution.", path);
            return EMPTY;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            log.warn("ExplicitTermSubstitutor: failed to read {}: {} — falling back to no-op",
                    path, e.getMessage());
            return EMPTY;
        }
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            map.put(key, props.getProperty(key));
        }
        log.info("ExplicitTermSubstitutor: loaded {} entries from {}", map.size(), path);
        return new ExplicitTermSubstitutor(map);
    }

    /**
     * Replaces every known explicit JP term in {@code input} with its English
     * equivalent. Returns {@code input} unchanged if it is null, empty, or
     * contains none of the patterns.
     */
    public String substitute(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** Number of loaded substitution entries. Useful for startup logging + tests. */
    public int size() {
        return substitutions.size();
    }
}
