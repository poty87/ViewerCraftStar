package io.github.poty87.viewercraftstar

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Connessioni al NAS che restano aperte, per la sezione Libreria: centinaia di miniature non possono autenticarsi una
 * per una (via VPN sarebbero minuti). Fino a SLOTS connessioni in parallelo; una che dà errore si riapre al giro dopo.
 */
object Remote {
    private const val SLOTS = 3

    private class Slot {
        var key = ""
        var client: SMBClient? = null
        var conn: Connection? = null
        var session: Session? = null
        val shares = HashMap<String, DiskShare>()

        fun share(cfg: NasConfig, name: String): DiskShare {
            val k = "${cfg.host}|${cfg.user}|${cfg.password.hashCode()}"
            if (k != key || conn?.isConnected != true) reset()
            if (session == null) {
                key = k
                val c = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS)
                    .withSoTimeout(40, TimeUnit.SECONDS).build())
                client = c
                val connection = c.connect(cfg.host)
                conn = connection
                val (domain, user) = if ('\\' in cfg.user) cfg.user.substringBefore('\\') to cfg.user.substringAfter('\\')
                else "" to cfg.user
                session = connection.authenticate(AuthenticationContext(user, cfg.password.toCharArray(), domain))
                NasId.learn(connection)
            }
            shares[name]?.let { if (it.isConnected) return it }
            return (session!!.connectShare(name) as DiskShare).also { shares[name] = it }
        }

        fun reset() {
            shares.values.forEach { runCatching { it.close() } }
            shares.clear()
            runCatching { session?.close() }
            runCatching { conn?.close() }
            runCatching { client?.close() }
            session = null
            conn = null
            client = null
            key = ""
        }
    }

    private val slots = Channel<Slot>(SLOTS).apply { repeat(SLOTS) { trySend(Slot()) } }

    /** Lavora su una cartella condivisa del NAS con una connessione del gruppo; se cade, riprova una volta. */
    suspend fun <T> use(cfg: NasConfig, share: String, work: (DiskShare) -> T): T {
        val slot = slots.receive()
        try {
            return withContext(Dispatchers.IO) {
                try {
                    work(slot.share(cfg, share))
                } catch (e: Exception) {
                    slot.reset()
                    work(slot.share(cfg, share))
                }
            }
        } catch (e: Exception) {
            slot.reset()
            throw e
        } finally {
            slots.send(slot)
        }
    }

    /** Un file del NAS aperto in sola lettura, letto a pezzi (offset, lunghezza). */
    class Reader(private val file: com.hierynomus.smbj.share.File) : AutoCloseable, Source {
        override val size: Long = file.fileInformation.standardInformation.endOfFile

        override fun read(offset: Long, length: Int): ByteArray {
            val n = minOf(length.toLong(), maxOf(0L, size - offset)).toInt()
            val out = ByteArray(n)
            var done = 0
            while (done < n) {
                val r = file.read(out, offset + done, done, n - done)
                if (r <= 0) break
                done += r
            }
            return if (done == n) out else out.copyOf(done)
        }

        override fun close() = file.close()
    }

    fun open(share: DiskShare, path: String): Reader = Reader(
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null))
}
