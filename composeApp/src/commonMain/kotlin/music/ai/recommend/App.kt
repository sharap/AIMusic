package music.ai.recommend

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import music.ai.recommend.ui.theme.AiMusicTheme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import music.ai.recommend.ui.screens.*
import music.ai.recommend.ui.components.SongItem
import music.ai.recommend.platform.rememberLocalImagePainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val viewModel = remember { MusicViewModel() }
    val folders by viewModel.folders.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val currentSection by viewModel.currentSection.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val listState = rememberLazyListState()
    
    val backgroundPainter = rememberLocalImagePainter(backgroundImageUri)
    var selectedTab by remember { mutableIntStateOf(0) }

    AiMusicTheme(darkTheme = isDarkTheme) {
        val backgroundColor = if (backgroundImageUri != null) Color.Transparent else MaterialTheme.colorScheme.background
        Surface(modifier = Modifier.fillMaxSize(), color = backgroundColor) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background Overlay
                if (backgroundPainter != null) {
                    Image(
                        painter = backgroundPainter,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = backgroundAlpha },
                        contentScale = ContentScale.Crop
                    )
                    // Scrim for readability
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f))
                    )
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isWide = maxWidth > 1100.dp
                    val showTabs = maxWidth < 900.dp && currentSection == AppSection.Folders

                    Row(modifier = Modifier.fillMaxSize()) {
                        // Side Navigation Rail
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                            header = {
                                IconButton(onClick = { viewModel.loadMusic() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                        ) {
                            NavigationRailItem(
                                selected = currentSection == AppSection.Playlists,
                                onClick = { viewModel.setSection(AppSection.Playlists) },
                                icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                label = { Text("Playlists") }
                            )
                            NavigationRailItem(
                                selected = currentSection == AppSection.Folders,
                                onClick = { viewModel.setSection(AppSection.Folders) },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                label = { Text("Music") }
                            )
                            NavigationRailItem(
                                selected = currentSection == AppSection.Settings,
                                onClick = { viewModel.setSection(AppSection.Settings) },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings") }
                            )
                        }

                        // Content Area (Music/Settings/Playlists)
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (showTabs) {
                                PrimaryTabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Music") })
                                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Queue") })
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (showTabs && selectedTab == 1) {
                                    QueueScreen(viewModel)
                                } else {
                                    MainContentArea(viewModel, currentSection, selectedFolder, folders, isScanning, listState, currentSong)
                                }
                            }
                        }

                        // Queue Pane (Visible only if wide and not in tabs mode)
                        if (isWide) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.1f))
                            ) {
                                QueueScreen(viewModel)
                            }
                        }

                        // Player Pane (Right) - Fixed width on Desktop
                        Box(
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f))
                        ) {
                            PlayerScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContentArea(
    viewModel: MusicViewModel,
    currentSection: AppSection,
    selectedFolder: music.ai.recommend.model.Folder?,
    folders: List<music.ai.recommend.model.Folder>,
    isScanning: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    currentSong: music.ai.recommend.model.Song?
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Text(
                        when {
                            selectedFolder != null -> selectedFolder.name
                            currentSection == AppSection.Playlists -> "Playlists"
                            currentSection == AppSection.Settings -> "Settings"
                            else -> "Folders"
                        }
                    )
                },
                navigationIcon = {
                    if (selectedFolder != null) {
                        IconButton(onClick = { viewModel.selectFolder(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (currentSection) {
                AppSection.Playlists -> PlaylistListScreen(viewModel)
                AppSection.Settings -> SettingsScreen(viewModel)
                AppSection.Folders -> {
                    if (selectedFolder == null) {
                        if (isScanning && folders.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                        } else if (folders.isEmpty()) {
                            Text("No music found", modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentPadding = PaddingValues(8.dp)
                                ) {
                                    items(folders) { folder ->
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            onClick = { viewModel.selectFolder(folder) },
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            ListItem(
                                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                                headlineContent = { Text(folder.name) },
                                                supportingContent = { Text("${folder.songs.size} tracks") }
                                            )
                                        }
                                    }
                                }
                                VerticalScrollbar(
                                    modifier = Modifier.fillMaxHeight(),
                                    adapter = rememberScrollbarAdapter(scrollState = listState)
                                )
                            }
                        }
                    } else {
                        val songListState = rememberLazyListState()
                        Row(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = songListState,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                items(selectedFolder.songs) { song ->
                                    SongItem(
                                        song = song,
                                        isActive = song.id == currentSong?.id,
                                        onClick = { viewModel.playSong(song, selectedFolder.songs) }
                                    )
                                }
                            }
                            VerticalScrollbar(
                                modifier = Modifier.fillMaxHeight(),
                                adapter = rememberScrollbarAdapter(scrollState = songListState)
                            )
                        }
                    }
                }
            }
        }
    }
}
