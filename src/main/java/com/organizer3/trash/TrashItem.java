package com.organizer3.trash;

import java.nio.file.Path;

/** A trashed item as returned by {@link TrashService#list}. */
public record TrashItem(Path sidecarPath, TrashSidecar sidecar) {}
