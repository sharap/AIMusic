package music.ai.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import music.ai.recommend.model.*
import music.ai.recommend.platform.MusicScanner
import music.ai.recommend.platform.MusicPlayer
import music.ai.recommend.platform.AiScanner
import music.ai.recommend.platform.ClapTextEncoder
import music.ai.recommend.platform.SmartAlbumBuilder
import music.ai.recommend.platform.DailyMixBuilder
import music.ai.recommend.history.PlayEvent
import music.ai.recommend.history.PlayTracker
import music.ai.recommend.db.PlayEventEntity
import music.ai.recommend.platform.PlaybackRemote
import music.ai.recommend.platform.RemoteControlService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.db.getAppDatabase
import kotlinx.coroutines.withContext
import aimusic.composeapp.generated.resources.Res

enum class AppSection {
    Folders, Playlists, Settings
}

/** A track matched by AI search, with the calibrated score the list shows. */
data class ScoredSong(val song: Song, val score: Float)

enum class PlaybackMode {
    RepeatQueue, StopAfterQueue, RepeatOne, StopAfterTrack, Shuffle
}

class MusicViewModel : ViewModel(), PlaybackRemote {
    private val _currentSection = MutableStateFlow(AppSection.Folders)
    val currentSection: StateFlow<AppSection> = _currentSection.asStateFlow()

    private val _playbackMode = MutableStateFlow(PlaybackMode.RepeatQueue)
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private val _eqBands = MutableStateFlow<List<EqBand>>(emptyList())
    val eqBands: StateFlow<List<EqBand>> = _eqBands.asStateFlow()

    private val _eqPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val eqPresets: StateFlow<List<EqPreset>> = _eqPresets.asStateFlow()

    private val scanner = MusicScanner()
    private val player = MusicPlayer()
    private val aiScanner = AiScanner()
    private val textEncoder = ClapTextEncoder()
    // Shares the ViewModel's encoder: a second one would open its own ~126 MB session.
    private val smartAlbumBuilder = SmartAlbumBuilder(textEncoder)
    private val remoteControl = RemoteControlService(this)
    private val dailyMixBuilder = DailyMixBuilder()

    /**
     * Turns playback into finished listens. The player reports positions; this decides what counts
     * as a listen and what counts as a skip, which is what the playlist of the day is built from.
     */
    private val playTracker = PlayTracker { event -> recordListen(event) }
    private val db = getAppDatabase()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _musicFolderPath = MutableStateFlow("")
    val musicFolderPath: StateFlow<String> = _musicFolderPath.asStateFlow()

    private val _backgroundImageUri = MutableStateFlow<String?>(null)
    val backgroundImageUri: StateFlow<String?> = _backgroundImageUri.asStateFlow()

    private val _backgroundAlpha = MutableStateFlow(0.3f)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    private val _playbackStopped = MutableStateFlow(true)

    private val _volumePercent = MutableStateFlow(100)
    val volume: StateFlow<Int> = _volumePercent.asStateFlow()

    private val _aiScanProgress = MutableStateFlow(0f)
    val aiScanProgress: StateFlow<Float> = _aiScanProgress.asStateFlow()

    private val _aiScanStatus = MutableStateFlow("")
    val aiScanStatus: StateFlow<String> = _aiScanStatus.asStateFlow()

    private val _isAiScanning = MutableStateFlow(false)
    val isAiScanning: StateFlow<Boolean> = _isAiScanning.asStateFlow()

    private val _scannedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val scannedSongIds: StateFlow<Set<String>> = _scannedSongIds.asStateFlow()

    private val _dailyMix = MutableStateFlow<List<Song>>(emptyList())
    val dailyMix: StateFlow<List<Song>> = _dailyMix.asStateFlow()

    private val _dailyMixBuilding = MutableStateFlow(false)
    val dailyMixBuilding: StateFlow<Boolean> = _dailyMixBuilding.asStateFlow()

    private val _dailyMixOpen = MutableStateFlow(false)
    val dailyMixOpen: StateFlow<Boolean> = _dailyMixOpen.asStateFlow()

    private val _smartAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val smartAlbums: StateFlow<List<SmartAlbum>> = _smartAlbums.asStateFlow()

    private val _smartAlbumsBuilding = MutableStateFlow(false)
    val smartAlbumsBuilding: StateFlow<Boolean> = _smartAlbumsBuilding.asStateFlow()

