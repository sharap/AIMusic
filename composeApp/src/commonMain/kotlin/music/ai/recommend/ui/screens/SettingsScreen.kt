package music.ai.recommend.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import music.ai.recommend.MusicViewModel
import music.ai.recommend.platform.pickDirectory
import music.ai.recommend.platform.pickImageFile
import music.ai.recommend.model.*
import music.ai.recommend.ai.SmartAlbumClustering

@Composable
fun SettingsScreen(viewModel: MusicViewModel) {
    val isScanning by viewModel.isScanning.collectAsState()
    val isAiScanning by viewModel.isAiScanning.collectAsState()
    val aiScanProgress by viewModel.aiScanProgress.collectAsState()
    val aiScanStatus by viewModel.aiScanStatus.collectAsState()
    val scannedSongIds by viewModel.scannedSongIds.collectAsState()
    val analysisReset by viewModel.analysisReset.collectAsState()
    val smartAlbumsEpsScale by viewModel.smartAlbumsEpsScale.collectAsState()
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsState()
    val backgroundImageUri by viewModel.backgroundImageUri.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val musicFolderPath by viewModel.musicFolderPath.collectAsState()
    val eqBands by viewModel.eqBands.collectAsState()
    val eqPresets by viewModel.eqPresets.collectAsState()

    var newPresetName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Music Folder", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (musicFolderPath.isEmpty()) "Default (~/Music)" else musicFolderPath,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = {
                            val path = pickDirectory()
                            if (path != null) {
                                viewModel.updateMusicPath(path)
                            }
                        }) {
                            Text("Change")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Refresh Library", style = MaterialTheme.typography.titleMedium)
                            Text("Re-scan selected folder for changes", style = MaterialTheme.typography.bodySmall)
                        }
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            IconButton(onClick = { viewModel.loadMusic() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Dark Theme", style = MaterialTheme.typography.titleMedium)
                            Text(if (isDarkTheme) "Dark mode active" else "Light mode active", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { viewModel.setDarkTheme(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(modifier = Modifier.graphicsLayer { alpha = 0.1f })
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background Image", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (backgroundImageUri != null) "Custom background active" else "Default theme background",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (backgroundImageUri != null) {
                            IconButton(onClick = { viewModel.setBackgroundImage(null) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        }
                        Button(onClick = {
                            val path = pickImageFile()
                            if (path != null) {
                                viewModel.setBackgroundImage(path)
                            }
                        }) {
                            Text("Select")
                        }
                    }
                    if (backgroundImageUri != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Transparency", style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = backgroundAlpha,
                            onValueChange = { viewModel.setBackgroundAlpha(it) },
                            valueRange = 0.05f..1f
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("AI Analysis", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Analyzed songs: ${scannedSongIds.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Without this the library would look as though it had un-analysed itself.
                    if (analysisReset) {
                        Text(
                            "Audio processing was corrected, so the previous analysis no longer " +
                                "applies. Run the scan again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (isAiScanning) {
                        Text(aiScanStatus, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { aiScanProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.stopAiScan() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Stop Analysis")
                        }
                    } else {
                        Text(
                            if (aiScanStatus.isNotEmpty()) aiScanStatus else "Run AI scan to enable smart features",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.startAiScan() },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Start AI Scan")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.clearAiData() }) {
                        Text("Clear AI Data", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Smart Albums", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // The radius is chosen per group, in that group's own reduced space, so an
                    // absolute value would mean nothing across libraries; this scales it.
                    var dragged by remember(smartAlbumsEpsScale) { mutableStateOf(smartAlbumsEpsScale) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Grouping radius (eps)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (dragged == 1f) "Auto" else "\u00D7${(dragged * 100).toInt() / 100f}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Multiplier on the eps chosen automatically. Lower makes albums tighter " +
                            "and smaller, leaving more tracks out; higher groups more loosely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = dragged,
                        onValueChange = { dragged = it },
                        // Regrouping is seconds of work, so it waits for the drag to end.
                        onValueChangeFinished = { viewModel.setSmartAlbumsEpsScale(dragged) },
                        valueRange = SmartAlbumClustering.MIN_EPS_SCALE..SmartAlbumClustering.MAX_EPS_SCALE,
                        steps = ((SmartAlbumClustering.MAX_EPS_SCALE - SmartAlbumClustering.MIN_EPS_SCALE) * 20).toInt() - 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tighter", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.weight(1f))
                        if (smartAlbumsEpsScale != 1f) {
                            TextButton(onClick = { viewModel.setSmartAlbumsEpsScale(1f) }) { Text("Auto") }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        Text("Looser", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text("Equalizer", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Presets Row
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(eqPresets) { preset ->
                            SuggestionChip(
                                onClick = { viewModel.applyPreset(preset) },
                                label = { Text(preset.name) },
                                icon = {
                                    if (preset.isCustom) {
                                        IconButton(
                                            onClick = { viewModel.deletePreset(preset) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            )
                        }
                    }

                    // Save Preset Field
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            placeholder = { Text("Preset name") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newPresetName.isNotBlank()) {
                                    viewModel.saveCustomPreset(newPresetName)
                                    newPresetName = ""
                                }
                            },
                            enabled = newPresetName.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }

                    // Sliders Row
                    Row(
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        eqBands.forEach { band ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${band.level.toInt()}dB", style = MaterialTheme.typography.labelSmall)
                                VerticalSlider(
                                    value = band.level,
                                    onValueChange = { viewModel.setEqBandLevel(band.index, it) },
                                    valueRange = -20f..20f,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(band.freq, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { 
                        viewModel.applyPreset(EqPreset("Flat", List(10) { 0f }))
                    }) {
                        Text("Reset")
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Box(
        modifier = modifier
            .width(32.dp)
            .graphicsLayer {
                rotationZ = 270f
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-((placeable.width - placeable.height) / 2), -((placeable.height - placeable.width) / 2))
                }
            }
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
