package music.ai.recommend.db

/** One finished listen as it is stored: which track, when, how far it got, how long it was. */
data class PlayEventEntity(
    val path: String,
    val playedAt: Long,
    val playedMs: Long,
    val durationMs: Long
)
