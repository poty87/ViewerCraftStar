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
) {
    val complete: Boolean get() = host.isNotBlank() && share.isNotBlank() && user.isNotBlank() && password.isNotEmpty()
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** La cartella sincronizzata con il NAS da Synology Drive. */
    var phoneFolder: String
        get() = prefs.getString("phone_folder", "Pictures/RICOH_DNG/")!!
        set(v) = prefs.edit().putString("phone_folder", normalizeFolder(v)).apply()

    /** La cartella di lavoro dell'app: qui arrivano i DNG nuovi, NON sincronizzata. "Spedisci al NAS" li sposta. */
    var workFolder: String
        get() = prefs.getString("work_folder", "Pictures/ViewerCraftStar/")!!
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

    /** Notifica di spedizione: seguire anche l'ingresso in LightCraft (serve il PC di casa acceso). */
    var notifyLightCraft: Boolean
        get() = prefs.getBoolean("notify_lightcraft", false)
        set(v) = prefs.edit().putBoolean("notify_lightcraft", v).apply()

    /** Avviso a spedizione finita (o sospesa). */
    var notifyEnd: Boolean
        get() = prefs.getBoolean("notify_end", true)
        set(v) = prefs.edit().putBoolean("notify_end", v).apply()

    var nas: NasConfig
        get() = NasConfig(
            host = prefs.getString("nas_host", "")!!,
            share = prefs.getString("nas_share", "")!!,
            folder = prefs.getString("nas_folder", "")!!,
            subfolders = prefs.getBoolean("nas_subfolders", true),
            user = prefs.getString("nas_user", "")!!,
            password = prefs.getString("nas_password", null)?.let(::decrypt) ?: "",
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

        private fun decrypt(data: String): String? = runCatching {
            val raw = Base64.decode(data, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}
