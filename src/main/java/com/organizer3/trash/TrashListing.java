package com.organizer3.trash;

import java.util.List;

/** Paginated result from {@link TrashService#list}. */
public record TrashListing(List<TrashItem> items, int totalCount, int page, int pageSize) {}
