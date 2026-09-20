package music.ai.recommend.ai

import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Decodes an excerpt of a track to mono 48 kHz float PCM by piping it through ffmpeg.
 *
 * @throws DecodeUnavailable when ffmpeg itself cannot be run, which is a problem with the
 *   installation rather than with the track and must not be mistaken for an undecodable file.
 */
class DesktopAudioDecoder {

    /** ffmpeg is missing from PATH, so no track can be decoded. */
    class DecodeUnavailable(message: String) : Exception(message)

    /**
     * Whether ffmpeg can be run at all, probed once.
     *
     * Without this a missing ffmpeg looked exactly like a library of undecodable files: every
     * track logged one line and was skipped, and the scan "finished" having stored nothing.
     */
    val isAvailable: Boolean by lazy {
        try {
            val probe = ProcessBuilder("ffmpeg", "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!probe.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                probe.destroyForcibly()
                false
            } else {
                probe.exitValue() == 0
            }
        } catch (e: Exception) {
            println("AudioDecoder: ffmpeg is not available: ${e.message}")
            false
        }
    }

    /**
     * @param durationMs how much audio to return.
     * @param trackDurationMs the track's full length, so the excerpt can be taken from the middle;
     *   0 when the scanner could not read it, in which case ffprobe is asked.
     * @return mono 48 kHz samples, or an empty array if the track could not be decoded.
     */
    fun decodeChunk(path: String, durationMs: Long, trackDurationMs: Long = 0L): FloatArray {
        if (!isAvailable) throw DecodeUnavailable("ffmpeg not found in PATH")

        val targetSamples = (SAMPLE_RATE * durationMs / 1000).toInt()
        val decodedSamples = FloatArray(targetSamples)

        var process: Process? = null
        try {
            // The middle of the track, the way the reference scanner picks it. Seeking to a fixed
            // 20 s instead meant every embedding described whatever happened to be playing there —
            // usually still the intro — and anything shorter than 20 s decoded to nothing at all.
            val startSeconds = startOffsetSeconds(path, durationMs, trackDurationMs)

            val builder = ProcessBuilder(
                "ffmpeg",
                "-ss", formatSeconds(startSeconds),
                "-i", path,
                "-t", formatSeconds(durationMs / 1000.0),
                "-f", "s16le",
                "-ac", "1",
                "-ar", SAMPLE_RATE.toString(),
                "-loglevel", "error",
                "pipe:1"
            )
            process = builder.start()

            // Drained on its own thread: ffmpeg blocks once the stderr pipe fills, and with the
            // reader on this thread that is a deadlock against the stdout read below.
            val errors = StringBuilder()
            val stderrPump = Thread {
                runCatching {
                    process.errorStream.bufferedReader().forEachLine { line ->
                        synchronized(errors) { if (errors.length < MAX_ERROR_CHARS) errors.append(line).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }

            var samplesRead = 0
            BufferedInputStream(process.inputStream).use { input ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                // A read can return an odd number of bytes, which splits a sample across two
                // reads. Carrying the leftover byte over keeps the stream aligned; stepping by two
                // from zero every time silently dropped it and shifted every later sample by a
                // byte, turning the rest of the excerpt into noise.
                var leftover = -1
                while (samplesRead < targetSamples) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    var i = 0
                    if (leftover >= 0 && bytesRead > 0) {
                        val high = buffer[0].toInt()
                        decodedSamples[samplesRead++] = (((high shl 8) or leftover).toShort()).toFloat() / 32768f
                        leftover = -1
                        i = 1
                    }
                    while (i + 1 < bytesRead && samplesRead < targetSamples) {
                        val low = buffer[i].toInt() and 0xFF
                        val high = buffer[i + 1].toInt()
                        decodedSamples[samplesRead++] = (((high shl 8) or low).toShort()).toFloat() / 32768f
                        i += 2
                    }
                    if (i == bytesRead - 1) leftover = buffer[i].toInt() and 0xFF
                }
            }

            if (!process.waitFor(DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                println("AudioDecoder: ffmpeg timed out on $path")
            }
            stderrPump.join(500)

            if (samplesRead == 0) {
                val reason = synchronized(errors) { errors.toString().trim() }
                println("AudioDecoder: no audio decoded from $path${if (reason.isEmpty()) "" else ": $reason"}")
                return FloatArray(0)
            }

            return if (samplesRead == targetSamples) decodedSamples else decodedSamples.copyOf(samplesRead)
        } catch (e: DecodeUnavailable) {
            throw e
        } catch (e: Exception) {
            println("AudioDecoder: ffmpeg error on $path: ${e.message}")
            return FloatArray(0)
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    /** Centres a [durationMs] excerpt in the track, clamped to zero for tracks shorter than that. */
    private fun startOffsetSeconds(path: String, durationMs: Long, trackDurationMs: Long): Double {
        val lengthMs = if (trackDurationMs > 0) trackDurationMs else probeDurationMs(path)
        if (lengthMs <= 0) return 0.0
        return max(0L, lengthMs / 2 - durationMs / 2) / 1000.0
    }

    /** Track length from ffprobe, for the files the library scanner could not read a header from. */
    private fun probeDurationMs(path: String): Long = try {
        val builder = ProcessBuilder(
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            path
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        ((text.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    } catch (e: Exception) {
        0L
    }

    /** ffmpeg parses times in the C locale, so the decimal separator must not follow the user's. */
    private fun formatSeconds(seconds: Double): String =
        String.format(java.util.Locale.ROOT, "%.3f", seconds)

    private companion object {
        const val SAMPLE_RATE = 48000
        const val READ_BUFFER_BYTES = 1 shl 16
        const val MAX_ERROR_CHARS = 2000
        const val PROBE_TIMEOUT_SECONDS = 10L

        /** Upper bound on one track's decode, so a stuck ffmpeg cannot hang the scan. */
        const val DECODE_TIMEOUT_SECONDS = 30L
    }
}
