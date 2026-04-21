package com.organizer3.media;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegLogCallback;

/**
 * Routes FFmpeg's native log output to SLF4J so messages like
 * {@code [http @ ...] Stream ends prematurely ...} and
 * {@code [mp3float @ ...] Header missing} no longer pollute stderr. They
 * instead land in {@code logs/organizer3.log} alongside the app's own logs
 * and are visible from the in-app Logs viewer.
 *
 * <p>Install once on startup via {@link #install()}. Idempotent — subsequent
 * calls are no-ops. FFmpeg's native log level is clamped to WARNING so
 * info/verbose/debug chatter is dropped at the source.
 */
@Slf4j
public final class FFmpegSlf4jBridge {

    private static volatile boolean installed = false;
    private FFmpegSlf4jBridge() {}

    public static synchronized void install() {
        if (installed) return;
        avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        avutil.setLogCallback(new Slf4jLogCallback());
        installed = true;
    }

    /** FFmpeg calls into this for every native log line; we forward to SLF4J. */
    private static final class Slf4jLogCallback extends FFmpegLogCallback {
        @Override
        public void call(int level, BytePointer msg) {
            if (msg == null) return;
            String line = msg.getString().trim();
            if (line.isEmpty()) return;
            if (level <= avutil.AV_LOG_ERROR)        log.error("[ffmpeg] {}", line);
            else if (level <= avutil.AV_LOG_WARNING) log.warn("[ffmpeg] {}",  line);
            else if (level <= avutil.AV_LOG_INFO)    log.info("[ffmpeg] {}",  line);
            else                                     log.debug("[ffmpeg] {}", line);
        }
    }
}
