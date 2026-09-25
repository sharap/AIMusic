package music.ai.recommend.db

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import music.ai.recommend.model.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Bumped whenever anything that changes the value of an embedding changes — the feature
 * extractor, the excerpt selection, the projection.
 *
 * Version 2 corrected two of those at once: the log-mel features now match `ClapFeatureExtractor`
 * (the previous ones used an HTK mel scale from 0 Hz, unnormalised filters on whole-bin edges and
 * a natural logarithm, which put the 64 mel channels on the wrong frequencies entirely), and the
 * excerpt is taken from the middle of the track rather than from a fixed 20 s in. Measured over a
 * 40-track library, the peak similarity of a matching description roughly doubled and genre
 * matches went from arbitrary to correct, so version 1 embeddings are not worth keeping.
 *
 * Smart albums stamp it into their cache signature too: albums built from version 1 embeddings
 * describe a grouping that no longer exists.
 */
internal const val ANALYSIS_VERSION = 2

class DesktopMusicDao(private val baseDir: File) : MusicDao {
    private val embeddingsFile = File(baseDir, "embeddings.bin")
    private val legacyEmbeddingsFile = File(baseDir, "embeddings.json")
    private val settingsFile = File(baseDir, "settings.json")
    private val playlistsDir = File(baseDir, "playlists")
    private val favoritesFile = File(baseDir, "favorites.m3u")
    private val historyFile = File(baseDir, "play_history.bin")

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Keyed by path, so replacing a track's embedding is a hash lookup.
     *
     * A list meant every insert scanned the whole table to drop the previous row, which over a
     * 4300-track scan is about nine million comparisons for work a map does in one step. The lock
     * is a separate object because the cache used to be a reassignable `var`, and synchronising on
     * something that gets replaced guards nothing once it has been.
     */
    private val lock = Any()
    private val historyLock = Any()
    private var countOnDisk = -1
    private val cache = LinkedHashMap<String, EmbeddingEntity>()

    /** The envelope the pre-binary format stored embeddings in. */
    private class StoredEmbeddings(
        val analysisVersion: Int = 0,
        val embeddings: List<EmbeddingEntity>? = null
    )

