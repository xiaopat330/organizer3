package com.organizer3.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MediaExtensions — video file extension detection.
 */
class MediaExtensionsTest {

    @Test
    void isVideoForMkv() {
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.mkv")));
    }

    @Test
    void isVideoForMp4() {
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.mp4")));
    }

    @Test
    void isVideoForAvi() {
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.avi")));
    }

    @Test
    void isVideoForM2ts() {
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.m2ts")));
    }

    @Test
    void isVideoIsCaseInsensitive() {
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.MKV")));
        assertTrue(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.MP4")));
    }

    @Test
    void isVideoReturnsFalseForJpg() {
        assertFalse(MediaExtensions.isVideo(Path.of("/vol/ABP-001/cover.jpg")));
    }

    @Test
    void isVideoReturnsFalseForNfo() {
        assertFalse(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001.nfo")));
    }

    @Test
    void isVideoReturnsFalseForNoExtension() {
        assertFalse(MediaExtensions.isVideo(Path.of("/vol/ABP-001/ABP-001")));
    }

    @Test
    void isVideoReturnsFalseForDirectoryLikePath() {
        // A path with no dot in the filename
        assertFalse(MediaExtensions.isVideo(Path.of("/vol/ABP-001")));
    }

    @Test
    void videoSetContainsExpectedExtensions() {
        assertTrue(MediaExtensions.VIDEO.contains("mkv"));
        assertTrue(MediaExtensions.VIDEO.contains("mp4"));
        assertTrue(MediaExtensions.VIDEO.contains("avi"));
        assertTrue(MediaExtensions.VIDEO.contains("m2ts"));
        assertTrue(MediaExtensions.VIDEO.contains("mov"));
    }
}
