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
    
    suspend fun saveSettings(settings: AppSettings)
    suspend fun loadSettings(): AppSettings?
    
    suspend fun savePlaylists(playlists: List<Playlist>)
    suspend fun loadPlaylists(allSongs: List<music.ai.recommend.model.Song>): List<Playlist>
    
    suspend fun saveFavorites(songPaths: Set<String>)
    suspend fun loadFavorites(): Set<String>
}