    @Volatile
    private var analysisResetFlag = false

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
        if (!playlistsDir.exists()) playlistsDir.mkdirs()
        load()
    }

    private fun load() {
        if (embeddingsFile.exists()) {
            loadBinary()
            return
        }
        if (legacyEmbeddingsFile.exists()) loadLegacyJson()
    }

    private fun loadBinary() {
        try {
            DataInputStream(embeddingsFile.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) {
                    println("DesktopMusicDao: embeddings.bin is not in the expected format, ignoring it")
                    return
                }
                val formatVersion = input.readInt()
                if (formatVersion != FORMAT_VERSION) {
                    println("DesktopMusicDao: unknown storage format $formatVersion, ignoring it")
                    return
                }
                val analysisVersion = input.readInt()
                val count = input.readInt()
                if (analysisVersion != ANALYSIS_VERSION) {
                    discardOutdatedAnalysis(analysisVersion)
                    return
                }
                val loaded = LinkedHashMap<String, EmbeddingEntity>(count * 2)
                repeat(count) {
                    val path = input.readUTF()
                    val dimension = input.readInt()
                    val vector = ArrayList<Float>(dimension)
                    repeat(dimension) { vector.add(input.readFloat()) }
                    loaded[path] = EmbeddingEntity(path, vector)
                }
                synchronized(lock) {
                    cache.clear()
                    cache.putAll(loaded)
                }
            }
        } catch (e: Exception) {
            println("DesktopMusicDao: could not read embeddings.bin: ${e.message}")
        }
    }

    /**
     * One-time conversion of the JSON envelope into the binary table.
     *
     * The JSON form was pretty-printed floats — 47 MB for 4300 tracks against about 9 MB packed —
     * so it is rewritten rather than kept. The vectors themselves are unchanged, and the binary
     * file is only written once it has been read in full, so an interrupted migration costs
     * nothing.
     */
    private fun loadLegacyJson() {
        try {
            // An outdated table is thrown away whole, so the format is identified from the first
            // character rather than by parsing tens of megabytes first and deciding afterwards.
            val head = legacyEmbeddingsFile.bufferedReader().use { reader ->
                val buffer = CharArray(HEAD_CHARS)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            when (head.firstOrNull { !it.isWhitespace() }) {
                null -> return
                '[' -> {
                    discardOutdatedAnalysis(1)
                    legacyEmbeddingsFile.delete()
                    return
                }
            }

            val stored = gson.fromJson(legacyEmbeddingsFile.readText(), StoredEmbeddings::class.java) ?: return
            if (stored.analysisVersion != ANALYSIS_VERSION) {
                discardOutdatedAnalysis(stored.analysisVersion)
                legacyEmbeddingsFile.delete()
                return
            }
            synchronized(lock) {
                cache.clear()
                for (entity in stored.embeddings.orEmpty()) cache[entity.path] = entity
            }
            forceSave()
            if (embeddingsFile.exists() && embeddingsFile.length() > 0) {
                legacyEmbeddingsFile.delete()
                println("DesktopMusicDao: migrated ${cache.size} embeddings to the binary table")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Drops embeddings produced by an older analysis.
     *
     * They are not merely stale but wrong: mixing them with current ones would give a similarity
     * space where part of the library sits in the wrong place, and since the scan skips paths it
     * already has, a rescan would quietly keep every one of them.
     */
    private fun discardOutdatedAnalysis(storedVersion: Int) {
        synchronized(lock) { cache.clear() }
        analysisResetFlag = true
        println("DesktopMusicDao: discarded embeddings from analysis version $storedVersion")
        forceSave()
    }

    fun forceSave() {
        try {
            if (!baseDir.exists()) baseDir.mkdirs()
            val rows = synchronized(lock) { cache.values.toList() }
            // Written aside and renamed, so an interrupted save cannot truncate the table.
            val tmp = File(embeddingsFile.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(ANALYSIS_VERSION)
                out.writeInt(rows.size)
                for (row in rows) {
                    out.writeUTF(row.path)
                    out.writeInt(row.vector.size)
                    for (x in row.vector) out.writeFloat(x)
                }
            }
            if (!tmp.renameTo(embeddingsFile)) {
                embeddingsFile.delete()
                tmp.renameTo(embeddingsFile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getAllEmbeddings(): List<EmbeddingEntity> {
        return synchronized(lock) { cache.values.toList() }
    }

    override suspend fun insertEmbedding(embedding: EmbeddingEntity) {
        synchronized(lock) {
            cache[embedding.path] = embedding
        }
    }

    override suspend fun deleteEmbedding(path: String) {
        deleteEmbeddingNoSave(path)
        forceSave()
    }

    fun deleteEmbeddingNoSave(path: String) {
        synchronized(lock) {
            cache.remove(path)
        }
    }

    override suspend fun clearAllEmbeddings() {
        synchronized(lock) {
            cache.clear()
        }
        analysisResetFlag = false
        forceSave()
    }

    // ---------------------------------------------------------------- listening history

    /**
     * An append-only log: one record per finished listen, written as it happens.
     *
     * Rewriting a whole table for every track played would be wasteful, and a crash mid-write
     * would take the history with it. A truncated tail from an interrupted append costs at most
     * the last listen, because the reader stops at the first short record.
     */
    override suspend fun appendPlayEvent(event: PlayEventEntity) {
        synchronized(historyLock) {
            try {
                if (!baseDir.exists()) baseDir.mkdirs()
                DataOutputStream(FileOutputStream(historyFile, true).buffered()).use { out ->
                    out.writeUTF(event.path)
                    out.writeLong(event.playedAt)
                    out.writeLong(event.playedMs)
                    out.writeLong(event.durationMs)
                }
            } catch (e: Exception) {
                println("DesktopMusicDao: could not record a listen: ${e.message}")
            }
        }
    }

    override suspend fun playEvents(): List<PlayEventEntity> = synchronized(historyLock) { readPlayEvents() }

    override suspend fun prunePlayEvents(cutoff: Long) {
        synchronized(historyLock) {
            val kept = readPlayEvents().filter { it.playedAt >= cutoff }
            if (kept.size == countOnDisk) return
            try {
                val tmp = File(historyFile.path + ".tmp")
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    for (event in kept) {
                        out.writeUTF(event.path)
                        out.writeLong(event.playedAt)
                        out.writeLong(event.playedMs)
                        out.writeLong(event.durationMs)
                    }
                }
                if (!tmp.renameTo(historyFile)) {
                    historyFile.delete()
                    tmp.renameTo(historyFile)
                }
                countOnDisk = kept.size
            } catch (e: Exception) {
                println("DesktopMusicDao: could not prune the history: ${e.message}")
            }
        }
    }

    /** Must be called with [historyLock] held. */
    private fun readPlayEvents(): List<PlayEventEntity> {
        if (!historyFile.exists()) return emptyList()
        val events = ArrayList<PlayEventEntity>()
        try {
            DataInputStream(historyFile.inputStream().buffered()).use { input ->
                while (true) {
                    val path = try {
                        input.readUTF()
                    } catch (e: java.io.EOFException) {
                        break
                    }
                    events += PlayEventEntity(path, input.readLong(), input.readLong(), input.readLong())
                }
            }
        } catch (e: Exception) {
            // A short record at the tail is an interrupted append; everything before it is good.
            println("DesktopMusicDao: history ends in a partial record, keeping ${events.size} listens")
        }
        countOnDisk = events.size
        return events
    }

    override suspend fun analysisWasReset(): Boolean = analysisResetFlag

    override suspend fun acknowledgeAnalysisReset() {
        analysisResetFlag = false
    }

    private companion object {
        /** Enough of the legacy file to find its first non-blank character. */
        const val HEAD_CHARS = 64

        /** "AIME", so a file that is not this table is recognised rather than misparsed. */
        const val MAGIC = 0x41494D45

        /** How the table is laid out, as opposed to what the vectors in it mean. */
        const val FORMAT_VERSION = 1
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

/**
 * One database per process.
 *
 * [DesktopMusicDao] keeps the embeddings table in memory and only reads the file in its
 * constructor, so a second instance means a second cache: the scanner wrote through its own copy
 * while the ViewModel kept serving the one it had loaded at startup, which is why a finished scan
 * left "Analyzed songs" unchanged and AI search empty until the app was restarted. Worse, a save
 * from the stale cache could overwrite what the scan had just written.
 */
private val databaseInstance: AppDatabase by lazy { DesktopAppDatabase() }

actual fun getAppDatabase(): AppDatabase = databaseInstance
