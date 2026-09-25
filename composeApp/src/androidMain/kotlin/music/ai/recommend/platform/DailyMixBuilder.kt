package music.ai.recommend.platform

import music.ai.recommend.model.Song

actual class DailyMixBuilder actual constructor() {
    actual suspend fun playlist(
        library: List<Song>,
        favourites: Set<String>,
        rebuild: Boolean
    ): DailyPlaylist = DailyPlaylist("", emptyList())

    actual fun millisUntilNextDay(): Long = Long.MAX_VALUE
}
