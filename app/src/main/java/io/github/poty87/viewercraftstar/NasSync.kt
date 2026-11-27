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
 * Nella cartella delle foto scrive SOLO file .xmp, e solo accanto a un DNG che c'è già: non crea cartelle, non cancella e
 * non modifica i DNG. Se il DNG non è ancora arrivato (caricamento in corso), la foto resta in coda per il prossimo invio.
 *
 * Preset, resoconto e battito del PC di casa stanno nella cartella di servizio di PresetCraftAppLicator, nella cartella
 * della libreria che riceve le foto (quella il cui uploads.json elenca la cartella del NAS impostata qui); lì vanno
 * anche i preset caricati dal telefono (import).
 */
object NasSync {
    data class Result(val sent: Set<String>, val waiting: Set<String>, val failed: Map<String, String>, val dngOnNas: Int,
                      val catalog: String? = null, val status: String? = null, val heartbeat: String? = null,
                      val names: Set<String> = emptySet())

    /** Quello che c'è sul NAS: preset, resoconto, battito del PC di casa, DNG presenti (nomi in minuscolo). */
    class Look(val catalog: String?, val status: String?, val heartbeat: String?, val names: Set<String>)

    /** Cartelle di servizio dei NAS (Synology, QNAP) e di Windows: non si guardano. */
    private val SKIP = setOf("@eadir", "#recycle", "#snapshot", ".synologyworkingdirectory", "\$recycle.bin",
        "@recycle", ".@__thumb", "@recently-snapshot", ".streams")

    private fun client() = SMBClient(
        SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build()
    )

    /** La cartella della libreria che riceve le foto, nella cartella di servizio; `status`: il suo resoconto. */
    private class Home(val share: DiskShare, val dir: String, val status: String?)

    private fun <T> withShares(cfg: NasConfig, work: (DiskShare, Home?) -> T): T {
        client().use { client ->
            client.connect(cfg.host).use { conn ->
                val (domain, user) = if ('\\' in cfg.user) cfg.user.substringBefore('\\') to cfg.user.substringAfter('\\')
                else "" to cfg.user
                val session = conn.authenticate(AuthenticationContext(user, cfg.password.toCharArray(), domain))
                NasId.learn(conn)
                (session.connectShare(cfg.share) as DiskShare).use { share ->
                    val libShare = cfg.libShare.ifEmpty { cfg.share }
                    if (cfg.libFolder.isEmpty()) return work(share, null)
                    if (libShare.equals(cfg.share, ignoreCase = true)) return work(share, home(share, cfg))
                    (session.connectShare(libShare) as DiskShare).use { other -> return work(share, home(other, cfg)) }
                }
            }
        }
    }

    private fun norm(path: String) = path.replace('/', '\\').trim('\\').lowercase()

    /** Ultima libreria trovata per questa cartella (per non rileggere ogni volta gli uploads.json di tutte). */
    @Volatile private var lastHome: Pair<String, String>? = null

    /**
     * Cerca, tra le librerie della cartella di servizio, quella che riceve le foto della cartella del NAS impostata qui
     * (uploads.json, scritto da PresetCraftAppLicator per ogni cartella osservata).
     */
    private fun home(share: DiskShare, cfg: NasConfig): Home? = runCatching {
        val root = listOf(cfg.libFolder, LibraryStore.LIBRARIES_DIR).filter { it.isNotEmpty() }.joinToString("\\")
        val wanted = cfg.share.lowercase() to norm(cfg.folder)
        val names = share.list(root).filter { e ->
            (e.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L && !e.fileName.startsWith(".")
        }.map { it.fileName }
        val known = lastHome?.takeIf { it.first == "${wanted.first}|${wanted.second}" }?.second
        for (name in listOfNotNull(known) + (names - setOfNotNull(known))) {
            val dir = "$root\\$name"
            val uploads = read(share, "$dir\\uploads.json") ?: continue
            val folders = org.json.JSONObject(uploads).optJSONArray("folders") ?: continue
            for (i in 0 until folders.length()) {
                val f = folders.getJSONObject(i)
                if (f.optString("share").lowercase() == wanted.first && norm(f.optString("dir")) == wanted.second) {
                    lastHome = "${wanted.first}|${wanted.second}" to name
                    val status = f.optString("status").takeIf { it.isNotEmpty() }?.let { read(share, "$dir\\$it") }
                    return@runCatching Home(share, dir, status)
                }
            }
        }
        null
    }.getOrNull()

    private fun read(share: DiskShare, path: String): String? = runCatching {
        if (!share.fileExists(path)) return null
        share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null).use { f -> f.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } }
    }.getOrNull()

    /**
     * Carica file di preset nella cartella import della libreria che riceve le foto (cartella di servizio):
     * PresetCraftAppLicator li importa nella libreria LightCraft.
     */
    fun uploadPresets(cfg: NasConfig, files: List<Pair<String, ByteArray>>): Int = withShares(cfg) { _, home ->
        home ?: error(L.t("La cartella di servizio non indica la libreria che riceve le foto: controlla Impostazioni › Libreria di casa e la cartella osservata in PresetCraftAppLicator.",
            "The service folder doesn't show which library receives the photos: check Settings › Home library and the watched folder in PresetCraftAppLicator."))
        val share = home.share
        val dir = "${home.dir}\\import"
        if (!share.folderExists(dir)) share.mkdir(dir)
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
    fun test(cfg: NasConfig): Pair<Int, String?> = withShares(cfg) { share, home ->
        index(share, cfg.folder, cfg.subfolders).size to catalog(home)
    }

    /** L'elenco dei preset della libreria, pubblicato da PresetCraftAppLicator (presets.json nella cartella di servizio). */
    private fun catalog(home: Home?): String? = home?.let { read(it.share, "${it.dir}\\presets.json") }

    private fun heartbeat(home: Home?): String? = home?.let { read(it.share, "${it.dir}\\heartbeat.json") }

    /** Elenco dei preset, resoconto e nomi (in minuscolo) dei DNG presenti nella cartella del NAS. */
    fun look(cfg: NasConfig): Look = withShares(cfg) { share, home ->
        Look(catalog(home), home?.status, heartbeat(home), index(share, cfg.folder, cfg.subfolders).keys)
    }

    /** Invia gli .xmp delle foto indicate (nome del DNG -> metadati). */
    fun send(cfg: NasConfig, items: Map<String, PhotoMeta>): Result = withShares(cfg) { share, home ->
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
        Result(sent, waiting, failed, where.size, catalog(home), home?.status, heartbeat(home), where.keys)
    }
}
