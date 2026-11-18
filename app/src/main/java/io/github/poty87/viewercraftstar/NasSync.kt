package io.github.poty87.viewercraftstar

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Scrive gli .xmp nella cartella del NAS, accanto ai DNG che Synology Drive ha già caricato.
 *
 * Sul NAS scrive SOLO file .xmp, e solo accanto a un DNG che c'è già: non crea cartelle, non cancella e non modifica i
 * DNG. Se il DNG non è ancora arrivato (caricamento in corso), la foto resta in coda per il prossimo invio.
 */
object NasSync {
    data class Result(val sent: Set<String>, val waiting: Set<String>, val failed: Map<String, String>, val dngOnNas: Int,
                      val catalog: String? = null, val status: String? = null, val heartbeat: String? = null,
                      val names: Set<String> = emptySet())

    /** Quello che c'è sul NAS: preset, resoconto, battito del PC di casa, DNG presenti (nomi in minuscolo). */
    class Look(val catalog: String?, val status: String?, val heartbeat: String?, val names: Set<String>)

    /** Cartelle di servizio di Synology e Windows: non si guardano. */
    private val SKIP = setOf("@eadir", "#recycle", "#snapshot", ".synologyworkingdirectory", "\$recycle.bin")

    private fun client() = SMBClient(
        SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build()
    )

    private fun <T> withShare(cfg: NasConfig, work: (DiskShare) -> T): T {
        client().use { client ->
            client.connect(cfg.host).use { conn ->
                val (domain, user) = if ('\\' in cfg.user) cfg.user.substringBefore('\\') to cfg.user.substringAfter('\\')
                else "" to cfg.user
                val session = conn.authenticate(AuthenticationContext(user, cfg.password.toCharArray(), domain))
                (session.connectShare(cfg.share) as DiskShare).use { return work(it) }
            }
        }
    }

    /** Carica file di preset in .viewercraftstar\\import: PresetCraftAppLicator li importa nella libreria LightCraft. */
    fun uploadPresets(cfg: NasConfig, files: List<Pair<String, ByteArray>>): Int = withShare(cfg) { share ->
        var dir = cfg.folder
        for (part in listOf(".viewercraftstar", "import")) {
            dir = if (dir.isEmpty()) part else "$dir\\$part"
            if (!share.folderExists(dir)) share.mkdir(dir)
        }
        var n = 0
        for ((name, bytes) in files) {
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val target = "$dir\\$safe"
            val tmp = "$dir\\.$safe.vcs-tmp" // nascosto finché non è completo: PresetCraftAppLicator lo ignora
            share.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.outputStream.use { it.write(bytes) }
                f.rename(target, true)
            }
            n++
        }
        n
    }

    /** I DNG nella cartella del NAS: nome in minuscolo -> cartella (relativa alla condivisione) in cui si trova. */
    private fun index(share: DiskShare, root: String, recursive: Boolean): Map<String, String> {
        val found = HashMap<String, String>()
        val stack = ArrayDeque(listOf(root))
        var folders = 0
        while (stack.isNotEmpty() && folders++ < 5000) {
            val dir = stack.removeLast()
            for (e in share.list(dir)) {
                val name = e.fileName
                if (name == "." || name == ".." || name.startsWith(".")) continue
                val isDir = (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                if (isDir) {
                    if (recursive && name.lowercase() !in SKIP) stack.addLast(if (dir.isEmpty()) name else "$dir\\$name")
                } else if (name.endsWith(".vcs-tmp", ignoreCase = true) &&
                    System.currentTimeMillis() - e.lastWriteTime.toEpochMillis() > 60 * 60 * 1000) {
                    // temporaneo di un invio interrotto più di un'ora fa: si toglie (se il NAS lo tiene ancora aperto, al prossimo giro)
                    runCatching { share.rm(if (dir.isEmpty()) name else "$dir\\$name") }
                } else if (name.endsWith(".dng", ignoreCase = true)) {
                    found.putIfAbsent(name.lowercase(), dir)
                }
            }
        }
        return found
    }

    /** Prova la connessione: quanti DNG vede nella cartella, e l'elenco dei preset (o null). */
    fun test(cfg: NasConfig): Pair<Int, String?> = withShare(cfg) { share ->
        index(share, cfg.folder, cfg.subfolders).size to catalog(share, cfg)
    }

    /** L'elenco dei preset della libreria, pubblicato da PresetCraftAppLicator in .viewercraftstar/presets.json. */
    private fun catalog(share: DiskShare, cfg: NasConfig): String? = readHome(share, cfg, "presets.json")

    /** Il resoconto di PresetCraftAppLicator: foto in libreria e versione dei metadati applicata. */
    private fun status(share: DiskShare, cfg: NasConfig): String? = readHome(share, cfg, "status.json")

    /** Elenco dei preset, resoconto e nomi (in minuscolo) dei DNG presenti nella cartella del NAS. */
    fun look(cfg: NasConfig): Look = withShare(cfg) { share ->
        Look(catalog(share, cfg), status(share, cfg), readHome(share, cfg, "heartbeat.json"),
            index(share, cfg.folder, cfg.subfolders).keys)
    }

    /** Elenco dei preset e resoconto, senza inviare niente. */
    fun fetch(cfg: NasConfig): Pair<String?, String?> = withShare(cfg) { share -> catalog(share, cfg) to status(share, cfg) }

    private fun readHome(share: DiskShare, cfg: NasConfig, file: String): String? = runCatching {
        val path = (if (cfg.folder.isEmpty()) "" else cfg.folder + "\\") + ".viewercraftstar\\" + file
        if (!share.fileExists(path)) return null
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { f -> f.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } }
    }.getOrNull()

    /** Invia gli .xmp delle foto indicate (nome del DNG -> metadati). */
    fun send(cfg: NasConfig, items: Map<String, PhotoMeta>): Result = withShare(cfg) { share ->
        val where = index(share, cfg.folder, cfg.subfolders)
        val sent = HashSet<String>()
        val waiting = HashSet<String>()
        val failed = HashMap<String, String>()
        for ((name, meta) in items) {
            val dir = where[name.lowercase()]
            if (dir == null) {
                waiting += name
                continue
            }
            val stem = name.substringBeforeLast('.')
            val target = if (dir.isEmpty()) "$stem.xmp" else "$dir\\$stem.xmp"
            // nome unico a ogni invio: se la rete cade a metà, il NAS può tenere aperto il file per un po'; così il
            // manico rimasto resta su un file temporaneo orfano e non finisce mai sull'.xmp vero
            val tmp = "$target.${System.currentTimeMillis()}.vcs-tmp"
            try {
                val bytes = Xmp.build(meta).toByteArray(Charsets.UTF_8)
                share.openFile(
                    tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null
                ).use { f ->
                    f.outputStream.use { it.write(bytes) }
                    f.rename(target, true) // scrittura atomica: chi legge trova il file vecchio o quello nuovo
                }
                sent += name
            } catch (e: Exception) {
                failed[name] = e.message ?: e.javaClass.simpleName
                runCatching { if (share.fileExists(tmp)) share.rm(tmp) }
            }
        }
        Result(sent, waiting, failed, where.size, catalog(share, cfg), status(share, cfg),
            readHome(share, cfg, "heartbeat.json"), where.keys)
    }
}
