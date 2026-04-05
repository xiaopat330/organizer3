package com.organizer3.mount;

/**
 * Retrieves the password for an SMB credential from a secure store.
 *
 * <p>The production implementation ({@link MacOsCredentialLookup}) reads from the macOS
 * Keychain using {@code security find-internet-password}. Test implementations can return
 * fixed values without any system calls.
 */
public interface CredentialLookup {

    /**
     * Returns the password stored under {@code credentialsKey} (service name) and
     * {@code username} (account name).
     *
     * @throws CredentialNotFoundException if no matching entry exists in the store
     * @throws CredentialLookupException   if the lookup process fails
     */
    String getPassword(String credentialsKey, String username);
}
