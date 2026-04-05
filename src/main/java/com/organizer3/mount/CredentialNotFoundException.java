package com.organizer3.mount;

public class CredentialNotFoundException extends RuntimeException {
    public CredentialNotFoundException(String credentialsKey, String username) {
        super("No Keychain entry found for service='" + credentialsKey + "', account='" + username + "'");
    }
}
