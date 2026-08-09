package music.ai.recommend.db

import com.google.gson.annotations.SerializedName

data class EmbeddingEntity(
    @SerializedName("path")
    val path: String,
    @SerializedName("vector")
    val vector: List<Float>
)
