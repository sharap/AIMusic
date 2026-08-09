package music.ai.recommend.ai

import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopAudioDecoder {

    fun decodeChunk(path: String, durationMs: Long): FloatArray {
        val targetSamples = (48000 * durationMs / 1000).toInt()
        val decodedSamples = FloatArray(targetSamples)
        
        try {
            // FFmpeg command: 
            // -ss 20 (start at 20s)
            // -i [input]
            // -t 10 (read 10s)
            // -f s16le (16-bit PCM little endian)
            // -ac 1 (mono)
            // -ar 48000 (sample rate)
            // pipe:1 (output to stdout)
            
            val pb = ProcessBuilder(
                "ffmpeg",
                "-ss", "20",
                "-i", path,
                "-t", (durationMs / 1000.0).toString(),
                "-f", "s16le",
                "-ac", "1",
                "-ar", "48000",
                "-loglevel", "error",
                "pipe:1"
            )
            
            val process = pb.start()
            val inputStream = BufferedInputStream(process.inputStream)
            
            val byteBuffer = ByteArray(4096)
            val shortBuffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            
            var samplesRead = 0
            var bytesRead: Int
            
            while (inputStream.read(byteBuffer).also { bytesRead = it } != -1 && samplesRead < targetSamples) {
                for (i in 0 until bytesRead step 2) {
                    if (samplesRead >= targetSamples) break
                    
                    if (i + 1 < bytesRead) {
                        val low = byteBuffer[i].toInt() and 0xFF
                        val high = byteBuffer[i + 1].toInt()
                        val sample = ((high shl 8) or low).toShort()
                        decodedSamples[samplesRead++] = sample.toFloat() / 32768f
                    }
                }
            }
            
            process.destroy() // Ensure process is closed
            
            if (samplesRead == 0) return FloatArray(0)
            
            // Return actual read samples if less than target
            return if (samplesRead == targetSamples) decodedSamples else decodedSamples.copyOf(samplesRead)
            
        } catch (e: Exception) {
            println("AudioDecoder: FFmpeg error on $path: ${e.message}")
            return FloatArray(0)
        }
    }
}
