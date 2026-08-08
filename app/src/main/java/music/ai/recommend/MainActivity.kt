package music.ai.recommend

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import music.ai.recommend.ui.*
import music.ai.recommend.ui.theme.AiMusicTheme

class MainActivity : ComponentActivity() {
    private var openPlayerAction by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            AiMusicTheme {
                val viewModel: MusicViewModel = viewModel()
                val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
                val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
                
                val navController = rememberNavController()
                var showPlayer by remember { mutableStateOf(false) }
                val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
                val scope = rememberCoroutineScope()

                LaunchedEffect(openPlayerAction) {
                    if (openPlayerAction) {
                        showPlayer = true
                        openPlayerAction = false
                    }
                }

                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.RECORD_AUDIO
                    )
                } else {
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.RECORD_AUDIO
                    )
                }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    if (results.values.all { it }) {
                        viewModel.loadMusic()
                    }
                }

                LaunchedEffect(Unit) {
                    val allGranted = permissions.all {
                        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    }
                    if (allGranted) {
                        viewModel.loadMusic()
                    } else {
                        launcher.launch(permissions)
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // Base background color (light or dark)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {}

                    if (backgroundImageUri != null) {
                        AsyncImage(
                            model = backgroundImageUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            alpha = backgroundAlpha
                        )
                    }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        bottomBar = {
                            Column {
                                PlayerOverlay(
                                    viewModel = viewModel,
                                    onClick = { showPlayer = true }
                                )
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = backgroundAlpha.coerceAtLeast(0.4f))
                                ) {
                                    NavigationBarItem(
                                        icon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null) },
                                        label = { Text("Playlists") },
                                        selected = pagerState.currentPage == 0,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } }
                                    )
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                        label = { Text("Music") },
                                        selected = pagerState.currentPage == 1,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } }
                                    )
                                    NavigationBarItem(
                                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        label = { Text("Settings") },
                                        selected = pagerState.currentPage == 2,
                                        onClick = { scope.launch { pagerState.animateScrollToPage(2) } }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                when (page) {
                                    0 -> PlaylistListScreen(viewModel = viewModel)
                                    1 -> NavHost(navController = navController, startDestination = "folder_list") {
                                        composable("folder_list") {
                                            FolderListScreen(
                                                viewModel = viewModel,
                                                onFolderClick = { folderName ->
                                                    navController.navigate("song_list/$folderName")
                                                }
                                            )
                                        }
                                        composable(
                                            "song_list/{folderName}",
                                            arguments = listOf(navArgument("folderName") { type = NavType.StringType })
                                        ) { backStackEntry ->
                                            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
                                            SongListScreen(
                                                viewModel = viewModel,
                                                folderName = folderName
                                            )
                                        }
                                    }
                                    2 -> SettingsScreen(viewModel = viewModel)
                                }
                            }
                        }

                        if (showPlayer) {
                            ModalBottomSheet(
                                onDismissRequest = { showPlayer = false },
                                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                                containerColor = MaterialTheme.colorScheme.background,
                                scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                                dragHandle = null // Optional: remove drag handle for cleaner look
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (backgroundImageUri != null) {
                                        AsyncImage(
                                            model = backgroundImageUri,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                            alpha = backgroundAlpha
                                        )
                                    }
                                    PlayerScreen(
                                        viewModel = viewModel,
                                        onClose = { showPlayer = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "OPEN_PLAYER") {
            openPlayerAction = true
        }
    }
}