    private val _smartAlbumsEpsScale = MutableStateFlow(1f)
    val smartAlbumsEpsScale: StateFlow<Float> = _smartAlbumsEpsScale.asStateFlow()

    private val _selectedSmartAlbum = MutableStateFlow<SmartAlbum?>(null)
    val selectedSmartAlbum: StateFlow<SmartAlbum?> = _selectedSmartAlbum.asStateFlow()

    /** Set when embeddings from an older, incorrect analysis had to be discarded on startup. */
    private val _analysisReset = MutableStateFlow(false)
    val analysisReset: StateFlow<Boolean> = _analysisReset.asStateFlow()

    private val _selectedFolder = MutableStateFlow<Folder?>(null)
    val selectedFolder: StateFlow<Folder?> = _selectedFolder.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _isAiSearchEnabled = MutableStateFlow(false)
    val isAiSearchEnabled: StateFlow<Boolean> = _isAiSearchEnabled.asStateFlow()

    // Ordered, because the displayed score saturates at the top of the range: two strong matches
    // both show 99% and sorting the UI by that number would put them in an arbitrary order.
    private val _aiSearchResults = MutableStateFlow<List<ScoredSong>>(emptyList())
    val aiSearchResults: StateFlow<List<ScoredSong>> = _aiSearchResults.asStateFlow()

    private val _favoriteSongPaths = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongPaths: StateFlow<Set<String>> = _favoriteSongPaths.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    private var allSongs: List<Song> = emptyList()
    private var progressJob: kotlinx.coroutines.Job? = null
    private var musicScanJob: kotlinx.coroutines.Job? = null
    private var aiSearchJob: kotlinx.coroutines.Job? = null
    private var smartAlbumsJob: kotlinx.coroutines.Job? = null
    private var dailyMixJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var stopRequested = false

    init {
        // Initialize EQ bands for Desktop VLC (10 bands)
        val freqs = listOf("60Hz", "170Hz", "310Hz", "600Hz", "1kHz", "3kHz", "6kHz", "12kHz", "14kHz", "16kHz")
        _eqBands.value = freqs.mapIndexed { index, f -> EqBand(index, f, 0f) }

        // Standard presets
        _eqPresets.value = listOf(
            EqPreset("Flat", List(10) { 0f }),
            EqPreset("Classical", listOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -1f, -1f, -2f)),
            EqPreset("Club", listOf(0f, 0f, 2f, 3f, 3f, 3f, 2f, 0f, 0f, 0f)),
            EqPreset("Dance", listOf(4f, 3f, 1f, 0f, 0f, -2f, -3f, -3f, 0f, 0f)),
            EqPreset("Full Bass", listOf(5f, 4f, 4f, 2f, 1f, -2f, -4f, -5f, -6f, -6f)),
            EqPreset("Full Treble", listOf(-6f, -6f, -6f, -4f, -1f, 2f, 5f, 5f, 6f, 6f)),
            EqPreset("Live", listOf(-2f, 0f, 1f, 2f, 2f, 2f, 1f, 1f, 1f, 1f)),
            EqPreset("Party", listOf(3f, 3f, 0f, 0f, 0f, 0f, 0f, 0f, 3f, 3f)),
            EqPreset("Pop", listOf(-1f, 2f, 3f, 3f, 2f, -1f, -1f, -1f, -1f, -1f)),
            EqPreset("Rock", listOf(3f, 2f, -3f, -4f, -1f, 2f, 4f, 4f, 5f, 5f)),
            EqPreset("Techno", listOf(4f, 3f, 0f, -3f, -3f, 0f, 4f, 5f, 5f, 4f))
        )

        player.onFinished = {
            println("ViewModel: Song finished, scheduling next track...")
            viewModelScope.launch {
                delay(500) 
                next(isAutomatic = true)
            }
        }
        
