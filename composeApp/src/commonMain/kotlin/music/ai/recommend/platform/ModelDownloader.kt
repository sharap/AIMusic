package music.ai.recommend.platform

import kotlinx.coroutines.flow.StateFlow

/** Progress of an in-flight model download. */
data class ModelProgress(
    val running: Boolean = false,
    val currentFile: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val error: String? = null
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * The CLAP weights, which are fetched on first use rather than shipped.
 *
 * They are 408 MB and not this project's to redistribute, so the repository carries the code and
 * the tokenizer's vocabulary while the weights come from Hugging Face, checked against a known
 * size and sha256 before they are used.
 */
expect class ModelDownloader() {
    val progress: StateFlow<ModelProgress>

    fun audioModelReady(): Boolean
    fun textModelReady(): Boolean

    /** Bytes still to fetch for everything the app uses. */
    fun pendingBytes(): Long

    /** Downloads whatever is missing. @return true once everything is present. */
    suspend fun ensureAll(): Boolean

    /** Removes the downloaded weights. @return bytes freed. */
    suspend fun deleteDownloaded(): Long
}
