package com.organizer3.mount;

import com.organizer3.config.volume.VolumeConfig;

import java.nio.file.Path;

/**
 * Handles OS-level SMB share mounting.
 *
 * <p>The production implementation ({@link OsSmbMounter}) calls {@code mount_smbfs} via
 * {@code ProcessBuilder}. Once mounted, the share is accessible as a standard filesystem
 * path and all further operations go through {@link com.organizer3.filesystem.VolumeFileSystem}.
 */
public interface SmbMounter {

    /**
     * Returns true if the share is already mounted at {@code mountPoint}.
     *
     * <p>Detection is by non-empty directory check: a mounted share will have contents;
     * an unmounted (or missing) mount point will be empty or absent.
     */
    boolean isMounted(Path mountPoint);

    /**
     * Mounts the SMB share described by {@code config} at its configured mount point.
     *
     * @throws MountException if the mount fails (wrong credentials, server unreachable, etc.)
     */
    void mount(VolumeConfig config, String password);
}
