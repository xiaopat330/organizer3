package com.organizer3.avstars.iafd;

/** Thrown when an IAFD HTTP request fails (non-2xx status or I/O error). */
public class IafdFetchException extends RuntimeException {

    public IafdFetchException(String message) {
        super(message);
    }

    public IafdFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
