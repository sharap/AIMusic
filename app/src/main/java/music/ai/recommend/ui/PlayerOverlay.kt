package music.ai.recommend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import music.ai.recommend.MusicViewModel

@Composable
fun PlayerOverlay(
    viewModel: MusicViewModel,
    onClick: () -> Unit
) {
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val scannedIds by viewModel.scannedSongIds.collectAsState()

    if (currentSong == null) return

    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSong?.title ?: "",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentSong?.artist ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (currentSong?.id in scannedIds) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(10.dp)
                            )
                        }
                    }
                    
                    val currentPosition by viewModel.currentPosition.collectAsState()
                    val duration by viewModel.duration.collectAsState()
                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    if (isPlaying) viewModel.pause() else viewModel.resume()
                }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { viewModel.next() }) {
                    Icon(imageVector = Icons.Default.SkipNext, contentDescription = "Next")
                }
            }

            val currentPosition by viewModel.currentPosition.collectAsState()
            val duration by viewModel.duration.collectAsState()
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { currentPosition.toFloat() / duration.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
