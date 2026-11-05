package music.ai.recommend.sync

import music.ai.recommend.ai.Excerpt
import music.ai.recommend.ai.Mp3Excerpt
import music.ai.recommend.db.ANALYSIS_VERSION
import music.ai.recommend.db.EMBEDDING_DIMENSIONS
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.MusicDao
import music.ai.recommend.model.Song

/**
 * This app's embeddings, as [SyncCoordinator] wants to see them.
 *
 * All of what is particular to this port lives here: the vectors are a packed file keyed by path,
 * and a duration comes from the tag reader — which is the same reading the analysis uses, so unlike
 * on the phone no file has to be opened to name an excerpt. That is the whole asymmetry between the
 * two implementations, and it is why the question is asked of the platform at all.
 *
 * Not a copy. The Android app's answer to the same question is `EmbeddingVectorStore`, over a Room
 * table keyed by MediaStore id and a container duration read through MediaExtractor.
 */
class DaoVectorStore(
    private val dao: MusicDao,
    private val songs: suspend () -> List<Song>,
    /** sha256 of the audio model this app analyses with. */
    private val model: String
) : VectorStore<String> {

    private var durations: Map<String, Long> = emptyMap()
    private var durationsFrom = 0

    private suspend fun durationOf(path: String): Long? {
        val library = songs()
        if (library.size != durationsFrom) {
            durations = library.associate { it.path to it.duration }
            durationsFrom = library.size
        }
        return durations[path]?.takeIf { it > 0 }
    }

    override suspend fun terms(): VectorTerms = VectorTerms(
        model = model,
        analysisVersion = ANALYSIS_VERSION,
        dimensions = EMBEDDING_DIMENSIONS
    )

    override suspend fun decoderFor(track: String): String =
        if (Mp3Excerpt.handles(track)) Mp3Excerpt.DECODER else PLATFORM

    override suspend fun excerptStartMs(track: String): Long? {
        // The same value `AiScanner` passes to the decoder, which is the library scanner's reading
        // and not a second opinion: the tag reader gives whole seconds, the excerpt rule truncates
        // to whole seconds anyway, and that agreement is what makes the two platforms meet.
        val duration = durationOf(track) ?: return null
        return Excerpt.startMs(duration)
    }

    override suspend fun toPublish(limit: Int): List<VectorStore.Held<String>> =
        dao.vectorsToPublish(limit).map { row ->
            VectorStore.Held(row.path, FloatArray(row.vector.size) { row.vector[it] })
        }

    override suspend fun markHandled(tracks: List<String>) {
        dao.markVectorsPublished(tracks, System.currentTimeMillis())
    }

    override suspend fun analysed(): Set<String> =
        dao.getAllEmbeddings().mapTo(HashSet()) { it.path }

    override suspend fun takeIn(vectors: List<VectorStore.Held<String>>): Int {
        val now = System.currentTimeMillis()
        val stored = dao.insertEmbeddings(vectors.map { held ->
            EmbeddingEntity(
                path = held.track,
                vector = held.vector.toList(),
                // Marked as dealt with as they are stored: with two devices there is nobody to
                // relay to, and offering them back out would have the folder carry each twice.
                publishedAt = now
            )
        })
        return stored
    }

    private companion object {
        /** What this platform decodes everything but mp3 with. Never allowed to travel. */
        const val PLATFORM = "ffmpeg-desktop"
    }
}
