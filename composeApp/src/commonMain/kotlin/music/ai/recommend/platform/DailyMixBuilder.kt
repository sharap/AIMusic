package music.ai.recommend.platform

import music.ai.recommend.model.Song

/** The playlist of the day, as the UI needs it. */
data class DailyPlaylist(val day: String, val songs: List<Song>)

/**
 * Builds and remembers the playlist of the day.
 *
 * The playlist is fixed for the day: built once, kept on disk, and the same on every launch until
 * the date changes. That is deliberate — a playlist that quietly reshuffled itself every time the
 * app opened would be a shuffle button with extra steps, and there would be no point in coming
 * back to it later in the day.
 *
 * The days before today are kept too, so the same tracks are not offered again all week.
 */
expect class DailyMixBuilder() {
    /**
     * @param rebuild builds a different playlist for today, on request. It replaces today's and
     *   the old one goes into the recent days, so the same tracks do not come straight back.
     */
    suspend fun playlist(library: List<Song>, favourites: Set<String>, rebuild: Boolean): DailyPlaylist

    /** Milliseconds until the next local midnight, when the playlist should be rebuilt. */
    fun millisUntilNextDay(): Long
}
