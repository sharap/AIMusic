package music.ai.recommend.sync

import kotlinx.coroutines.runBlocking
import music.ai.recommend.ai.Excerpt
import music.ai.recommend.db.ANALYSIS_VERSION
import music.ai.recommend.db.DesktopMusicDao
import music.ai.recommend.db.EMBEDDING_DIMENSIONS
import music.ai.recommend.db.EmbeddingEntity
import music.ai.recommend.model.Song
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Carrying embeddings, end to end, over this port's own store and folder.
 *
 * The format, the terms, the outbox and the reader are shared with the phone and tested there. What
 * this says is that the joins hold here: a track is a path, a vector is a row in a packed file, and
 * the excerpt is named from the duration the library scanner read — which is the same one the
 * analysis uses, and the reason this side needs no file open to answer.
 */
class VectorRoundTripTest {

    private val first = "aa".repeat(16)
    private val second = "bb".repeat(16)
    private val model = "3ecc72d27740e2a09ced20cf22fd6244122e5e506008763a0f368b3b4ff6eac8"
    private val now = 1_793_880_000_000L

    private lateinit var baseDir: File
    private lateinit var folder: File

    private val tracks = listOf(
        song("/music/Imagine Dragons - Believer.mp3", "Imagine Dragons", "Believer", 207_000),
        song("/music/Мельница - Ночная кобыла.mp3", "", "", 210_000),
        song("/music/01 - Atlantia.m4a", "Secret Garden", "Atlantia", 179_000)
    )

    private fun song(path: String, artist: String, title: String, duration: Long) = Song(
        id = 0,
        title = title,
        artist = artist,
        album = "",
        albumId = 0,
        duration = duration,
        uri = path,
        path = path,
        folderName = "music"
    )

    @BeforeTest
    fun emptyEverything() {
        baseDir = File(System.getProperty("java.io.tmpdir"), "aimusic-vectors-${System.nanoTime()}")
        folder = File(baseDir, "shared").apply { mkdirs() }
    }

    @AfterTest
    fun tidyUp() {
        baseDir.deleteRecursively()
    }

    private val dao by lazy { DesktopMusicDao(File(baseDir, "data")) }

    private fun vector(seed: Int) = FloatArray(EMBEDDING_DIMENSIONS) { ((it + seed) % 17) * 0.05f + 0.013f }

    private fun coordinator(deviceId: String, library: List<Song> = tracks): SyncCoordinator<String> =
        SyncCoordinator(
            deviceId = deviceId,
            state = SyncState.load(File(baseDir, "state-$deviceId.json")),
            store = PlayLogStore(dao, { library }),
            folder = { FileSyncFolder(folder, deviceId) },
            vectors = DaoVectorStore(dao, { library }, model)
        )

    private suspend fun analysed(vararg indices: Int) {
        for (index in indices) {
            dao.insertEmbedding(EmbeddingEntity(tracks[index].path, vector(index).toList()))
        }
    }

    @Test
    fun anMp3VectorCrossesTheFolderAndComesBackTheSame() = runBlocking {
        analysed(0, 1)

        val pushed = coordinator(first).pushVectors(now)
        assertEquals(2, pushed.written, "отправлено: $pushed")
        assertEquals(0, pushed.couldNotTravel)

        // As the other machine: the same library, none of it analysed.
        dao.clearAllEmbeddings()
        val pulled = coordinator(second).pullVectors(now)
        assertEquals(2, pulled.takenIn, "принято: $pulled")
        assertTrue(pulled.refused.isEmpty(), "отказы: ${pulled.refused}")

        val held = dao.getAllEmbeddings().associateBy { it.path }
        assertEquals(setOf(tracks[0].path, tracks[1].path), held.keys)
        for (index in 0..1) {
            assertEquals(
                vector(index).toList(),
                held.getValue(tracks[index].path).vector,
                "вектор ${tracks[index].path}"
            )
        }
        // And taken in means dealt with: nobody to relay to, and the folder would carry each twice.
        assertEquals(0, coordinator(second).pushVectors(now).written)
    }

