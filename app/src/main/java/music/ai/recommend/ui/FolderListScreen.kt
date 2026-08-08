package music.ai.recommend.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel
import music.ai.recommend.model.Folder
import music.ai.recommend.model.Song

@Composable
fun FolderListScreen(
    viewModel: MusicViewModel,
    onFolderClick: (String) -> Unit
) {
    val folders by viewModel.folders.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val activeFolderName = currentSong?.folderName

    var selectedFolderForMenu by remember { mutableStateOf<Folder?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(folders) { folder ->
                FolderItem(
                    folder = folder,
                    isActive = folder.name == activeFolderName,
                    onClick = { onFolderClick(folder.name) },
                    onLongClick = { selectedFolderForMenu = folder }
                )
            }
        }

        if (selectedFolderForMenu != null) {
            FolderContextMenu(
                folder = selectedFolderForMenu!!,
                onDismiss = { selectedFolderForMenu = null },
                onPlayNext = {
                    selectedFolderForMenu!!.songs.asReversed().forEach { viewModel.playNext(it) }
                    selectedFolderForMenu = null
                },
                onAddToQueue = {
                    selectedFolderForMenu!!.songs.forEach { viewModel.addToEndOfQueue(it) }
                    selectedFolderForMenu = null
                },
                onCreatePlaylist = {
                    showNewPlaylistDialog = true
                },
                onAddToExistingPlaylist = {
                    showPlaylistPicker = true
                }
            )
        }

        if (showNewPlaylistDialog && selectedFolderForMenu != null) {
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
                            viewModel.createPlaylistWithSongs(newPlaylistName, selectedFolderForMenu!!.songs)
                            showNewPlaylistDialog = false
                            selectedFolderForMenu = null
                            newPlaylistName = ""
                        }
                    }) {
                        Text("Create")
                    }
                }
            )
        }

        if (showPlaylistPicker && selectedFolderForMenu != null) {
            AlertDialog(
                onDismissRequest = { showPlaylistPicker = false },
                title = { Text("Add Folder to Playlist") },
                text = {
                    LazyColumn {
                        items(playlists) { playlist ->
                            ListItem(
                                headlineContent = { Text(playlist.name) },
                                modifier = Modifier.clickable {
                                    viewModel.addSongsToPlaylist(playlist.name, selectedFolderForMenu!!.songs)
                                    showPlaylistPicker = false
                                    selectedFolderForMenu = null
                                }
                            )
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderItem(folder: Folder, isActive: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 12.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${folder.songs.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderContextMenu(
    folder: Folder,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onAddToExistingPlaylist: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Folder: ${folder.name}",
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
                leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToQueue)
            )
            ListItem(
                headlineContent = { Text("Create Playlist from Folder") },
                leadingContent = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onCreatePlaylist)
            )
            ListItem(
                headlineContent = { Text("Add Folder to Existing Playlist") },
                leadingContent = { Icon(Icons.Default.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddToExistingPlaylist)
            )
        }
    }
}
