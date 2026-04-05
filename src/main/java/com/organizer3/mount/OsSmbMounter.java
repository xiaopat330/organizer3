package com.organizer3.mount;

import com.organizer3.config.volume.VolumeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Mounts SMB shares at the OS level using {@code mount_smbfs}.
 *
 * <p>Equivalent to:
 * <pre>
 *   mount_smbfs //username:password@server/share /Volumes/MountPoint
 * </pre>
 *
 * <p>The password is URL-encoded before being embedded in the path to handle special
 * characters safely. Mount points must already exist as directories; this class does not
 * create them.
 */
public class OsSmbMounter implements SmbMounter {

    @Override
    public boolean isMounted(Path mountPoint) {
        if (!Files.isDirectory(mountPoint)) {
            return false;
        }
        try (Stream<Path> contents = Files.list(mountPoint)) {
            return contents.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void mount(VolumeConfig config, String password) {
        String smbUrl = buildSmbUrl(config.smbPath(), config.username(), password);

        ProcessBuilder pb = new ProcessBuilder("mount_smbfs", smbUrl, config.mountPoint().toString());
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new MountException("Failed to launch mount_smbfs: " + e.getMessage());
        }

        String stderr;
        int exitCode;
        try {
            stderr = new String(process.getErrorStream().readAllBytes()).strip();
            exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MountException("Failed to read mount_smbfs output: " + e.getMessage());
        }

        if (exitCode != 0) {
            throw new MountException("mount_smbfs failed (exit " + exitCode + "): " + stderr);
        }
    }

    /**
     * Builds the SMB URL for mount_smbfs by injecting credentials into the path.
     * The password is percent-encoded so special characters don't break the URL.
     * Example: {@code //pandora/jav_A} + user/pass -> {@code //user:pass@pandora/jav_A}
     */
    private static String buildSmbUrl(String smbPath, String username, String password) {
        // smbPath is "//server/share" — insert "username:password@" after "//"
        String encodedPassword = percentEncode(password);
        return smbPath.replaceFirst("^//", "//" + username + ":" + encodedPassword + "@");
    }

    private static String percentEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }
}
