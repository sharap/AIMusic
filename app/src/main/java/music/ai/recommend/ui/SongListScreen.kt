package music.ai.recommend.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel
import music.ai.recommend.model.Song

@Composable
fun SongListScreen(
    viewModel: MusicViewModel,
    folderName: String
) {
    val folders by viewModel.folders.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()
    val folder = folders.find { it.name == folderName }
    val songs = folder?.songs ?: emptyList()

    val listState = rememberLazyListState()
    var selectedSongForMenu by remember { mutableStateOf<Song?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    LaunchedEffect(currentSong, folder) {
        val index = songs.indexOfFirst { it.id == currentSong?.id }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            itemsIndexed(songs) { _, song ->
                val isActive = song.id == currentSong?.id
                val isScanned = song.id in scannedIds
                SongItem(
                    song = song,
                    isActive = isActive,
                    isScanned = isScanned,
                    onClick = { viewModel.playSong(song, songs) },
                    onLongClick = { selectedSongForMenu = song }
                )
            }
        }

        if (selectedSongForMenu != null) {
            SongContextMenu(
                song = selectedSongForMenu!!,
                onDismiss = { selectedSongForMenu = null },
                onPlayNext = {
                    viewModel.playNext(selectedSongForMenu!!)
                    selectedSongForMenu = null
                },
                onAddToQueue = {
                    viewModel.addToEndOfQueue(selectedSongForMenu!!)
                    selectedSongForMenu = null
                },
                onAddToPlaylist = {
                    showPlaylistPicker = true
                },
                onCreatePlaylist = {
                    showNewPlaylistDialog = true
                },
                onDelete = {
                    viewModel.deleteSong(selectedSongForMenu!!)
                    selectedSongForMenu = null
                }
            )
        }

        if (showPlaylistPicker && selectedSongForMenu != null) {
            AlertDialog(
                onDismissRequest = { showPlaylistPicker = false },
                title = { Text("Add to Playlist") },
                text = {
                    LazyColumn {
                        items(playlists) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                modifier = Modifier.clickable {
                                    viewModel.addSongToPlaylist(playlist.name, selectedSongForMenu!!)
                                    showPlaylistPicker = false
                                    selectedSongForMenu = null
                                }
                            )
                        }
                        if (playlists.isEmpty()) {
                            item { Text("No playlists found. Create one from the menu.") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPlaylistPicker = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showNewPlaylistDialog && selectedSongForMenu != null) {
            AlertDialog(
                onDismissRequest = { showNewPlaylistDialog = false },
                title = { Text("Create New Playlist") },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylistWithSongs(newPlaylistName, listOf(selectedSongForMenu!!))
                            showNewPlaylistDialog = false
                            selectedSongForMenu = null
                            newPlaylistName = ""
                        }
                    }) {
                        Text("Create")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItem(
    song: Song,
    isActive: Boolean,
    isScanned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.PlayArrow else Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        
        if (isScanned) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "AI Analyzed",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongContextMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Play Next") },
                leadingContent = { Icon(Icons.Default.SkipNext, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onPlayNext)
            )
            ListItem(
                headlineContent = { Text("Add to Queue") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToQueue)
            )
            ListItem(
                headlineContent = { Text("Create New Playlist with Song") },
                leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onCreatePlaylist)
            )
            ListItem(
                headlineContent = { Text("Add to Existing Playlist") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToPlaylist)
            )
            ListItem(
                headlineContent = { Text("Delete from Device") },
                leadingContent = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
    }
}
