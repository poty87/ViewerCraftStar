package io.github.poty87.viewercraftstar

import android.content.Context
import android.content.Intent
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup della configurazione dell'app in un file zip nella cartella di servizio del NAS
 * (`<cartella di servizio>\backups\ViewerCraftStar\`). Contiene:
 * - `settings.json`: tutte le impostazioni, TRANNE la password del NAS (è cifrata con una chiave che esiste solo su
 *   questo telefono: altrove non servirebbe, e in chiaro sul NAS non deve stare);
 * - `metadata.json`: stelle, bandierine, etichette e testi delle foto del telefono, anche quelli non ancora spediti;
 * - `libraries/<libreria>/edits.json`: le modifiche fatte nelle Librerie e non ancora applicate dal PC di casa;
 * - `info.json`: versione dell'app, data e modello del telefono.
 * Le librerie, le anteprime e il modello IA non ci sono: si riscaricano dal NAS.
 */
object ConfigBackup {
    const val DIR = "backups\\ViewerCraftStar"
    private const val KEEP = 20 // i backup più vecchi oltre questi si cancellano
    private val SKIP = setOf("nas_password", "vpn_user", "vpn_password", "adguard_password", "adguard_paused_by_us", "nas_guid") // credenziali: si riscrivono

    data class Entry(val name: String, val size: Long, val time: Long)

    private fun where(s: Settings): Pair<String, String> {
        val share = s.libShare.ifEmpty { s.nas.share }
        val dir = if (s.libFolder.isEmpty()) DIR else "${s.libFolder}\\$DIR"
        return share to dir
    }

    fun ready(s: Settings) = s.nas.complete && s.libFolder.isNotEmpty()

    // ---------------------------------------------------------------- profilo VPN nella cartella di servizio

    private fun vpnWhere(s: Settings): Pair<String, String> {
        val share = s.libShare.ifEmpty { s.nas.share }
        val dir = if (s.libFolder.isEmpty()) "vpn" else "${s.libFolder}\\vpn"
        return share to dir
    }

    /** Copia lo zip del profilo VPN nella cartella di servizio (`<cartella di servizio>\vpn\openvpn.zip`). */
    suspend fun putVpn(s: Settings, bytes: ByteArray) {
        val (share, dir) = vpnWhere(s)
        Remote.use(s.nas, share) { sh ->
            var d = ""
            for (part in dir.split('\\').filter { it.isNotEmpty() }) {
                d = if (d.isEmpty()) part else "$d\\$part"
                if (!sh.folderExists(d)) sh.mkdir(d)
            }
            sh.openFile("$dir\\.openvpn.zip.tmp", EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.outputStream.use { it.write(bytes) }
                f.rename("$dir\\openvpn.zip", true)
            }
        }
    }

    /** Lo zip del profilo VPN dalla cartella di servizio (null se non c'è). */
    suspend fun getVpn(s: Settings): ByteArray? {
        val (share, dir) = vpnWhere(s)
        return Remote.use(s.nas, share) { sh ->
            if (!sh.fileExists("$dir\\openvpn.zip")) null
            else Remote.open(sh, "$dir\\openvpn.zip").use { it.read(0, it.size.toInt()) }
        }
    }

    /** Lo zip, costruito in memoria (pochi KB). */
    fun build(context: Context): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            val prefs = JSONObject()
            for ((k, v) in context.getSharedPreferences("settings", Context.MODE_PRIVATE).all) {
                if (k in SKIP || v == null) continue
                val type = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "set"; else -> "s" }
                prefs.put(k, JSONObject().put("t", type).put("v", if (v is Set<*>) org.json.JSONArray(v.toList()) else v))
            }
            put("settings.json", prefs.toString(1).toByteArray())
            File(context.filesDir, "metadata.json").takeIf { it.isFile }?.let { put("metadata.json", it.readBytes()) }
            File(context.filesDir, "libraries").listFiles()?.forEach { d ->
                File(d, "edits.json").takeIf { it.isFile }?.let { put("libraries/${d.name}/edits.json", it.readBytes()) }
            }
            Vpn.zipFile(context).takeIf { it.isFile }?.let { put("vpn/openvpn.zip", it.readBytes()) }
            put("info.json", JSONObject().put("app", "ViewerCraftStar").put("version", BuildConfigInfo.version)
                .put("created", System.currentTimeMillis()).put("device", android.os.Build.MODEL).toString(1).toByteArray())
        }
        return out.toByteArray()
    }

    /** Salva un backup nuovo sul NAS e tiene solo gli ultimi [KEEP]. Restituisce il nome del file. */
    suspend fun save(context: Context, s: Settings): String {
        val bytes = build(context)
        val (share, dir) = where(s)
        val name = "ViewerCraftStar " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date()) + ".zip"
        Remote.use(s.nas, share) { sh ->
            var d = ""
            for (part in dir.split('\\').filter { it.isNotEmpty() }) {
                d = if (d.isEmpty()) part else "$d\\$part"
                if (!sh.folderExists(d)) sh.mkdir(d)
            }
            val tmp = "$dir\\.$name.tmp"
            sh.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { f ->
                f.outputStream.use { it.write(bytes) }
                f.rename("$dir\\$name", true)
            }
            // i più vecchi oltre KEEP: via (sono file nostri, nella nostra cartella)
            sh.list(dir).map { it.fileName }.filter { it.startsWith("ViewerCraftStar ") && it.endsWith(".zip") }
                .sortedDescending().drop(KEEP).forEach { runCatching { sh.rm("$dir\\$it") } }
        }
        return name
    }

    /** I backup sul NAS, dal più recente. */
    suspend fun list(s: Settings): List<Entry> {
        val (share, dir) = where(s)
        return Remote.use(s.nas, share) { sh ->
            if (!sh.folderExists(dir)) emptyList()
            else sh.list(dir).filter { it.fileName.startsWith("ViewerCraftStar ") && it.fileName.endsWith(".zip") }
                .map { Entry(it.fileName, it.endOfFile, it.lastWriteTime.toEpochMillis()) }
                .sortedByDescending { it.name }
        }
    }

    /**
     * Ripristina un backup: impostazioni (la password del NAS resta quella di adesso), metadati delle foto e modifiche
     * delle librerie in attesa. Poi l'app va riavviata ([restart]) perché rilegga tutto.
     */
    suspend fun restore(context: Context, s: Settings, name: String) {
        val (share, dir) = where(s)
        val bytes = Remote.use(s.nas, share) { sh -> Remote.open(sh, "$dir\\$name").use { it.read(0, it.size.toInt()) } }
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = zip.readBytes()
            }
        }
        val settingsJson = files["settings.json"] ?: error(L.t("Nel file manca settings.json: non è un backup di ViewerCraftStar.",
            "settings.json is missing: this isn't a ViewerCraftStar backup."))
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val keepPassword = prefs.getString("nas_password", null)
        val json = JSONObject(String(settingsJson))
        val ed = prefs.edit().clear()
        for (k in json.keys()) {
            if (k in SKIP) continue
            val o = json.getJSONObject(k)
            when (o.getString("t")) {
                "b" -> ed.putBoolean(k, o.getBoolean("v"))
                "i" -> ed.putInt(k, o.getInt("v"))
                "l" -> ed.putLong(k, o.getLong("v"))
                "f" -> ed.putFloat(k, o.getDouble("v").toFloat())
                "set" -> ed.putStringSet(k, o.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> ed.putString(k, o.getString("v"))
            }
        }
        if (keepPassword != null) ed.putString("nas_password", keepPassword)
        ed.commit()
        files["metadata.json"]?.let { File(context.filesDir, "metadata.json").writeBytes(it) }
        files["vpn/openvpn.zip"]?.let { z -> Vpn.zipFile(context).let { it.parentFile?.mkdirs(); it.writeBytes(z) } }
        for ((n, b) in files) {
            if (!n.startsWith("libraries/") || !n.endsWith("/edits.json") || ".." in n) continue
            val f = File(context.filesDir, n)
            f.parentFile?.mkdirs()
            f.writeBytes(b)
        }
    }

    /** Riavvia l'app: impostazioni e metadati ripristinati vengono riletti da capo. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }
}
