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
import com.hierynomus.smbj.share.VcsPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Connessioni al NAS che restano aperte, per la sezione Libreria: centinaia di miniature non possono autenticarsi una
 * per una (via VPN sarebbero minuti). Fino a SLOTS connessioni in parallelo; una che dà errore si riapre al giro dopo.
 */
object Remote {
    private const val SLOTS = 4

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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    slot.reset()
                    work(slot.share(cfg, share))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // annullata (una miniatura uscita dallo schermo, una pagina chiusa): la connessione è buona, si tiene
            throw e
        } catch (e: Exception) {
            slot.reset()
            throw e
        } finally {
            // trySend e non send: in una coroutine annullata send si interrompe SENZA restituire la connessione, e dopo
            // quattro annullamenti (scorrendo veloce la galleria) tutte le letture aspettavano per sempre (editor fermo
            // sul tondo, miniature grigie fino al riavvio dell'app). Il posto nel canale c'è sempre: trySend riesce
            slots.trySend(slot)
        }
    }

    /** Letture tenute "in volo" insieme sulla stessa connessione (misurato: 8 riempiono il Wi-Fi di casa). */
    const val IN_FLIGHT = 8

    /** Quanto leggere per volta quando si copia un file intero: abbastanza da tenere piene le letture in volo. */
    const val BIG_READ = 8 shl 20

    /**
     * Copia un file grande del NAS (da `from` in poi) a blocchi di 16 MB, ognuno letto a pezzi su più connessioni insieme
     * (fino a [SLOTS]). In VPN sulla rete mobile una connessione sola arrivava a 11-13 Mbit/s, quattro a 20-26 (misurato
     * il 25/11/2026 con uno speed test di 32 Mbit/s); a casa non cambia niente. `sink(pezzo, copiati finora)` riceve i
     * dati in ordine. Torna la dimensione del file.
     */
    suspend fun download(cfg: NasConfig, share: String, path: String, from: Long = 0, sink: (ByteArray, Long) -> Unit): Long {
        val size = use(cfg, share) { s -> open(s, path).use { it.size } }
        var at = from.coerceIn(0, size)
        val segment = 16L shl 20
        val minPart = 1L shl 20
        while (at < size) {
            val seg = minOf(segment, size - at)
            val n = minOf(SLOTS.toLong(), maxOf(1L, seg / minPart)).toInt()
            val parts = (0 until n).map { i -> val o = at + seg * i / n; o to (at + seg * (i + 1) / n - o).toInt() }
            val data = kotlinx.coroutines.coroutineScope {
                parts.map { (o, l) -> async { use(cfg, share) { s -> open(s, path).use { it.read(o, l) } } } }.awaitAll()
            }
            for ((k, d) in data.withIndex()) {
                if (d.size != parts[k].second) error("lettura incompleta di $path")
                at += d.size
                sink(d, at)
            }
        }
        return size
    }

    /**
     * Un file del NAS aperto in sola lettura, letto a pezzi (offset, lunghezza). Le letture grandi partono a blocchi,
     * fino a [IN_FLIGHT] insieme, senza aspettare la risposta di ognuno prima di chiedere il successivo: con il tempo di
     * risposta del Wi-Fi (o della VPN) una lettura alla volta lasciava la linea ferma per gran parte del tempo.
     */
    class Reader(private val file: com.hierynomus.smbj.share.File) : AutoCloseable, Source {
        override val size: Long = file.fileInformation.standardInformation.endOfFile
        private val chunk = runCatching { VcsPipeline.chunk(file) }.getOrDefault(1 shl 20).coerceIn(64 * 1024, 8 shl 20)

        override fun read(offset: Long, length: Int): ByteArray {
            val n = minOf(length.toLong(), maxOf(0L, size - offset)).toInt()
            if (n <= 0) return ByteArray(0)
            if (n <= chunk) return readOne(offset, n)
            return readMany(listOf(offset to n)).first()
        }

        /** Più pezzi del file, chiesti tutti in fila (fino a [IN_FLIGHT] in volo): torna i pezzi nello stesso ordine. */
        fun readMany(parts: List<Pair<Long, Int>>): List<ByteArray> {
            // ogni pezzo si divide in blocchi della dimensione massima di una lettura
            data class Block(val part: Int, val at: Int, val offset: Long, val length: Int)
            val outs = parts.map { (o, l) -> ByteArray(minOf(l.toLong(), maxOf(0L, size - o)).toInt()) }
            val blocks = ArrayList<Block>()
            parts.forEachIndexed { i, (o, _) ->
                var at = 0
                while (at < outs[i].size) { val len = minOf(chunk, outs[i].size - at); blocks += Block(i, at, o + at, len); at += len }
            }
            val got = IntArray(parts.size)
            val queue = ArrayDeque<Pair<Block, java.util.concurrent.Future<com.hierynomus.mssmb2.messages.SMB2ReadResponse>>>()
            var next = 0
            try {
                while (next < blocks.size || queue.isNotEmpty()) {
                    while (queue.size < IN_FLIGHT && next < blocks.size) {
                        val b = blocks[next++]
                        queue += b to VcsPipeline.readAsync(file, b.offset, b.length)
                    }
                    val (b, f) = queue.removeFirst()
                    var data = VcsPipeline.data(f.get(60, java.util.concurrent.TimeUnit.SECONDS))
                    System.arraycopy(data, 0, outs[b.part], b.at, data.size)
                    // risposta più corta del chiesto (succede di rado): il resto si legge qui, una lettura alla volta
                    if (data.size in 1 until b.length) {
                        val rest = readOne(b.offset + data.size, b.length - data.size)
                        System.arraycopy(rest, 0, outs[b.part], b.at + data.size, rest.size)
                        data = ByteArray(data.size + rest.size)
                    }
                    got[b.part] += data.size
                }
            } finally {
                // un errore a metà: le letture ancora in volo si lasciano finire (le risposte si scartano)
                queue.forEach { (_, f) -> runCatching { f.get(60, java.util.concurrent.TimeUnit.SECONDS) } }
            }
            return outs.mapIndexed { i, o -> if (got[i] == o.size) o else o.copyOf(got[i]) }
        }

        private fun readOne(offset: Long, n: Int): ByteArray {
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
