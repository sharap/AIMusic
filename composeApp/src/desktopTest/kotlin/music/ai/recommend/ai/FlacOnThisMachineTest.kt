package music.ai.recommend.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test

/**
 * The same as the phone's `FlacOnTheDeviceTest`, on this machine's library, printing the same
 * lines so the two can be put side by side.
 *
 * Both devices hold the same music — that is what the sync folder is for — so a file of one name
 * is the same bytes on both. Equal digests then say that an embedding made on either is the
 * embedding the other would have made, which is the only thing that justifies carrying one.
 */
class FlacOnThisMachineTest {

    private val library = File(System.getProperty("user.home"), "Music")

    @Test
    fun theFlacsOnThisMachineDecodeToSomethingWorthComparing() {
        if (!library.isDirectory) {
            println("FlacOnThisMachine: no ${library.path}, nothing to compare")
            return
        }
        val files = library.walkTopDown()
            .filter { it.isFile && FlacExcerpt.handles(it.name) }
            .sortedBy { it.path }
            .take(40)
            .toList()
        for (file in files) {
            val samples = FlacExcerpt.decode(file)
            if (samples.isEmpty()) {
                println("FlacOnThisMachine: ${file.name} would not decode")
                continue
            }
            println("FLACSUM ${file.parentFile?.name}/${file.name}|${FlacExcerpt.startMs(file)}|${samples.size}|${digestOf(samples)}")
        }
    }

    private fun digestOf(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(samples)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }
}
