package music.ai.recommend.model

import androidx.compose.runtime.Immutable

@Immutable
data class EqBand(val index: Int, val freq: String, val level: Float)

@Immutable
data class EqPreset(val name: String, val levels: List<Float>, val isCustom: Boolean = false)

data class AppSettings(
    val musicFolderPath: String = "",
    val isDarkTheme: Boolean = true,
    val backgroundImageUri: String? = null,
    val backgroundAlpha: Float = 0.3f,
    val eqLevels: List<Float> = List(10) { 0f },
    val customEqPresets: List<EqPreset> = emptyList()
)
