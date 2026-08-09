package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
    private val modelFileName = "audio_model.onnx"
    private val db = getAppDatabase()
    private val audioProcessor = AudioProcessor()
    private val audioDecoder = DesktopAudioDecoder()
    
    // Strict single-threaded mode for absolute stability
    private val semaphore = Semaphore(1) 
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isStopping = false

    private fun loadModel(onStatus: (String) -> Unit) {
        if (ortSession != null) return
        try {
            val cacheDir = File(System.getProperty("user.home"), ".aimusic/cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheModelFile = File(cacheDir, modelFileName)
            
            if (!cacheModelFile.exists()) {
                onStatus("Extracting AI model...")
                val paths = listOf(
                    "composeResources/aimusic.composeapp.generated.resources/files/$modelFileName",
                    "composeResources/files/$modelFileName",
                    "files/$modelFileName"
                )
                var loaded = false
                val classLoaders = listOf(javaClass.classLoader, Thread.currentThread().contextClassLoader)
                for (cl in classLoaders) {
                    for (p in paths) {
                        val stream = cl.getResourceAsStream(p) ?: cl.getResourceAsStream("/$p")
                        if (stream != null) {
                            stream.use { input -> cacheModelFile.outputStream().use { output -> input.copyTo(output) } }
                            loaded = true; break
                        }
                    }
                    if (loaded) break
                }
                if (!loaded) {
                    onStatus("Error: Model file not found!")
                    return
                }
            }

            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortSession = ortEnv?.createSession(cacheModelFile.absolutePath, options)
            println("AiScanner: Model loaded successfully")
        } catch (e: Exception) {
            e.printStackTrace()
            onStatus("Error loading AI model")
        }
    }

    actual fun stop() {
        isStopping = true
    }

    actual suspend fun scanSongs(
        songs: List<Song>,
        onProgress: (Float, String, Int, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        isStopping = false
        loadModel { status -> onProgress(0f, status, 0, -1L) }

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
                if (isStopping) return@async
                semaphore.withPermit {
                    if (isStopping) return@async
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

                            val audioData = audioDecoder.decodeChunk(song.path, 10000)
                            
                            if (audioData.isNotEmpty()) {
                                val features = audioProcessor.extractFeatures(audioData)
                                val embedding = runInference(features)
                                dao.insertEmbedding(EmbeddingEntity(song.path, embedding.toList()))
                            }
                        }
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
        if (!isStopping) onProgress(1f, "Complete", songs.size, 0L)
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: throw IllegalStateException("ONNX Session is null")
        val env = ortEnv ?: throw IllegalStateException("ONNX Env is null")
        try {
            val shape = longArrayOf(1, 1, 1001, 64)
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)
            val output = session.run(mapOf("input_features" to tensor))
            @Suppress("UNCHECKED_CAST")
            val result = output.get(0).value as Array<FloatArray>
            return normalize(result[0])
        } catch (e: Exception) {
            e.printStackTrace()
            return FloatArray(512)
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
}
