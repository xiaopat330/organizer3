package com.organizer3.shell;

import com.organizer3.config.volume.VolumeConfig;
import com.organizer3.smb.VolumeConnection;
import com.organizer3.sync.VolumeIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SessionContext — mutable session state container.
 */
class SessionContextTest {

    @Test
    void defaultStateIsDryRunTrueAndRunningTrue() {
        SessionContext ctx = new SessionContext();
        assertTrue(ctx.isDryRun());
        assertTrue(ctx.isRunning());
    }

    @Test
    void defaultStateHasNoMountedVolumeOrConnection() {
        SessionContext ctx = new SessionContext();
        assertNull(ctx.getMountedVolume());
        assertNull(ctx.getMountedVolumeId());
        assertNull(ctx.getActiveConnection());
        assertNull(ctx.getIndex());
    }

    @Test
    void getMountedVolumeIdReturnsNullWhenNoVolumeSet() {
        SessionContext ctx = new SessionContext();
        assertNull(ctx.getMountedVolumeId());
    }

    @Test
    void getMountedVolumeIdReturnsIdWhenVolumeSet() {
        SessionContext ctx = new SessionContext();
        VolumeConfig vol = new VolumeConfig("a", "//nas/share", "conventional", "nas", null);
        ctx.setMountedVolume(vol);
        assertEquals("a", ctx.getMountedVolumeId());
    }

    @Test
    void isConnectedReturnsFalseWhenNoConnection() {
        SessionContext ctx = new SessionContext();
        assertFalse(ctx.isConnected());
    }

    @Test
    void isConnectedReturnsFalseWhenConnectionNotConnected() {
        SessionContext ctx = new SessionContext();
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(false);
        ctx.setActiveConnection(conn);
        assertFalse(ctx.isConnected());
    }

    @Test
    void isConnectedReturnsTrueWhenConnectionConnected() {
        SessionContext ctx = new SessionContext();
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        ctx.setActiveConnection(conn);
        assertTrue(ctx.isConnected());
    }

    @Test
    void setDryRunUpdatesState() {
        SessionContext ctx = new SessionContext();
        ctx.setDryRun(false);
        assertFalse(ctx.isDryRun());
    }

    @Test
    void setIndexStoresIndex() {
        SessionContext ctx = new SessionContext();
        VolumeIndex index = VolumeIndex.empty("a");
        ctx.setIndex(index);
        assertSame(index, ctx.getIndex());
    }

    @Test
    void shutdownClosesConnectionAndSetsRunningFalse() {
        SessionContext ctx = new SessionContext();
        VolumeConnection conn = mock(VolumeConnection.class);
        ctx.setActiveConnection(conn);

        ctx.shutdown();

        verify(conn).close();
        assertNull(ctx.getActiveConnection());
        assertFalse(ctx.isRunning());
    }

    @Test
    void shutdownWithNoConnectionDoesNotThrow() {
        SessionContext ctx = new SessionContext();
        assertDoesNotThrow(ctx::shutdown);
        assertFalse(ctx.isRunning());
    }

    @Test
    void setMountInstallsTripleAtomically() {
        SessionContext ctx = new SessionContext();
        VolumeConfig vol = new VolumeConfig("a", "//nas/a", "conventional", "nas", null);
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);
        VolumeIndex index = VolumeIndex.empty("a");

        ctx.setMount(vol, conn, index);

        assertSame(vol, ctx.getMountedVolume());
        assertSame(conn, ctx.getActiveConnection());
        assertSame(index, ctx.getIndex());
        assertEquals("a", ctx.getMountedVolumeId());
    }

    @Test
    void clearMountNullsAllThreeFields() {
        SessionContext ctx = new SessionContext();
        VolumeConfig vol = new VolumeConfig("a", "//nas/a", "conventional", "nas", null);
        VolumeConnection conn = mock(VolumeConnection.class);
        VolumeIndex index = VolumeIndex.empty("a");
        ctx.setMount(vol, conn, index);

        ctx.clearMount();

        assertNull(ctx.getMountedVolume());
        assertNull(ctx.getActiveConnection());
        assertNull(ctx.getIndex());
    }

    @Test
    void writesFromOneThreadVisibleToAnother() throws Exception {
        SessionContext ctx = new SessionContext();
        VolumeConfig vol = new VolumeConfig("r", "//nas/r", "conventional", "nas", null);
        VolumeConnection conn = mock(VolumeConnection.class);
        when(conn.isConnected()).thenReturn(true);

        Thread writer = new Thread(() -> ctx.setMount(vol, conn, VolumeIndex.empty("r")));
        writer.start();
        writer.join();

        String observedId = ctx.getMountedVolumeId();
        VolumeConnection observedConn = ctx.getActiveConnection();
        assertEquals("r", observedId);
        assertSame(conn, observedConn);
    }

    @Test
    void mountFieldsAreSynchronizedForMemoryVisibility() throws Exception {
        java.lang.reflect.Method getId = SessionContext.class.getMethod("getMountedVolumeId");
        java.lang.reflect.Method getConn = SessionContext.class.getMethod("getActiveConnection");
        java.lang.reflect.Method setMount = SessionContext.class.getMethod(
                "setMount", VolumeConfig.class, VolumeConnection.class, VolumeIndex.class);
        assertTrue(java.lang.reflect.Modifier.isSynchronized(getId.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isSynchronized(getConn.getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isSynchronized(setMount.getModifiers()));
    }
}