        // Start loading sequence
        viewModelScope.launch {
            loadAllPersistedDataSync() // 1. Load settings first
            loadMusic().join()         // 2. Wait for scan to finish with correct path
            loadScannedIds()           // 3. Then load AI state
        }
    }

    private suspend fun loadAllPersistedDataSync() {
        val dao = db.musicDao()
        
        // 1. Settings
        dao.loadSettings()?.let { s ->
            _musicFolderPath.value = s.musicFolderPath
            _isDarkTheme.value = s.isDarkTheme
            _backgroundImageUri.value = s.backgroundImageUri
            _backgroundAlpha.value = s.backgroundAlpha
            _volumePercent.value = s.volumePercent.coerceIn(0, 100)
            player.volumePercent = _volumePercent.value
            _smartAlbumsEpsScale.value = s.smartAlbumsEpsScale.coerceIn(
                SmartAlbumClustering.MIN_EPS_SCALE, SmartAlbumClustering.MAX_EPS_SCALE
            )
            _eqBands.value = _eqBands.value.mapIndexed { i, band ->
                val level = s.eqLevels.getOrElse(i) { 0f }
                player.setEqBand(i, level)
                band.copy(level = level)
            }
            _eqPresets.value = _eqPresets.value + s.customEqPresets
        }
        
        // 2. Favorites
        _favoriteSongPaths.value = dao.loadFavorites()
    }


    private fun saveSettings() {
        viewModelScope.launch(Dispatchers.Default) {
            val settings = AppSettings(
                musicFolderPath = _musicFolderPath.value,
                isDarkTheme = _isDarkTheme.value,
                backgroundImageUri = _backgroundImageUri.value,
                backgroundAlpha = _backgroundAlpha.value,
                eqLevels = _eqBands.value.map { it.level },
                customEqPresets = _eqPresets.value.filter { it.isCustom },
                smartAlbumsEpsScale = _smartAlbumsEpsScale.value,
                volumePercent = _volumePercent.value
            )
            db.musicDao().saveSettings(settings)
        }
    }

    private fun savePlaylists() {
        viewModelScope.launch(Dispatchers.Default) {
            db.musicDao().savePlaylists(_playlists.value)
        }
    }

    private fun saveFavorites() {
        viewModelScope.launch(Dispatchers.Default) {
            db.musicDao().saveFavorites(_favoriteSongPaths.value)
        }
    }

    private fun loadScannedIds() {
        viewModelScope.launch {
            try {
                val paths = db.musicDao().getAllEmbeddings().map { it.path }.toSet()
                _scannedSongIds.value = paths
                _analysisReset.value = db.musicDao().analysisWasReset()
                refreshSmartAlbums()
                refreshDailyMix()
                prunePlayHistory()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSection(section: AppSection) {
        _currentSection.value = section
        if (section != AppSection.Folders) {
            _selectedFolder.value = null
        }
    }

    fun cyclePlaybackMode() {
        val modes = PlaybackMode.entries
        val nextIndex = (modes.indexOf(_playbackMode.value) + 1) % modes.size
        _playbackMode.value = modes[nextIndex]
    }

    fun setEqBandLevel(index: Int, level: Float) {
        player.setEqBand(index, level)
        _eqBands.value = _eqBands.value.map { 
            if (it.index == index) it.copy(level = level) else it 
        }
        saveSettings()
    }

    fun applyPreset(preset: EqPreset) {
        preset.levels.forEachIndexed { index, level ->
            player.setEqBand(index, level)
        }
        _eqBands.value = _eqBands.value.mapIndexed { index, band ->
            band.copy(level = preset.levels.getOrElse(index) { 0f })
        }
        saveSettings()
    }

    fun saveCustomPreset(name: String) {
        if (name.isBlank()) return
        val currentLevels = _eqBands.value.map { it.level }
        val newPreset = EqPreset(name, currentLevels, isCustom = true)
        _eqPresets.value = _eqPresets.value + newPreset
        saveSettings()
    }

    fun deletePreset(preset: EqPreset) {
        if (preset.isCustom) {
            _eqPresets.value = _eqPresets.value.filter { it.name != preset.name }
            saveSettings()
        }
    }

    fun selectFolder(folder: Folder?) {
        _selectedFolder.value = folder
    }

    fun updateMusicPath(path: String) {
        _musicFolderPath.value = path
        allSongs = emptyList() // Clear stale data immediately
        _folders.value = emptyList()
        saveSettings()
        loadMusic()
    }

    fun loadMusic(): kotlinx.coroutines.Job {
        musicScanJob?.cancel() // Cancel existing scan if any
        _isScanning.value = true
        
        val job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val path = _musicFolderPath.value
                println("MusicViewModel: Starting scan for folder: ${if (path.isEmpty()) "Default (~/Music)" else path}")
                
                val scannedFolders = if (path.isEmpty()) scanner.scanMusic() else scanner.scanCustomPath(path)
                val songs = scannedFolders.flatMap { it.songs }
                
                synchronized(this@MusicViewModel) {
                    allSongs = songs
                }
                
                withContext(Dispatchers.Main) {
                    _folders.value = scannedFolders
                    _isScanning.value = false
                    _playlists.value = db.musicDao().loadPlaylists(allSongs)
                    println("MusicViewModel: Scan complete. Found ${scannedFolders.size} folders, ${allSongs.size} total songs.")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    println("MusicViewModel: Scan cancelled.")
                } else {
                    e.printStackTrace()
                }
                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                }
            }
        }
        musicScanJob = job
        return job
    }

    fun playSong(song: Song, fromList: List<Song> = emptyList()) {
        if (fromList.isNotEmpty()) {
            _queue.value = fromList
        } else if (!_queue.value.any { it.id == song.id }) {
            _queue.value = _queue.value + song
        }
        
        _currentSong.value = song
        player.play(song)
        _isPlaying.value = true
        _playbackStopped.value = false
        playTracker.started(song.path, song.duration, System.currentTimeMillis())
        startProgressTracker()
    }

    fun playNext(song: Song) {
        val currentQueue = _queue.value.toMutableList()
        val currentIndex = currentQueue.indexOfFirst { it.id == _currentSong.value?.id }
        
        // Remove if already in queue to re-position it
        currentQueue.removeAll { it.id == song.id }
        
        if (currentIndex != -1) {
            currentQueue.add(currentIndex + 1, song)
        } else {
            currentQueue.add(0, song)
        }
        _queue.value = currentQueue
    }

    fun addToEndOfQueue(song: Song) {
        if (!_queue.value.any { it.id == song.id }) {
            _queue.value = _queue.value + song
        }
    }

    fun deleteSong(song: Song) {
        // Remove from current state
        _folders.value = _folders.value.map { folder ->
            folder.copy(songs = folder.songs.filter { it.id != song.id })
        }.filter { it.songs.isNotEmpty() }
        
        _queue.value = _queue.value.filter { it.id != song.id }
        
        if (_currentSong.value?.id == song.id) {
            next()
        }

        // Try delete from disk (platform specific ideally, but we have path)
        try {
            val file = java.io.File(song.path)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        player.resume()
        _isPlaying.value = true
        _playbackStopped.value = false
    }

    fun pause() {
        player.pause()
        _isPlaying.value = false
    }

    fun next(isAutomatic: Boolean = false) {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return

        val currentIndex = currentQueue.indexOfFirst { it.id == _currentSong.value?.id }
        
        when (_playbackMode.value) {
            PlaybackMode.RepeatOne -> {
                _currentSong.value?.let { playSong(it) }
            }
            PlaybackMode.Shuffle -> {
                val nextSong = currentQueue.random()
                playSong(nextSong)
            }
            PlaybackMode.StopAfterTrack -> {
                if (!isAutomatic) {
                    val nextIndex = (currentIndex + 1) % currentQueue.size
                    playSong(currentQueue[nextIndex])
                } else {
                    _isPlaying.value = false
                }
            }
            PlaybackMode.StopAfterQueue -> {
                if (currentIndex < currentQueue.size - 1) {
                    playSong(currentQueue[currentIndex + 1])
                } else {
                    _isPlaying.value = false
                }
            }
            PlaybackMode.RepeatQueue -> {
                val nextIndex = (currentIndex + 1) % currentQueue.size
                playSong(currentQueue[nextIndex])
            }
        }
    }

    fun previous() {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return
        val currentIndex = currentQueue.indexOfFirst { it.id == _currentSong.value?.id }
        val prevIndex = if (currentIndex <= 0) currentQueue.size - 1 else currentIndex - 1
        playSong(currentQueue[prevIndex])
    }

    fun seekTo(position: Long) {
        player.seekTo(position)
        _currentPosition.value = position
    }

    fun seekRelative(offsetMs: Long) {
        val newPos = (_currentPosition.value + offsetMs).coerceIn(0, _duration.value)
        seekTo(newPos)
    }

    fun togglePlayPause() {
        if (_isPlaying.value) pause() else resume()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                if (_isPlaying.value) {
                    _currentPosition.value = player.currentPosition
                    _duration.value = player.duration
                    playTracker.progress(player.currentPosition, player.duration)
                }
                delay(1000)
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val current = _queue.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _queue.value = current
        }
    }

    fun clearQueue() {
        _queue.value = emptyList()
    }

    fun createPlaylist(name: String) {
        val newPlaylist = Playlist(id = System.currentTimeMillis(), name = name, songs = emptyList())
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
    }

    fun deletePlaylist(playlist: Playlist) {
        _playlists.value = _playlists.value.filter { it.id != playlist.id }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = null
        }
        savePlaylists()
    }

    fun selectPlaylist(playlist: Playlist?) {
        _selectedPlaylist.value = playlist
    }

    fun addSongToPlaylist(playlist: Playlist, song: Song) {
        _playlists.value = _playlists.value.map { 
            if (it.id == playlist.id) {
                if (!it.songs.contains(song)) it.copy(songs = it.songs + song) else it
            } else it 
        }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = _playlists.value.find { it.id == playlist.id }
        }
        savePlaylists()
    }

    fun removeSongFromPlaylist(playlist: Playlist, song: Song) {
        _playlists.value = _playlists.value.map {
            if (it.id == playlist.id) {
                it.copy(songs = it.songs.filter { s -> s.id != song.id })
            } else it
        }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = _playlists.value.find { it.id == playlist.id }
        }
        savePlaylists()
    }

    fun toggleFavorite(song: Song) {
        val current = _favoriteSongPaths.value.toMutableSet()
        if (current.contains(song.path)) {
            current.remove(song.path)
        } else {
            current.add(song.path)
        }
        _favoriteSongPaths.value = current
        saveFavorites()
    }

    fun isFavorite(songPath: String): Boolean {
        return _favoriteSongPaths.value.contains(songPath)
    }

    fun getFavoriteSongs(): List<Song> {
        return allSongs.filter { _favoriteSongPaths.value.contains(it.path) }
    }

    fun setBackgroundImage(uri: String?) {
        _backgroundImageUri.value = uri
        saveSettings()
    }

    fun setBackgroundAlpha(alpha: Float) {
        _backgroundAlpha.value = alpha
        saveSettings()
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
        saveSettings()
    }

    private fun formatEtr(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "(${h}h ${m}m ${s}s left)"
            m > 0 -> "(${m}m ${s}s left)"
            else -> "(${s}s left)"
        }
    }

    fun startAiScan() {
        if (_isAiScanning.value) return
        
        val songsToScan = synchronized(this) { allSongs.toList() }
        if (songsToScan.isEmpty()) {
            _aiScanStatus.value = "No songs found to scan. Please check your music folder."
            return
        }

        println("MusicViewModel: Starting AI Scan for ${songsToScan.size} songs. First song path: ${songsToScan.firstOrNull()?.path}")

        _isAiScanning.value = true
        stopRequested = false
        _aiScanStatus.value = "Starting AI Analysis..."
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                aiScanner.scanSongs(songsToScan) { progress, status, scannedCount, etr ->
                    viewModelScope.launch {
                        _aiScanProgress.value = progress
                        val etrText = formatEtr(etr)
                        _aiScanStatus.value = if (etrText.isNotEmpty()) "$status $etrText" else status
                    }
                }
                
                withContext(Dispatchers.Main) {
                    _aiScanStatus.value = if (stopRequested) "Analysis Stopped" else "Analysis Complete"
                    // A scan the user stopped has not replaced what the version bump discarded,
                    // so the notice asking them to rescan still applies.
                    if (!stopRequested) {
                        _aiScanProgress.value = 1f
                        db.musicDao().acknowledgeAnalysisReset()
                    }
                    loadScannedIds()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _aiScanStatus.value = "Error: ${e.message}"
                }
            } finally {
                // The one place the flag is cleared, so the UI never offers to start a second
                // scan while the first is still winding down.
                withContext(NonCancellable + Dispatchers.Main) {
                    _isAiScanning.value = false
                }
            }
        }
    }

    /**
     * Asks the scan to stop and leaves the flag to the scan coroutine.
     *
     * Clearing it here put the UI back into "not scanning" while the scanner was still finishing
     * the track in flight and closing its session, so "Start AI Scan" was live again and a second
     * scan could be started on top of the first.
     */
    fun stopAiScan() {
        aiScanner.stop()
        stopRequested = true
        _aiScanStatus.value = "Stopping…"
    }

    fun clearAiData() {
        viewModelScope.launch {
            db.musicDao().clearAllEmbeddings()
            _scannedSongIds.value = emptySet()
            _analysisReset.value = false
            _smartAlbums.value = emptyList()
            _selectedSmartAlbum.value = null
            _aiScanStatus.value = "AI Data Cleared"
            _aiScanProgress.value = 0f
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (_isAiSearchEnabled.value && query.length > 2) {
            performAiSearch(query)
        } else {
            _aiSearchResults.value = emptyList()
        }
    }

    fun toggleAiSearch() {
        _isAiSearchEnabled.value = !_isAiSearchEnabled.value
        if (_isAiSearchEnabled.value) {
            _selectedFolder.value = null
            _selectedPlaylist.value = null
            if (_searchQuery.value.length > 2) {
                performAiSearch(_searchQuery.value)
            }
        } else {
            _aiSearchResults.value = emptyList()
        }
    }

    /**
     * CLAP matches for [query] across the whole library.
     *
     * Every keystroke cancels the previous search, so the 126 MB text model runs once the typing
     * pauses rather than once per character, and an earlier query can no longer finish last and
     * overwrite the results of a later one.
     */
    private fun performAiSearch(query: String) {
        aiSearchJob?.cancel()
        aiSearchJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(SEARCH_DEBOUNCE_MS)

                // Without a usable query vector every score would be identical noise.
                val queryVector = textEncoder.encode(query) ?: return@launch
                val embeddings = db.musicDao().getAllEmbeddings()
                if (embeddings.isEmpty()) {
                    withContext(Dispatchers.Main) { _aiSearchResults.value = emptyList() }
                    return@launch
                }

                val songsByPath = allSongs.associateBy { it.path }
                val scored = embeddings.mapNotNull { emb ->
                    val song = songsByPath[emb.path] ?: return@mapNotNull null
                    // An embedding of another size is from a different model and cannot be
                    // compared; indexing into it by the query's length would throw.
                    if (emb.vector.size != queryVector.size) return@mapNotNull null
                    song to cosineSimilarity(queryVector, emb.vector)
                }
                if (scored.isEmpty()) {
                    withContext(Dispatchers.Main) { _aiSearchResults.value = emptyList() }
                    return@launch
                }

                // Where the bulk of the library sits for this particular query. A fixed threshold
                // cannot work: the absolute cosine depends on the wording and on what is in the
                // library, so what marks a match is standing out from the rest. Measured over a
                // 40-track library the cut lands at 0.40 for "celtic harp music", which that
                // library has plenty of, and at 0.25 for "hip hop beat with rap vocals", which it
                // has none of.
                val mean = scored.sumOf { it.second.toDouble() } / scored.size
                val deviation = kotlin.math.sqrt(
                    scored.sumOf { (it.second - mean) * (it.second - mean) } / scored.size
                )
                val cut = maxOf(mean + deviation, MIN_SEARCH_SIMILARITY.toDouble())

                val matches = scored
                    .filter { it.second >= cut }
                    .sortedByDescending { it.second }
                    .take(AI_SEARCH_RESULTS)
                    .map { ScoredSong(it.first, mapSimilarityToDisplay(it.second)) }

                withContext(Dispatchers.Main) {
                    _aiSearchResults.value = matches
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Both vectors are stored unit-length, so the dot product is already the cosine. */
    private fun cosineSimilarity(query: FloatArray, stored: List<Float>): Float {
        var dot = 0f
        for (i in query.indices) dot += query[i] * stored[i]
        return dot
    }

    /**
     * Turns a text-audio cosine into the percentage the list shows.
     *
     * Calibrated for the corrected features: measured over this checkpoint an unrelated track
     * scores around 0.0-0.17 against a query and a good match 0.45-0.65. Showing the raw cosine
     * instead reported every unrelated track as a double-digit match.
     */
    private fun mapSimilarityToDisplay(rawSimilarity: Float): Float {
        return when {
            rawSimilarity >= STRONG_SIMILARITY -> 0.99f
            rawSimilarity <= MIN_SEARCH_SIMILARITY -> 0f
            else -> (rawSimilarity - MIN_SEARCH_SIMILARITY) / (STRONG_SIMILARITY - MIN_SEARCH_SIMILARITY)
        }.coerceIn(0f, 1f)
    }

    /**
     * Regroups the library by sound. Cheap when nothing has changed — the builder keeps the last
     * result under a signature of the analysed tracks — so this can run on every library reload.
     */
    fun refreshSmartAlbums(rebuild: Boolean = false) {
        val library = allSongs
        if (library.isEmpty()) return
        smartAlbumsJob?.cancel()
        smartAlbumsJob = viewModelScope.launch {
            _smartAlbumsBuilding.value = true
            try {
                val built = smartAlbumBuilder.albums(library, _smartAlbumsEpsScale.value, rebuild)
                _smartAlbums.value = built
                // The open album is a snapshot; after a rebuild it must point at the new one.
                _selectedSmartAlbum.value = _selectedSmartAlbum.value?.let { open ->
                    built.firstOrNull { it.id == open.id }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (smartAlbumsJob === kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]) {
                    _smartAlbumsBuilding.value = false
                }
            }
        }
    }

    fun setSmartAlbumsEpsScale(scale: Float) {
        val snapped = (scale.coerceIn(SmartAlbumClustering.MIN_EPS_SCALE, SmartAlbumClustering.MAX_EPS_SCALE) * 20)
            .let { kotlin.math.round(it) / 20f }
        if (snapped == _smartAlbumsEpsScale.value) return
        _smartAlbumsEpsScale.value = snapped
        saveSettings()
        refreshSmartAlbums()
    }

    /**
     * Builds today's playlist, then sleeps until midnight and builds the next one.
     *
     * @param rebuild asks for a different playlist for today, on the user's request.
     */
    fun refreshDailyMix(rebuild: Boolean = false) {
        val library = allSongs
        if (library.isEmpty()) return
        dailyMixJob?.cancel()
        dailyMixJob = viewModelScope.launch {
            _dailyMixBuilding.value = true
            try {
                val playlist = dailyMixBuilder.playlist(library, _favoriteSongPaths.value, rebuild)
                _dailyMix.value = playlist.songs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (dailyMixJob === kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]) {
                    _dailyMixBuilding.value = false
                }
            }
            delay(dailyMixBuilder.millisUntilNextDay())
            refreshDailyMix()
        }
    }

    fun playDailyMix() {
        val mix = _dailyMix.value
        if (mix.isNotEmpty()) playSong(mix.first(), mix)
    }

    fun openDailyMix(open: Boolean) {
        _dailyMixOpen.value = open
    }

    /**
     * Drops listens older than a year, once per launch.
     *
     * They carry no weight in the taste profile any more — it looks back ninety days and halves
     * every three weeks — so keeping them only grows the log.
     */
    private fun prunePlayHistory() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                db.musicDao().prunePlayEvents(System.currentTimeMillis() - HISTORY_KEEP_DAYS * DAY_MS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun recordListen(event: PlayEvent) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                db.musicDao().appendPlayEvent(
                    PlayEventEntity(event.songPath, event.playedAt, event.playedMs, event.durationMs)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun selectSmartAlbum(album: SmartAlbum?) {
        _selectedSmartAlbum.value = album
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
            _aiSearchResults.value = emptyList()
            _isAiSearchEnabled.value = false
        }
    }

    fun createSmartPlaylist(seedSong: Song) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val embeddings = db.musicDao().getAllEmbeddings()
                val seedEmb = embeddings.find { it.path == seedSong.path }?.vector ?: return@launch
                
                // Use a map for faster lookup of songs by path
                val songMap = allSongs.associateBy { it.path }
                
                val similarSongs = embeddings
                    .filter {
                        it.path != seedSong.path && songMap.containsKey(it.path) &&
                            it.vector.size == seedEmb.size
                    }
                    .map { emb ->
                        // Since vectors are normalized, dot product = cosine similarity
                        var dotProduct = 0f
                        for (i in seedEmb.indices) {
                            dotProduct += seedEmb[i] * emb.vector[i]
                        }
                        emb.path to dotProduct
                    }
                    .sortedByDescending { it.second }
                    .take(20)
                    .mapNotNull { songMap[it.first] }
                
                withContext(Dispatchers.Main) {
                    val currentQueue = _queue.value.toMutableList()
                    val currentIndex = currentQueue.indexOfFirst { it.path == seedSong.path }
                    
                    if (currentIndex != -1) {
                        // Insert after current song
                        currentQueue.addAll(currentIndex + 1, similarSongs)
                        _queue.value = currentQueue
                    } else {
                        // Add seed and similar to end
                        _queue.value = _queue.value + listOf(seedSong) + similarSongs
                    }
                    
                    playSong(seedSong)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private companion object {
        /** A pause in typing, so the text model runs once per query rather than per character. */
        const val SEARCH_DEBOUNCE_MS = 250L

        /** Below this a track is not a match for the query under any reading. */
        const val MIN_SEARCH_SIMILARITY = 0.15f

        /** Where a match is unambiguous, and the displayed score saturates. */
        const val STRONG_SIMILARITY = 0.45f

        const val AI_SEARCH_RESULTS = 50

        /** How long listens are kept at all; the taste profile only looks back ninety days. */
        const val HISTORY_KEEP_DAYS = 365L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    // ---------------------------------------------------------------- PlaybackRemote

    override val nowPlaying: StateFlow<Song?> get() = currentSong
    override val playing: StateFlow<Boolean> get() = isPlaying
    override val stopped: StateFlow<Boolean> = _playbackStopped.asStateFlow()
    override val positionMs: StateFlow<Long> get() = currentPosition
    override val durationMs: StateFlow<Long> get() = duration
    override val volumePercent: StateFlow<Int> get() = volume

    override val canGoNext: StateFlow<Boolean> =
        _queue.map { it.size > 1 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    override val canGoPrevious: StateFlow<Boolean> get() = canGoNext

    /** After a stop MPRIS expects Play to start the track again, not to resume a dead player. */
    override fun remotePlay() {
        val song = _currentSong.value
        if (_playbackStopped.value && song != null) playSong(song) else resume()
    }
    override fun remotePause() = pause()
    override fun remotePlayPause() = togglePlayPause()
    override fun remoteNext() = next()
    override fun remotePrevious() = previous()
    override fun remoteSeekBy(offsetMs: Long) = seekRelative(offsetMs)
    override fun remoteSeekTo(positionMs: Long) = seekTo(positionMs.coerceIn(0, _duration.value))

    override fun remoteStop() {
        playTracker.finished(System.currentTimeMillis())
        player.stop()
        _isPlaying.value = false
        _playbackStopped.value = true
        _currentPosition.value = 0L
    }

    override fun remoteSetVolume(percent: Int) = setVolume(percent)

    override fun remoteOpenUri(uri: String): Boolean {
        val path = when {
            uri.startsWith("file://") -> runCatching { java.net.URI(uri).path }.getOrNull()
            uri.startsWith("/") -> uri
            else -> null
        } ?: return false
        // Only tracks the library already knows: playing an arbitrary path would leave the queue,
        // the favourites and the AI state describing something that is not in the library.
        val song = allSongs.firstOrNull { it.path == path } ?: return false
        playSong(song, allSongs)
        return true
    }

    /** Raising the window is the host's business; App.kt installs the handler that does it. */
    var onRaiseRequested: (() -> Unit)? = null

    /**
     * Publishes playback for external control.
     *
     * Called by the host once the ViewModel exists, never from `init`: the remote reads the flows
     * declared in this section, and a constructor runs property initialisers in declaration order,
     * so starting it there left those fields null. The resulting NullPointerException happened
     * inside a coroutine and showed up only as state that silently never reached the bus.
     */
    fun startRemoteControl() {
        remoteControl.start()
    }

    override fun remoteRaise() {
        onRaiseRequested?.invoke()
    }

    /**
     * Silences playback and restores the previous level on the next press.
     *
     * The level is remembered rather than snapped back to a default, so unmuting returns to
     * whatever the user had set — including a level reached from outside over MPRIS.
     */
    fun toggleMute() {
        val current = _volumePercent.value
        if (current > 0) {
            volumeBeforeMute = current
            setVolume(0)
        } else {
            setVolume(volumeBeforeMute.coerceAtLeast(1))
        }
    }

    private var volumeBeforeMute = 100

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == _volumePercent.value) return
        player.volumePercent = clamped
        _volumePercent.value = clamped
        saveSettings()
    }

    fun release() {
        // A listen in flight when the window closes is still a listen.
        playTracker.finished(System.currentTimeMillis())
        remoteControl.stop()
        player.release()
        // Both hold an ONNX session — ~280 MB for the audio model, ~126 MB for the text one — and
        // neither is freed by the garbage collector, since the memory is native.
        aiScanner.release()
        textEncoder.release()
    }
}
