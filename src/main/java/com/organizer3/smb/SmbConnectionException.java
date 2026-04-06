package com.organizer3.smb;

public class SmbConnectionException extends RuntimeException {

    public SmbConnectionException(String message) {
        super(message);
    }

    public SmbConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
