package io.github.poty87.viewercraftstar

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import java.io.File

/**
 * Import da un disco esterno collegato al telefono (chiavetta, disco USB, lettore di schede): Android non lascia leggere
 * direttamente le memorie USB, si passa dal selettore di cartelle di sistema. La prima volta il selettore si apre già sul
 * disco collegato (basta «Usa questa cartella» e «Consenti»); il permesso resta, e le volte dopo quel disco si apre da
 * solo. Le foto si copiano come quelle della fotocamera: i DNG nella cartella delle foto nuove, i JPEG nella galleria,
 * con il nome «millisecondi dello scatto_nome» (come GR World e come le foto già sul NAS).
 */
object ExternalDisk {
    private const val TAG = "VcsDisk"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val EXT = setOf("dng", "jpg", "jpeg")

    /** Una foto sul disco. `dir`: la cartella (percorso dentro il disco), per riconoscere il JPEG gemello di un DNG. */
    data class Item(val uri: Uri, val name: String, val dir: String, val size: Long, val modified: Long) {
        val key: String get() = uri.toString()
        val isDng: Boolean get() = name.endsWith(".dng", true)
        val isJpeg: Boolean get() = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
    }

    /** I dischi rimovibili collegati e montati (non la memoria del telefono). */
    fun volumes(context: Context): List<StorageVolume> =
        context.getSystemService(StorageManager::class.java).storageVolumes
            .filter { it.isRemovable && !it.isPrimary && it.state == android.os.Environment.MEDIA_MOUNTED && it.uuid != null }

    /** Il permesso già dato (in una volta precedente) per una cartella di questo disco: la radice se c'è, se no la prima. */
    fun grantedTree(context: Context, volume: StorageVolume): Uri? {
        val uuid = volume.uuid ?: return null
        val trees = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
            .filter { it.authority == AUTHORITY && runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull()?.startsWith("$uuid:") == true }
        return trees.firstOrNull { DocumentsContract.getTreeDocumentId(it) == "$uuid:" } ?: trees.firstOrNull()
    }

    /** Il selettore di cartelle di Android: già sul disco, se se ne conosce uno; altrimenti dove decide Android. */
    fun pickIntent(volume: StorageVolume?): Intent =
        (volume?.createOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    /** Tiene il permesso della cartella scelta (vale anche dopo aver staccato e ricollegato il disco). */
    fun keep(context: Context, tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { Log.w(TAG, "permesso non conservato per $tree", it) }
    }

    /** Il nome del disco come lo mostra Android («SanDisk», «Disco USB»…), dalla cartella scelta. */
    fun label(context: Context, tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return ""
        val uuid = id.substringBefore(':')
        val vol = volumes(context).firstOrNull { it.uuid == uuid }
        val name = vol?.getDescription(context) ?: if (uuid == "primary") L.t("Memoria del telefono", "Phone storage") else uuid
        val sub = id.substringAfter(':', "")
        return if (sub.isEmpty()) name else "$name › ${sub.replace("/", " › ")}"
    }

    /**
     * Tutte le foto (DNG e JPEG) nella cartella scelta e nelle sue sottocartelle (le cartelle nascoste no).
     * `found` riceve il numero trovato finora, per dire a che punto è su un disco grande.
     */
    fun list(context: Context, tree: Uri, found: (Int) -> Unit = {}): List<Item> {
        val cr = context.contentResolver
        val out = ArrayList<Item>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val queue = ArrayDeque<Pair<String, String>>() // (id della cartella, percorso)
        queue += DocumentsContract.getTreeDocumentId(tree) to ""
        while (queue.isNotEmpty()) {
            val (dirId, path) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
            runCatching {
                cr.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue += id to (if (path.isEmpty()) name else "$path/$name")
                        } else if (name.substringAfterLast('.', "").lowercase() in EXT) {
                            out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, path, c.getLong(3), c.getLong(4))
                            if (out.size % 50 == 0) found(out.size)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "cartella $path non letta", it) }
        }
        found(out.size)
        return out
    }

    /** La data di scatto (EXIF, nel fuso del telefono) in millisecondi, o 0 se non si legge. */
    fun taken(context: Context, uri: Uri): Long = runCatching {
        val t = context.contentResolver.openInputStream(uri)!!.use {
            android.media.ExifInterface(it).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
        } ?: return 0L
        java.time.LocalDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** La miniatura: l'anteprima dentro il DNG, la miniatura EXIF del JPEG (o il JPEG ridotto). Tenuta anche sul disco del telefono. */
    fun thumbnail(context: Context, item: Item): Bitmap? {
        val key = "${item.key}:${item.modified}"
        memory.get(key)?.let { return it }
        val disk = File(context.cacheDir, "disk-thumbs/" + Integer.toHexString(key.hashCode()) + "_${item.size}.jpg")
        if (disk.exists()) BitmapFactory.decodeFile(disk.path)?.let { memory.put(key, it); return it }
        val bmp = runCatching {
            if (item.isDng) DngPreview.decode(context.contentResolver, item.uri, DngPreview.THUMB)
            else jpegThumb(context, item.uri)
        }.getOrNull() ?: return null
        memory.put(key, bmp)
        runCatching { disk.parentFile?.mkdirs(); disk.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        return bmp
    }

    private fun jpegThumb(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            android.media.ExifInterface(pfd.fileDescriptor).thumbnailBitmap?.let { return it }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DngPreview.THUMB) sample *= 2
        return cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    /**
     * Copia una foto nel telefono, come lo scarico dalla fotocamera (GrCamera.download): nella cartella `folder`, con il
     * nome «millisecondi dello scatto_nome» se la data si legge. `onBytes`: byte copiati e totali.
     */
    fun copy(context: Context, item: Item, folder: String, taken: Long, onBytes: (Long, Long) -> Unit): Boolean {
        val mime = when (item.name.substringAfterLast('.', "").lowercase()) { "dng" -> "image/x-adobe-dng"; else -> "image/jpeg" }
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, if (taken > 0) "${taken}_${item.name}" else item.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            cr.openOutputStream(uri)!!.use { out ->
                cr.openInputStream(item.uri)!!.use { inp ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onBytes(done, item.size)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "copia di ${item.name} non riuscita", it) }.isSuccess
        if (ok) cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        else runCatching { cr.delete(uri, null, null) }
        return ok
    }
}
