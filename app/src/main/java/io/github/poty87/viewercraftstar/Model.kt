package io.github.poty87.viewercraftstar

import android.net.Uri
import java.util.Locale

/** Bandierina, come in LightCraft (lc:flag). */
enum class Flag(val xmp: String) {
    NONE("none"), PICK("pick"), REJECT("reject");

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.xmp == s } ?: NONE
    }
}

/** Etichetta colore: in XMP si scrive il nome inglese (xmp:Label), come fanno LightCraft e Lightroom. */
enum class ColorLabel(val xmp: String, val argb: Long) {
    RED("Red", 0xFFEF4444), YELLOW("Yellow", 0xFFFACC15), GREEN("Green", 0xFF22C55E),
    BLUE("Blue", 0xFF3B82F6), PURPLE("Purple", 0xFFA855F7);

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.xmp.equals(s, ignoreCase = true) }
    }
}

/** Una foto della cartella del telefono. */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateTaken: Long,
    val modified: Long,
    /** Nella cartella di lavoro dell'app (non ancora spedita nella cartella sincronizzata con il NAS). */
    val inWork: Boolean = false,
) {
    val stem: String get() = name.substringBeforeLast('.')
}

/** I metadati che l'utente assegna a una foto. `changed` > `sent` = da inviare al NAS. */
data class PhotoMeta(
    val flag: Flag = Flag.NONE,
    val rating: Int = 0,
    val label: ColorLabel? = null,
    val title: String = "",
    val caption: String = "",
    val keywords: List<String> = emptyList(),
    val creator: String = "",
    val copyright: String = "",
    /** Preset di LightCraft scelto sul telefono ("" = profilo della fotocamera). */
    val presetId: String = "",
    val presetName: String = "",
    val changed: Long = 0,
    val sent: Long = 0,
    val error: String = "",
    /** All'ultimo invio il DNG non c'era sul NAS (non ancora caricato, o cancellato dal NAS). */
    val notOnNas: Boolean = false,
) {
    val pending: Boolean get() = changed > sent
    val isEmpty: Boolean
        get() = flag == Flag.NONE && rating == 0 && label == null && title.isEmpty() && caption.isEmpty() &&
            keywords.isEmpty() && creator.isEmpty() && copyright.isEmpty() && presetId.isEmpty()
}

/** Un preset della libreria LightCraft, dall'elenco che PresetCraftAppLicator pubblica sul NAS. */
data class PresetInfo(val id: String, val name: String, val group: String, val profile: String?, val profileOnly: Boolean)

/** Testi dell'interfaccia: italiano, altrimenti inglese. */
object L {
    private val it = Locale.getDefault().language == "it"
    fun t(italian: String, english: String) = if (it) italian else english
}
