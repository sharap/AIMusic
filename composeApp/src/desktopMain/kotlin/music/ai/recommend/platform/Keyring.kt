package music.ai.recommend.platform

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.Variant

/**
 * Where a desktop keeps secrets, as little of it as keeping one phrase needs.
 *
 * An interface rather than the calls themselves so that what [PhraseVault] does when this is
 * absent, locked or broken can be tested at all: the real one talks to the owner's actual keyring,
 * and a test that exercised the fallbacks against it would be writing into their passwords on every
 * build.
 */
interface Keyring {

    /** The phrase, or null for nothing stored, no service, or a service that will not open. */
    fun read(): String?

    /** @return true when the keyring took it. */
    fun write(phrase: String): Boolean

    /** Takes it out, if it is there. */
    fun forget()
}

/**
 * The Secret Service over the session bus: gnome-keyring or KWallet, whichever the desktop runs.
 *
 * The same service the browser keeps its passwords in, and the app already speaks D-Bus — that is
 * how the media keys work — so this needs no new dependency.
 *
 * Everything degrades quietly. There is no session bus on a server, and none on Windows or macOS
 * where this same build runs, and a missing keyring is not an error but an answer: see
 * [PhraseVault], which then keeps the phrase in a file only the owner can read.
 */
class SecretServiceKeyring : Keyring {

    override fun read(): String? = inSession { service, connection, session ->
        val found = service.SearchItems(ATTRIBUTES)
        val item = found.unlocked.firstOrNull()
        if (item == null) {
            // Locked means a prompt, and a prompt is the owner's business rather than something to
            // spring on them while a sync pass runs. Said once, and left alone.
            if (found.locked.isNotEmpty()) println("Sync: the phrase is in the keyring, and it is locked")
            return@inSession null
        }
        val secret = connection.getRemoteObject(SECRETS, item.path, SecretItem::class.java)
            .GetSecret(session)
        String(secret.value, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    override fun write(phrase: String): Boolean = inSession { service, connection, session ->
        val collection = connection.getRemoteObject(
            SECRETS, DEFAULT_COLLECTION, SecretCollection::class.java
        )
        val made = collection.CreateItem(
            mapOf(
                "org.freedesktop.Secret.Item.Label" to Variant(LABEL),
                "org.freedesktop.Secret.Item.Attributes" to Variant(ATTRIBUTES, "a{ss}")
            ),
            Secret(session, ByteArray(0), phrase.toByteArray(Charsets.UTF_8), CONTENT_TYPE),
            true
        )
        // A prompt instead of an item means a locked collection, and nothing was stored.
        made.item.path != "/"
    } ?: false

    override fun forget() {
        inSession { service, connection, _ ->
            for (item in service.SearchItems(ATTRIBUTES).unlocked) {
                runCatching {
                    connection.getRemoteObject(SECRETS, item.path, SecretItem::class.java).Delete()
                }
            }
        }
    }

    private fun <T> inSession(
        body: (SecretService, org.freedesktop.dbus.connections.impl.DBusConnection, DBusPath) -> T?
    ): T? = try {
        DBusConnectionBuilder.forSessionBus().build().use { connection ->
            val service = connection.getRemoteObject(SECRETS, SERVICE_PATH, SecretService::class.java)
            val opened = service.OpenSession(PLAIN, Variant(""))
            body(service, connection, opened.result)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SECRETS = "org.freedesktop.secrets"
        const val SERVICE_PATH = "/org/freedesktop/secrets"
        const val DEFAULT_COLLECTION = "/org/freedesktop/secrets/aliases/default"
        const val LABEL = "AiMusic sync phrase"
        const val CONTENT_TYPE = "text/plain; charset=utf8"

        /**
         * The session is opened as `plain`, so the bytes are not encrypted between this app and the
         * keyring. That is what the session bus is: a socket in the owner's own runtime directory
         * that only they can open. The alternative the spec offers is a Diffie-Hellman exchange
         * against a daemon already running as the same user, which would be ceremony and not
         * security.
         */
        const val PLAIN = "plain"

        /**
         * What the item is called in the keyring. Two attributes rather than one because that is
         * how every other client does it, and because it leaves room for a second secret later
         * without the first having to be found by guesswork.
         */
        val ATTRIBUTES = mapOf("service" to "aimusic", "key" to "sync-phrase")
    }
}

/** The Secret Service, as much of it as keeping one phrase needs. */
@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): OpenedSession
    fun SearchItems(attributes: Map<String, String>): FoundItems
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(
        properties: Map<String, Variant<*>>,
        secret: Secret,
        replace: Boolean
    ): CreatedItem
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun GetSecret(session: DBusPath): Secret
    fun Delete(): DBusPath
}

/** The session, and whatever the service wants to say about how it was opened. */
class OpenedSession(
    @Position(0) val output: Variant<*>,
    @Position(1) val result: DBusPath
) : Tuple()

/** What a search found, and what it found but could not open. */
class FoundItems(
    @Position(0) val unlocked: List<DBusPath>,
    @Position(1) val locked: List<DBusPath>
) : Tuple()

/** The item, and the prompt that would have to be answered first — `/` when there is none. */
class CreatedItem(
    @Position(0) val item: DBusPath,
    @Position(1) val prompt: DBusPath
) : Tuple()

/** A secret on the wire: which session it belongs to, and the bytes. */
class Secret(
    @Position(0) val session: DBusPath,
    @Position(1) val parameters: ByteArray,
    @Position(2) val value: ByteArray,
    @Position(3) val contentType: String
) : Struct()
