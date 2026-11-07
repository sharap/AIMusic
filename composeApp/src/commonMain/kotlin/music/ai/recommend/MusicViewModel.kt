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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import music.ai.recommend.model.*
import music.ai.recommend.platform.MusicScanner
import music.ai.recommend.platform.MusicPlayer
import music.ai.recommend.platform.AiScanner
import music.ai.recommend.platform.ClapTextEncoder
import music.ai.recommend.platform.SmartAlbumBuilder
import music.ai.recommend.platform.DailyMixBuilder
import music.ai.recommend.platform.ModelDownloader
import music.ai.recommend.platform.ModelProgress
import music.ai.recommend.history.PlayEvent
import music.ai.recommend.history.PlayTracker
import music.ai.recommend.db.PlayEventEntity
import music.ai.recommend.platform.PlaybackRemote
import music.ai.recommend.platform.RemoteControlService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.ai.SmartAlbumClustering
import music.ai.recommend.db.getAppDatabase
import music.ai.recommend.sync.FileSyncFolder
import music.ai.recommend.sync.DaoVectorStore
import music.ai.recommend.sync.GcmSealing
import music.ai.recommend.sync.M3uShelf
import music.ai.recommend.sync.Sealing
import music.ai.recommend.sync.PathMixShelf
import music.ai.recommend.sync.PlaylistLog
import music.ai.recommend.sync.PlaylistOp
import music.ai.recommend.sync.PlayLogStore
import music.ai.recommend.sync.SyncCoordinator
import music.ai.recommend.sync.SyncFolder
import music.ai.recommend.sync.SyncIdentity
import music.ai.recommend.sync.SyncState
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
    private val models = ModelDownloader()

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

    val modelProgress: StateFlow<ModelProgress> = models.progress

    private val _modelsReady = MutableStateFlow(false)
    val modelsReady: StateFlow<Boolean> = _modelsReady.asStateFlow()

    private val _modelsPendingBytes = MutableStateFlow(0L)
    val modelsPendingBytes: StateFlow<Long> = _modelsPendingBytes.asStateFlow()

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

    // ---------------------------------------------------------------------------- sync
    //
    // Declared above `init` rather than beside the functions that use them, further down. Kotlin
    // initialises properties in declaration order and runs `init` among them, so a flow declared
    // below an init block that reads it is still null when it is read. On the phone that came out
    // as an app which would not open at all, and this is the same language.

    /** What the settings screen shows about sync, and all it needs to know. */
    data class SyncSummary(
        /** The folder as a person would recognise it, or null when none has been chosen. */
        val folder: String? = null,
        /** The first characters of this device's id, enough to tell two devices apart by eye. */
        val deviceId: String = "",
        val sent: Int = 0,
        val takenIn: Int = 0,
        /** Embeddings offered to the other devices, and taken from them. */
        val vectorsSent: Int = 0,
        val vectorsTakenIn: Int = 0,
        /** True while a library's worth of vectors is still working its way across. */
        val vectorsWaiting: Boolean = false,
        /** Playlist and favourite changes offered to the other devices, and taken from them. */
        val changesSent: Int = 0,
        val changesTakenIn: Int = 0,
        /** Playlists of the day offered to the other devices, and taken from them. */
        val mixSent: Int = 0,
        val mixTakenIn: Int = 0,
        /** Whether what this machine writes into the folder is sealed; see [GcmSealing]. */
        val sealed: Boolean = false,
        /**
         * Sealed batches met in the folder that would not open.
         *
         * Shown because it is the only way this fails: the phrase typed here differs from the one
         * typed there, and nothing else would ever say so — a batch that does not open is counted
         * as unreadable and stepped over.
         */
        val unopened: Int = 0,
        /** Listens about tracks this library has not got yet; they wait rather than being lost. */
        val waiting: Int = 0,
        val busy: Boolean = false,
        val lastRunAt: Long = 0L
    )

    private val _syncSummary = MutableStateFlow(SyncSummary())
    val syncSummary: StateFlow<SyncSummary> = _syncSummary.asStateFlow()

    private val _syncFolderPath = MutableStateFlow("")
    val syncFolderPath: StateFlow<String> = _syncFolderPath.asStateFlow()

    private val _syncPhrase = MutableStateFlow("")

    /** The phrase in use and what was made from it, so a key is derived once and not per pass. */
    private var sealingInUse: Pair<String, Sealing>? = null

    /**
     * What seals what this machine writes and opens what it reads, or null while no phrase is set.
     *
     * Asked on every pass rather than held, because the owner can set, correct or clear the phrase
     * while the app is running — and what is cached is the expensive part: deriving the key takes
     * two hundred thousand rounds of PBKDF2, and that must not happen twice.
     */
    private fun syncSealing(): Sealing? {
        val phrase = _syncPhrase.value.takeIf { it.isNotEmpty() } ?: return null
        sealingInUse?.let { (held, sealing) -> if (held == phrase) return sealing }
        return GcmSealing(phrase).also { sealingInUse = phrase to it }
    }

    /**
     * Sets the phrase, or clears it when given nothing.
     *
     * Trimmed at both ends, because the same phrase typed on two devices is the whole mechanism
     * and a trailing space is invisible. The pass that follows writes under the new phrase and
     * reads the folder again from the beginning: everything the other device sealed was stepped
     * over while this one could not open it.
     */
    fun setSyncPhrase(text: String) {
        val phrase = text.trim()
        _syncPhrase.value = phrase
        sealingInUse = null
        _syncSummary.value = _syncSummary.value.copy(sealed = phrase.isNotEmpty())
        saveSettings()
        viewModelScope.launch { syncPass(force = true, takeIn = true) }
    }

    /** Sync's own files — the device id and what it remembers between runs — beside the database. */
    private val syncDirectory by lazy { java.io.File(db.dataDirectory(), "sync") }

    private var syncCoordinator: SyncCoordinator<String>? = null

    /** One pass over the folder at a time; see [syncPass]. */
    private val syncGate = Mutex()

    /** The wait after a playlist change before it is sent; see [settlePlaylists]. */
    private var playlistSettling: kotlinx.coroutines.Job? = null

    /** Whether the window is in front of the owner right now; see [observeSync]. */
    private val foreground = MutableStateFlow(false)

    /**
     * Every playlist and favourite change this machine knows of, its own and the ones that arrived.
     *
     * The log is what travels and what the merged answer is replayed from. The visible state is
     * still kept where it was — m3u files and a set of paths — and is overwritten by the replay on
     * each pass, so a change made here shows at once and is agreed on later.
     */
    private val playlistLog by lazy { PlaylistLog(java.io.File(syncDirectory, "playlist-log.jsonl")) }

    /** When the folder was last read, so passes nobody asked for do not read it over and over. */
    private var lastPullAt = 0L

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
        
        observeSync()

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
            _syncFolderPath.value = s.syncFolderPath
            _syncPhrase.value = s.syncPhrase
            _syncSummary.value = _syncSummary.value.copy(sealed = s.syncPhrase.isNotEmpty())
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

        // Said again now that the chosen folder is known: the first call ran before settings were
        // read, when there was nothing to say.
        refreshSyncSummary()
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
                volumePercent = _volumePercent.value,
                syncFolderPath = _syncFolderPath.value,
                syncPhrase = _syncPhrase.value
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
                refreshModelState()
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

                // The earliest moment anything from the other device can be placed: before the
                // scan there is nothing to match a listen against. A machine coming back after
                // being away catches up here, without being asked.
                syncPass(force = true, takeIn = true)
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

    // ------------------------------------------------------------------ playlists that travel
    //
    // The visible state stays where it was — m3u files and a set of paths — and every change to it
    // is also written into the log as an operation, so it can travel. A pass then replays the whole
    // log, its own changes among them, and replaces what is shown with the result. So a change made
    // here is visible at once and agreed on with the other device shortly after.

    /** One change, before it is given a device, a counter and a time. */
    private data class Change(
        val kind: PlaylistOp.Kind,
        val playlist: String? = null,
        val track: Song? = null,
        val newName: String? = null,
        /** The whole playlist, for [PlaylistOp.Kind.ORDER]. */
        val tracks: List<Song> = emptyList()
    )

    /**
     * Writes changes into the log, which numbers them as it writes.
     *
     * Numbering is the log's to do and not this method's: taking a counter and writing with it are
     * two steps, and a sync pass can write between them.
     */
    private fun recordPlaylistChanges(changes: List<Change>) {
        if (changes.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val deviceId = syncDeviceId() ?: return@launch
            val at = nowMs()
            playlistLog.appendOwn { firstSeq ->
                changes.mapIndexed { index, change ->
                    PlaylistOp(
                        kind = change.kind,
                        seq = firstSeq + index,
                        deviceId = deviceId,
                        at = at,
                        playlist = change.playlist,
                        track = change.track?.let { M3uShelf.keyOf(it) },
                        newName = change.newName,
                        tracks = change.tracks.map { M3uShelf.keyOf(it) }
                    )
                }
            }
            // What is shown is the replay of the log, so it is re-derived here rather than left as
            // whatever the mutation did to it. Two things come out of that: a pass can no longer
            // take away a change made here, and a change the log refused does not appear to have
            // worked and then vanish at the next pass.
            runCatching { coordinator()?.showPlaylists() }
            settlePlaylists()
        }
    }

    /**
     * Sends playlist changes a few seconds after the last of them.
     *
     * A heart tapped is a deliberate act and its absence on the other device is noticed — which is
     * how this came to light: a track hearted here and one there, and neither moved, because a
     * change by itself caused no pass and the next one was whenever the window happened to be
     * clicked again. A few seconds rather than at once so that hearting a dozen tracks is one
     * write and not a dozen; each change restarts the wait.
     */
    private fun settlePlaylists() {
        playlistSettling?.cancel()
        playlistSettling = viewModelScope.launch {
            delay(PLAYLIST_SETTLE_MS)
            syncPass(force = true, takeIn = false)
        }
    }

    /**
     * Replaces what this machine shows with what the log says, once a pass has merged it.
     *
     * A playlist keeps the id it already had, so that whatever is selected stays selected; a new
     * one is given the id its name would get when read back off disk.
     */
    private suspend fun showMergedPlaylists(
        favourites: List<String>,
        playlists: Map<String, List<String>>
    ) {
        val byPath = allSongs.associateBy { it.path }
        val byName = _playlists.value.associateBy { it.name }
        val merged = playlists.map { (name, paths) ->
            Playlist(
                id = byName[name]?.id ?: name.hashCode().toLong(),
                name = name,
                songs = paths.mapNotNull { byPath[it] }
            )
        }
        withContext(Dispatchers.Main) {
            _favoriteSongPaths.value = favourites.toSet()
            _playlists.value = merged
            _selectedPlaylist.value = _selectedPlaylist.value?.let { chosen ->
                merged.find { it.name == chosen.name }
            }
        }
        savePlaylists()
        saveFavorites()
    }

    fun createPlaylist(name: String) {
        val newPlaylist = Playlist(id = System.currentTimeMillis(), name = name, songs = emptyList())
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
        // Created explicitly, so that a playlist made empty is still a playlist on the other side.
        recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.CREATE, playlist = name)))
    }

    fun deletePlaylist(playlist: Playlist) {
        _playlists.value = _playlists.value.filter { it.id != playlist.id }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = null
        }
        savePlaylists()
        recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.DELETE, playlist = playlist.name)))
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
        recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.ADD, playlist.name, song)))
    }

    /**
     * Sets the order of a playlist, as the owner has just dragged it.
     *
     * The whole list travels, not the move, because a position means nothing on a device whose
     * copy of the playlist has changed since. Replay then puts what it is given first and
     * anything it does not mention after, so a track that arrived while the list was being
     * dragged about is at the end rather than gone.
     *
     * Which is also why what is shown here is not simply [songs]: the re-derivation that follows
     * the write brings back anything that arrived meanwhile. Showing [songs] alone would drop it
     * until the next pass.
     */
    fun reorderPlaylist(playlist: Playlist, songs: List<Song>) {
        val before = _playlists.value.find { it.id == playlist.id } ?: return
        if (before.songs.map { it.path } == songs.map { it.path }) return
        _playlists.value = _playlists.value.map {
            if (it.id == playlist.id) it.copy(songs = songs) else it
        }
        if (_selectedPlaylist.value?.id == playlist.id) {
            _selectedPlaylist.value = _playlists.value.find { it.id == playlist.id }
        }
        savePlaylists()
        recordPlaylistChanges(
            listOf(Change(PlaylistOp.Kind.ORDER, playlist = playlist.name, tracks = songs))
        )
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
        recordPlaylistChanges(listOf(Change(PlaylistOp.Kind.REMOVE, playlist.name, song)))
    }

    fun toggleFavorite(song: Song) {
        val current = _favoriteSongPaths.value.toMutableSet()
        val added = !current.remove(song.path)
        if (added) current.add(song.path)
        _favoriteSongPaths.value = current
        saveFavorites()
        recordPlaylistChanges(
            listOf(Change(if (added) PlaylistOp.Kind.ADD else PlaylistOp.Kind.REMOVE, track = song))
        )
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

    /** Fetches the CLAP weights, which are not shipped with the source. */
    fun downloadModels() {
        viewModelScope.launch {
            models.ensureAll()
            refreshModelState()
            // AI search and the album names both need the text model; with it here they work now.
            refreshSmartAlbums()
        }
    }

    fun deleteModels() {
        viewModelScope.launch {
            val freed = models.deleteDownloaded()
            refreshModelState()
            _aiScanStatus.value = "Removed ${freed / (1024 * 1024)} MB of model weights"
        }
    }

    private fun refreshModelState() {
        _modelsReady.value = models.audioModelReady() && models.textModelReady()
        _modelsPendingBytes.value = models.pendingBytes()
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
            // Offered to the outbox, which decides whether it is yet worth touching the folder.
            // After the listen is stored, not before: the outbox's watermark is a row number, and
            // a row that is not there yet cannot be described.
            syncPass(force = false, takeIn = false)
        }
    }

    // ---------------------------------------------------------------------------- sync

    /** The folder itself, rebuilt whenever the choice changes and null until one is made. */
    private fun syncFolder(): SyncFolder? {
        val path = _syncFolderPath.value
        if (path.isEmpty()) return null
        val deviceId = syncDeviceId() ?: return null
        // Looked up afresh on every pass: a folder on a drive that has been unplugged, or that
        // Syncthing has not recreated yet, is a null rather than a handle that means nothing.
        return FileSyncFolder({ java.io.File(path).takeIf { it.isDirectory } }, deviceId)
    }

    private fun syncDeviceId(): String? =
        SyncIdentity.loadOrCreate(java.io.File(syncDirectory, "device-id"))

    private fun coordinator(): SyncCoordinator<String>? {
        syncCoordinator?.let { return it }
        val deviceId = syncDeviceId() ?: return null
        val state = SyncState.load(java.io.File(syncDirectory, "state.json"))
        return SyncCoordinator(
            deviceId = deviceId,
            state = state,
            store = PlayLogStore(db.musicDao(), { allSongs }, HISTORY_KEEP_DAYS),
            folder = { syncFolder() },
            keepDays = HISTORY_KEEP_DAYS.toInt(),
            note = { println("Sync: $it") },
            vectors = DaoVectorStore(db.musicDao(), { allSongs }, models.audioModelSha256()),
            playlists = M3uShelf(
                songs = { allSongs },
                favouritePaths = { _favoriteSongPaths.value },
                storedPlaylists = { _playlists.value.map { it.name to it.songs.map { song -> song.path } } },
                onShow = { favourites, playlists -> showMergedPlaylists(favourites, playlists) }
            ),
            playlistLog = playlistLog,
            sealing = { syncSealing() },
            mix = PathMixShelf(
                builder = dailyMixBuilder,
                songs = { allSongs },
                onShow = { songs -> withContext(Dispatchers.Main) { _dailyMix.value = songs } }
            ),
            // The whole library in one pass. Here a vector costs a map lookup and a line of text,
            // not a file open and a radio wake-up, so there is nothing to spread out — and a cache
            // that takes nine sittings to be shared is a cache nobody gets.
            publishLimit = 20_000
        ).also { syncCoordinator = it }
    }

    /**
     * The two moments worth reacting to, which are the same two the phone reacts to.
     *
     * Every finished listen is offered to the outbox, which writes only when it judges it worth
     * touching the folder — about twice an hour rather than once a track, because on the other end
     * of this folder is a phone, and every change to it is a radio wake-up there. And when playback
     * stops, whatever is waiting goes out at once: that is the end of a listening session, and the
     * next flush might otherwise be a long way off.
     */
    private fun observeSync() {
        viewModelScope.launch {
            isPlaying.collect { playing ->
                // The end of a listening session: say what was heard, and take in what the other
                // device has said since, in time for the next daily mix to count it.
                if (!playing) syncPass(force = true, takeIn = true, throttled = true)
            }
        }
        viewModelScope.launch {
            // The analysis finishing is the one moment there is certainly something new to offer,
            // and the only one that does not depend on somebody touching the app. Without it an
            // hour of analysis sits unshared until the owner happens to come back to the window.
            var wasScanning = false
            isAiScanning.collect { scanning ->
                // Whether it ran out of tracks or the owner stopped it, everything analysed goes
                // out as one piece. A limit is right for a pass nobody asked for; here the work has
                // just been done, the machine is already busy, and holding the rest back until
                // somebody happens to come back to the window is what makes this feel jerky.
                if (wasScanning && !scanning) {
                    syncPass(force = true, takeIn = true, allVectors = true)
                }
                wasScanning = scanning
            }
        }
        viewModelScope.launch {
            // While the window is in front of the owner, the folder is read every so often.
            //
            // Every other moment that reads it is something the owner did — stopped playback, came
            // back to the window — and there is one case none of them covers: looking at the
            // screen. A playlist open here while it is changed on the phone would sit there
            // unchanged, because watching it causes nothing. Found exactly that way.
            //
            // The interval is the governor rather than the throttle, which is why these passes are
            // not throttled.
            foreground.collectLatest { inFront ->
                while (inFront) {
                    delay(FOREGROUND_POLL_MS)
                    syncPass(force = false, takeIn = true)
                }
            }
        }
        refreshSyncSummary()
    }

    /**
     * One pass over the folder: what is waiting goes out, and what has arrived comes in.
     *
     * Behind a lock, because the moments that ask for a pass can arrive together — a listen
     * finishing is also often playback stopping — and two passes at once would read the same files
     * twice and write the state from under each other.
     *
     * @param takeIn false for a pass that only has something to say. A listen finishing is one of
     *   those: it comes every few minutes, and reading the whole folder each time would be work
     *   for nothing.
     * @param allVectors for the end of an analysis: everything just scanned goes out at once.
     *   See [SyncCoordinator.pushVectors].
     * @param throttled for the passes a person causes without meaning to — pausing, switching back
     *   to the window — which arrive in flurries. See [PULL_THROTTLE_MS]. A pass the owner asked
     *   for, and the one after a scan, are never throttled.
     */
    private suspend fun syncPass(
        force: Boolean,
        takeIn: Boolean,
        throttled: Boolean = false,
        allVectors: Boolean = false
    ) = withContext(Dispatchers.Default) {
        val coordinator = coordinator() ?: return@withContext
        syncGate.withLock {
            val now = nowMs()
            val sent = runCatching { coordinator.push(now, force).written }
                .getOrElse { println("Sync: could not write the history out: $it"); 0 }
            // A pass with nothing to take in happens often — a listen finishing is one — and
            // reading the whole folder each time would be work for nothing. A pass before the
            // library is scanned is turned away by the coordinator itself.
            val tooSoon = throttled && now - lastPullAt < PULL_THROTTLE_MS
            val pulled = if (!takeIn || tooSoon) null else
                // Marked as read only when the folder really was read: the coordinator turns a
                // pass away when there is no library yet, and counting that as a read would make
                // the next real one wait out the throttle for nothing.
                runCatching { coordinator.pull(now).also { if (it.read != null) lastPullAt = now } }
                    .getOrElse { println("Sync: could not read the history in: $it"); null }

            // Vectors on the same moments as a full pass, and never on a finished listen: there is
            // nothing new to publish then, and reading a library's worth of them would be work for
            // nothing. In the steady state this costs one empty query.
            val sentVectors = if (pulled == null) null else
                runCatching {
                    // Without a limit when it is not the end of an analysis, so the coordinator's
                    // own choice stands.
                    if (allVectors) coordinator.pushVectors(now, SyncCoordinator.PUBLISH_EVERYTHING)
                    else coordinator.pushVectors(now)
                }.getOrElse { println("Sync: could not write the vectors out: $it"); null }
            val tookVectors = if (pulled == null) null else
                runCatching { coordinator.pullVectors(now) }
                    .getOrElse { println("Sync: could not read the vectors in: $it"); null }
            sentVectors?.let {
                if (it.written + it.couldNotTravel + it.gone > 0) {
                    println(
                        "Sync: vectors out: ${it.written} written, ${it.couldNotTravel} cannot " +
                            "travel, ${it.gone} with nothing to name them by"
                    )
                }
            }
            // The whole outcome, not only the refusals: "nothing was refused" says very little on
            // its own, since a vector for a track already analysed here never gets as far as being
            // judged. These four numbers are what a pass actually did.
            tookVectors?.let {
                val seen = it.takenIn + it.alreadyHeld + it.unmatched + it.refused.values.sum()
                if (seen > 0) {
                    println(
                        "Sync: vectors: $seen seen, ${it.takenIn} taken in, ${it.alreadyHeld} " +
                            "already held, ${it.unmatched} for tracks we lack, " +
                            "refused ${it.refused}" + if (it.more) ", more waiting" else ""
                    )
                }
            }

            // On every pass, with no condition at all. A change the owner made has to be able
            // to go out on its own, and when there is nothing to send this costs a lookup: the
            // outbox is asked for its own changes past a watermark and answers with none. Tying it
            // to the kinds of pass that also read the folder was wrong twice over — it left a
            // hearted track sitting until the app was next opened, and then left it sitting
            // through the opening too, because coming back to the app forces nothing.
            val sentChanges =
                runCatching { coordinator.pushPlaylists(now) }
                    .getOrElse { println("Sync: could not write the playlist changes out: $it"); null }
            val tookChanges = if (pulled == null) null else
                runCatching { coordinator.pullPlaylists(now) }
                    .getOrElse { println("Sync: could not read the playlist changes in: $it"); null }
            sentChanges?.let {
                if (it.written + it.seeded > 0) {
                    println("Sync: playlists out: ${it.written} written, ${it.seeded} seeded from what was here")
                }
            }
            tookChanges?.let {
                if (it.takenIn > 0 || it.unmatched > 0) {
                    println(
                        "Sync: playlists in: ${it.takenIn} changes, ${it.favourites} favourites, " +
                            "${it.playlists} playlists, ${it.unmatched} tracks not here"
                    )
                }
            }

            // The playlist of the day, taken in before it is offered: a machine that is about to
            // adopt the other's mix should not first publish one of its own that is going to lose.
            val tookMix = if (pulled == null) null else
                runCatching { coordinator.pullMix(now) }
                    .getOrElse { println("Sync: could not read the playlist of the day in: $it"); null }
            val sentMix =
                runCatching { coordinator.pushMix(now) }
                    .getOrElse { println("Sync: could not write the playlist of the day out: $it"); null }
            tookMix?.let {
                if (it.adopted || it.unmatched > 0) {
                    println(
                        "Sync: daily mix in: ${if (it.adopted) "adopted" else "kept ours"} for " +
                            "${it.day}, ${it.tracks} tracks here, ${it.unmatched} not"
                    )
                }
            }
            sentMix?.let {
                if (it.written > 0) println("Sync: daily mix out: ${it.day}, attempt ${it.attempt}")
            }

            _syncSummary.value = _syncSummary.value.let {
                it.copy(
                    // Totals since this run started, not of the last pass: most passes happen on
                    // their own now, and a card that read "sent 0" after each of them would say
                    // nothing was working.
                    sent = it.sent + sent,
                    takenIn = it.takenIn + (pulled?.takenIn ?: 0),
                    waiting = pulled?.waiting ?: it.waiting,
                    vectorsSent = it.vectorsSent + (sentVectors?.written ?: 0),
                    vectorsTakenIn = it.vectorsTakenIn + (tookVectors?.takenIn ?: 0),
                    vectorsWaiting = tookVectors?.more ?: it.vectorsWaiting,
                    changesSent = it.changesSent + (sentChanges?.written ?: 0),
                    changesTakenIn = it.changesTakenIn + (tookChanges?.takenIn ?: 0),
                    mixSent = it.mixSent + (sentMix?.written ?: 0),
                    mixTakenIn = it.mixTakenIn + (if (tookMix?.adopted == true) 1 else 0),
                    unopened = coordinator.unopenedBatches,
                    lastRunAt = now
                )
            }
            // Listens taken in change what counts as recently played, which the daily mix uses.
            if ((pulled?.takenIn ?: 0) > 0) refreshDailyMix()
            // Vectors taken in are tracks that have become analysed without being scanned, which
            // is what the smart albums and the daily mix are built from.
            if ((tookVectors?.takenIn ?: 0) > 0) {
                loadScannedIds()
            }
        }
    }

    /** Takes the folder the owner picked. */
    fun setSyncFolder(path: String?) {
        if (path.isNullOrEmpty()) return
        _syncFolderPath.value = path
        syncCoordinator = null
        saveSettings()
        refreshSyncSummary()
        syncNow()
    }

    fun forgetSyncFolder() {
        _syncFolderPath.value = ""
        syncCoordinator = null
        saveSettings()
        refreshSyncSummary()
    }

    /**
     * The window came to the front, or was opened. Read the folder, so that what the other device
     * heard is already counted by the time the owner starts a wave or opens a playlist — which is
     * the whole of why this moment is worth reacting to. Nothing is forced out: coming back to the
     * window says nothing new about what was played.
     */
    fun syncOnOpen() {
        foreground.value = true
        viewModelScope.launch { syncPass(force = false, takeIn = true, throttled = true) }
    }

    /** The window is no longer in front of the owner, so the slow reading of the folder stops. */
    fun syncOnLeave() {
        foreground.value = false
    }

    /** A pass the owner asked for, which is the same pass with a spinner on it. */
    fun syncNow() {
        if (_syncSummary.value.busy) return
        _syncSummary.value = _syncSummary.value.copy(busy = true)
        viewModelScope.launch {
            syncPass(force = true, takeIn = true)
            _syncSummary.value = _syncSummary.value.copy(busy = false)
        }
    }

    private fun refreshSyncSummary() {
        val path = _syncFolderPath.value
        _syncSummary.value = _syncSummary.value.copy(
            folder = path.ifEmpty { null },
            deviceId = if (path.isEmpty()) "" else syncDeviceId().orEmpty().take(8)
        )
    }

    private fun nowMs(): Long = System.currentTimeMillis()

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

        /**
         * How soon after one read the sync folder may be read again by a pass nobody asked for.
         *
         * The moments that ask for a read are moments a person creates — a pause, a switch to
         * another window and back — and they arrive in flurries. Here a read is nearly free, but
         * the phone on the other end of the same folder pays for every change to it, and the two
         * apps are better off behaving the same way. Nothing a person is waiting for is behind
         * this minute: what they will look at is already in the log from the read a moment ago.
         */
        const val PULL_THROTTLE_MS = 60_000L

        /**
         * How long a playlist change waits for others to join it before being sent.
         *
         * Short, because the owner is standing there: hearting a track and switching to the other
         * device is a thing people do in one movement. Long enough that a dozen hearts in a row are
         * one write.
         */
        const val PLAYLIST_SETTLE_MS = 5_000L

        /**
         * How often the folder is read while the window is in front of the owner.
         *
         * Fifteen seconds is the compromise: a change made on the other device shows up while the
         * owner is still looking at the screen. Here it costs a directory listing; the phone at the
         * other end of the same folder pays a little more, and nothing at all when its app is away.
         */
        const val FOREGROUND_POLL_MS = 15_000L
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
