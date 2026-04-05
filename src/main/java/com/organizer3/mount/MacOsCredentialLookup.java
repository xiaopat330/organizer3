package com.organizer3.mount;

import java.io.IOException;

/**
 * Retrieves SMB passwords from the macOS Keychain using the {@code security} CLI tool.
 *
 * <p>Equivalent to:
 * <pre>
 *   security find-internet-password -s &lt;credentialsKey&gt; -a &lt;username&gt; -w
 * </pre>
 *
 * <p>Exit code 44 means no matching entry was found. Any other non-zero exit code is an
 * unexpected error.
 */
public class MacOsCredentialLookup implements CredentialLookup {

    @Override
    public String getPassword(String credentialsKey, String username) {
        ProcessBuilder pb = new ProcessBuilder(
                "security", "find-internet-password",
                "-s", credentialsKey,
                "-a", username,
                "-w"
        );
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new CredentialLookupException("Failed to launch 'security' command", e);
        }

        String stdout;
        String stderr;
        int exitCode;
        try {
            stdout = new String(process.getInputStream().readAllBytes()).strip();
            stderr = new String(process.getErrorStream().readAllBytes()).strip();
            exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CredentialLookupException("Failed to read 'security' command output", e);
        }

        if (exitCode == 44) {
            throw new CredentialNotFoundException(credentialsKey, username);
        }
        if (exitCode != 0) {
            throw new CredentialLookupException(
                    "security command failed (exit " + exitCode + "): " + stderr, null);
        }

        return stdout;
    }
}
