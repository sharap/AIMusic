package music.ai.recommend

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    println("Starting Desktop App...")
    application {
        Window(onCloseRequest = ::exitApplication, title = "AiMusic") {
            println("Window created")
            App()
        }
    }
}
