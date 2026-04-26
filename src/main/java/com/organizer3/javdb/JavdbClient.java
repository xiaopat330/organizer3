package com.organizer3.javdb;

public interface JavdbClient {

    /** Returns the raw HTML of the search results page for the given product code (e.g. "ABP-123"). */
    String searchByCode(String code);

    /** Returns the raw HTML of a title detail page given its slug (e.g. "AbXy12"). */
    String fetchTitlePage(String slug);

    /** Returns the raw HTML of an actress profile page given her slug (e.g. "OpzD"). */
    String fetchActressPage(String slug);
}
