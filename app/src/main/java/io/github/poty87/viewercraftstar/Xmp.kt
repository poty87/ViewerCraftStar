package io.github.poty87.viewercraftstar

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Il file .xmp accanto al DNG sul NAS, nello stesso formato che scrive LightCraft (provato con lightcraft-cli):
 * xmp:Rating, xmp:Label (nome inglese), lc:flag, dc:title / dc:description / dc:rights (rdf:Alt), dc:creator (rdf:Seq),
 * dc:subject e lr:hierarchicalSubject (rdf:Bag, "a|b" = parola chiave gerarchica).
 *
 * Contiene sempre tutti i campi, anche vuoti: PresetCraftAppLicator lo legge e imposta ogni valore in LightCraft
 * (così anche togliere una stella o un titolo arriva in libreria). Non contiene impostazioni di sviluppo: il profilo
 * dato da PresetCraftAppLicator non viene toccato.
 */
object Xmp {
    const val TOOL = "ViewerCraftStar"
    const val VCS_NS = "http://ns.poty87.github.io/viewercraftstar/1.0/"

    fun build(m: PhotoMeta, now: Date = Date()): String {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now)
        val flat = LinkedHashSet<String>()
        m.keywords.forEach { k -> k.split('|').map(String::trim).filter(String::isNotEmpty).forEach(flat::add) }
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"$TOOL\">\n")
        sb.append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        sb.append("  <rdf:Description rdf:about=\"\"\n")
        sb.append("    xmlns:dc=\"http://purl.org/dc/elements/1.1/\"\n")
        sb.append("    xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"\n")
        sb.append("    xmlns:lr=\"http://ns.adobe.com/lightroom/1.0/\"\n")
        sb.append("    xmlns:lc=\"http://ns.lightcraft.app/lc/1.0/\"\n")
        sb.append("    xmlns:vcs=\"$VCS_NS\">\n")
        sb.append("   <xmp:CreatorTool>$TOOL</xmp:CreatorTool>\n")
        sb.append("   <xmp:MetadataDate>$date</xmp:MetadataDate>\n")
        sb.append("   <xmp:Rating>${m.rating.coerceIn(0, 5)}</xmp:Rating>\n")
        sb.append("   <xmp:Label>${esc(m.label?.xmp ?: "")}</xmp:Label>\n")
        sb.append("   <lc:flag>${m.flag.xmp}</lc:flag>\n")
        // preset di LightCraft scelto sul telefono: PresetCraftAppLicator lo applica e non lo sovrascrive
        sb.append("   <vcs:preset>${esc(m.presetId)}</vcs:preset>\n")
        sb.append("   <vcs:presetName>${esc(m.presetName)}</vcs:presetName>\n")
        // versione di questi metadati: PresetCraftAppLicator dice quale ha applicato (resoconto "al sicuro a casa")
        sb.append("   <vcs:revision>${m.changed}</vcs:revision>\n")
        alt(sb, "dc:title", m.title)
        alt(sb, "dc:description", m.caption)
        alt(sb, "dc:rights", m.copyright)
        list(sb, "dc:creator", "rdf:Seq", if (m.creator.isEmpty()) emptyList() else listOf(m.creator))
        list(sb, "dc:subject", "rdf:Bag", flat.toList())
        list(sb, "lr:hierarchicalSubject", "rdf:Bag", m.keywords)
        sb.append("  </rdf:Description>\n </rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>\n")
        return sb.toString()
    }

    private fun alt(sb: StringBuilder, tag: String, value: String) {
        sb.append("   <$tag><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(value)}</rdf:li></rdf:Alt></$tag>\n")
    }

    private fun list(sb: StringBuilder, tag: String, kind: String, values: List<String>) {
        if (values.isEmpty()) {
            sb.append("   <$tag><$kind/></$tag>\n")
            return
        }
        sb.append("   <$tag><$kind>")
        values.forEach { sb.append("<rdf:li>${esc(it)}</rdf:li>") }
        sb.append("</$kind></$tag>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
