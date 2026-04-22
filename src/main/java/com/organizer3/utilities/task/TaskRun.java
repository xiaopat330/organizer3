package com.organizer3.utilities.task;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * A single invocation of a {@link Task}. Tracks status, accumulated {@link TaskEvent}s, and
 * live subscribers for streaming (SSE clients).
 *
 * <p>Thread-safety: event append and subscriber fan-out both use copy-on-write collections so
 * late subscribers can safely snapshot history without blocking the producing thread. The
 * status field is a plain volatile reference; one thread writes (the task runner), many read.
 */
public final class TaskRun {

    public enum Status { RUNNING, OK, FAILED, PARTIAL }

    private final String runId = UUID.randomUUID().toString();
    private final String taskId;
    private final Instant startedAt = Instant.now();
    private volatile Instant endedAt;
    private volatile Status status = Status.RUNNING;
    private volatile String summary = "";

    private final CopyOnWriteArrayList<TaskEvent> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArraySet<Consumer<TaskEvent>> subscribers = new CopyOnWriteArraySet<>();

    /**
     * Lock held for the brief critical section that appends an event or attaches a subscriber
     * with a replay snapshot. Ensures late subscribers never miss an event: the append and
     * subscribe operations cannot interleave in a way that drops events after the snapshot
     * but before the listener is registered.
     */
    private final Object writeLock = new Object();

    TaskRun(String taskId) {
        this.taskId = taskId;
    }

    public String runId() { return runId; }
    public String taskId() { return taskId; }
    public Instant startedAt() { return startedAt; }
    public Instant endedAt() { return endedAt; }
    public Status status() { return status; }
    public String summary() { return summary; }

    public List<TaskEvent> eventSnapshot() {
        return List.copyOf(events);
    }

    /**
     * Register a subscriber. Returns a handle; caller must invoke it to unsubscribe. Prefer
     * {@link #subscribeWithReplay} for clients that also need the event history — that variant
     * snapshots and subscribes atomically, so no events slip through the gap.
     */
    public Runnable subscribe(Consumer<TaskEvent> listener) {
        synchronized (writeLock) {
            subscribers.add(listener);
        }
        return () -> subscribers.remove(listener);
    }

    /**
     * Atomic variant of {@link #subscribe}: returns the current event history and registers the
     * listener under the same write lock, so no event can be appended between the snapshot and
     * the listener becoming active. Use this for SSE clients joining mid-run.
     */
    public SubscribeHandle subscribeWithReplay(Consumer<TaskEvent> listener) {
        synchronized (writeLock) {
            List<TaskEvent> snapshot = List.copyOf(events);
            subscribers.add(listener);
            Runnable unsubscribe = () -> subscribers.remove(listener);
            return new SubscribeHandle(snapshot, status, unsubscribe);
        }
    }

    public record SubscribeHandle(List<TaskEvent> replay, Status statusAtSubscribe, Runnable unsubscribe) {}

    /** Record an event and fan out to subscribers. Called only by the task runner / TaskIO impl. */
    void append(TaskEvent event) {
        synchronized (writeLock) {
            events.add(event);
            for (Consumer<TaskEvent> sub : subscribers) {
                try {
                    sub.accept(event);
                } catch (RuntimeException ignored) {
                    // Subscribers must not break the producer.
                }
            }
        }
    }

    void markEnded(Status finalStatus, String finalSummary) {
        this.status = finalStatus;
        this.summary = finalSummary;
        this.endedAt = Instant.now();
    }
}
