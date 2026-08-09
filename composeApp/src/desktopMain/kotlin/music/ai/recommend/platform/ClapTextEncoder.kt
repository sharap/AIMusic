package music.ai.recommend.platform

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import music.ai.recommend.ai.BpeTokenizer
import java.io.File
import java.nio.LongBuffer

actual class ClapTextEncoder actual constructor() {
    private val modelFileName = "text_model.onnx"
    private val vocabFileName = "vocab.json"
    private val mergesFileName = "merges.txt"
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: BpeTokenizer? = null

    private fun loadModel() {
        if (ortSession != null) return
        try {
            val cacheDir = File(System.getProperty("user.home"), ".aimusic/cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val cacheModelFile = extractResource(modelFileName, cacheDir) ?: return
            val vocabContent = readResourceText(vocabFileName) ?: return
            val mergesContent = readResourceText(mergesFileName) ?: return
            
            tokenizer = BpeTokenizer(vocabContent, mergesContent)

            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            ortSession = ortEnv?.createSession(cacheModelFile.absolutePath, options)
            println("ClapTextEncoder: Model and tokenizer loaded successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractResource(name: String, targetDir: File): File? {
        val targetFile = File(targetDir, name)
        if (targetFile.exists()) return targetFile
        
        val paths = listOf(
            "composeResources/aimusic.composeapp.generated.resources/files/$name",
            "composeResources/files/$name",
            "files/$name",
            name
        )
        
        for (path in paths) {
            val stream = javaClass.classLoader.getResourceAsStream(path) ?: javaClass.getResourceAsStream("/$path")
            if (stream != null) {
                stream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return targetFile
            }
        }
        return null
    }

    private fun readResourceText(name: String): String? {
        val paths = listOf(
            "composeResources/aimusic.composeapp.generated.resources/files/$name",
            "composeResources/files/$name",
            "files/$name",
            name
        )
        for (path in paths) {
            val stream = javaClass.classLoader.getResourceAsStream(path) ?: javaClass.getResourceAsStream("/$path")
            if (stream != null) {
                return stream.bufferedReader().use { it.readText() }
            }
        }
        return null
    }

    actual suspend fun encode(text: String): FloatArray? = withContext(Dispatchers.IO) {
        loadModel()
        val session = ortSession ?: return@withContext null
        val env = ortEnv ?: return@withContext null
        val tok = tokenizer ?: return@withContext null

        try {
            val tokens = tok.tokenize(text, 77)
            val shape = longArrayOf(1, 77)
            val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape)
            
            val output = session.run(mapOf("input_ids" to tensor))
            @Suppress("UNCHECKED_CAST")
            val result = output.get(0).value as Array<FloatArray>
            
            return@withContext normalize(result[0])
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
