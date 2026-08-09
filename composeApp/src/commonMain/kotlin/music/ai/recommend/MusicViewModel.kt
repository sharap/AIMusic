package music.ai.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song
import music.ai.recommend.model.Playlist
import music.ai.recommend.platform.MusicScanner
import music.ai.recommend.platform.MusicPlayer
import kotlinx.coroutines.withContext

enum class AppSection {
    Folders, Playlists, Settings
}

enum class PlaybackMode {
    RepeatQueue, StopAfterQueue, RepeatOne, StopAfterTrack, Shuffle
}

data class EqBand(val index: Int, val freq: String, val level: Float)
data class EqPreset(val name: String, val levels: List<Float>, val isCustom: Boolean = false)

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

    private val _scannedSongIds = MutableStateFlow<Set<Long>>(emptySet())
    val scannedSongIds: StateFlow<Set<Long>> = _scannedSongIds.asStateFlow()

    private val _selectedFolder = MutableStateFlow<Folder?>(null)
    val selectedFolder: StateFlow<Folder?> = _selectedFolder.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private var allSongs: List<Song> = emptyList()
    private var progressJob: kotlinx.coroutines.Job? = null

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
        loadMusic()
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
    }

    fun applyPreset(preset: EqPreset) {
        preset.levels.forEachIndexed { index, level ->
            player.setEqBand(index, level)
        }
        _eqBands.value = _eqBands.value.mapIndexed { index, band ->
            band.copy(level = preset.levels.getOrElse(index) { 0f })
        }
    }

    fun saveCustomPreset(name: String) {
        if (name.isBlank()) return
        val currentLevels = _eqBands.value.map { it.level }
        val newPreset = EqPreset(name, currentLevels, isCustom = true)
        _eqPresets.value = _eqPresets.value + newPreset
    }

    fun deletePreset(preset: EqPreset) {
        if (preset.isCustom) {
            _eqPresets.value = _eqPresets.value.filter { it.name != preset.name }
        }
    }

    fun selectFolder(folder: Folder?) {
        _selectedFolder.value = folder
    }

    fun updateMusicPath(path: String) {
        _musicFolderPath.value = path
        loadMusic()
    }

    fun loadMusic() {
        if (_isScanning.value) return
        _isScanning.value = true
        
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val path = _musicFolderPath.value
                val scannedFolders = if (path.isEmpty()) scanner.scanMusic() else scanner.scanCustomPath(path)
                allSongs = scannedFolders.flatMap { it.songs }
                
                withContext(Dispatchers.Main) {
                    _folders.value = scannedFolders
                    _isScanning.value = false
                    println("Found ${scannedFolders.size} folders")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                }
            }
        }
    }

    fun playSong(song: Song, fromList: List<Song> = emptyList()) {
        if (fromList.isNotEmpty()) {
            _queue.value = fromList
        } else if (!_queue.value.contains(song)) {
            _queue.value = _queue.value + song
        }
        
        _currentSong.value = song
        player.play(song)
        _isPlaying.value = true
        startProgressTracker()
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

    fun setBackgroundImage(uri: String?) {
        _backgroundImageUri.value = uri
    }

    fun setBackgroundAlpha(alpha: Float) {
        _backgroundAlpha.value = alpha
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    fun startAiScan() {
        if (_isAiScanning.value) return
        _isAiScanning.value = true
        _aiScanStatus.value = "Starting AI Analysis..."
        
        viewModelScope.launch(Dispatchers.Default) {
            val songsToScan = allSongs.filter { !scannedSongIds.value.contains(it.id) }
            songsToScan.forEachIndexed { index, song ->
                if (!_isAiScanning.value) return@launch
                
                withContext(Dispatchers.Main) {
                    _aiScanProgress.value = (index + 1).toFloat() / songsToScan.size
                    _aiScanStatus.value = "Analyzing: ${song.title}"
                }
                
                delay(500) 
                
                withContext(Dispatchers.Main) {
                    _scannedSongIds.value = _scannedSongIds.value + song.id
                }
            }
            
            withContext(Dispatchers.Main) {
                _isAiScanning.value = false
                _aiScanStatus.value = "Analysis Complete"
                _aiScanProgress.value = 1f
            }
        }
    }

    fun stopAiScan() {
        _isAiScanning.value = false
        _aiScanStatus.value = "Analysis Stopped"
    }

    fun clearAiData() {
        _scannedSongIds.value = emptySet()
        _aiScanStatus.value = ""
        _aiScanProgress.value = 0f
    }
}
