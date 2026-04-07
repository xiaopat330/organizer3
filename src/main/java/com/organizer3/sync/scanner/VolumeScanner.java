package com.organizer3.sync.scanner;

import com.organizer3.config.volume.VolumeStructureDef;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.shell.io.CommandIO;

import java.io.IOException;
import java.util.List;

/**
 * Walks a volume's filesystem and discovers titles according to the volume's
 * structure type.
 *
 * <p>Each structure type (conventional, queue, exhibition, collections) provides
 * its own scanner implementation that knows the folder layout and how to
 * identify actress folders, title folders, and subfolders.
 *
 * <p>Scanners are stateless and reusable across multiple sync runs. They produce
 * a flat list of {@link DiscoveredTitle} records — the caller is responsible for
 * persisting them and resolving actress records.
 */
public interface VolumeScanner {

    /**
     * Scans the volume and returns all discovered titles.
     *
     * @param structure the volume's structure definition from config
     * @param fs        filesystem handle for the mounted volume
     * @param io        output channel for progress and status messages
     * @return discovered titles in scan order
     */
    List<DiscoveredTitle> scan(VolumeStructureDef structure, VolumeFileSystem fs,
                               CommandIO io) throws IOException;
}
