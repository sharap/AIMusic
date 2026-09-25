package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import music.ai.recommend.ai.AudioProcessor
import music.ai.recommend.ai.DesktopAudioDecoder
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.model.Song
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

actual class AiScanner actual constructor() {
    private val models = ModelRepository.instance
    private val db = getAppDatabase()
    private val audioProcessor = AudioProcessor()
    private val audioDecoder = DesktopAudioDecoder()

    // Strict single-threaded mode for absolute stability. AudioProcessor reuses its working
    // buffers between calls, so this permit is also what keeps featurisation correct.
    private val semaphore = Semaphore(1)

    @Volatile
    private var ortEnv: OrtEnvironment? = null

    @Volatile
    private var ortSession: OrtSession? = null

    // Written by stop() from the UI thread, read from the scan coroutines.
    @Volatile
    private var isStopping = false

    /** Set when something has gone wrong for the whole library, not just one track. */
    @Volatile
    private var fatalError: String? = null

    private suspend fun loadModel(onStatus: (String) -> Unit): Boolean {
        if (ortSession != null) return true
        // 268 MB over the network the first time; the scan cannot start without it, so the
        // download is part of starting rather than something the user has to arrange first.
        if (!models.isAvailable(ModelAsset.AUDIO_MODEL)) {
            onStatus("Downloading the audio model...")
            if (!models.ensure(listOf(ModelAsset.AUDIO_MODEL))) {
                onStatus("Download failed: ${models.progress.value.error ?: "unknown error"}")
                return false
            }
        }
        return try {
            val modelFile = models.localFile(ModelAsset.AUDIO_MODEL)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortEnv = env
            ortSession = env.createSession(modelFile.absolutePath, options)
            println("AiScanner: Model loaded successfully")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            onStatus("Error loading AI model")
            false
        }
    }

    actual fun stop() {
        isStopping = true
    }

    /**
     * Frees the ~280 MB inference session. Worth doing as soon as a scan ends: the text encoder
     * holds its own session, and keeping both alive is what pushes a modest machine into swap.
     */
    actual fun release() {
        runCatching { ortSession?.close() }
        ortSession = null
        ortEnv = null
    }

    actual suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        isStopping = false
        fatalError = null

        // Checked before the model is unpacked: without ffmpeg every track would "fail to decode",
        // and the scan would report success having analysed nothing.
        if (!audioDecoder.isAvailable) {
            onProgress(0f, "ffmpeg not found. Install ffmpeg and add it to PATH.", 0, -1L)
            return@withContext
        }
        if (!loadModel { status -> onProgress(0f, status, 0, -1L) }) return@withContext

        try {
            val dao = db.musicDao()
            val allEmbeddings = dao.getAllEmbeddings()

            // 1. Cleanup
            onProgress(0f, "Cleaning up database...", 0, -1L)
            val pathsToRemove = allEmbeddings.filter { !File(it.path).exists() }.map { it.path }
            if (pathsToRemove.isNotEmpty()) {
                pathsToRemove.forEach { (dao as? DesktopMusicDao)?.deleteEmbeddingNoSave(it) }
                (dao as? DesktopMusicDao)?.forceSave()
            }

            // 2. Identify remaining
            val currentEmbeddings = dao.getAllEmbeddings()
            val scannedPathsInDb = currentEmbeddings.map { it.path }.toSet()
            val songsToProcess = songs.filter { it.path !in scannedPathsInDb }
            val alreadyScannedCount = songs.size - songsToProcess.size
            val totalToProcess = songsToProcess.size

            if (totalToProcess == 0) {
                onProgress(1f, "Complete", songs.size, 0L)
                return@withContext
            }

            onProgress(alreadyScannedCount.toFloat() / songs.size, "Starting analysis...", alreadyScannedCount, -1L)

            val startTime = System.currentTimeMillis()
            val processedInThisSession = AtomicInteger(0)

            val jobs = songsToProcess.map { song ->
                async {
                    if (isStopping || fatalError != null) return@async
                    semaphore.withPermit {
                        if (isStopping || fatalError != null) return@async
                        try {
                            withTimeoutOrNull(45.seconds) {
                                val currentIdx = processedInThisSession.get()
                                onProgress(
                                    (alreadyScannedCount + currentIdx).toFloat() / songs.size,
                                    "Analyzing: ${song.title}",
                                    alreadyScannedCount + currentIdx,
                                    -1L
                                )

                                if (!File(song.path).exists()) return@withTimeoutOrNull

                                val audioData = audioDecoder.decodeChunk(song.path, CHUNK_MS, song.duration)

                                // A track that would not decode is still stored, as a zero vector,
                                // so the next scan does not keep retrying it. Cosine similarity
                                // against it is zero, so it never surfaces as a recommendation.
                                val embedding = if (audioData.isEmpty()) {
                                    FloatArray(EMBEDDING_SIZE)
                                } else {
                                    runInference(audioProcessor.extractFeatures(audioData))
                                }
                                dao.insertEmbedding(EmbeddingEntity(song.path, embedding.toList()))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DesktopAudioDecoder.DecodeUnavailable) {
                            // The installation is broken, not this track: stop rather than write
                            // a library's worth of zero vectors that would never be retried.
                            fatalError = "ffmpeg stopped working: ${e.message}"
                        } catch (e: Exception) {
                            println("AiScanner: Error on ${song.title}: ${e.message}")
                        } finally {
                            val currentCount = processedInThisSession.incrementAndGet()
                            val currentTime = System.currentTimeMillis()
                            val elapsed = currentTime - startTime
                            val avgTimePerSong = elapsed / currentCount.coerceAtLeast(1)
                            val remainingCount = totalToProcess - currentCount
                            val etrSeconds = (remainingCount * avgTimePerSong) / 1000

                            onProgress(
                                (alreadyScannedCount + currentCount).toFloat() / songs.size,
                                song.title,
                                alreadyScannedCount + currentCount,
                                etrSeconds
                            )

                            if (currentCount % 10 == 0) (dao as? DesktopMusicDao)?.forceSave()
                        }
                    }
                }
            }

            jobs.awaitAll()
            (dao as? DesktopMusicDao)?.forceSave()

            val failure = fatalError
            when {
                failure != null -> onProgress(1f, failure, songs.size, 0L)
                !isStopping -> onProgress(1f, "Complete", songs.size, 0L)
            }
        } finally {
            // Also runs when the user stops the scan, so the ~280 MB session is not left resident.
            release()
        }
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: return FloatArray(EMBEDDING_SIZE)
        val env = ortEnv ?: return FloatArray(EMBEDDING_SIZE)
        return try {
            // CLAP expects [batch, 1, frames, mels] -> [1, 1, 1001, 64]
            val shape = longArrayOf(1, 1, FRAMES, MELS)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape).use { tensor ->
                session.run(mapOf("input_features" to tensor)).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val result = output.get(0).value as Array<FloatArray>
                    normalize(result[0])
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            FloatArray(EMBEDDING_SIZE)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private companion object {
        const val EMBEDDING_SIZE = 512
        const val CHUNK_MS = 10_000L
        const val FRAMES = 1001L
        const val MELS = 64L
    }
}
