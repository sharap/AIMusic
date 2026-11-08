package music.ai.recommend.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to the one secret this arrangement has when the desktop cannot keep it properly.
 *
 * The keyring is handed in rather than used, deliberately: the real one is the owner's own, and a
 * test that exercised these paths against it would write into their passwords on every build. What
 * is being proved is the rule that matters — a place is trusted only after it has given the phrase
 * back — and the three ways a desktop can fail to have a keyring at all.
 */
class PhraseVaultTest {

    private lateinit var directory: File

    @BeforeTest
    fun emptyDirectory() {
        directory = Files.createTempDirectory("aimusic-phrase").toFile()
    }

    @AfterTest
    fun tidyUp() {
        directory.deleteRecursively()
    }

    private fun phraseFile() = File(directory, "phrase")

    /** A keyring that works, and counts what it was asked. */
    private class Working : Keyring {
        var held: String? = null
        var reads = 0
        override fun read(): String? {
            reads++
            return held
        }
        override fun write(phrase: String): Boolean {
            held = phrase
            return true
        }
        override fun forget() {
            held = null
        }
    }

    /** No keyring at all: a server, a minimal desktop, Windows, macOS. */
    private class Absent : Keyring {
        override fun read(): String? = null
        override fun write(phrase: String) = false
        override fun forget() = Unit
    }

    /** Takes the secret and will not give it back, which is the dangerous one. */
    private class Forgetful : Keyring {
        var writes = 0
        override fun read(): String? = null
        override fun write(phrase: String): Boolean {
            writes++
            return true
        }
        override fun forget() = Unit
    }

    @Test
    fun aKeyringThatWorksKeepsThePhraseAndTheFileGoes() {
        val keyring = Working()
        val vault = PhraseVault(directory, keyring)
        // A phrase that was in a file before — what a machine updating into this build has.
        phraseFile().writeText("было в файле")

        assertTrue(vault.keep("sync early up"))
        assertEquals(PhraseKept.KEYRING, vault.where)
        assertEquals("sync early up", keyring.held)
        assertFalse(phraseFile().exists(), "файл должен уйти, когда связка приняла фразу")
        assertEquals("sync early up", vault.phrase())
    }

    @Test
    fun withoutAKeyringThePhraseGoesIntoAFileOnlyItsOwnerCanRead() {
        val vault = PhraseVault(directory, Absent())

        assertFalse(vault.keep("sync early up"), "без связки это не keyring")
        assertEquals(PhraseKept.FILE, vault.where)
        assertEquals("sync early up", phraseFile().readText())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(phraseFile().toPath()),
            "права должны быть только у владельца"
        )
        // And a fresh vault over the same directory finds it, which is what a restart is.
        assertEquals("sync early up", PhraseVault(directory, Absent()).phrase())
    }

    @Test
    fun aKeyringThatWillNotGiveThePhraseBackIsNotTrustedWithIt() {
        val keyring = Forgetful()
        val vault = PhraseVault(directory, keyring)

        assertFalse(vault.keep("sync early up"))
        assertEquals(1, keyring.writes)
        // The whole rule: it was offered, it was read back, the answer was wrong, so the phrase
        // went somewhere this device can actually read it again.
        assertEquals(PhraseKept.FILE, vault.where)
        assertEquals("sync early up", phraseFile().readText())
        assertEquals("sync early up", vault.phrase())
    }

    @Test
    fun forgettingTakesItOutOfBoth() {
        val keyring = Working()
        val vault = PhraseVault(directory, keyring)
        vault.keep("sync early up")
        // A file left over from before the keyring took it.
        phraseFile().writeText("sync early up")

        assertTrue(vault.keep(""))

        assertNull(keyring.held)
        assertFalse(phraseFile().exists())
        assertEquals(PhraseKept.PLAIN, vault.where)
        assertNull(vault.phrase())
    }

    @Test
    fun whatTheKeyringHoldsWinsOverAFileLeftBehind() {
        val keyring = Working().also { it.held = "в связке" }
        phraseFile().writeText("в файле")

        val vault = PhraseVault(directory, keyring)

        assertEquals("в связке", vault.phrase())
        assertEquals(PhraseKept.KEYRING, vault.where)
        // Asked once and then remembered: this is read on every pass, and each read would
        // otherwise be a round trip over the session bus.
        assertEquals("в связке", vault.phrase())
        assertEquals(1, keyring.reads)
    }
}
