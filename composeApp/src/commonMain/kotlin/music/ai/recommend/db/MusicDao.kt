package music.ai.recommend.db

import music.ai.recommend.model.AppSettings
import music.ai.recommend.model.Playlist

interface MusicDao {
    /**
     * Every stored vector. Refusals are not among them: everything that computes a similarity
     * reads this, and a track with no direction would sit at distance 1 from the whole library —
     * a point DBSCAN has to cluster and a candidate the wave scores 0 on every term, which beats
     * anything the listener is moving away from. See [refusals].
     */
    suspend fun getAllEmbeddings(): List<EmbeddingEntity>

    /**
     * Tracks the analysis tried and could not read, each with the stamp of the file it gave up on;
     * see [music.ai.recommend.ai.AnalysisBacklog].
     */
    suspend fun refusals(): Map<String, String>

    suspend fun insertEmbedding(embedding: EmbeddingEntity)

    /** Remembers that this file could not be read, so the next scan does not try it again for nothing. */
    suspend fun refuseEmbedding(path: String, stamp: String)

    suspend fun deleteEmbedding(path: String)

    /** Several at once, for the vectors whose track the library no longer has. */
    suspend fun deleteEmbeddings(paths: List<String>)

    suspend fun clearAllEmbeddings()

    /**
     * Several at once, which is how vectors arrive from another device, and saved before this
     * returns so that a crash does not lose what was just taken in.
     *
     * @return how many were stored. Zero for a failure, so the caller can leave the folder looking
     *   unread rather than marking a batch taken in that was not.
     */
    suspend fun insertEmbeddings(embeddings: List<EmbeddingEntity>): Int

    /**
     * Vectors not yet offered to the other devices; see [EmbeddingEntity.publishedAt].
     *
     * Refusals are left out, and not as an optimisation. "I could not read this file" is a verdict
     * about one machine's decoders; the device at the other end may read the format perfectly
     * well, and if it took the refusal for a vector it would mark the track analysed and never
     * look at it again.
     */
    suspend fun vectorsToPublish(limit: Int): List<EmbeddingEntity>

    /** Records that these have been dealt with, whether or not they could travel. */
    suspend fun markVectorsPublished(paths: List<String>, at: Long)

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
     *
     * @return how many were stored. Zero means none were, and the caller must then not treat the
     *   batch as taken in — it will be offered again.
     */
    suspend fun appendPlayEvents(events: List<PlayEventEntity>): Int

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
