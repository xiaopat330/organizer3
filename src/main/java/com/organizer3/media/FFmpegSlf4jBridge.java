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
        // Clamp at WARNING to drop info/verbose/debug chatter at the source. We deliberately
        // do NOT install a custom LogCallback here: FFmpeg calls the callback from arbitrary
        // native threads at very high volume during frame extraction, and any exception
        // (e.g. a BytePointer.getString() decode failure or an SLF4J hiccup) crosses the
        // JNI boundary into C++ and SIGABRT's the entire JVM. Leaving the default callback
        // lets FFmpeg write to stderr only — we lose Logs-viewer integration for FFmpeg
        // lines but the app stays alive. See incident 2026-04-21 (av screenshots Jenna Haze).
        avutil.av_log_set_level(avutil.AV_LOG_WARNING);
        installed = true;
    }

    /**
     * Optional: route FFmpeg logs into SLF4J via a hardened callback. Currently NOT
     * installed by {@link #install()}. Kept here for reference — if re-enabled, the
     * try/catch(Throwable) guard is mandatory so exceptions never cross JNI.
     */
    @SuppressWarnings("unused")
    private static final class Slf4jLogCallback extends FFmpegLogCallback {
        @Override
        public void call(int level, BytePointer msg) {
            try {
                if (msg == null || msg.isNull()) return;
                String line = msg.getString();
                if (line == null) return;
                line = line.trim();
                if (line.isEmpty()) return;
                if (level <= avutil.AV_LOG_ERROR)        log.error("[ffmpeg] {}", line);
                else if (level <= avutil.AV_LOG_WARNING) log.warn("[ffmpeg] {}",  line);
                else if (level <= avutil.AV_LOG_INFO)    log.info("[ffmpeg] {}",  line);
                else                                     log.debug("[ffmpeg] {}", line);
            } catch (Throwable t) {
                // Never let exceptions cross the JNI boundary — C++ cannot unwind them.
                System.err.println("FFmpeg log callback failed: " + t);
            }
        }
    }
}
