package io.github.poty87.viewercraftstar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
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

    /**
     * Un JPEG incorporato. Se `tiles` non è null l'anteprima è divisa in tasselli JPEG da `tileW`×`tileH` (DNG salvati
     * dalle versioni recenti di Lightroom/Camera Raw, compressione 34892), da rimettere insieme riga per riga.
     */
    class Jpeg(val offset: Long, val length: Int, val width: Int, val height: Int,
               val tiles: List<Pair<Long, Int>>? = null, val tileW: Int = 0, val tileH: Int = 0,
               val develop: Develop? = null)

    /**
     * Come "sviluppare" i Fast Load Data che Lightroom mette nei DNG (tasselli con Photometric = LinearRaw): non sono
     * un'anteprima finita ma i colori del sensore. Per ogni canale un polinomio (OpcodeList2, MapPolynomial) li riporta
     * lineari; `matrix` (3×3, per righe) li porta in sRGB lineare già col bilanciamento del bianco dello scatto e
     * l'esposizione di base; poi una curva morbida e la gamma sRGB.
     */
    class Develop(val poly: Array<DoubleArray>, val matrix: DoubleArray)

    /**
     * Quello che c'è da decodificare: un JPEG solo (`tiles` vuoto), i tasselli da ricomporre (e da sviluppare se
     * `develop` non è null), oppure (`pixels` non null) i pixel già pronti, in ARGB, di un TIFF senza JPEG incorporato
     * (`width`×`height`, già ridotti).
     */
    class Picked(val jpeg: ByteArray, val orientation: Int, val tiles: List<ByteArray> = emptyList(),
                 val width: Int = 0, val height: Int = 0, val tileW: Int = 0, val tileH: Int = 0,
                 val pixels: IntArray? = null, val develop: Develop? = null) {
        /** Un JPEG da salvare così com'è, senza ricodificarlo. */
        val isPlainJpeg get() = tiles.isEmpty() && pixels == null
    }

    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Cambia quando cambia il modo di ricavare le miniature, così quelle vecchie (sbagliate) non si usano più. */
    private const val CACHE_VERSION = 2

    /** Un'anteprima sviluppata da PresetCraftAppLicator sul NAS (JPEG già dritto); `sig` cambia con lo sviluppo. */
    class Developed(val share: String, val path: String, val sig: String)

    private fun diskFile(context: Context, lib: String, photo: LibPhoto, dev: Developed? = null) =
        File(context.cacheDir, "library/" + lib.replace(Regex("[\\\\/:*?\"<>|]"), "_") +
            "/${photo.id}_${photo.name.hashCode()}_v$CACHE_VERSION" + (dev?.let { "_d${it.sig}" } ?: "") + ".jpg")

    /** La miniatura già sul telefono (nessun accesso al NAS), o null. */
    /** La miniatura se è già in memoria (nessuna lettura dal disco: si può chiamare mentre si disegna), o null. */
    fun memoryThumb(lib: String, photo: LibPhoto, dev: Developed? = null): Bitmap? =
        memory.get("$lib:${photo.id}:$THUMB:${dev?.sig ?: ""}")

    fun cachedThumb(context: Context, lib: String, photo: LibPhoto, dev: Developed? = null): Bitmap? {
        val key = "$lib:${photo.id}:$THUMB:${dev?.sig ?: ""}"
        memory.get(key)?.let { return it }
        val f = diskFile(context, lib, photo, dev)
        return if (f.exists()) BitmapFactory.decodeFile(f.path)?.also { memory.put(key, it) } else null
    }

    suspend fun load(context: Context, cfg: NasConfig, index: LibIndex, photo: LibPhoto, maxSide: Int,
                     dev: Developed? = null): Bitmap? {
        val key = "${index.library}:${photo.id}:$maxSide:${dev?.sig ?: ""}"
        memory.get(key)?.let { return it }
        if (maxSide <= THUMB) cachedThumb(context, index.library, photo, dev)?.let { return it }
        val bmp = if (dev != null) {
            // sviluppata: se non si legge (appena tolta, o non ancora copiata) si mostra quella della fotocamera
            val bytes = runCatching {
                Remote.use(cfg, dev.share) { s -> Remote.open(s, dev.path).use { r -> r.read(0, r.size.toInt()) } }
            }.getOrNull()
            bytes?.let { decodeJpeg(it, maxSide) } ?: return load(context, cfg, index, photo, maxSide, null)
        } else {
            val (share, path) = index.locate(photo) ?: return null
            val picked = Remote.use(cfg, share) { s -> Remote.open(s, path).use { r -> pick(r, photo, maxSide) } } ?: return null
            decode(picked, maxSide) ?: return null
        }
        memory.put(key, bmp)
        if (maxSide <= THUMB) runCatching {
            val f = diskFile(context, index.library, photo, dev)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return bmp
    }

    /** Il JPEG (o i tasselli) da mostrare e l'orientamento (1 = dritta). */
    fun pick(r: Source, photo: LibPhoto, maxSide: Int): Picked? {
        val ext = photo.name.substringAfterLast('.', "").lowercase()
        if (ext == "jpg" || ext == "jpeg") {
            // la miniatura EXIF non ha un orientamento suo: vale quello della foto, letto dall'inizio del file
            if (maxSide <= THUMB) exifThumb(r)?.let { return Picked(it, orientationOfJpeg(r.read(0, 64 * 1024))) }
            if (r.size > 60L * 1024 * 1024) return null
            val all = r.read(0, r.size.toInt())
            return Picked(all, orientationOfJpeg(all))
        }
        if (ext == "png") return if (r.size < 40L * 1024 * 1024) Picked(r.read(0, r.size.toInt()), 1) else null
        val (jpegs, orientation) = tiffJpegs(r) ?: return null
        // prima le anteprime già sviluppate (hanno anche le modifiche fatte in Lightroom); i Fast Load Data da
        // sviluppare qui solo se quelle sono troppo piccole; se non c'è niente, i pixel della prima immagine
        val rendered = jpegs.filter { it.develop == null }
        val raw = jpegs.filter { it.develop != null }
        val pool = when {
            rendered.any { maxOf(it.width, it.height) >= THUMB } -> rendered
            raw.isNotEmpty() -> raw
            else -> rendered
        }
        if (pool.isEmpty()) return tiffPixels(r, maxSide, orientation)
        val sized = pool.sortedBy { maxOf(it.width, it.height) }
        val chosen = if (maxSide <= THUMB) sized.firstOrNull { maxOf(it.width, it.height) >= THUMB } ?: sized.last()
        else sized.last()
        val tiles = chosen.tiles ?: return Picked(r.read(chosen.offset, chosen.length), orientation)
        return Picked(ByteArray(0), orientation, (if (r is Remote.Reader) r.readMany(tiles) else tiles.map { (at, n) -> r.read(at, n) }),
            chosen.width, chosen.height, chosen.tileW, chosen.tileH, develop = chosen.develop)
    }

    /** Sviluppa (sul posto) i pixel dei Fast Load Data: polinomi per canale, matrice, curva, gamma sRGB. */
    private fun develop(bmp: Bitmap, d: Develop) {
        val lin = Array(3) { c -> DoubleArray(256) { v -> val x = v / 255.0; d.poly[c].foldIndexed(0.0) { k, s, a -> s + a * Math.pow(x, k.toDouble()) } } }
        val bins = 16384
        val top = 4.0 // valori lineari oltre 4 sono comunque bianco pieno
        val out = IntArray(bins) { i ->
            val v = i * top / bins
            val toned = (v / (1 + 0.15 * v) * 1.15).coerceIn(0.0, 1.0)
            val s = if (toned <= 0.0031308) 12.92 * toned else 1.055 * Math.pow(toned, 1 / 2.4) - 0.055
            (s * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        val m = d.matrix
        val w = bmp.width
        val row = IntArray(w)
        val k = bins / top
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = lin[0][p shr 16 and 0xFF]
                val g = lin[1][p shr 8 and 0xFF]
                val b = lin[2][p and 0xFF]
                fun ch(o: Int) = out[((m[o] * r + m[o + 1] * g + m[o + 2] * b) * k).toInt().coerceIn(0, bins - 1)]
                row[x] = (0xFF shl 24) or (ch(0) shl 16) or (ch(3) shl 8) or ch(6)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** L'immagine decodificata e ruotata, ridotta perché il lato lungo sia circa `maxSide`. */
    fun decode(p: Picked, maxSide: Int): Bitmap? {
        val bmp = when {
            p.pixels != null -> Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888)
            p.tiles.isEmpty() -> decodeJpeg(p.jpeg, maxSide)
            else -> decodeTiles(p, maxSide)
        } ?: return null
        val degrees = when (p.orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        if (degrees == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    /** Rimette insieme i tasselli (da sinistra a destra, dall'alto in basso), ciascuno ridotto dello stesso fattore. */
    private fun decodeTiles(p: Picked, maxSide: Int): Bitmap? {
        if (p.width <= 0 || p.height <= 0 || p.tileW <= 0 || p.tileH <= 0) return null
        var sample = 1
        while (maxOf(p.width, p.height) / (sample * 2) >= maxSide) sample *= 2
        val s = sample.toFloat()
        val out = Bitmap.createBitmap((p.width + sample - 1) / sample, (p.height + sample - 1) / sample, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val across = (p.width + p.tileW - 1) / p.tileW
        var drawn = 0
        p.tiles.forEachIndexed { i, bytes ->
            val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@forEachIndexed
            val x = (i % across) * p.tileW / s
            val y = (i / across) * p.tileH / s
            canvas.drawBitmap(tile, null, RectF(x, y, x + p.tileW / s, y + p.tileH / s), null)
            tile.recycle()
            drawn++
        }
        if (drawn == 0) { out.recycle(); return null }
        p.develop?.let { develop(out, it) }
        return out
    }

    private fun decodeJpeg(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------------------------------------------------------- TIFF (DNG, CR2, TIF…)

    private class Ifd(val tags: Map<Int, LongArray>, val next: Long)

    /**
     * Per i TIFF senza nessun JPEG dentro (per esempio i fotogrammi esportati da un programma video): i pixel della prima
     * immagine, se non è compressa, a 8 o 16 bit, RGB(A) o in scala di grigi, con i campioni uno dopo l'altro. Si
     * prende una riga ogni `step` e un pixel ogni `step` perché il lato lungo sia circa `maxSide`.
     */
    fun tiffPixels(r: Source, maxSide: Int, orientation: Int): Picked? = runCatching {
        val head = r.read(0, 8)
        val order = if (head[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val t = readIfd(r, ByteBuffer.wrap(head).order(order).getInt(4).toLong() and 0xFFFFFFFFL, order)?.tags ?: return null
        fun one(tag: Int, def: Long) = t[tag]?.firstOrNull() ?: def
        val w = one(0x100, 0).toInt()
        val h = one(0x101, 0).toInt()
        val samples = one(0x115, 1).toInt()
        val bits = one(0x102, 8).toInt()
        val photometric = one(0x106, 2).toInt()
        if (one(0x103, 1) != 1L || one(0x11C, 1) != 1L || (bits != 8 && bits != 16)) return null
        if (w <= 0 || h <= 0 || w.toLong() * h > 200_000_000L) return null
        val gray = photometric == 0 || photometric == 1
        if (!gray && !(photometric == 2 && samples >= 3)) return null
        val offs = t[0x111] ?: return null
        val lens = t[0x117] ?: return null
        val rowsPerStrip = one(0x116, h.toLong()).coerceIn(1, h.toLong()).toInt()
        val bps = bits / 8
        val rowBytes = w.toLong() * samples * bps
        if (offs.size != lens.size || offs.size < (h + rowsPerStrip - 1) / rowsPerStrip) return null
        var step = 1
        while (maxOf(w, h) / (step * 2) >= maxSide) step *= 2
        // non più di ~12 milioni di pixel in memoria (i pixel e poi la Bitmap)
        while ((w.toLong() / step) * (h / step) > 12_000_000L) step *= 2
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val px = IntArray(ow * oh)
        // a 16 bit basta il byte più significativo
        val hi = if (bps == 2 && order == ByteOrder.LITTLE_ENDIAN) 1 else 0
        // si leggono più strisce di seguito in un colpo solo (al massimo ~4 MB), poi si prendono le righe che servono
        var strip = 0
        while (strip < offs.size && strip * rowsPerStrip < h) {
            var last = strip
            var bytes = lens[strip]
            while (last + 1 < offs.size && (last + 1) * rowsPerStrip < h && offs[last + 1] == offs[last] + lens[last] &&
                bytes + lens[last + 1] <= 4L * 1024 * 1024) { last++; bytes += lens[last] }
            val firstRow = strip * rowsPerStrip
            val endRow = minOf(h, (last + 1) * rowsPerStrip)
            val firstWanted = ((firstRow + step - 1) / step) * step
            if (firstWanted < endRow) {
                val chunk = r.read(offs[strip], bytes.toInt())
                var y = firstWanted
                while (y < endRow) {
                    val base = (y - firstRow) * rowBytes
                    if (base + rowBytes > chunk.size) break
                    val row = (y / step) * ow
                    for (ox in 0 until ow) {
                        val p = (base + ox.toLong() * step * samples * bps).toInt() + hi
                        val red = chunk[p].toInt() and 0xFF
                        px[row + ox] = if (gray) {
                            val v = if (photometric == 0) 255 - red else red
                            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        } else {
                            (0xFF shl 24) or (red shl 16) or ((chunk[p + bps].toInt() and 0xFF) shl 8) or
                                (chunk[p + 2 * bps].toInt() and 0xFF)
                        }
                    }
                    y += step
                }
            }
            strip = last + 1
        }
        Picked(ByteArray(0), orientation, width = ow, height = oh, pixels = px)
    }.getOrNull()

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
            val w = t[0x100]?.firstOrNull()?.toInt() ?: 160
            val h = t[0x101]?.firstOrNull()?.toInt() ?: 120
            // anteprima a tasselli JPEG (34892 = "lossy JPEG" dei DNG salvati da Lightroom/Camera Raw recenti)
            if (compression == 34892 && subType == 1L && t[0x144] != null && t[0x145] != null) {
                val tw = t[0x142]?.firstOrNull()?.toInt() ?: 0
                val th = t[0x143]?.firstOrNull()?.toInt() ?: 0
                val offs = t.getValue(0x144)
                val lens = t.getValue(0x145)
                val expected = if (tw > 0 && th > 0) ((w + tw - 1) / tw) * ((h + th - 1) / th) else -1
                // Photometric = LinearRaw: Fast Load Data di Lightroom, da sviluppare (senza i dati per farlo, si salta)
                val linear = t[0x106]?.firstOrNull() == 34892L
                val dev = if (linear) runCatching { developFor(r, order, ifd0.tags, t) }.getOrNull() else null
                if (offs.size == lens.size && offs.size == expected && (!linear || dev != null) &&
                    offs.indices.all { lens[it] in 1..(16L * 1024 * 1024) && offs[it] + lens[it] <= r.size }) {
                    out += Jpeg(offs[0], lens.sum().toInt(), w, h, offs.indices.map { offs[it] to lens[it].toInt() }, tw, th, dev)
                }
                continue
            }
            val (offset, length) = when {
                t[0x201] != null && t[0x202] != null -> t.getValue(0x201)[0] to t.getValue(0x202)[0]
                // JPEG "vecchio" (6) va sempre bene; il 7 solo per le anteprime (nei raw il 7 è il dato raw senza perdite)
                (compression == 6 || ((compression == 7 || compression == 34892) && subType == 1L)) &&
                    t[0x111]?.size == 1 && t[0x117]?.size == 1 -> t.getValue(0x111)[0] to t.getValue(0x117)[0]
                else -> continue
            }
            if (length <= 0 || length > 64L * 1024 * 1024 || offset + length > r.size) continue
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

    /** I valori di un tag RATIONAL/SRATIONAL (o FLOAT/DOUBLE) tenuto come posizione da readIfd, o null. */
    private fun numbers(r: Source, order: ByteOrder, loc: LongArray?): DoubleArray? {
        if (loc == null || loc.size < 3) return null
        val (at, n, type) = Triple(loc[0], loc[1].toInt(), loc[2].toInt())
        if (n <= 0 || n > 64) return null
        val size = when (type) { 5, 10, 12 -> 8; 11 -> 4; else -> return null }
        val bb = ByteBuffer.wrap(r.read(at, n * size)).order(order)
        if (bb.capacity() < n * size) return null
        return DoubleArray(n) { i ->
            when (type) {
                5 -> (bb.getInt(i * 8).toLong() and 0xFFFFFFFFL).toDouble() / maxOf(1L, bb.getInt(i * 8 + 4).toLong() and 0xFFFFFFFFL)
                10 -> bb.getInt(i * 8).toDouble() / (bb.getInt(i * 8 + 4).takeIf { it != 0 } ?: 1)
                12 -> bb.getDouble(i * 8)
                else -> bb.getFloat(i * 4).toDouble()
            }
        }
    }

    /**
     * Prepara lo sviluppo dei Fast Load Data: i polinomi da OpcodeList2 dell'IFD dei tasselli, il bianco dello scatto
     * (AsShotNeutral) e la matrice colore della fotocamera (ForwardMatrix se c'è, altrimenti ColorMatrix) da IFD0.
     */
    private fun developFor(r: Source, order: ByteOrder, ifd0: Map<Int, LongArray>, t: Map<Int, LongArray>): Develop? {
        val ops = t[0xC741] ?: return null
        if (ops[1] !in 4..65536) return null
        val ol = ByteBuffer.wrap(r.read(ops[0], ops[1].toInt())).order(ByteOrder.BIG_ENDIAN) // gli opcode sono sempre big-endian
        val poly = arrayOfNulls<DoubleArray>(3)
        var at = 4
        repeat(ol.getInt(0)) {
            val id = ol.getInt(at)
            val size = ol.getInt(at + 12)
            val body = at + 16
            if (id == 8) { // MapPolynomial: area, piano, quanti piani, passi, grado, coefficienti
                val plane = ol.getInt(body + 16)
                val planes = ol.getInt(body + 20)
                val degree = ol.getInt(body + 32)
                if (degree in 0..8) {
                    val c = DoubleArray(degree + 1) { ol.getDouble(body + 36 + 8 * it) }
                    for (p in plane until minOf(3, plane + planes)) poly[p] = c
                }
            } else if (ol.getInt(at + 8) and 1 == 0) return null // operazione che non sappiamo fare e non facoltativa
            at = body + size
        }
        val polys = Array(3) { poly[it] ?: doubleArrayOf(0.0, 1.0) }
        val neutral = numbers(r, order, ifd0[0xC628])?.takeIf { it.size == 3 && it.all { v -> v > 0 } } ?: return null
        val exposure = Math.pow(2.0, numbers(r, order, ifd0[0xC62A])?.firstOrNull() ?: 0.0)
        val fm = (numbers(r, order, ifd0[0xC715]) ?: numbers(r, order, ifd0[0xC714]))?.takeIf { it.size == 9 }
        val m = if (fm != null) {
            mul(XYZ50_TO_SRGB, mul(fm, doubleArrayOf(1 / neutral[0], 0.0, 0.0, 0.0, 1 / neutral[1], 0.0, 0.0, 0.0, 1 / neutral[2])))
        } else {
            val cm = (numbers(r, order, ifd0[0xC622]) ?: numbers(r, order, ifd0[0xC621]))?.takeIf { it.size == 9 } ?: return null
            val camToSrgb = mul(XYZ65_TO_SRGB, inverse(cm) ?: return null)
            // il bianco dello scatto diventa bianco
            val white = DoubleArray(3) { i -> (0..2).sumOf { j -> camToSrgb[i * 3 + j] * neutral[j] } }
            if (white.any { it <= 0 }) return null
            DoubleArray(9) { camToSrgb[it] / white[it / 3] }
        }
        return Develop(polys, DoubleArray(9) { m[it] * exposure })
    }

    /**
     * Tag di cui readIfd tiene solo la posizione: MakerNote, risorse di Photoshop, OpcodeList2, AsShotNeutral,
     * BaselineExposure, ColorMatrix1/2, ForwardMatrix1/2.
     */
    private val LOCATED = setOf(0x927C, 0x8649, 0xC741, 0xC628, 0xC62A, 0xC621, 0xC622, 0xC714, 0xC715)

    private val XYZ50_TO_SRGB =doubleArrayOf(3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540,
        0.0719453, -0.2289914, 1.4052427)
    private val XYZ65_TO_SRGB = doubleArrayOf(3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252)

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] }
    }

    private fun inverse(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (Math.abs(det) < 1e-12) return null
        val c = doubleArrayOf(m[4] * m[8] - m[5] * m[7], m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            m[5] * m[6] - m[3] * m[8], m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            m[3] * m[7] - m[4] * m[6], m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3])
        return DoubleArray(9) { c[it] / det }
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
            if (tag in LOCATED) { // solo dove stanno: [posizione, quanti valori, tipo]
                val bytes = n * when (type) { 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 1 }
                tags[tag] = longArrayOf(if (bytes <= 4) offset + 2 + e + 8 else buf.getInt(e + 8).toLong() and 0xFFFFFFFFL,
                    n, type.toLong())
                return@repeat
            }
            val size = when (type) { 3 -> 2; 4, 13 -> 4; else -> 0 }
            // offset e lunghezze dei tasselli possono essere tanti; gli altri tag che servono hanno pochi valori
            val max = if (tag == 0x111 || tag == 0x117 || tag == 0x144 || tag == 0x145) 65536 else 64
            if (size == 0 || n == 0L || n > max) return@repeat
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
