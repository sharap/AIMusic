package music.ai.recommend.platform

import music.ai.recommend.model.Song
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

actual class MusicPlayer actual constructor() {
    private val factory = try {
        MediaPlayerFactory()
    } catch (e: Exception) {
        null
    }
    
    private val mediaPlayer = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()
    private val equalizer = factory?.equalizer()?.newEqualizer()
    
    actual var onFinished: (() -> Unit)? = null
    private var _duration = 0L

    init {
        equalizer?.let { eq ->
            mediaPlayer?.audio()?.setEqualizer(eq)
        }
        mediaPlayer?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer?) {
                onFinished?.invoke()
            }

            override fun lengthChanged(mediaPlayer: MediaPlayer?, newLength: Long) {
                _duration = newLength
            }
        })
    }

    actual fun play(song: Song) {
        try {
            println("MusicPlayer: Playing path ${song.path}")
            mediaPlayer?.media()?.play(song.path)
        } catch (e: Exception) {
            println("MusicPlayer: Error playing ${song.title}: ${e.message}")
        }
    }

    actual fun pause() {
        mediaPlayer?.controls()?.pause()
    }

    actual fun resume() {
        mediaPlayer?.controls()?.play()
    }

    actual fun stop() {
        mediaPlayer?.controls()?.stop()
    }

    actual fun seekTo(position: Long) {
        mediaPlayer?.controls()?.setTime(position)
    }

    actual fun setEqBand(index: Int, level: Float) {
        try {
            // VLC equalizer band levels are in dB, typically -20 to 20
            equalizer?.setAmp(index, level)
        } catch (e: Exception) {}
    }

    actual val isPlaying: Boolean
        get() = mediaPlayer?.status()?.isPlaying ?: false

    actual val currentPosition: Long
        get() = mediaPlayer?.status()?.time() ?: 0L

    actual val duration: Long
        get() = _duration
}
