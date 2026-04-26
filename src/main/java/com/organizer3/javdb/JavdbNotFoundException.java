package com.organizer3.javdb;

/** Thrown when javdb returns HTTP 404 for a title or actress page. */
public class JavdbNotFoundException extends JavdbFetchException {
    public JavdbNotFoundException(String url) {
        super("HTTP 404 (not found) fetching " + url);
    }
}
