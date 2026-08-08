package music.ai.recommend.ui

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.util.Log
import kotlin.math.hypot

@Composable
fun BarVisualizer(
    audioSessionId: Int?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary
) {
    var magnitudes by remember { mutableStateOf(FloatArray(32) { 0f }) }

    DisposableEffect(audioSessionId) {
        if (audioSessionId == null || audioSessionId <= 0) return@DisposableEffect onDispose {}
        
        var visualizer: Visualizer? = null
        try {
            visualizer = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0] // Use minimum size for safety
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || !isPlaying) return
                        
                        val newMagnitudes = FloatArray(32)
                        var maxFrameMag = 0f
                        
                        for (i in 0 until 32) {
                            val index = (i + 1) * 2
                            if (index + 1 >= fft.size) break
                            val real = fft[index].toInt()
                            val imag = fft[index + 1].toInt()
                            val mag = Math.sqrt((real * real + imag * imag).toDouble()).toFloat()
                            newMagnitudes[i] = mag
                            if (mag > maxFrameMag) maxFrameMag = mag
                        }
                        
                        // Normalization against current frame max + smoothing
                        val smoothed = FloatArray(32)
                        for (i in 0 until 32) {
                            val normalized = if (maxFrameMag > 0) (newMagnitudes[i] / maxFrameMag) else 0f
                            // Smooth with previous value (80% old, 20% new)
                            smoothed[i] = magnitudes[i] * 0.7f + normalized * 0.3f
                        }
                        magnitudes = smoothed
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (e: Exception) {
            // Log if needed
        }

        onDispose {
            visualizer?.enabled = false
            visualizer?.release()
        }
    }

    Canvas(modifier = modifier) {
        val barWidth = size.width / magnitudes.size
        val spacing = 2.dp.toPx()
        val actualBarWidth = (barWidth - spacing).coerceAtLeast(1f)

        magnitudes.forEachIndexed { index, magnitude ->
            // Use 80% of height for max magnitude to keep it "inside" and look better
            val barHeight = (magnitude * size.height * 0.8f).coerceAtLeast(4.dp.toPx())
            drawRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = index * barWidth + spacing / 2,
                    y = size.height - barHeight
                ),
                size = androidx.compose.ui.geometry.Size(
                    width = actualBarWidth,
                    height = barHeight
                )
            )
        }
    }
}
