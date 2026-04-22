package com.organizer3.utilities.task;

import java.util.List;

/**
 * Declarative metadata for a {@link Task}: stable id, human title, description, and declared
 * inputs. Tasks expose this to the UI and to MCP so clients can render forms and validate calls
 * without reflecting on the task class.
 */
public record TaskSpec(String id, String title, String description, List<InputSpec> inputs) {

    public TaskSpec {
        inputs = List.copyOf(inputs);
    }

    public record InputSpec(String name, String label, InputType type, boolean required) {
        public enum InputType {
            STRING,
            VOLUME_ID
        }
    }
}
