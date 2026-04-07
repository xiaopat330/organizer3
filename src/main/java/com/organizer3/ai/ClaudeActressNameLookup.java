package com.organizer3.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.organizer3.model.Actress;
import com.organizer3.model.Title;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves actress names to Japanese via the Claude API (Haiku — fast, cheap, and
 * more than capable of factual name recall).
 *
 * <p>When titles are provided, their product codes and studio labels are included in
 * the prompt as additional context to improve accuracy for less well-known actresses.
 */
@Slf4j
@RequiredArgsConstructor
public class ClaudeActressNameLookup implements ActressNameLookup {

    private static final String SYSTEM =
            "You are a JAV actress database. When given a romanized actress name " +
            "(and optionally product codes and studio labels as context), return ONLY " +
            "their Japanese stage name in kanji/kana — nothing else, no punctuation, no explanation. " +
            "If you don't know the name, respond with exactly: unknown";

    private final MessageSender sender;

    /** Production factory — wires the sender against a live Anthropic client. */
    public static ClaudeActressNameLookup create(AnthropicClient client) {
        return new ClaudeActressNameLookup((system, userMessage) -> {
            var params = MessageCreateParams.builder()
                    .model("claude-haiku-4-5")
                    .maxTokens(32L)
                    .system(system)
                    .addUserMessage(userMessage)
                    .build();
            return client.messages().create(params).content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text().strip())
                    .findFirst()
                    .orElse("unknown");
        });
    }

    @Override
    public Optional<String> findJapaneseName(Actress actress, List<Title> titles) {
        try {
            String result = sender.send(SYSTEM, buildMessage(actress.getCanonicalName(), titles)).strip();
            return result.equalsIgnoreCase("unknown") ? Optional.empty() : Optional.of(result);
        } catch (Exception e) {
            log.warn("Claude name lookup failed for '{}': {}", actress.getCanonicalName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Package-private for testing. */
    String buildMessage(String romanizedName, List<Title> titles) {
        var sb = new StringBuilder();
        sb.append("Actress: ").append(romanizedName);

        if (!titles.isEmpty()) {
            List<String> codes = titles.stream()
                    .map(Title::getCode)
                    .filter(Objects::nonNull)
                    .limit(10)
                    .toList();
            if (!codes.isEmpty()) {
                sb.append("\nProduct codes: ").append(String.join(", ", codes));
            }

            List<String> labels = titles.stream()
                    .map(Title::getLabel)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!labels.isEmpty()) {
                sb.append("\nLabels: ").append(String.join(", ", labels));
            }
        }

        return sb.toString();
    }
}
