package xyz.doikki.videoplayer.exo;

import androidx.media3.decoder.ffmpeg.FfmpegLibrary;

/**
 * FFmpeg native 库的可用性探测。
 *
 * jellyfin 的 media3-ffmpeg-decoder 通过 FfmpegLibrary 暴露 native 库状态。
 * 这里缓存探测结果，避免重复查询。
 */
public final class FfmpegSupport {

    private static volatile Boolean available;

    private FfmpegSupport() {
    }

    /** FFmpeg 是否可用（结果缓存）。 */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (FfmpegSupport.class) {
            if (available != null) return available;
            boolean ok;
            try {
                ok = FfmpegLibrary.isAvailable();
            } catch (Throwable th) {
                // 库未打包或架构不匹配
                ok = false;
            }
            available = ok;
            return ok;
        }
    }

    /** 返回 FFmpeg 版本，不可用时返回 "unavailable"。 */
    public static String version() {
        if (!isAvailable()) return "unavailable";
        try {
            String v = FfmpegLibrary.getVersion();
            return v == null ? "unknown" : v;
        } catch (Throwable th) {
            return "unavailable";
        }
    }
}
