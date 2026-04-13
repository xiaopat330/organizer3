package com.organizer3.backup;

/** Thrown when a backup file's version exceeds what the current parser supports. */
public class UnsupportedBackupVersionException extends RuntimeException {

    public UnsupportedBackupVersionException(int fileVersion, int supportedVersion) {
        super("Backup file version " + fileVersion + " is not supported by this parser " +
              "(max supported: " + supportedVersion + "). Upgrade Organizer3 to restore this backup.");
    }
}
