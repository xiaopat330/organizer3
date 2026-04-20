package com.organizer3.web;

import com.organizer3.covers.CoverPath;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.model.Title;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.smb.SmbConnectionFactory.SmbShareHandle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoverWriteServiceTest {

    private static final String VOL = "unsorted";

    @TempDir Path dataDir;

    private SmbConnectionFactory smbFactory;
    private SmbShareHandle handle;
    private VolumeFileSystem fs;
    private CoverPath coverPath;
    private CoverWriteService service;

    @BeforeEach
    void setUp() throws Exception {
        smbFactory = mock(SmbConnectionFactory.class);
        handle = mock(SmbShareHandle.class);
        fs = mock(VolumeFileSystem.class);
        when(smbFactory.open(VOL)).thenReturn(handle);
        when(handle.fileSystem()).thenReturn(fs);
        coverPath = new CoverPath(dataDir);
        service = new CoverWriteService(smbFactory, coverPath, VOL);
    }

    @Test
    void writesToNasAndLocalCache() throws Exception {
        Title title = Title.builder().code("ONED-123").baseCode("ONED-123").label("ONED").build();
        byte[] bytes = new byte[]{1, 2, 3};

        service.save(title, "/root/Some Title (ONED-123)", bytes, "jpg");

        ArgumentCaptor<Path> pathCap = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(fs).writeFile(pathCap.capture(), bytesCap.capture());
        assertEquals(Path.of("/root/Some Title (ONED-123)/ONED-123.jpg"), pathCap.getValue());
        assertArrayEquals(bytes, bytesCap.getValue());

        Path cached = dataDir.resolve("covers/ONED/ONED-123.jpg");
        assertTrue(Files.exists(cached));
        assertArrayEquals(bytes, Files.readAllBytes(cached));

        verify(handle).close();
    }

    @Test
    void nasWriteFailurePropagatesAndSkipsLocalCache() throws Exception {
        Title title = Title.builder().code("ONED-123").baseCode("ONED-123").label("ONED").build();
        doThrow(new IOException("smb down")).when(fs).writeFile(any(), any());

        assertThrows(IOException.class, () -> service.save(title, "/root/x (ONED-123)", new byte[]{1}, "jpg"));

        Path cached = dataDir.resolve("covers/ONED/ONED-123.jpg");
        assertFalse(Files.exists(cached), "local cache must not be written when NAS write fails");
    }

    @Test
    void localCacheFailureDoesNotPropagate() throws Exception {
        // Make the local covers directory a file so mkdir fails.
        Path coversRoot = dataDir.resolve("covers");
        Files.createFile(coversRoot);  // file instead of directory → any createDirectories attempt fails
        Title title = Title.builder().code("ONED-123").baseCode("ONED-123").label("ONED").build();

        assertDoesNotThrow(() -> service.save(title, "/root/x (ONED-123)", new byte[]{1}, "jpg"));

        verify(fs).writeFile(any(), any());
    }

    @Test
    void extensionIsPreservedInBothWrites() throws Exception {
        Title title = Title.builder().code("ONED-123").baseCode("ONED-123").label("ONED").build();

        service.save(title, "/root/x (ONED-123)", new byte[]{9}, "png");

        ArgumentCaptor<Path> pathCap = ArgumentCaptor.forClass(Path.class);
        verify(fs).writeFile(pathCap.capture(), any());
        assertEquals("ONED-123.png", pathCap.getValue().getFileName().toString());

        assertTrue(Files.exists(dataDir.resolve("covers/ONED/ONED-123.png")));
    }
}
