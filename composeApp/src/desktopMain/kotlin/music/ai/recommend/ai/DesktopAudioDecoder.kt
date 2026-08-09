package music.ai.recommend.ai

import com.sun.jna.Pointer
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.callback.AudioCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DesktopAudioDecoder(private val factory: MediaPlayerFactory) {

    fun decodeChunk(path: String, durationMs: Long): FloatArray {
        val decodedSamples = mutableListOf<Float>()
        val latch = CountDownLatch(1)
        val targetSize = (48000 * durationMs / 1000).toInt()
        
        val audioCallback = object : AudioCallback {
            override fun play(mediaPlayer: MediaPlayer, samples: Pointer, sampleCount: Int, pts: Long) {
                val byteCount = sampleCount * 2 // 16-bit = 2 bytes
                val buffer = samples.getByteBuffer(0, byteCount.toLong()).order(ByteOrder.nativeOrder())
                
                for (i in 0 until sampleCount) {
                    if (decodedSamples.size < targetSize) {
                        val s = buffer.short.toFloat() / 32768f
                        decodedSamples.add(s)
                    }
                }
                
                if (decodedSamples.size >= targetSize) {
                    mediaPlayer.controls().stop()
                    latch.countDown()
                }
            }

            override fun pause(mediaPlayer: MediaPlayer, pts: Long) {}
            override fun resume(mediaPlayer: MediaPlayer, pts: Long) {}
            override fun flush(mediaPlayer: MediaPlayer, pts: Long) {}
            override fun drain(mediaPlayer: MediaPlayer) {}
            override fun setVolume(volume: Float, mute: Boolean) {}
        }

        val mediaPlayer = factory.mediaPlayers().newMediaPlayer()
        mediaPlayer.audio().callback("S16N", 48000, 1, audioCallback)
        
        // Optimization: Disable audio sync to decode as fast as hardware allows
        // ":no-audio-sync" and ":no-video-sync" tell VLC not to wait for clock
        if (mediaPlayer.media().start(path, ":start-time=20.0", ":no-audio-sync", ":no-video-sync")) {
            try {
                var retry = 0
                while (!mediaPlayer.status().isPlaying && retry < 40) {
                    Thread.sleep(100)
                    retry++
                }
                latch.await(20, TimeUnit.SECONDS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        mediaPlayer.release()
        
        return decodedSamples.toFloatArray()
    }
    
    fun release() {
        factory.release()
    }
}
