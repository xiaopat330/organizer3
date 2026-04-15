package com.organizer3.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Small helpers for hand-building JSON Schema fragments for tool inputs.
 *
 * <p>We don't need the full draft 2020-12 vocabulary — just {@code type: object} with
 * named properties, a few scalar types, and {@code required}. Everything else would be
 * ceremony for no benefit.
 */
public final class Schemas {

    private static final ObjectMapper M = new ObjectMapper();

    private Schemas() {}

    public static Builder object() { return new Builder(); }

    /** Convenience for tools that take no arguments. */
    public static ObjectNode empty() {
        ObjectNode s = M.createObjectNode();
        s.put("type", "object");
        s.putObject("properties");
        return s;
    }

    public static final class Builder {
        private final ObjectNode root = M.createObjectNode();
        private final ObjectNode properties;
        private final ArrayNode required;

        private Builder() {
            root.put("type", "object");
            properties = root.putObject("properties");
            required = root.putArray("required");
        }

        public Builder prop(String name, String type, String description) {
            ObjectNode p = properties.putObject(name);
            p.put("type", type);
            if (description != null) p.put("description", description);
            return this;
        }

        public Builder prop(String name, String type, String description, Object defaultValue) {
            prop(name, type, description);
            ObjectNode p = (ObjectNode) properties.get(name);
            p.set("default", M.valueToTree(defaultValue));
            return this;
        }

        public Builder require(String... names) {
            for (String n : names) required.add(n);
            return this;
        }

        public ObjectNode build() {
            if (required.isEmpty()) root.remove("required");
            return root;
        }
    }

    // ── Argument extraction helpers ─────────────────────────────────────────

    public static String requireString(com.fasterxml.jackson.databind.JsonNode args, String name) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return v.asText();
    }

    public static String optString(com.fasterxml.jackson.databind.JsonNode args, String name, String defaultValue) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        return (v == null || v.isNull()) ? defaultValue : v.asText();
    }

    public static int optInt(com.fasterxml.jackson.databind.JsonNode args, String name, int defaultValue) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        return (v == null || v.isNull()) ? defaultValue : v.asInt(defaultValue);
    }

    public static long optLong(com.fasterxml.jackson.databind.JsonNode args, String name, long defaultValue) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        return (v == null || v.isNull()) ? defaultValue : v.asLong(defaultValue);
    }

    public static long requireLong(com.fasterxml.jackson.databind.JsonNode args, String name) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("Missing required argument: " + name);
        }
        return v.asLong();
    }

    public static boolean optBoolean(com.fasterxml.jackson.databind.JsonNode args, String name, boolean defaultValue) {
        com.fasterxml.jackson.databind.JsonNode v = args.get(name);
        return (v == null || v.isNull()) ? defaultValue : v.asBoolean(defaultValue);
    }
}
