package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.AudioProcessor
import music.ai.recommend.ai.DesktopAudioDecoder
import music.ai.recommend.db.AppDatabase
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.model.Song
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import music.ai.recommend.db.DesktopMusicDao
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

import java.util.concurrent.atomic.AtomicInteger

actual class AiScanner actual constructor() {
    private val modelFileName = "audio_model.onnx"
    private val db = getAppDatabase()
    private val audioProcessor = AudioProcessor()
    
    // Shared VLC factory for all decoders - MUCH faster
    private val vlcFactory = MediaPlayerFactory()
    
    // Limits simultaneous songs dynamically
    private val semaphore: Semaphore by lazy {
        val cores = Runtime.getRuntime().availableProcessors()
        val permits = if (cores > 4) cores - 2 else cores.coerceAtLeast(1)
        println("AiScanner: Using $permits parallel threads for processing")
        Semaphore(permits)
    }
    
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
                val classLoaders = listOf(
                    javaClass.classLoader,
                    Thread.currentThread().contextClassLoader
                )

                for (cl in classLoaders) {
                    for (p in paths) {
                        val stream = cl.getResourceAsStream(p) ?: cl.getResourceAsStream("/$p")
                        if (stream != null) {
                            stream.use { input ->
                                cacheModelFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            loaded = true
                            break
                        }
                    }
                    if (loaded) break
                }
                
                if (!loaded) {
                    val error = "Error: Model file $modelFileName not found in resources!"
                    onStatus(error)
                    return
                }
            }

            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            
            // Limit internal threads per execution to allow more parallel song tasks
            options.setIntraOpNumThreads(2) 
            options.setInterOpNumThreads(2)
            
            val session = ortEnv?.createSession(cacheModelFile.absolutePath, options)
            ortSession = session
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
        
        loadModel { status ->
            onProgress(0f, status, 0, -1L)
        }

        val dao = db.musicDao()
        val scannedPathsInDb = try { dao.getAllEmbeddings().map { it.path }.toSet() } catch(e: Exception) { emptySet() }
        
        val songsToProcess = songs.filter { it.path !in scannedPathsInDb }
        val alreadyScannedCount = songs.size - songsToProcess.size
        val totalToProcess = songsToProcess.size
        
        if (totalToProcess == 0) {
            onProgress(1f, "Complete", songs.size, 0L)
            return@withContext
        }

        // Initial progress report
        onProgress(alreadyScannedCount.toFloat() / songs.size, "Starting...", alreadyScannedCount, -1L)

        val startTime = System.currentTimeMillis()
        val processedInThisSession = AtomicInteger(0)

        val jobs = songsToProcess.map { song ->
            async {
                if (isStopping) return@async
                
                semaphore.withPermit {
                    try {
                        val decoder = DesktopAudioDecoder(vlcFactory)
                        val audioData = decoder.decodeChunk(song.path, 10000)
                        
                        if (audioData.isNotEmpty()) {
                            val features = audioProcessor.extractFeatures(audioData)
                            val embedding = runInference(features)
                            dao.insertEmbedding(EmbeddingEntity(song.path, embedding.toList()))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        val currentCount = processedInThisSession.incrementAndGet()
                        val currentTime = System.currentTimeMillis()
                        val elapsed = currentTime - startTime
                        val avgTimePerSong = elapsed / currentCount
                        val remainingCount = totalToProcess - currentCount
                        val etrSeconds = (remainingCount * avgTimePerSong) / 1000
                        
                        val totalProcessed = alreadyScannedCount + currentCount
                        onProgress(
                            totalProcessed.toFloat() / songs.size,
                            song.title,
                            totalProcessed,
                            etrSeconds
                        )
                        
                        // Periodic save every 10 songs
                        if (currentCount % 10 == 0) {
                            (dao as? DesktopMusicDao)?.forceSave()
                        }
                    }
                }
            }
        }
        
    jobs.awaitAll()
        (dao as? DesktopMusicDao)?.forceSave()
        
        if (!isStopping) {
            onProgress(1f, "Complete", songs.size, 0L)
        }
    }

    private fun processSong(song: Song): FloatArray {
        // Not used anymore in parallel mode
        return FloatArray(512)
    }

    private fun runInference(features: FloatArray): FloatArray {
        val session = ortSession ?: throw IllegalStateException("ONNX Session is null")
        val env = ortEnv ?: throw IllegalStateException("ONNX Env is null")
        
        try {
            val shape = longArrayOf(1, 1, 1001, 64)
            val tensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(features), shape)
            
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
