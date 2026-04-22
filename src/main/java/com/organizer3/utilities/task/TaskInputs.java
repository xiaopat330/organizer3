package com.organizer3.utilities.task;

import java.util.Map;

/**
 * Wraps task invocation arguments. Keys correspond to {@link TaskSpec.InputSpec#name()}.
 * Typed accessors throw {@link IllegalArgumentException} for missing or mistyped values;
 * tasks should fail fast at the top of {@code run()} rather than defensively probing.
 */
public record TaskInputs(Map<String, Object> values) {

    public TaskInputs {
        values = Map.copyOf(values);
    }

    public static TaskInputs of(String k, Object v) {
        return new TaskInputs(Map.of(k, v));
    }

    public String getString(String key) {
        Object raw = values.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("Missing task input: " + key);
        }
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException("Task input '" + key + "' is not a string: " + raw);
        }
        return s;
    }
}
