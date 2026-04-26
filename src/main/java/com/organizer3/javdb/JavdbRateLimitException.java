package com.organizer3.javdb;

/** Thrown when javdb returns HTTP 429 Too Many Requests. */
public class JavdbRateLimitException extends JavdbFetchException {
    public JavdbRateLimitException(String url) {
        super("HTTP 429 (rate limited) fetching " + url);
    }
}
