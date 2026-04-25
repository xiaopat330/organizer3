package com.organizer3.javdb;

public class JavdbFetchException extends RuntimeException {

    public JavdbFetchException(String message) {
        super(message);
    }

    public JavdbFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
