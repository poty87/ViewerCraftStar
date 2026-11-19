package io.github.poty87.viewercraftstar

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Il lettore delle anteprime della Libreria sui file veri (se raggiungibili da questo PC, per esempio dal NAS collegato
 * come unità): da ogni formato deve uscire un JPEG, piccolo per la griglia e grande per il visualizzatore.
 * Percorsi in VCS_TEST_FILES (separati da ;), altrimenti il test non fa niente.
 */
class PreviewTest {
    private class FileSource(path: String) : Source, AutoCloseable {
        private val f = RandomAccessFile(path, "r")
        override val size: Long = f.length()
        override fun read(offset: Long, length: Int): ByteArray {
            val n = minOf(length.toLong(), maxOf(0L, size - offset)).toInt()
            val out = ByteArray(n)
            f.seek(offset)
            f.readFully(out)
            return out
        }
        override fun close() = f.close()
    }

    @Test
    fun previewsFromRealFiles() {
        val paths = (System.getenv("VCS_TEST_FILES") ?: "").split(';').filter { it.isNotBlank() }
        assumeTrue(paths.isNotEmpty())
        val report = StringBuilder()
        for (path in paths) {
            if (!File(path).exists()) { report.append("MANCA  $path\n"); continue }
            val photo = LibPhoto(1, File(path).name, path, "", "", 0, "none", "", "raw", 0, 0)
            FileSource(path).use { src ->
                val jpegs = runCatching { RemotePreview.tiffJpegs(src)?.first?.map { "${it.width}x${it.height}@${it.offset}+${it.length}" + (it.tiles?.let { t -> "[${t.size} tasselli${if (it.develop != null) " da sviluppare" else ""}]" } ?: "") } }
                for (side in listOf(RemotePreview.THUMB, RemotePreview.FULL)) {
                    val r = runCatching { RemotePreview.pick(src, photo, side) }
                    val picked = r.getOrNull()
                    fun jpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
                    val bytes = picked?.let { if (it.tiles.isEmpty()) it.jpeg else it.tiles.reduce { a, b -> a + b } }
                    val px = picked?.pixels
                    val ok = picked != null && when {
                        px != null -> px.size == picked.width * picked.height && px.distinct().size > 16
                        picked.tiles.isEmpty() -> jpeg(picked.jpeg)
                        else -> picked.tiles.all(::jpeg)
                    }
                    if (px != null) { // i pixel letti, da guardare (PPM: formato senza compressione)
                        val out = java.io.ByteArrayOutputStream()
                        out.write("P6\n${picked.width} ${picked.height}\n255\n".toByteArray())
                        for (c in px) { out.write(c shr 16 and 0xFF); out.write(c shr 8 and 0xFF); out.write(c and 0xFF) }
                        File(System.getProperty("java.io.tmpdir"), "vcs-pixels-${File(path).nameWithoutExtension}-$side.ppm")
                            .writeBytes(out.toByteArray())
                    }
                    report.append("${if (ok) "OK   " else "NO   "} $side ${File(path).name}: " +
                        (if (px != null) "pixel ${picked.width}x${picked.height}" else "${bytes?.size ?: 0} byte") + (if (picked?.develop != null) " SVILUPPO" else "") +
                        (r.exceptionOrNull()?.let { " ERRORE $it" } ?: "") + " | jpeg trovati ${jpegs.getOrNull()}\n")
                }
            }
        }
        File(System.getProperty("java.io.tmpdir"), "vcs-preview-report.txt").writeText(report.toString())
        println(report)
    }
}
