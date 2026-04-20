package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves cover images for Title Editor "fully-structured" titles in two places, in order:
 *
 * <ol>
 *   <li><b>NAS</b> — written to the title folder base on the unsorted volume. This is
 *       the cover that redistribution carries into the library. Failure propagates as
 *       an error to the caller.</li>
 *   <li><b>Local cache</b> — written to {@code <dataDir>/covers/<LABEL>/<baseCode>.<ext>}
 *       via {@link CoverPath}. Best-effort: failures are logged and swallowed, since the
 *       next {@code sync covers} (or the editor's next page load) can heal the cache.</li>
 * </ol>
 *
 * <p>Writes preserve the source image's extension (jpg/png/webp); {@link CoverPath#find}
 * already probes multiple extensions on read, so mixed extensions are tolerated.
 */
@Slf4j
public class CoverWriteService {

    private final SmbConnectionFactory smbFactory;
    private final CoverPath coverPath;
    private final String unsortedVolumeId;

    public CoverWriteService(SmbConnectionFactory smbFactory, CoverPath coverPath,
                             String unsortedVolumeId) {
        this.smbFactory = smbFactory;
        this.coverPath = coverPath;
        this.unsortedVolumeId = unsortedVolumeId;
    }

    /**
     * Save {@code bytes} as the cover for {@code title}. {@code folderPath} is the SMB
     * share-relative path to the title folder (as stored in {@code title_locations.path}).
     *
     * @throws IOException when the NAS write fails; in that case, the local cache is not touched.
     */
    public void save(Title title, String folderPath, byte[] bytes, String extension) throws IOException {
        writeToNas(title, folderPath, bytes, extension);
        writeLocalCacheBestEffort(title, bytes, extension);
    }

    private void writeToNas(Title title, String folderPath, byte[] bytes, String extension) throws IOException {
        try (SmbShareHandle handle = smbFactory.open(unsortedVolumeId)) {
            VolumeFileSystem fs = handle.fileSystem();
            // Filename is the normalized product number (baseCode), matching how the
            // local cover cache and library-side covers are named.
            String filename = title.getBaseCode() + "." + extension;
            Path target = Path.of(folderPath, filename);
            fs.writeFile(target, bytes);
            log.info("Saved cover to NAS: {} ({} bytes)", target, bytes.length);
        }
    }

    private void writeLocalCacheBestEffort(Title title, byte[] bytes, String extension) {
        try {
            Path dest = coverPath.resolve(title, extension);
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
            log.info("Updated local cover cache: {}", dest);
        } catch (IOException e) {
            log.warn("Local cover cache write failed (NAS write succeeded): {}", e.getMessage());
        }
    }
}
