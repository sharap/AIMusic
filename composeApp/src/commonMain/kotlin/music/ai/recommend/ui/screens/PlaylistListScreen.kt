package music.ai.recommend.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import music.ai.recommend.MusicViewModel
import music.ai.recommend.ai.SmartAlbum
import music.ai.recommend.model.Playlist
import music.ai.recommend.model.Song
import music.ai.recommend.ui.components.SongItem

@Composable
fun PlaylistListScreen(
    viewModel: MusicViewModel,
    onShowInfo: (Song) -> Unit = {}
) {
    val playlists by viewModel.playlists.collectAsState()
    val selectedPlaylist by viewModel.selectedPlaylist.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val favoritePaths by viewModel.favoriteSongPaths.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val smartAlbums by viewModel.smartAlbums.collectAsState()
    val smartAlbumsBuilding by viewModel.smartAlbumsBuilding.collectAsState()
    val selectedSmartAlbum by viewModel.selectedSmartAlbum.collectAsState()
    val dailyMix by viewModel.dailyMix.collectAsState()
    val dailyMixBuilding by viewModel.dailyMixBuilding.collectAsState()
    val dailyMixOpen by viewModel.dailyMixOpen.collectAsState()
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (dailyMixOpen) {
        TrackListDetail(
            title = "Daily mix",
            subtitle = "${dailyMix.size} tracks, picked for today",
            songs = dailyMix,
            viewModel = viewModel,
            currentSong = currentSong,
            favoritePaths = favoritePaths,
            onBack = { viewModel.openDailyMix(false) },
            onShowInfo = onShowInfo
        )
    } else if (selectedSmartAlbum != null) {
        val album = selectedSmartAlbum!!
        TrackListDetail(
            title = album.title,
            subtitle = album.subtitle,
            songs = album.songs,
            viewModel = viewModel,
            currentSong = currentSong,
            favoritePaths = favoritePaths,
            onBack = { viewModel.selectSmartAlbum(null) },
            onShowInfo = onShowInfo
        )
    } else if (selectedPlaylist == null) {
        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
                if (!isSearchActive) {
                    FloatingActionButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Create Playlist")
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // The list is always composed. Having "no playlists yet" replace it outright hid
                // the smart albums section too, which is the one thing on this screen the user
                // does not create by hand.
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    if (isSearchActive) {
                        val allSongs = playlists.flatMap { it.songs }.distinctBy { it.id }
                        val filteredSongs = allSongs.filter { 
                            it.title.contains(searchQuery, ignoreCase = true) || 
                            it.artist.contains(searchQuery, ignoreCase = true) 
                        }
                        items(filteredSongs) { song ->
                            SongItem(
                                song = song,
                                isActive = song.id == currentSong?.id,
                                isFavorite = favoritePaths.contains(song.path),
                                isScanned = scannedIds.contains(song.path),
                                onFavoriteClick = { viewModel.toggleFavorite(song) },
                                onPlayNext = { viewModel.playNext(song) },
                                onAddToEnd = { viewModel.addToEndOfQueue(song) },
                                onSmartPlaylist = { viewModel.createSmartPlaylist(song) },
                                onDelete = { viewModel.deleteSong(song) },
                                onShowInfo = { onShowInfo(song) },
                                onClick = { viewModel.playSong(song, filteredSongs) }
                            )
                        }
                    } else {
                        // Nothing to group until some tracks are analysed, so the section
                        // stays out of the way entirely until then.
                        // Above everything else, because it is the one thing here that changes
                        // on its own from one day to the next.
                        if (dailyMix.isNotEmpty() || dailyMixBuilding) {
                            item(key = "daily_mix") {
                                DailyMixCard(
                                    songs = dailyMix,
                                    building = dailyMixBuilding,
                                    containsCurrent = currentSong?.let { song ->
                                        dailyMix.any { it.path == song.path }
                                    } == true,
                                    onOpen = { viewModel.openDailyMix(true) },
                                    onPlay = { viewModel.playDailyMix() },
                                    onRebuild = { viewModel.refreshDailyMix(rebuild = true) }
                                )
                            }
                        }
                        if (smartAlbums.isNotEmpty() || smartAlbumsBuilding || scannedIds.isNotEmpty()) {
                            item(key = "smart_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Smart albums",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (smartAlbumsBuilding) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        IconButton(onClick = { viewModel.refreshSmartAlbums(rebuild = true) }) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Rebuild smart albums")
                                        }
                                    }
                                }
                            }
                            if (smartAlbums.isEmpty()) {
                                item(key = "smart_hint") {
                                    Text(
                                        if (smartAlbumsBuilding) "Grouping tracks by sound\u2026"
                                        else "Analyse tracks with AI to group them into albums by sound.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                            items(smartAlbums, key = { "smart_${it.id}" }) { album ->
                                val isActive = currentSong?.let { song ->
                                    album.songs.any { it.path == song.path }
                                } == true
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    onClick = { viewModel.selectSmartAlbum(album) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isActive)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                    )
                                ) {
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(album.title) },
                                        supportingContent = {
                                            val count = "${album.songs.size} songs"
                                            Text(if (album.subtitle.isEmpty()) count else "$count \u00B7 ${album.subtitle}")
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = if (isActive) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    )
                                }
                            }
                            item(key = "playlists_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Playlists",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (playlists.none { it.id != -1L }) {
                            item(key = "no_playlists") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("No playlists yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = { showCreateDialog = true }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Create First Playlist")
                                    }
                                }
                            }
                        }
                        items(playlists) { playlist ->
                            if (playlist.id != -1L) { // Don't show Favorites in the list if it's already at the top of Music
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    onClick = { viewModel.selectPlaylist(playlist) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                    )
                                ) {
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(playlist.name) },
                                        supportingContent = { Text("${playlist.songs.size} songs") },
                                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                        trailingContent = {
                                            IconButton(onClick = { viewModel.deletePlaylist(playlist) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        val playlist = selectedPlaylist!!
        val songsToShow = if (isSearchActive) {
            playlist.songs.filter { 
                it.title.contains(searchQuery, ignoreCase = true) || 
                it.artist.contains(searchQuery, ignoreCase = true) 
            }
        } else {
            playlist.songs
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (!isSearchActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(playlist.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = { viewModel.playSong(playlist.songs.first(), playlist.songs) }, enabled = playlist.songs.isNotEmpty()) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Play All")
                    }
                }
            }

            if (songsToShow.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (isSearchActive) "No results found" else "This playlist is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(songsToShow) { song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                SongItem(
                                    song = song,
                                    isActive = song.id == currentSong?.id,
                                    isFavorite = favoritePaths.contains(song.path),
                                    isScanned = scannedIds.contains(song.path),
                                    onFavoriteClick = { viewModel.toggleFavorite(song) },
                                    onPlayNext = { viewModel.playNext(song) },
                                    onAddToEnd = { viewModel.addToEndOfQueue(song) },
                                    onSmartPlaylist = { viewModel.createSmartPlaylist(song) },
                                    onDelete = { viewModel.deleteSong(song) },
                                    onShowInfo = { onShowInfo(song) },
                                    onClick = { viewModel.playSong(song, songsToShow) }
                                )
                            }
                            IconButton(onClick = { viewModel.removeSongFromPlaylist(playlist, song) }) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Playlist") },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        viewModel.createPlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreateDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * A read-only track list with a header, shared by the smart albums and the playlist of the day.
 * Neither is a collection the user edits, so there is nothing to remove here.
 */
@Composable
private fun TrackListDetail(
    title: String,
    subtitle: String,
    songs: List<Song>,
    viewModel: MusicViewModel,
    currentSong: Song?,
    favoritePaths: Set<String>,
    onBack: () -> Unit,
    onShowInfo: (Song) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = { viewModel.playSong(songs.first(), songs) },
                enabled = songs.isNotEmpty()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("Play All")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(songs) { song ->
                SongItem(
                    song = song,
                    isActive = song.id == currentSong?.id,
                    isFavorite = favoritePaths.contains(song.path),
                    isScanned = true,
                    onFavoriteClick = { viewModel.toggleFavorite(song) },
                    onPlayNext = { viewModel.playNext(song) },
                    onAddToEnd = { viewModel.addToEndOfQueue(song) },
                    onSmartPlaylist = { viewModel.createSmartPlaylist(song) },
                    onDelete = { viewModel.deleteSong(song) },
                    onShowInfo = { onShowInfo(song) },
                    onClick = { viewModel.playSong(song, songs) }
                )
            }
        }
    }
}

/** The day's playlist, with the controls to play it, reopen it, or ask for another one. */
@Composable
private fun DailyMixCard(
    songs: List<Song>,
    building: Boolean,
    containsCurrent: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onRebuild: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        onClick = onOpen,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Today,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Daily mix", style = MaterialTheme.typography.titleMedium)
                    if (containsCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (building && songs.isEmpty()) "Picking today's tracks..."
                    else "${songs.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (building) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRebuild) {
                    Icon(Icons.Default.Refresh, contentDescription = "Build another one for today")
                }
            }
            IconButton(onClick = onPlay, enabled = songs.isNotEmpty()) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
