package io.github.poty87.viewercraftstar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Anteprime delle foto della libreria lette dal NAS: dai raw (DNG, CR2…) e dai TIFF si legge solo il JPEG che la
 * fotocamera ha incorporato (per la griglia il più piccolo che basta, per il visualizzatore il più grande); dai JPEG la
 * miniatura EXIF per la griglia, se c'è, altrimenti il file. Mostrano la foto come l'ha scattata la fotocamera, non con
 * le modifiche fatte in LightCraft. Le miniature restano anche sul telefono.
 */
/** Un file da leggere a pezzi: sul NAS (Remote.Reader) o, nei test, un file locale. */
interface Source {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

object RemotePreview {
    const val THUMB = 400
    const val FULL = 2400

    class Jpeg(val offset: Long, val length: Int, val width: Int, val height: Int)

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun diskFile(context: Context, lib: String, photo: LibPhoto) =
        File(context.cacheDir, "library/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "/${photo.id}_${photo.name.hashCode()}.jpg")

    /** La miniatura già sul telefono (nessun accesso al NAS), o null. */
    fun cachedThumb(context: Context, lib: String, photo: LibPhoto): Bitmap? {
        val key = "$lib:${photo.id}:$THUMB"
        memory.get(key)?.let { return it }
        val f = diskFile(context, lib, photo)
        return if (f.exists()) BitmapFactory.decodeFile(f.path)?.also { memory.put(key, it) } else null
    }

    suspend fun load(context: Context, cfg: NasConfig, index: LibIndex, photo: LibPhoto, maxSide: Int): Bitmap? {
        val key = "${index.library}:${photo.id}:$maxSide"
        memory.get(key)?.let { return it }
        if (maxSide <= THUMB) cachedThumb(context, index.library, photo)?.let { return it }
        val (share, path) = index.locate(photo) ?: return null
        val bytes = Remote.use(cfg, share) { s -> Remote.open(s, path).use { r -> pick(r, photo, maxSide) } } ?: return null
        val bmp = decode(bytes.first, maxSide, bytes.second) ?: return null
        memory.put(key, bmp)
        if (maxSide <= THUMB) runCatching {
            val f = diskFile(context, index.library, photo)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return bmp
    }

    /** I byte del JPEG da mostrare e l'orientamento (1 = dritta). */
    fun pick(r: Source, photo: LibPhoto, maxSide: Int): Pair<ByteArray, Int>? {
        val ext = photo.name.substringAfterLast('.', "").lowercase()
        if (ext == "jpg" || ext == "jpeg") {
            // la miniatura EXIF non ha un orientamento suo: vale quello della foto, letto dall'inizio del file
            if (maxSide <= THUMB) exifThumb(r)?.let { return it to orientationOfJpeg(r.read(0, 64 * 1024)) }
            if (r.size > 60L * 1024 * 1024) return null
            val all = r.read(0, r.size.toInt())
            return all to orientationOfJpeg(all)
        }
        if (ext == "png") return if (r.size < 40L * 1024 * 1024) r.read(0, r.size.toInt()) to 1 else null
        val (jpegs, orientation) = tiffJpegs(r) ?: return null
        if (jpegs.isEmpty()) return null
        val sized = jpegs.sortedBy { maxOf(it.width, it.height) }
        val chosen = if (maxSide <= THUMB) sized.firstOrNull { maxOf(it.width, it.height) >= THUMB } ?: sized.last()
        else sized.last()
        return r.read(chosen.offset, chosen.length) to orientation
    }

    private fun decode(bytes: ByteArray, maxSide: Int, orientation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val degrees = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    // ---------------------------------------------------------------- TIFF (DNG, CR2, TIF…)

    private class Ifd(val tags: Map<Int, LongArray>, val next: Long)

    /** I JPEG incorporati (IFD0, SubIFD, IFD successive) e l'orientamento. */
    fun tiffJpegs(r: Source): Pair<List<Jpeg>, Int>? {
        val head = r.read(0, 8)
        if (head.size < 8) return null
        val order = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val first = ByteBuffer.wrap(head).order(order).getInt(4).toLong() and 0xFFFFFFFFL
        val ifd0 = readIfd(r, first, order) ?: return null
        val orientation = ifd0.tags[0x112]?.firstOrNull()?.toInt() ?: 1
        val all = mutableListOf(ifd0)
        ifd0.tags[0x14A]?.forEach { off -> readIfd(r, off, order)?.let(all::add) }
        var next = ifd0.next
        var guard = 0
        while (next != 0L && guard++ < 8) {
            val ifd = readIfd(r, next, order) ?: break
            all += ifd
            next = ifd.next
        }
        val out = ArrayList<Jpeg>()
        for (ifd in all) {
            val t = ifd.tags
            val compression = t[0x103]?.firstOrNull()?.toInt() ?: 0
            val subType = t[0xFE]?.firstOrNull() ?: 0L
            val (offset, length) = when {
                t[0x201] != null && t[0x202] != null -> t.getValue(0x201)[0] to t.getValue(0x202)[0]
                // JPEG "vecchio" (6) va sempre bene; il 7 solo per le anteprime (nei raw il 7 è il dato raw senza perdite)
                (compression == 6 || (compression == 7 && subType == 1L)) &&
                    t[0x111]?.size == 1 && t[0x117]?.size == 1 -> t.getValue(0x111)[0] to t.getValue(0x117)[0]
                else -> continue
            }
            if (length <= 0 || length > 64L * 1024 * 1024 || offset + length > r.size) continue
            val w = t[0x100]?.firstOrNull()?.toInt() ?: 160
            val h = t[0x101]?.firstOrNull()?.toInt() ?: 120
            out += Jpeg(offset, length.toInt(), w, h)
        }
        // TIFF salvati da Photoshop: la miniatura JPEG nelle sue risorse (piccola, ma meglio di niente)
        if (out.isEmpty()) ifd0.tags[0x8649]?.let { (at, n) -> photoshopThumb(r, at, n)?.let(out::add) }
        // Olympus (ORF): l'anteprima sta nelle note del produttore, non nelle IFD normali
        if (out.none { maxOf(it.width, it.height) >= THUMB }) ifd0.tags[0x8769]?.firstOrNull()?.let { exifAt ->
            readIfd(r, exifAt, order)?.tags?.get(0x927C)?.let { (at, n) -> olympusPreview(r, at, n)?.let(out::add) }
        }
        return out to orientation
    }

    /** La miniatura JPEG (risorsa 0x040C) nelle risorse di Photoshop di un TIFF, o null. */
    private fun photoshopThumb(r: Source, at: Long, n: Long): Jpeg? = runCatching {
        if (n <= 0 || n > 4L * 1024 * 1024) return null
        val irb = r.read(at, n.toInt())
        val bb = ByteBuffer.wrap(irb).order(ByteOrder.BIG_ENDIAN)
        var i = 0
        while (i + 12 <= irb.size && String(irb, i, 4, Charsets.ISO_8859_1) == "8BIM") {
            val id = bb.getShort(i + 4).toInt() and 0xFFFF
            var j = i + 6 + 1 + (irb[i + 6].toInt() and 0xFF)
            if (j % 2 == 1) j++
            val size = bb.getInt(j)
            val data = j + 4
            if (id == 0x040C && size > 28) {
                return Jpeg(at + data + 28, size - 28, bb.getInt(data + 4), bb.getInt(data + 8))
            }
            i = data + size + (size % 2)
        }
        null
    }.getOrNull()

    /**
     * L'anteprima JPEG nelle note del produttore Olympus. Formato nuovo ("OLYMPUS\u0000" + ordine dei byte): IFD a +12,
     * CameraSettings (0x2020) con PreviewImageStart (0x101) e PreviewImageLength (0x102), offset dall'inizio delle note.
     * Formato vecchio ("OLYMP\u0000"): IFD a +8, PreviewImageStart (0x88) e Length (0x89) dall'inizio del file.
     */
    private fun olympusPreview(r: Source, at: Long, n: Long): Jpeg? = runCatching {
        val head = r.read(at, 12)
        val sig = String(head, Charsets.ISO_8859_1)
        val newStyle = sig.startsWith("OLYMPUS\u0000")
        if (!newStyle && !sig.startsWith("OLYMP\u0000")) return null
        val order = if (newStyle) (if (head[8] == 'M'.code.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        else ByteOrder.LITTLE_ENDIAN
        val note = readIfd(r, at + if (newStyle) 12 else 8, order) ?: return null
        val (start, length) = if (newStyle) {
            val cs = note.tags[0x2020]?.firstOrNull() ?: return null
            val sub = readIfd(r, at + cs, order) ?: return null
            val s0 = sub.tags[0x101]?.firstOrNull() ?: return null
            (at + s0) to (sub.tags[0x102]?.firstOrNull() ?: return null)
        } else {
            (note.tags[0x88]?.firstOrNull() ?: return null) to (note.tags[0x89]?.firstOrNull() ?: return null)
        }
        if (length <= 0 || start + length > r.size) return null
        Jpeg(start, length.toInt(), 3200, 2400) // dimensioni vere non scritte: è comunque l'anteprima grande
    }.getOrNull()

    private fun readIfd(r: Source, offset: Long, order: ByteOrder): Ifd? {
        if (offset <= 0 || offset >= r.size) return null
        val countBytes = r.read(offset, 2)
        if (countBytes.size < 2) return null
        val count = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xFFFF
        if (count == 0 || count > 1000) return null
        val raw = r.read(offset + 2, count * 12 + 4)
        if (raw.size < count * 12 + 4) return null
        val buf = ByteBuffer.wrap(raw).order(order)
        val tags = HashMap<Int, LongArray>()
        repeat(count) { i ->
            val e = i * 12
            val tag = buf.getShort(e).toInt() and 0xFFFF
            val type = buf.getShort(e + 2).toInt() and 0xFFFF
            val n = buf.getInt(e + 4).toLong() and 0xFFFFFFFFL
            if (tag == 0x927C || tag == 0x8649) { // MakerNote e risorse di Photoshop: solo dove stanno
                tags[tag] = longArrayOf(if (n <= 4) offset + 2 + e + 8 else buf.getInt(e + 8).toLong() and 0xFFFFFFFFL, n)
                return@repeat
            }
            val size = when (type) { 3 -> 2; 4, 13 -> 4; else -> 0 }
            if (size == 0 || n == 0L || n > 64) return@repeat
            val values = LongArray(n.toInt())
            if (size * n <= 4) {
                for (k in 0 until n.toInt()) values[k] = if (size == 2) (buf.getShort(e + 8 + 2 * k).toLong() and 0xFFFF)
                else (buf.getInt(e + 8).toLong() and 0xFFFFFFFFL)
            } else {
                val at = buf.getInt(e + 8).toLong() and 0xFFFFFFFFL
                val data = r.read(at, (size * n).toInt())
                if (data.size < size * n) return@repeat
                val d = ByteBuffer.wrap(data).order(order)
                for (k in 0 until n.toInt()) values[k] = if (size == 2) (d.getShort(2 * k).toLong() and 0xFFFF)
                else (d.getInt(4 * k).toLong() and 0xFFFFFFFFL)
            }
            tags[tag] = values
        }
        return Ifd(tags, buf.getInt(count * 12).toLong() and 0xFFFFFFFFL)
    }

    // ---------------------------------------------------------------- JPEG

    /** La miniatura nell'EXIF di un JPEG (di solito 160×120), letta dai primi 64 KB, oppure null. */
    private fun exifThumb(r: Source): ByteArray? {
        val head = r.read(0, 64 * 1024)
        if (head.size < 12 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        var at = 2
        while (at + 4 < head.size) {
            if (head[at] != 0xFF.toByte()) return null
            val marker = head[at + 1].toInt() and 0xFF
            val len = ((head[at + 2].toInt() and 0xFF) shl 8) or (head[at + 3].toInt() and 0xFF)
            if (marker == 0xE1 && at + 10 < head.size && String(head, at + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val tiff = at + 10
                val order = if (head[tiff] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                val bb = ByteBuffer.wrap(head).order(order)
                fun u32(p: Int) = bb.getInt(p).toLong() and 0xFFFFFFFFL
                fun u16(p: Int) = bb.getShort(p).toInt() and 0xFFFF
                val ifd0 = tiff + u32(tiff + 4).toInt()
                if (ifd0 + 2 > head.size) return null
                val n0 = u16(ifd0)
                val ifd1Ptr = ifd0 + 2 + n0 * 12
                if (ifd1Ptr + 4 > head.size) return null
                val ifd1 = u32(ifd1Ptr).toInt()
                if (ifd1 == 0) return null
                val p1 = tiff + ifd1
                if (p1 + 2 > head.size) return null
                var off = -1L
                var length = -1L
                for (i in 0 until u16(p1)) {
                    val e = p1 + 2 + i * 12
                    if (e + 12 > head.size) break
                    when (u16(e)) { 0x201 -> off = u32(e + 8); 0x202 -> length = u32(e + 8) }
                }
                if (off <= 0 || length <= 0) return null
                val start = tiff + off.toInt()
                return if (start + length <= head.size) head.copyOfRange(start, start + length.toInt())
                else r.read((tiff + off), length.toInt())
            }
            if (marker == 0xDA) return null
            at += 2 + len
        }
        return null
    }

    private fun orientationOfJpeg(bytes: ByteArray): Int = runCatching {
        android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
    }.getOrDefault(1)
}
