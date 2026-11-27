package io.github.poty87.viewercraftstar

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * I raw che LightCraft apre (lo stesso elenco dell'importazione di PresetCraftAppLicator): il telefono li importa,
 * li fa rivedere e li spedisce come i DNG. Solo i profili della fotocamera e l'import diretto via Wi-Fi restano per le
 * Ricoh GR; le foto delle altre fotocamere entrano in LightCraft con il suo profilo predefinito.
 */
object RawFormats {
    /** Estensione → tipo MIME (quello che Android conosce, per metterli nelle cartelle Foto). */
    private val MIME = mapOf(
        "dng" to "image/x-adobe-dng", "cr2" to "image/x-canon-cr2", "cr3" to "image/x-canon-cr3",
        "crw" to "image/x-canon-crw", "nef" to "image/x-nikon-nef", "nrw" to "image/x-nikon-nrw",
        "arw" to "image/x-sony-arw", "srf" to "image/x-sony-srf", "sr2" to "image/x-sony-sr2",
        "orf" to "image/x-olympus-orf", "rw2" to "image/x-panasonic-rw2", "rwl" to "image/x-leica-rwl",
        "raf" to "image/x-fuji-raf", "pef" to "image/x-pentax-pef", "srw" to "image/x-samsung-srw",
        "x3f" to "image/x-sigma-x3f", "3fr" to "image/x-hasselblad-3fr", "iiq" to "image/x-phaseone-iiq",
        "erf" to "image/x-epson-erf", "mrw" to "image/x-minolta-mrw", "kdc" to "image/x-kodak-kdc",
        "dcr" to "image/x-kodak-dcr",
    )
    val EXTENSIONS: Set<String> = MIME.keys

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isRaw(name: String) = ext(name) in MIME
    fun isJpeg(name: String) = ext(name) in setOf("jpg", "jpeg")

    /** Il tipo MIME da dare ad Android: quello che conosce il sistema, se no il nostro. */
    fun phoneMime(name: String): String =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name))?.takeIf { it.startsWith("image/") }
            ?: mime(name)

    private val accepted = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Android accetta questo tipo di file nelle cartelle Foto? Su Android 17 i CR3 no («Unsupported MIME type»), e il
     * registro dei tipi non basta a saperlo: la prima volta si prova davvero (una voce vuota, tolta subito), poi si ricorda.
     * Da chiamare fuori dal thread dell'interfaccia.
     */
    fun phoneAccepts(context: android.content.Context, name: String): Boolean = accepted.getOrPut(ext(name)) {
        if (isJpeg(name)) return@getOrPut true
        val cr = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, ".vcs-probe-${System.nanoTime()}.${ext(name)}")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, phoneMime(name))
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        runCatching {
            val uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) runCatching { cr.delete(uri, null, null) }
            uri != null
        }.getOrDefault(false)
    }

    /** Il tipo MIME del file (raw, JPEG, TIFF, PNG); generico (immagine qualsiasi) se non si conosce. */
    fun mime(name: String): String = MIME[ext(name)] ?: when (ext(name)) {
        "jpg", "jpeg" -> "image/jpeg"; "tif", "tiff" -> "image/tiff"; "png" -> "image/png"; else -> "image/*"
    }
}

/** Un file del telefono (o di un disco esterno) letto a pezzi, come quelli del NAS: per il lettore delle anteprime. */
class LocalSource(pfd: ParcelFileDescriptor) : Source, AutoCloseable {
    private val stream = FileInputStream(pfd.fileDescriptor)
    private val channel: FileChannel = stream.channel
    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        val n = minOf(length.toLong(), maxOf(0L, size - offset)).toInt()
        if (n <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(n)
        var at = offset
        while (buf.hasRemaining()) { val r = channel.read(buf, at); if (r <= 0) break; at += r }
        return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
    }

    override fun close() = stream.close()
}

/** L'anteprima di un file locale con lo stesso lettore della Libreria (DNG, CR2, CR3, NEF, ARW, RAF, ORF, RW2, PEF…). */
object LocalPreview {
    fun decode(cr: ContentResolver, uri: Uri, name: String, maxSide: Int): Bitmap? =
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            LocalSource(pfd).use { s -> RemotePreview.pick(s, name, maxSide)?.let { RemotePreview.decode(it, maxSide) } }
        }
}
