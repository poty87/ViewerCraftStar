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
                val jpegs = runCatching { RemotePreview.tiffJpegs(src)?.first?.map { "${it.width}x${it.height}@${it.offset}+${it.length}" } }
                for (side in listOf(RemotePreview.THUMB, RemotePreview.FULL)) {
                    val r = runCatching { RemotePreview.pick(src, photo, side) }
                    val bytes = r.getOrNull()?.first
                    val ok = bytes != null && bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
                    report.append("${if (ok) "OK   " else "NO   "} $side ${File(path).name}: ${bytes?.size ?: 0} byte" +
                        (r.exceptionOrNull()?.let { " ERRORE $it" } ?: "") + " | jpeg trovati ${jpegs.getOrNull()}\n")
                }
            }
        }
        File(System.getProperty("java.io.tmpdir"), "vcs-preview-report.txt").writeText(report.toString())
        println(report)
    }
}
