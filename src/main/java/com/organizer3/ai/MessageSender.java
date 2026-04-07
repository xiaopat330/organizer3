package com.organizer3.ai;

/**
 * Thin wrapper around a Claude API call. Exists to decouple callers from the
 * Anthropic SDK so the lookup can be tested with a simple lambda.
 */
@FunctionalInterface
interface MessageSender {
    /**
     * Sends a message with the given system prompt and user content,
     * and returns the model's text response.
     */
    String send(String system, String userMessage);
}
