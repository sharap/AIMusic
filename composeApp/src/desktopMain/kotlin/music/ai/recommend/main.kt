package music.ai.recommend

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.system.exitProcess

fun main() {
    println("Starting Desktop App...")
    application {
        Window(
            onCloseRequest = {
                exitApplication()
                exitProcess(0)
            }, 
            title = "AiMusic"
        ) {
            println("Window created")
            App()
        }
    }
}
