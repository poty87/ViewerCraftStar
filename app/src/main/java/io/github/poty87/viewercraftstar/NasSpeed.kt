package io.github.poty87.viewercraftstar

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.VcsPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Prova di velocità verso il NAS: legge per qualche secondo una foto grande della libreria in modi diversi (una
 * connessione o più, una richiesta alla volta o più insieme, una porta di rete del NAS o tutte e due) e dice quanti
 * MB/s arrivano. Serve a capire cosa conviene davvero al telefono, a casa e in VPN: smbj non fa SMB Multichannel,
 * ma più connessioni (anche verso le due porte del NAS) e più letture insieme si possono fare.
 */
object NasSpeed {
    private const val TAG = "VcsSpeed"
    private const val SECONDS = 6L
    private const val CHUNK = 1 shl 20

    /** Un modo di leggere: quante connessioni, quante letture insieme per connessione, su quali indirizzi. */
    private data class Mode(val label: String, val connections: Int, val inFlight: Int, val hosts: List<String>)

    /** Le porte di rete del NAS (IPv4), come le dichiara lui stesso: è così che SMB Multichannel le trova. */
    private fun interfaces(share: DiskShare): List<String> {
        // FSCTL_QUERY_NETWORK_INTERFACE_INFO: un elenco di NETWORK_INTERFACE_INFO (Next, IfIndex, Capability, Reserved,
        // LinkSpeed, SockAddr_Storage di 128 byte)
        val out = share.ioctl(0x001401FCL, true, ByteArray(0), 0, 0)
        val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val ips = ArrayList<String>()
        var at = 0
        while (at + 152 <= out.size) {
            val next = b.getInt(at)
            val speed = b.getLong(at + 16)
            val family = b.getShort(at + 24).toInt() and 0xFFFF
            if (family == 2) {
                val ip = (0 until 4).joinToString(".") { (out[at + 28 + it].toInt() and 0xFF).toString() }
                Log.i(TAG, "porta del NAS: $ip, ${speed / 1_000_000} Mbit/s")
                ips += ip
            }
            if (next <= 0) break
            at += next
        }
        return ips.distinct()
    }

