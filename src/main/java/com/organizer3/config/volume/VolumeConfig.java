package com.organizer3.config.volume;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;

/**
 * Configuration for a single NAS volume, loaded from {@code organizer-config.yaml}.
 *
 * <p>{@code smbPath} uses forward-slash UNC notation (e.g., {@code //pandora/jav_A}) as
 * required by {@code mount_smbfs}. {@code mountPoint} is the local path where the OS will
 * mount the share (e.g., {@code /Volumes/jav_A}).
 *
 * <p>{@code credentialsKey} is the service name under which this server's password is stored
 * in the macOS Keychain. {@code username} is the account name for both Keychain lookup and
 * the SMB connection. Only the password is considered sensitive and lives in Keychain.
 */
public record VolumeConfig(
        @JsonProperty("id")             String id,
        @JsonProperty("smbPath")        String smbPath,
        @JsonProperty("mountPoint")     Path mountPoint,
        @JsonProperty("structureType")  String structureType,
        @JsonProperty("credentialsKey") String credentialsKey,
        @JsonProperty("username")       String username
) {}
