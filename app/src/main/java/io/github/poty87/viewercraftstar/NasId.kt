package io.github.poty87.viewercraftstar

import android.content.Context
import com.hierynomus.smbj.connection.Connection

/**
 * L'identità del NAS di casa: il codice univoco (server GUID) che il NAS manda appena gli si parla, prima di qualunque
 * password. Si impara a ogni accesso riuscito con utente e password (quindi è davvero il tuo NAS), e serve a capire se
 * il Wi-Fi a cui sei collegato è quello di casa: lì risponde proprio quel NAS, non un apparecchio qualsiasi con lo
 * stesso indirizzo. Niente permesso di posizione, niente nomi di reti (che chiunque può copiare).
 */
object NasId {
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    val guid: String? get() = prefs?.getString("nas_guid", null)

    /** Dopo un accesso con utente e password riuscito: questo è il NAS di casa. */
    fun learn(conn: Connection) {
        val g = runCatching { conn.connectionContext.serverGuid?.toString() }.getOrNull() ?: return
        if (g != guid) prefs?.edit()?.putString("nas_guid", g)?.apply()
    }
}