    private class Link(cfg: NasConfig, host: String, shareName: String) : AutoCloseable {
        val client = SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(40, TimeUnit.SECONDS)
            .withReadBufferSize(CHUNK).build())
        val conn = client.connect(host)
        val session = run {
            val (domain, user) = if ('\\' in cfg.user) cfg.user.substringBefore('\\') to cfg.user.substringAfter('\\') else "" to cfg.user
            conn.authenticate(AuthenticationContext(user, cfg.password.toCharArray(), domain))
        }
        val share = session.connectShare(shareName) as DiskShare
        val chunk = minOf(CHUNK, conn.negotiatedProtocol.maxReadSize)
        fun open(path: String) = share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null)
        override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { conn.close() }; runCatching { client.close() } }
    }

    /**
     * Esegue la prova. `say` riceve ogni riga del risultato man mano. Usa la libreria aperta (una foto raw grande) e
     * l'indirizzo del NAS delle impostazioni.
     */
    suspend fun run(cfg: NasConfig, idx: LibIndex, say: (String) -> Unit) = withContext(Dispatchers.IO) {
        // la foto da leggere: la più grande tra alcune raw della libreria (la si legge più volte, a giro)
        val candidates = idx.photos.filter { LibraryViewModel.fileTypeOf(it.name) == "raw" }.take(12)
            .mapNotNull { p -> idx.locate(p)?.let { p to it } }
        require(candidates.isNotEmpty()) { L.t("Nella libreria non ci sono foto raw da leggere.", "There are no raw photos in the library to read.") }
        val (shareName, _) = candidates.first().second
        val main = Link(cfg, cfg.host, shareName)
        val (path, size) = try {
            candidates.filter { it.second.first == shareName }.mapNotNull { (_, loc) ->
                runCatching { main.open(loc.second).use { f -> loc.second to f.fileInformation.standardInformation.endOfFile } }.getOrNull()
            }.maxBy { it.second }
        } catch (e: Exception) { main.close(); throw e }
        say(L.t("File di prova: ${path.substringAfterLast('\\')} (${size / 1_000_000} MB)", "Test file: ${path.substringAfterLast('\\')} (${size / 1_000_000} MB)"))
        // latenza: 20 letture piccole una dopo l'altra
        val rtt = main.open(path).use { f ->
            val buf = ByteArray(4096)
            val t0 = System.nanoTime()
            repeat(20) { f.read(buf, (it * 65536L) % maxOf(1, size - 4096), 0, 4096) }
            (System.nanoTime() - t0) / 20 / 1_000_000.0
        }
        say(L.t("Tempo di risposta: %.1f ms".format(rtt), "Response time: %.1f ms".format(rtt)))
        val ports = runCatching { interfaces(main.share) }.onFailure { Log.w(TAG, "porte del NAS non lette", it) }.getOrDefault(emptyList())
        main.close()
        val other = ports.filter { it != cfg.host }
        say(if (ports.isEmpty()) L.t("Porte del NAS: non dichiarate", "NAS ports: not reported")
            else L.t("Porte del NAS: ", "NAS ports: ") + ports.joinToString(", "))
        val both = if (other.isNotEmpty()) listOf(cfg.host, other.first()) else emptyList()
        val modes = buildList {
            add(Mode(L.t("1 connessione, 1 lettura alla volta (come oggi)", "1 connection, 1 read at a time (as today)"), 1, 1, listOf(cfg.host)))
            add(Mode(L.t("1 connessione, 8 letture insieme", "1 connection, 8 reads at once"), 1, 8, listOf(cfg.host)))
            add(Mode(L.t("4 connessioni, 1 porta", "4 connections, 1 port"), 4, 1, listOf(cfg.host)))
            add(Mode(L.t("4 connessioni, 4 letture insieme, 1 porta", "4 connections, 4 reads at once, 1 port"), 4, 4, listOf(cfg.host)))
            if (both.isNotEmpty()) {
                add(Mode(L.t("4 connessioni, 2 porte", "4 connections, 2 ports"), 4, 1, both))
                add(Mode(L.t("4 connessioni, 4 letture insieme, 2 porte", "4 connections, 4 reads at once, 2 ports"), 4, 4, both))
            }
        }
        // come scarica davvero l'app i file grandi (Remote.download: più connessioni, letture in volo)
        runCatching {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS)
            val t0 = System.nanoTime()
            var bytes = 0L
            while (System.nanoTime() < deadline) bytes += Remote.download(cfg, shareName, path) { _, _ -> }
            bytes / 1_000_000.0 / ((System.nanoTime() - t0) / 1e9)
        }.let { r ->
            val line = L.t("Scarico dell'app", "The app's download") + ": " + r.fold({ mbps(it) }, { L.t("non riuscita", "failed") + " (${it.message})" })
            Log.i(TAG, line)
            say(line)
        }
        for (m in modes) {
            val r = runCatching { measure(cfg, shareName, path, size, m) }
            val line = r.fold({ "${m.label}: " + mbps(it) }, { "${m.label}: " + L.t("non riuscita", "failed") + " (${it.message})" })
            Log.i(TAG, line)
            say(line)
        }
    }

    /** MB/s con un decimale sotto i 10 (in VPN le differenze stanno lì), e i Mbit/s per confrontarli con uno speed test. */
    private fun mbps(v: Double) = (if (v < 10) "%.1f MB/s".format(v) else "%.0f MB/s".format(v)) + " (%.0f Mbit/s)".format(v * 8)

    /** MB/s letti in [SECONDS] secondi con il modo indicato. */
    private suspend fun measure(cfg: NasConfig, shareName: String, path: String, size: Long, m: Mode): Double = coroutineScope {
        val links = (0 until m.connections).map { i -> async(Dispatchers.IO) { Link(cfg, m.hosts[i % m.hosts.size], shareName) } }.awaitAll()
        try {
            val total = AtomicLong()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS)
            val t0 = System.nanoTime()
            links.mapIndexed { i, link ->
                async(Dispatchers.IO) {
                    link.open(path).use { f ->
                        val chunk = link.chunk
                        val slots = maxOf(1L, size / chunk)
                        var next = (i * slots / links.size)
                        if (m.inFlight <= 1) {
                            val buf = ByteArray(chunk)
                            while (System.nanoTime() < deadline) {
                                val n = f.read(buf, (next % slots) * chunk, 0, chunk)
                                total.addAndGet(n.toLong()); next++
                            }
                        } else {
                            val queue = ArrayDeque<java.util.concurrent.Future<com.hierynomus.mssmb2.messages.SMB2ReadResponse>>()
                            while (System.nanoTime() < deadline || queue.isNotEmpty()) {
                                while (queue.size < m.inFlight && System.nanoTime() < deadline) {
                                    queue += VcsPipeline.readAsync(f, (next % slots) * chunk, chunk); next++
                                }
                                val resp = queue.removeFirst().get(40, TimeUnit.SECONDS)
                                total.addAndGet(resp.dataLength.toLong())
                            }
                        }
                    }
                }
            }.awaitAll()
            val secs = (System.nanoTime() - t0) / 1e9
            total.get() / 1_000_000.0 / secs
        } finally {
            links.forEach { it.close() }
        }
    }
}
