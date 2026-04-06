package com.organizer3.smb;

/**
 * Receives status messages at each phase of an SMB mount operation.
 * Implementations may display these as a spinner, status line, or log output.
 */
@FunctionalInterface
public interface MountProgressListener {

    /**
     * Called when a new connection phase begins.
     *
     * @param message short description of the current phase (e.g. "Connecting to qnap2")
     */
    void onStep(String message);
}
