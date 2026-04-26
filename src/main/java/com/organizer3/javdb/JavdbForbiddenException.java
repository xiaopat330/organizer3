package com.organizer3.javdb;

/** Thrown when javdb returns HTTP 403 Forbidden (typically bot detection / IP block). */
public class JavdbForbiddenException extends JavdbFetchException {
    public JavdbForbiddenException(String url) {
        super("HTTP 403 (forbidden) fetching " + url);
    }
}
