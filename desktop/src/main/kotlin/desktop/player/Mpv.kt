package desktop.player

import java.io.File

/**
 * Locating and driving the bundled mpv.
 *
 * mpv is both the playback engine and, in encode mode (`--ovc=copy
 * --oac=copy`), the desktop app's muxer: the Android download engine relies on
 * `MediaExtractor`/`MediaMuxer` to glue a video-only rendition and a separate
 * audio rendition into one playable file, and those are Android platform
 * classes with no JVM equivalent. mpv already ships with the app and its
 * stream-copy mode does exactly that remux without re-encoding a single frame.
 */
object Mpv {

    /** `app/mpv/mpv.exe` inside the installed app (jpackage layout), with a
     *  couple of fallbacks for running from an IDE / loose jar. */
    fun exe(): File? {
        val runtimeHome = runCatching { File(System.getProperty("java.home")) }.getOrNull()
        val appDir = runtimeHome?.parentFile
        val rels = listOfNotNull(
            appDir?.resolve("app/mpv/mpv.exe"),
            appDir?.resolve("mpv/mpv.exe"),
            appDir?.resolve("mpv.exe"),
            File("mpv/mpv.exe"),
            File("mpv.exe"),
        )
        return rels.firstOrNull { it.isFile }
    }

    /**
     * Stream-copies [video] plus, when given, the separate [audio] rendition
     * into a single MP4 at [out]. Returns null on success, or a short reason on
     * failure (the caller falls back to exporting the local HLS bundle).
     *
     * No re-encode happens: both inputs are MPEG-TS or fragmented MP4 exactly as
     * they came off the CDN, and mpv's `copy` codecs hand those packets to the
     * mp4 muxer unchanged — the same samples the on-device MediaMuxer path
     * would have written.
     */
    fun remux(video: File, audio: File?, out: File): String? {
        val mpv = exe() ?: return "the bundled player (mpv) wasn't found next to the app"
        if (out.exists()) out.delete()
        val args = buildList {
            add(mpv.absolutePath)
            add(video.absolutePath)
            if (audio != null) add("--audio-file=${audio.absolutePath}")
            add("--o=${out.absolutePath}")
            add("--ovc=copy")
            add("--oac=copy")
            // Ignore the user's own mpv.conf: a stray `profile=` there would
            // change the output, and a broken one would fail the mux outright.
            add("--no-config")
            add("--no-terminal")
            add("--no-ytdl")
            add("--msg-level=all=error")
        }
        val p = runCatching { ProcessBuilder(args).redirectErrorStream(true).start() }.getOrElse { t ->
            return "couldn't start mpv: ${t.message ?: t.javaClass.simpleName}"
        }
        val text = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        if (code != 0 || !out.isFile || out.length() == 0L) {
            runCatching { p.destroyForcibly() }
            return "mpv remux failed (exit $code): " +
                text.lineSequence().filter { it.isNotBlank() }.takeLast(4).joinToString(" | ").take(400)
        }
        return null
    }
}
