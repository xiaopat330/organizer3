package com.organizer3.media;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/**
 * Extracts video metadata (duration, resolution, codec, bitrate) via JavaCV.
 *
 * <p>Probes the video through the local HTTP streaming endpoint so it works
 * with any volume without requiring a direct SMB connection. Results are
 * cached in memory by video ID since metadata never changes.
 */
@Slf4j
public class VideoProbe {

    private final int serverPort;
    private final Map<Long, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public VideoProbe(int serverPort) {
        this.serverPort = serverPort;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    /**
     * Returns metadata for the given video. Results are cached in memory.
     * Returns an empty map if probing fails.
     */
    public Map<String, Object> probe(long videoId, String filename) {
        return cache.computeIfAbsent(videoId, id -> probeUncached(id, filename));
    }

    /**
     * Per-probe socket I/O timeout, microseconds. Prevents an unreachable or stale file
     * from hanging a long-running backfill indefinitely. {@code timeout} covers HTTP
     * connects/reads; {@code rw_timeout} is the generic protocol-level read/write cap.
     */
    private static final String PROBE_TIMEOUT_MICROS = String.valueOf(60L * 1_000_000L); // 60s

    private Map<String, Object> probeUncached(long videoId, String filename) {
        String streamUrl = "http://localhost:" + serverPort + "/api/stream/" + videoId;
        try {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(streamUrl);
            grabber.setAudioChannels(0);
            grabber.setOption("timeout",    PROBE_TIMEOUT_MICROS);
            grabber.setOption("rw_timeout", PROBE_TIMEOUT_MICROS);
            grabber.start();
            try {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("filename", filename);

                // Duration
                long durationMicros = grabber.getLengthInTime();
                long durationSeconds = durationMicros / 1_000_000;
                info.put("durationSeconds", durationSeconds);
                info.put("duration", formatDuration(durationSeconds));

                // Resolution
                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                info.put("width", width);
                info.put("height", height);
                info.put("resolution", width + "x" + height);

                // Video codec
                String videoCodecName = grabber.getVideoCodecName();
                info.put("videoCodec", videoCodecName != null ? videoCodecName : "unknown");

                // Audio codec
                String audioCodecName = grabber.getAudioCodecName();
                info.put("audioCodec", audioCodecName != null ? audioCodecName : "unknown");

                // Bitrate
                int bitrate = grabber.getVideoBitrate();
                if (bitrate > 0) {
                    info.put("bitrate", formatBitrate(bitrate));
                    info.put("bitrateKbps", bitrate / 1000);
                }

                // Frame rate
                double fps = grabber.getVideoFrameRate();
                if (fps > 0) {
                    info.put("frameRate", String.format("%.3f fps", fps));
                }

                log.info("Probed video {}: {}x{} {} {}",
                        videoId, width, height,
                        videoCodecName, formatDuration(durationSeconds));
                return info;
            } finally {
                grabber.stop();
                grabber.release();
            }
        } catch (IOException e) {
            log.warn("Failed to probe video {}: {}", videoId, e.getMessage());
            return Map.of();
        }
    }

    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static String formatBitrate(int bitsPerSecond) {
        if (bitsPerSecond >= 1_000_000) {
            return String.format("%.1f Mbps", bitsPerSecond / 1_000_000.0);
        }
        return String.format("%d kbps", bitsPerSecond / 1000);
    }
}
