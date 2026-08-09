package music.ai.recommend.platform

expect class ClapTextEncoder() {
    suspend fun encode(text: String): FloatArray?
}
