package music.ai.recommend.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer

class ClapTextEncoder(private val context: Context) {
    private val modelFileName = "text_model.onnx"
    private val tokenizer = BpeTokenizer(context)
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val cacheModelFile = File(context.cacheDir, modelFileName)
            if (!cacheModelFile.exists()) {
                context.assets.open(modelFileName).use { input ->
                    cacheModelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.addNnapi()
            } catch (e: Exception) {}
            ortSession = ortEnv?.createSession(cacheModelFile.absolutePath, options)
            Log.d("ClapTextEncoder", "Text model loaded successfully")
        } catch (e: Exception) {
            Log.e("ClapTextEncoder", "Failed to load text model", e)
        }
    }

    fun encode(text: String): FloatArray {
        val env = ortEnv ?: return FloatArray(512)
        val session = ortSession ?: return FloatArray(512)
        
        try {
            val tokens = tokenizer.tokenize(text, 77)
            val shape = longArrayOf(1, 77)
            val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape)
            
            // CLAP text model inputs: [input_ids]
            val output = session.run(mapOf("input_ids" to tensor))
            @Suppress("UNCHECKED_CAST")
            val result = output.get(0).value as Array<FloatArray>
            
            return normalize(result[0])
        } catch (e: Exception) {
            Log.e("ClapTextEncoder", "Text encoding failed", e)
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
