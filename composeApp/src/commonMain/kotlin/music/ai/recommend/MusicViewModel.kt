package music.ai.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import music.ai.recommend.db.getAppDatabase
import kotlinx.coroutines.withContext
import aimusic.composeapp.generated.resources.Res

enum class AppSection {
    Folders, Playlists, Settings
}

enum class PlaybackMode {
    RepeatQueue, StopAfterQueue, RepeatOne, StopAfterTrack, Shuffle
}

class MusicViewModel : ViewModel() {
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

    private val _aiScanProgress = MutableStateFlow(0f)
    val aiScanProgress: StateFlow<Float> = _aiScanProgress.asStateFlow()

    private val _aiScanStatus = MutableStateFlow("")
    val aiScanStatus: StateFlow<String> = _aiScanStatus.asStateFlow()

    private val _isAiScanning = MutableStateFlow(false)
    val isAiScanning: StateFlow<Boolean> = _isAiScanning.asStateFlow()

    private val _scannedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val scannedSongIds: StateFlow<Set<String>> = _scannedSongIds.asStateFlow()

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

    private val _aiSearchRankings = MutableStateFlow<Map<String, Float>>(emptyMap())
    val aiSearchRankings: StateFlow<Map<String, Float>> = _aiSearchRankings.asStateFlow()

    private val _favoriteSongPaths = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongPaths: StateFlow<Set<String>> = _favoriteSongPaths.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedPlaylist: StateFlow<Playlist?> = _selectedPlaylist.asStateFlow()

    private var allSongs: List<Song> = emptyList()
    private var progressJob: kotlinx.coroutines.Job? = null
    private var musicScanJob: kotlinx.coroutines.Job? = null

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
                customEqPresets = _eqPresets.value.filter { it.isCustom }
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
                    _isAiScanning.value = false
                    _aiScanStatus.value = "Analysis Complete"
                    _aiScanProgress.value = 1f
                    loadScannedIds()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isAiScanning.value = false
                    _aiScanStatus.value = "Error: ${e.message}"
                }
            }
        }
    }

    fun stopAiScan() {
        aiScanner.stop()
        _isAiScanning.value = false
        _aiScanStatus.value = "Analysis Stopped"
    }

    fun clearAiData() {
        viewModelScope.launch {
            db.musicDao().clearAllEmbeddings()
            _scannedSongIds.value = emptySet()
            _aiScanStatus.value = "AI Data Cleared"
            _aiScanProgress.value = 0f
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (_isAiSearchEnabled.value && query.length > 2) {
            performAiSearch(query)
        } else {
            _aiSearchRankings.value = emptyMap()
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
            _aiSearchRankings.value = emptyMap()
        }
    }

    private fun performAiSearch(query: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val queryVector = textEncoder.encode(query) ?: return@launch
                val embeddings = db.musicDao().getAllEmbeddings()
                
                val rankings = embeddings.associate { emb ->
                    var dotProduct = 0f
                    for (i in queryVector.indices) {
                        dotProduct += queryVector[i] * emb.vector[i]
                    }
                    emb.path to dotProduct
                }
                
                withContext(Dispatchers.Main) {
                    _aiSearchRankings.value = rankings
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
            _aiSearchRankings.value = emptyMap()
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
                    .filter { it.path != seedSong.path && songMap.containsKey(it.path) }
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

    fun release() {
        player.release()
    }
}
