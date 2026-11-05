package music.ai.recommend.db

import music.ai.recommend.model.AppSettings
import music.ai.recommend.model.Playlist

interface MusicDao {
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>
    suspend fun insertEmbedding(embedding: EmbeddingEntity)
    suspend fun deleteEmbedding(path: String)
    suspend fun clearAllEmbeddings()

    /**
     * True when embeddings from an older analysis had to be thrown away on startup.
     *
     * The settings screen says so, because otherwise the library would appear to have
     * un-analysed itself between one launch and the next.
     */
    suspend fun analysisWasReset(): Boolean

    /** Clears the notice, once a fresh scan has replaced what was discarded. */
    suspend fun acknowledgeAnalysisReset()

    /**
     * The listening history, which the playlist of the day is built from.
     *
     * Appended to rather than rewritten: a listen is recorded every few minutes, and rewriting the
     * whole log each time would be pure waste. Everything stays on this machine.
     */
    suspend fun appendPlayEvent(event: PlayEventEntity)

    /** Every recorded listen, oldest first. Pruning keeps this bounded. */
    suspend fun playEvents(): List<PlayEventEntity>

    /** Drops listens recorded before [cutoff]; they no longer carry weight in the taste profile. */
    suspend fun prunePlayEvents(cutoff: Long)

    /**
     * Listens heard on another device, already matched to tracks in this library, written in one
     * go because that is how they arrive.
     *
     * Kept apart from [appendPlayEvent] by [PlayEventEntity.deviceId], which is what stops them
     * being sent back out as this machine's own.
     */
    suspend fun appendPlayEvents(events: List<PlayEventEntity>)

    /**
     * This machine's own listens past [afterId], oldest first, for the sync outbox. At most [limit]
     * of them, so a history of years does not have to be described in one pass.
     */
    suspend fun playEventsToExport(afterId: Long, limit: Int): List<PlayEventEntity>

    /** The newest id in the log, so an outbox starting for the first time starts from the present. */
    suspend fun newestPlayEventId(): Long

    /**
     * Listens taken in from other devices since [since] — what the merge is told this machine
     * already holds, so that reading the same file twice costs nothing.
     */
    suspend fun playEventsFromOtherDevices(since: Long): List<PlayEventEntity>
    
    suspend fun saveSettings(settings: AppSettings)
    suspend fun loadSettings(): AppSettings?
    
    suspend fun savePlaylists(playlists: List<Playlist>)
    suspend fun loadPlaylists(allSongs: List<music.ai.recommend.model.Song>): List<Playlist>
    
    suspend fun saveFavorites(songPaths: Set<String>)
    suspend fun loadFavorites(): Set<String>
}
