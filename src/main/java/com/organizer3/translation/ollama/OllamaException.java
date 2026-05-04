package com.organizer3.translation.ollama;

/**
 * Thrown by {@link OllamaAdapter} on HTTP errors, timeout, or parse failures.
 */
public class OllamaException extends RuntimeException {

    public OllamaException(String message) {
        super(message);
    }

    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
