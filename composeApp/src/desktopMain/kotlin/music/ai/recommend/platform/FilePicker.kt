package music.ai.recommend.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import java.io.FileInputStream
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

actual fun pickDirectory(): String? {
    return showChooser(JFileChooser.DIRECTORIES_ONLY, "Select Music Folder", null)
}

actual fun pickImageFile(): String? {
    return showChooser(
        JFileChooser.FILES_ONLY, 
        "Select Background Image", 
        FileNameExtensionFilter("Images", "jpg", "png", "jpeg", "webp")
    )
}

@Composable
actual fun rememberLocalImagePainter(path: String?): Painter? {
    if (path == null) return null
    return remember(path) {
        try {
            val file = java.io.File(path)
            if (file.exists()) {
                BitmapPainter(loadImageBitmap(FileInputStream(file)))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

private fun showChooser(mode: Int, title: String, filter: FileNameExtensionFilter?): String? {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {}

    val chooser = JFileChooser()
    chooser.fileSelectionMode = mode
    chooser.dialogTitle = title
    filter?.let { chooser.fileFilter = it }
    
    val result = chooser.showOpenDialog(null)
    
    return if (result == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