    @Test
    fun aVectorFromAFormatWithoutASharedDecoderNeverLeaves() = runBlocking {
        analysed(2)                            // the m4a
        val pushed = coordinator(first).pushVectors(now)
        assertEquals(0, pushed.written)
        assertEquals(1, pushed.couldNotTravel)
        assertTrue(FileSyncFolder(folder, first).files(first, VectorWriter.DIRECTORY).isEmpty())

        // And it is not reconsidered on the next pass: the answer will not change while the
        // analysis version stays the same, and a library of four thousand would be walked again.
        assertEquals(0, coordinator(first).pushVectors(now).couldNotTravel)
    }

    @Test
    fun theExcerptIsNamedFromTheDurationTheAnalysisUses() = runBlocking {
        val store = DaoVectorStore(dao, { tracks }, model)
        // The tag reader gives whole seconds and `Excerpt.startMs` truncates to whole seconds
        // anyway, which is the agreement that lets the two platforms meet at all.
        assertEquals(Excerpt.startMs(207_000), store.excerptStartMs(tracks[0].path))
        assertEquals(0L, store.excerptStartMs(tracks[0].path)!! % 1000)
        // A track this library does not hold has no excerpt and therefore nothing to say.
        assertEquals(null, store.excerptStartMs("/music/нет такого.mp3"))
    }

    @Test
    fun aVectorMadeByAnotherModelIsRefused() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val day = File(folder, "aura/v1/$first/${VectorWriter.dayFile(now)}")
        day.writeText(day.readText().replace(model, "ff".repeat(32)))

        val pulled = coordinator(second).pullVectors(now)
        assertEquals(0, pulled.takenIn)
        assertEquals(mapOf(VectorVerdict.OtherModel to 1), pulled.refused)
        assertTrue(dao.getAllEmbeddings().isEmpty())
    }

    @Test
    fun aVectorFromAnotherAnalysisVersionIsRefused() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val day = File(folder, "aura/v1/$first/${VectorWriter.dayFile(now)}")
        day.writeText(day.readText().replace("\"av\":$ANALYSIS_VERSION", "\"av\":${ANALYSIS_VERSION - 1}"))

        val pulled = coordinator(second).pullVectors(now)
        assertEquals(mapOf(VectorVerdict.OtherVersion to 1), pulled.refused)
    }

    @Test
    fun aVectorAlreadyHeldIsNotTakenTwice() = runBlocking {
        analysed(0)
        coordinator(first).pushVectors(now)
        // Still analysed here: the vector on offer has nothing to add.
        val pulled = coordinator(second).pullVectors(now)
        assertEquals(0, pulled.takenIn)
        assertEquals(1, pulled.alreadyHeld)
        assertTrue(pulled.refused.isEmpty())
    }

    @Test
    fun aVectorForATrackThisLibraryDoesNotHoldWaitsUnclaimed() = runBlocking {
        analysed(0, 1)
        coordinator(first).pushVectors(now)
        dao.clearAllEmbeddings()

        val pulled = coordinator(second, library = tracks.take(1)).pullVectors(now)
        assertEquals(1, pulled.takenIn)
        assertEquals(1, pulled.unmatched)
    }

    @Test
    fun aVersionOneTableIsReadAndNothingInItCountsAsPublished() = runBlocking {
        // What is on a real machine right now: nine megabytes of analysis written before there was
        // anywhere to send it. Every row of it is therefore still to be offered.
        analysed(0, 1)
        dao.forceSave()
        val packed = File(baseDir, "data/embeddings.bin")
        assertTrue(packed.isFile)

        val reopened = DesktopMusicDao(File(baseDir, "data"))
        assertEquals(2, reopened.getAllEmbeddings().size)
        assertEquals(2, reopened.vectorsToPublish(100).size)

        reopened.markVectorsPublished(listOf(tracks[0].path), now)
        assertEquals(listOf(tracks[1].path), reopened.vectorsToPublish(100).map { it.path })
        // And the mark survives being written and read again.
        val again = DesktopMusicDao(File(baseDir, "data"))
        assertEquals(listOf(tracks[1].path), again.vectorsToPublish(100).map { it.path })
    }
}
