package com.organizer3.smb;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.organizer3.filesystem.FileTimestamps;
import com.organizer3.filesystem.VolumeFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * {@link VolumeFileSystem} backed by a smbj {@link DiskShare}.
 *
 * <p>Path convention: callers use Unix-style {@code Path} objects rooted at {@code /}
 * (e.g., {@code Path.of("/stars/popular")}). This class translates them to share-relative
 * Windows paths (e.g., {@code "stars\\popular"}) before passing to smbj.
 *
 * <p>An optional {@code subPath} prefix supports volumes whose root is a subdirectory
 * within the share (e.g., {@code smbPath = //host/SHARE/subdir}).
 */
class SmbFileSystem implements VolumeFileSystem {

    private final DiskShare share;
    private final String subPath; // share-relative prefix, may be empty

    SmbFileSystem(DiskShare share, String subPath) {
        this.share = share;
        this.subPath = subPath;
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    @Override
    public List<Path> listDirectory(Path path) throws IOException {
        String smbPath = toSmbPath(path);
        List<Path> result = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation info : share.list(smbPath)) {
                String name = info.getFileName();
                if (name.equals(".") || name.equals("..")) continue;
                result.add(path.resolve(name));
            }
        } catch (Exception e) {
            throw new IOException("Failed to list directory: " + path, e);
        }
        return result;
    }

    @Override
    public List<Path> walk(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        walkRecursive(root, result);
        return result;
    }

    private void walkRecursive(Path path, List<Path> accumulator) throws IOException {
        accumulator.add(path);
        if (isDirectory(path)) {
            for (Path child : listDirectory(path)) {
                walkRecursive(child, accumulator);
            }
        }
    }

    @Override
    public boolean exists(Path path) {
        String smbPath = toSmbPath(path);
        return share.fileExists(smbPath) || share.folderExists(smbPath);
    }

    @Override
    public boolean isDirectory(Path path) {
        return share.folderExists(toSmbPath(path));
    }

    @Override
    public LocalDate getLastModifiedDate(Path path) throws IOException {
        String smbPath = toSmbPath(path);
        try (File f = share.openFile(
                smbPath,
                EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions.class))) {
            FileBasicInformation info = f.getFileInformation(FileBasicInformation.class);
            com.hierynomus.msdtyp.FileTime lwt = info.getLastWriteTime();
            if (lwt == null) return null;
            java.util.Date date = lwt.toDate();
            if (date == null) return null;
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        } catch (Exception e) {
            throw new IOException("Failed to get last modified date: " + path, e);
        }
    }

    /**
     * Fetches file size via a dedicated {@code open} + {@code FileStandardInformation}.
     * Note: {@link #listDirectory} already pulls {@code FileIdBothDirectoryInformation}
     * which carries the size on its directory entries, so bulk scanners that need size
     * for every child should eventually surface it through listing rather than calling
     * this method per file. See MCP_NEXT_STEPS "SMB listDirectory optimization".
     */
    @Override
    public long size(Path path) throws IOException {
        String smbPath = toSmbPath(path);
        try (File f = share.openFile(
                smbPath,
                EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions.class))) {
            return f.getFileInformation(FileStandardInformation.class).getEndOfFile();
        } catch (Exception e) {
            throw new IOException("Failed to get size: " + path, e);
        }
    }

    @Override
    public InputStream openFile(Path path) throws IOException {
        String smbPath = toSmbPath(path);
        try {
            File f = share.openFile(
                    smbPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.noneOf(SMB2CreateOptions.class));
            return f.getInputStream();
        } catch (Exception e) {
            throw new IOException("Failed to open file: " + path, e);
        }
    }

    // -------------------------------------------------------------------------
    // Write operations
    // -------------------------------------------------------------------------

    @Override
    public void move(Path source, Path destination) throws IOException {
        String srcPath = toSmbPath(source);
        String dstPath = toSmbPath(destination);
        // Minimal rights for FileRenameInformation: DELETE on the source, plus
        // FILE_READ_ATTRIBUTES which some SMB stacks require during the rename flow.
        // GENERIC_ALL was rejected by NAS ACLs that grant modify but not full control.
        try (File f = share.openFile(
                srcPath,
                EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_DELETE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions.class))) {
            f.rename(dstPath, false);
        } catch (Exception e) {
            throw new IOException("Failed to move " + source + " -> " + destination, e);
        }
    }

    @Override
    public void rename(Path path, String newName) throws IOException {
        Path destination = path.getParent().resolve(newName);
        move(path, destination);
    }

    @Override
    public void createDirectories(Path path) throws IOException {
        // Walk from root to leaf, creating any missing directories
        List<Path> segments = new ArrayList<>();
        Path current = path;
        while (current != null && !current.equals(Path.of("/"))) {
            segments.add(0, current);
            current = current.getParent();
        }
        for (Path segment : segments) {
            String smbPath = toSmbPath(segment);
            if (!share.folderExists(smbPath)) {
                try {
                    share.mkdir(smbPath);
                } catch (Exception e) {
                    throw new IOException("Failed to create directory: " + segment, e);
                }
            }
        }
    }

    @Override
    public void writeFile(Path path, byte[] contents) throws IOException {
        String smbPath = toSmbPath(path);
        try (File f = share.openFile(
                smbPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.noneOf(SMB2CreateOptions.class))) {
            f.write(contents, 0);
        } catch (Exception e) {
            throw new IOException("Failed to write file: " + path, e);
        }
    }

    @Override
    public FileTimestamps getTimestamps(Path path) throws IOException {
        String smbPath = toSmbPath(path);
        boolean isDir = share.folderExists(smbPath);
        try (DiskEntry e = share.open(
                smbPath,
                EnumSet.of(AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                isDir ? EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                      : EnumSet.noneOf(SMB2CreateOptions.class))) {
            FileBasicInformation info = e.getFileInformation(FileBasicInformation.class);
            return new FileTimestamps(
                    toInstant(info.getCreationTime()),
                    toInstant(info.getLastWriteTime()),
                    toInstant(info.getLastAccessTime()));
        } catch (Exception ex) {
            throw new IOException("Failed to read timestamps: " + path, ex);
        }
    }

    @Override
    public void setTimestamps(Path path, Instant created, Instant modified) throws IOException {
        String smbPath = toSmbPath(path);
        boolean isDir = share.folderExists(smbPath);
        try (DiskEntry e = share.open(
                smbPath,
                EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                isDir ? EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
                      : EnumSet.noneOf(SMB2CreateOptions.class))) {
            FileBasicInformation current = e.getFileInformation(FileBasicInformation.class);
            FileTime newCre = created  != null ? FileTime.fromInstant(created)  : current.getCreationTime();
            FileTime newMod = modified != null ? FileTime.fromInstant(modified) : current.getLastWriteTime();
            FileBasicInformation updated = new FileBasicInformation(
                    newCre,
                    current.getLastAccessTime(),
                    newMod,
                    current.getChangeTime(),
                    current.getFileAttributes());
            e.setFileInformation(updated);
        } catch (Exception ex) {
            throw new IOException("Failed to set timestamps: " + path, ex);
        }
    }

    private static Instant toInstant(FileTime ft) {
        return ft == null ? null : ft.toInstant();
    }

    // -------------------------------------------------------------------------
    // Path translation
    // -------------------------------------------------------------------------

    /**
     * Converts a Unix-style {@code Path} (rooted at {@code /}) to a share-relative
     * Windows path string expected by smbj (no leading backslash, backslash separators).
     * Prepends {@code subPath} if this filesystem is rooted at a subdirectory within the share.
     *
     * <p>Examples (subPath = ""):
     * <ul>
     *   <li>{@code /} → {@code ""}
     *   <li>{@code /stars/popular} → {@code "stars\\popular"}
     * </ul>
     * Examples (subPath = "classic"):
     * <ul>
     *   <li>{@code /} → {@code "classic"}
     *   <li>{@code /stars/popular} → {@code "classic\\stars\\popular"}
     * </ul>
     */
    String toSmbPath(Path path) {
        String s = path.toString();
        if (s.equals("/")) s = "";
        else if (s.startsWith("/")) s = s.substring(1);
        s = s.replace('/', '\\');
        if (subPath.isEmpty()) return s;
        return s.isEmpty() ? subPath : subPath + '\\' + s;
    }
}
