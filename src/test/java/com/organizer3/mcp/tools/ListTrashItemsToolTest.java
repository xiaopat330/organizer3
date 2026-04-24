package com.organizer3.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.organizer3.filesystem.VolumeFileSystem;
import com.organizer3.smb.SmbConnectionFactory;
import com.organizer3.trash.TrashItem;
import com.organizer3.trash.TrashListing;
import com.organizer3.trash.TrashService;
import com.organizer3.trash.TrashSidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListTrashItemsToolTest {

    private static final ObjectMapper M = new ObjectMapper();

    private TrashService trashService;
    private SmbConnectionFactory smbFactory;
    private ListTrashItemsTool tool;

    @BeforeEach
    void setUp() {
        trashService = mock(TrashService.class);
        smbFactory   = mock(SmbConnectionFactory.class);
        tool         = new ListTrashItemsTool(trashService, smbFactory);
    }

    @Test
    void returnsEmptyListingWhenNoItems() throws Exception {
        stubFactory("vol-a", emptyListing(0, 0, 50));

        var r = (ListTrashItemsTool.Result) tool.call(args("vol-a", null, null));
        assertEquals(0, r.totalCount());
        assertEquals(0, r.page());
        assertEquals(50, r.pageSize());
        assertTrue(r.items().isEmpty());
    }

    @Test
    void returnsItemRowsWithSidecarFields() throws Exception {
        TrashSidecar sc = new TrashSidecar(
                "/jav/SSIS-001", "2026-04-20T10:00:00Z", "vol-a", "duplicate",
                "2026-05-01T00:00:00Z", null, null);
        TrashItem item = new TrashItem(Path.of("/_trash/jav/SSIS-001.sidecar.json"), sc);
        stubFactory("vol-a", new TrashListing(List.of(item), 1, 0, 50));

        var r = (ListTrashItemsTool.Result) tool.call(args("vol-a", null, null));
        assertEquals(1, r.totalCount());
        assertEquals(1, r.items().size());

        var row = r.items().get(0);
        assertEquals("/_trash/jav/SSIS-001.sidecar.json", row.sidecarPath());
        assertEquals("/jav/SSIS-001",        row.originalPath());
        assertEquals("2026-04-20T10:00:00Z", row.trashedAt());
        assertEquals("vol-a",                row.volumeId());
        assertEquals("duplicate",            row.reason());
        assertEquals("2026-05-01T00:00:00Z", row.scheduledDeletionAt());
        assertNull(row.lastDeletionAttempt());
        assertNull(row.lastDeletionError());
    }

    @Test
    void passesPageAndPageSizeToService() throws Exception {
        stubFactory("vol-a", emptyListing(0, 2, 25));

        tool.call(args("vol-a", 2, 25));

        verify(trashService).list(any(), eq("vol-a"), eq(Path.of("/_trash")), eq(2), eq(25));
    }

    @Test
    void defaultsPageAndPageSize() throws Exception {
        stubFactory("vol-a", emptyListing(0, 0, 50));

        tool.call(args("vol-a", null, null));

        verify(trashService).list(any(), eq("vol-a"), eq(Path.of("/_trash")), eq(0), eq(50));
    }

    @Test
    void wrapsIllegalArgumentAsVolumeNotFound() throws Exception {
        doThrow(new IllegalArgumentException("unknown volume"))
                .when(smbFactory).withRetry(eq("bad-vol"), any());

        assertThrows(IllegalArgumentException.class,
                () -> tool.call(args("bad-vol", null, null)));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stubFactory(String volumeId, TrashListing listing) throws Exception {
        doAnswer(inv -> {
            SmbConnectionFactory.SmbOperation<Object> op = inv.getArgument(1);
            SmbConnectionFactory.SmbShareHandle handle = mock(SmbConnectionFactory.SmbShareHandle.class);
            VolumeFileSystem fs = mock(VolumeFileSystem.class);
            when(handle.fileSystem()).thenReturn(fs);
            when(trashService.list(eq(fs), eq(volumeId), any(), anyInt(), anyInt()))
                    .thenReturn(listing);
            return op.execute(handle);
        }).when(smbFactory).withRetry(eq(volumeId), any());
    }

    private static TrashListing emptyListing(int total, int page, int pageSize) {
        return new TrashListing(List.of(), total, page, pageSize);
    }

    private static ObjectNode args(String volumeId, Integer page, Integer pageSize) {
        ObjectNode n = M.createObjectNode();
        n.put("volumeId", volumeId);
        if (page     != null) n.put("page",     page);
        if (pageSize != null) n.put("pageSize", pageSize);
        return n;
    }
}
