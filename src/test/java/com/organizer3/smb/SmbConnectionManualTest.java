package com.organizer3.smb;

import com.organizer3.config.volume.OrganizerConfig;
import com.organizer3.config.volume.OrganizerConfigLoader;
import com.organizer3.config.volume.ServerConfig;
import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.filesystem.VolumeFileSystem;
import java.nio.file.Path;

/**
 * Manual SMB connectivity smoke test. NOT a JUnit test — run explicitly from the IDE
 * or via: java -cp "build/install/organizer3/lib/*:build/classes/java/test" \
 *              com.organizer3.smb.SmbConnectionManualTest [volume-id]
 *
 * Verifies that credentials in organizer-config.yaml can authenticate and list the
 * root of the given volume. Default volume id is "a".
 */
public class SmbConnectionManualTest {

    public static void main(String[] args) throws Exception {
        String volumeId = args.length > 0 ? args[0] : "a";

        OrganizerConfig config = new OrganizerConfigLoader().load();
        VolumeConfig volume = config.findById(volumeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown volume: " + volumeId));
        ServerConfig server = config.findServerById(volume.server())
                .orElseThrow(() -> new IllegalStateException("No server config for: " + volume.server()));

        System.out.println("Connecting to " + volume.smbPath() + " as " + server.username() + " ...");

        SmbjConnector connector = new SmbjConnector();
        try (VolumeConnection conn = connector.connect(volume, server)) {
            System.out.println("SUCCESS — connected to " + volume.smbPath());
            System.out.println("isConnected: " + conn.isConnected());

            VolumeFileSystem fs = conn.fileSystem();
            System.out.println("Listing root:");
            fs.listDirectory(Path.of("/")).forEach(p -> System.out.println("  " + p));
        }
    }
}
