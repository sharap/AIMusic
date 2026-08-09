package music.ai.recommend.platform

import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.charset.Charset
import java.util.logging.Level
import java.util.logging.Logger

actual class MusicScanner actual constructor() {
    
    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    actual fun scanMusic(): List<Folder> {
        // This is a fallback if no path is provided, but we should use the one from settings
        return scanCustomPath(System.getProperty("user.home") + "/Music")
    }

    actual fun scanCustomPath(path: String): List<Folder> {
        val rootDir = File(path)
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        
        val songList = mutableListOf<Song>()
        scanDir(rootDir, songList)
        
        return songList.groupBy { it.folderName }
            .map { (name, songs) -> Folder(name, songs) }
            .sortedBy { it.name }
    }
    
    private fun scanDir(dir: File, result: MutableList<Song>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDir(file, result)
            } else if (file.extension.lowercase() in listOf("mp3", "flac", "wav", "m4a", "ogg")) {
                try {
                    val audioFile = AudioFileIO.read(file)
                    val tag = audioFile.tag
                    val header = audioFile.audioHeader
                    
                    val title = fixEncoding(tag?.getFirst(FieldKey.TITLE)) ?: file.nameWithoutExtension
                    val artist = fixEncoding(tag?.getFirst(FieldKey.ARTIST)) ?: "Unknown Artist"
                    val album = fixEncoding(tag?.getFirst(FieldKey.ALBUM)) ?: "Unknown Album"
                    val duration = (header?.trackLength ?: 0).toLong() * 1000
                    
                    result.add(
                        Song(
                            id = file.absolutePath.hashCode().toLong(),
                            title = title,
                            artist = artist,
                            album = album,
                            albumId = album.hashCode().toLong(),
                            duration = duration,
                            uri = file.toURI().toString(),
                            path = file.absolutePath,
                            folderName = dir.name
                        )
                    )
                } catch (e: Exception) {
                    result.add(
                        Song(
                            id = file.absolutePath.hashCode().toLong(),
                            title = file.nameWithoutExtension,
                            artist = "Unknown",
                            album = "Unknown",
                            albumId = 0L,
                            duration = 0L,
                            uri = file.toURI().toString(),
                            path = file.absolutePath,
                            folderName = dir.name
                        )
                    )
                }
            }
        }
    }

    /**
     * Attempts to fix common encoding issues where CP1251 (Cyrillic) 
     * is incorrectly read as ISO-8859-1.
     */
    private fun fixEncoding(text: String?): String? {
        if (text == null || text.isBlank()) return null
        
        // If the string contains characters typical for incorrectly decoded Cyrillic
        // (like 'à', 'á', 'â' etc. instead of Russian letters)
        return try {
            val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))
            val decoded = String(bytes, Charset.forName("Windows-1251"))
            
            // Basic check: if decoded string has more "normal" Cyrillic chars than original
            if (decoded.any { it in '\u0410'..'\u044F' }) {
                decoded
            } else {
                text
            }
        } catch (e: Exception) {
            text
        }
    }
}
