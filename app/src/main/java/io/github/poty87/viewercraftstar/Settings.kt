package io.github.poty87.viewercraftstar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Impostazioni dell'app. La password del NAS è cifrata con una chiave del Keystore di Android (non lascia il telefono). */
data class NasConfig(
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val subfolders: Boolean = true,
    val user: String = "",
    val password: String = "",
    /** La cartella di servizio di PresetCraftAppLicator (condivisione, vuota = quella sopra, e cartella). */
    val libShare: String = "",
    val libFolder: String = "",
) {
    val complete: Boolean get() = host.isNotBlank() && share.isNotBlank() && user.isNotBlank() && password.isNotEmpty()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** La cartella sincronizzata con il NAS da Synology Drive. */
    var phoneFolder: String
        get() = prefs.getString("phone_folder", DEFAULT_SYNC)!!
        set(v) = prefs.edit().putString("phone_folder", normalizeFolder(v)).apply()

    /** La cartella di lavoro dell'app: qui arrivano i DNG nuovi, NON sincronizzata. "Spedisci al NAS" li sposta. */
    var workFolder: String
        get() = prefs.getString("work_folder", DEFAULT_WORK)!!
        set(v) = prefs.edit().putString("work_folder", normalizeFolder(v)).apply()

    var defaultCreator: String
        get() = prefs.getString("default_creator", "")!!
        set(v) = prefs.edit().putString("default_creator", v.trim()).apply()

    var defaultCopyright: String
        get() = prefs.getString("default_copyright", "")!!
        set(v) = prefs.edit().putString("default_copyright", v.trim()).apply()

    var advance: Boolean
        get() = prefs.getBoolean("advance", false)
        set(v) = prefs.edit().putBoolean("advance", v).apply()

    /** Libreria di casa: cartella condivisa (vuota = quella del NAS) e cartella con gli indici di PresetCraftAppLicator. */
    var libShare: String
        get() = prefs.getString("lib_share", "")!!
        set(v) = prefs.edit().putString("lib_share", v.trim().trim('/', '\\')).apply()

    var libFolder: String
        get() = prefs.getString("lib_folder", "")!!
        set(v) = prefs.edit().putString("lib_folder", v.trim().replace('/', '\\').trim('\\')).apply()

    /**
     * Fotocamera, cosa si scarica: "dng" solo i DNG, "jpg" solo i JPEG, "both" il DNG e il suo JPEG gemello.
     * I JPEG vanno sempre nella galleria del telefono (DCIM/Camera), i DNG nella cartella delle foto nuove.
     */
    var cameraFormat: String
        get() = prefs.getString("camera_format", "both")!!
        set(v) = prefs.edit().putString("camera_format", v).apply()

    /** Galleria della Libreria: "justified" (righe con le proporzioni delle foto) o "square" (celle quadrate). */
    var libGrid: String
        get() = prefs.getString("lib_grid", "justified")!!
        set(v) = prefs.edit().putString("lib_grid", v).apply()

    /** Dimensione delle foto nella galleria: 1 (piccole, tante per riga) … 5 (grandi). */
    var libSize: Int
        get() = prefs.getInt("lib_size", 3)
        set(v) = prefs.edit().putInt("lib_size", v.coerceIn(1, 5)).apply()

    /** Galleria divisa per periodo, e per quale: "year", "month" o "day". */
    var libSegment: Boolean
        get() = prefs.getBoolean("lib_segment", true)
        set(v) = prefs.edit().putBoolean("lib_segment", v).apply()

    var libSegmentBy: String
        get() = prefs.getString("lib_segment_by", "month")!!
        set(v) = prefs.edit().putString("lib_segment_by", v).apply()

    /** La libreria scelta l'ultima volta. */
    var library: String
        get() = prefs.getString("library", "")!!
        set(v) = prefs.edit().putString("library", v).apply()

    /** Avviso a spedizione finita (o sospesa). */
    var notifyEnd: Boolean
        get() = prefs.getBoolean("notify_end", true)
        set(v) = prefs.edit().putBoolean("notify_end", v).apply()

    /** VPN verso casa (con OpenVPN for Android): server e porta esterni, MTU ridotto, utente e password (cifrata). */
    var vpnHost: String
        get() = prefs.getString("vpn_host", "")!!
        set(v) = prefs.edit().putString("vpn_host", v.trim()).apply()

    var vpnPort: Int
        get() = prefs.getInt("vpn_port", 0)
        set(v) = prefs.edit().putInt("vpn_port", v.coerceIn(0, 65535)).apply()

    /** Pacchetti più piccoli (tun-mtu 1340, mssfix 1300): sulla rete mobile quelli grandi si perdono nel tunnel. */
    var vpnSmallMtu: Boolean
        get() = prefs.getBoolean("vpn_small_mtu", true)
        set(v) = prefs.edit().putBoolean("vpn_small_mtu", v).apply()

    /**
     * Come si comporta la VPN: "manual" (di partenza: la accendi e la spegni tu con lo scudo), "start" (si accende da
     * sola aprendo l'app fuori casa, si spegne solo a mano) o "auto" (sperimentale: si accende aprendo l'app fuori casa,
     * si spegne a casa e un po' dopo l'uscita dall'app, mette in pausa AdGuard e lo riaccende).
     */
    var vpnMode: String
        get() = prefs.getString("vpn_mode", "manual")!!
        set(v) = prefs.edit().putString("vpn_mode", v).apply()

    /** La password di automazione di AdGuard (cifrata). */
    var adguardPassword: String
        get() = prefs.getString("adguard_password", null)?.let(::decrypt) ?: ""
        set(v) = prefs.edit().putString("adguard_password", if (v.isEmpty()) null else encrypt(v)).apply()

    var vpnUser: String
        get() = prefs.getString("vpn_user", "")!!
        set(v) = prefs.edit().putString("vpn_user", v.trim()).apply()

    var vpnPassword: String
        get() = prefs.getString("vpn_password", null)?.let(::decrypt) ?: ""
        set(v) = prefs.edit().putString("vpn_password", if (v.isEmpty()) null else encrypt(v)).apply()

    /** La cartella condivisa del NAS, senza leggere (e decifrare) tutta la configurazione: si chiede anche mentre si disegna. */
    val nasShare: String get() = prefs.getString("nas_share", "")!!

    var nas: NasConfig
        get() = NasConfig(
            host = prefs.getString("nas_host", "")!!,
            share = prefs.getString("nas_share", "")!!,
            folder = prefs.getString("nas_folder", "")!!,
            subfolders = prefs.getBoolean("nas_subfolders", true),
            user = prefs.getString("nas_user", "")!!,
            password = prefs.getString("nas_password", null)?.let(::decrypt) ?: "",
            libShare = libShare,
            libFolder = libFolder,
        )
        set(v) {
            prefs.edit()
                .putString("nas_host", v.host.trim())
                .putString("nas_share", v.share.trim().trim('/', '\\'))
                .putString("nas_folder", v.folder.trim().replace('/', '\\').trim('\\'))
                .putBoolean("nas_subfolders", v.subfolders)
                .putString("nas_user", v.user.trim())
                .putString("nas_password", if (v.password.isEmpty()) null else encrypt(v.password))
                .apply()
        }

    companion object {
        fun normalizeFolder(v: String): String {
            val f = v.trim().trim('/').removePrefix("sdcard/").removePrefix("storage/emulated/0/")
            return if (f.isEmpty()) "" else "$f/"
        }

        private const val KEY = "viewercraftstar-nas"
        /** Le due cartelle del telefono di un'installazione nuova, create dall'app dentro Pictures. */
        const val DEFAULT_WORK = "Pictures/VCS_Import_Photo/"
        const val DEFAULT_SYNC = "Pictures/VCS_Dng_Sync/"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return gen.generateKey()
        }

        private fun encrypt(text: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(out, Base64.NO_WRAP)
        }

        /**
         * I testi già decifrati, per cifrato: decifrare passa dal Keystore di Android (millisecondi a ogni
         * chiamata), e la configurazione del NAS si legge spesso, anche mentre si disegna la galleria. Resta solo in
         * memoria, come la password quando si usa.
         */
        private val decrypted = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun decrypt(data: String): String? {
            decrypted[data]?.let { return it }
            return decryptNow(data)?.also { if (decrypted.size > 8) decrypted.clear(); decrypted[data] = it }
        }

        private fun decryptNow(data: String): String? = runCatching {
            val raw = Base64.decode(data, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}
