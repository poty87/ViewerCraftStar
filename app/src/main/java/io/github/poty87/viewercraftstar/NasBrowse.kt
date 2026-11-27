package io.github.poty87.viewercraftstar

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.concurrent.TimeUnit

/**
 * Per scegliere le cartelle del NAS invece di scriverle a mano: l'elenco delle cartelle condivise (servizio srvsvc) e
 * delle sottocartelle di una cartella. Solo lettura.
 */
object NasBrowse {
    /** Cartelle di servizio dei NAS (Synology, QNAP) e di Windows: non si mostrano. */
    private val SKIP = setOf("@eadir", "#recycle", "#snapshot", ".synologyworkingdirectory", "\$recycle.bin", "system volume information",
        "@recycle", ".@__thumb", "@recently-snapshot", ".streams")

    private fun <T> session(host: String, user: String, password: String, work: (Session) -> T): T {
        SMBClient(SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build()).use { client ->
            client.connect(host.trim()).use { conn ->
                val (domain, name) = if ('\\' in user) user.substringBefore('\\') to user.substringAfter('\\') else "" to user
                return work(conn.authenticate(AuthenticationContext(name.trim(), password.toCharArray(), domain)))
            }
        }
    }

    /** Le cartelle condivise del NAS visibili a questo utente (senza quelle nascoste, che finiscono con $). */
    fun shares(host: String, user: String, password: String): List<String> = session(host, user, password) { s ->
        val transport = SMBTransportFactories.SRVSVC.getTransport(s)
        ServerService(transport).shares1
            .filter { (it.type and 0xFFFF) == 0 } // solo dischi (niente stampanti né IPC)
            .map { it.netName }
            .filter { !it.endsWith("$") }
            .sortedBy { it.lowercase() }
    }

    /** Le sottocartelle di `path` ("" = la cartella condivisa stessa), in ordine alfabetico. */
    fun folders(host: String, user: String, password: String, share: String, path: String): List<String> =
        session(host, user, password) { s ->
            (s.connectShare(share) as DiskShare).use { d ->
                d.list(path.replace('/', '\\').trim('\\'))
                    .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L }
                    .map { it.fileName }
                    .filter { it != "." && it != ".." && it.lowercase() !in SKIP && !it.startsWith(".") }
                    .sortedBy { it.lowercase() }
            }
        }

    /** La cartella esiste e contiene `child` (per riconoscere la cartella di servizio: ha "libraries"). */
    fun has(host: String, user: String, password: String, share: String, path: String, child: String): Boolean =
        runCatching { folders(host, user, password, share, path).any { it.equals(child, ignoreCase = true) } }.getOrDefault(false)
}
