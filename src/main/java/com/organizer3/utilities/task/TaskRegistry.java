package com.organizer3.utilities.task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable registry of {@link Task}s by id. Populated once at startup in {@code Application.java}
 * and passed to the route layer and MCP adapter.
 */
public final class TaskRegistry {

    private final Map<String, Task> tasks;

    public TaskRegistry(Collection<Task> tasks) {
        Map<String, Task> byId = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (byId.putIfAbsent(t.spec().id(), t) != null) {
                throw new IllegalArgumentException("Duplicate task id: " + t.spec().id());
            }
        }
        this.tasks = Map.copyOf(byId);
    }

    public Optional<Task> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public Collection<TaskSpec> specs() {
        return tasks.values().stream().map(Task::spec).toList();
    }
}
