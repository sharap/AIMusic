package music.ai.recommend.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import music.ai.recommend.model.*
import java.io.File

class DesktopMusicDao(private val baseDir: File) : MusicDao {
    private val file = File(baseDir, "embeddings.json")
    private val settingsFile = File(baseDir, "settings.json")
    private val playlistsDir = File(baseDir, "playlists")
    private val favoritesFile = File(baseDir, "favorites.m3u")
    
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private var cache: MutableList<EmbeddingEntity> = mutableListOf()

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        if (!playlistsDir.exists()) playlistsDir.mkdirs()
        load()
    }

    private fun load() {
        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<EmbeddingEntity>>() {}.type
                val loaded: List<EmbeddingEntity> = gson.fromJson(json, type)
                
                synchronized(cache) {
                    cache = loaded.toMutableList()
                }
                
                if (cache.size < loaded.size) {
                    println("DesktopMusicDao: Dropped ${loaded.size - cache.size} legacy embedding entries")
                    forceSave() // Clean up file immediately
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun forceSave() {
        try {
            if (!file.parentFile.exists()) file.parentFile.mkdirs()
            val dataToSave = synchronized(cache) {
                prettyGson.toJson(cache)
            }
            file.writeText(dataToSave)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getAllEmbeddings(): List<EmbeddingEntity> {
        return synchronized(cache) {
            cache.toList()
        }
    }

    override suspend fun insertEmbedding(embedding: EmbeddingEntity) {
        synchronized(cache) {
            cache.removeAll { it.path == embedding.path }
            cache.add(embedding)
        }
    }

    override suspend fun clearAllEmbeddings() {
        synchronized(cache) {
            cache.clear()
        }
        forceSave()
    }

    override suspend fun saveSettings(settings: AppSettings) {
        try {
            settingsFile.writeText(prettyGson.toJson(settings))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadSettings(): AppSettings? {
        if (!settingsFile.exists()) return null
        return try {
            val json = settingsFile.readText()
            gson.fromJson(json, AppSettings::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun savePlaylists(playlists: List<Playlist>) {
        try {
            // Clear existing M3U files
            playlistsDir.listFiles()?.forEach { it.delete() }
            
            playlists.forEach { playlist ->
                val m3uFile = File(playlistsDir, "${playlist.name}.m3u")
                val content = buildString {
                    appendLine("#EXTM3U")
                    playlist.songs.forEach { song ->
                        appendLine("#EXTINF:${song.duration / 1000},${song.artist} - ${song.title}")
                        appendLine(song.path)
                    }
                }
                m3uFile.writeText(content)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadPlaylists(allSongs: List<Song>): List<Playlist> {
        val result = mutableListOf<Playlist>()
        try {
            playlistsDir.listFiles { _, name -> name.endsWith(".m3u") }?.forEach { file ->
                val lines = file.readLines()
                val songPaths = lines.filter { it.isNotBlank() && !it.startsWith("#") }
                val playlistSongs = songPaths.mapNotNull { path ->
                    allSongs.find { it.path == path }
                }
                result.add(
                    Playlist(
                        id = file.nameWithoutExtension.hashCode().toLong(),
                        name = file.nameWithoutExtension,
                        songs = playlistSongs
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    override suspend fun saveFavorites(songPaths: Set<String>) {
        try {
            val content = buildString {
                appendLine("#EXTM3U")
                songPaths.forEach { appendLine(it) }
            }
            favoritesFile.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun loadFavorites(): Set<String> {
        if (!favoritesFile.exists()) return emptySet()
        return try {
            favoritesFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toSet()
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }
}

class DesktopAppDatabase : AppDatabase {
    private val baseDir = File(System.getProperty("user.home"), ".aimusic")
    private val dao = DesktopMusicDao(baseDir)
    
    override fun musicDao(): MusicDao = dao
}

actual fun getAppDatabase(): AppDatabase = DesktopAppDatabase()
