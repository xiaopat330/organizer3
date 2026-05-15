package com.organizer3.notes;

/**
 * Discriminator for the {@code notes} table.
 *
 * <p>{@link #wireValue()} is the string used in both the HTTP API paths and the
 * DB {@code entity_type} column. Use {@link #fromWireValue(String)} to parse.
 */
public enum EntityType {

    ACTRESS("actress"),
    TITLE("title");

    private final String wireValue;

    EntityType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Returns the lowercase wire/DB value: {@code "actress"} or {@code "title"}. */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses a wire/DB string into the corresponding enum constant.
     *
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static EntityType fromWireValue(String value) {
        for (EntityType t : values()) {
            if (t.wireValue.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown entity type: " + value);
    }
}
