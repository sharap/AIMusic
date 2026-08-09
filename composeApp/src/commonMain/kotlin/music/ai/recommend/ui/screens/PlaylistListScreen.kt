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
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    if (selectedPlaylist == null) {
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
                if (playlists.isEmpty() && !isSearchActive) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No playlists yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showCreateDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create First Playlist")
                            }
                        }
                    }
                } else {
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
                                    onFavoriteClick = { viewModel.toggleFavorite(song) },
                                    onPlayNext = { viewModel.playNext(song) },
                                    onAddToEnd = { viewModel.addToEndOfQueue(song) },
                                    onDelete = { viewModel.deleteSong(song) },
                                    onShowInfo = { onShowInfo(song) },
                                    onClick = { viewModel.playSong(song, filteredSongs) }
                                )
                            }
                        } else {
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
                                    onFavoriteClick = { viewModel.toggleFavorite(song) },
                                    onPlayNext = { viewModel.playNext(song) },
                                    onAddToEnd = { viewModel.addToEndOfQueue(song) },
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
