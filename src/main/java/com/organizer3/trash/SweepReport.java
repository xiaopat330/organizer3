package com.organizer3.trash;

/** Result of a single {@link TrashService#sweepExpired} run for one volume. */
public record SweepReport(String volumeId, int deleted, int skipped, int errors) {}
