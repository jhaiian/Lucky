package com.jhaiian.lucky.data

import android.content.Context
import com.jhaiian.lucky.engine.TrackInfo
import java.util.Locale

object TrackLanguage {
    const val FILE_DEFAULT = ""
    const val APP = "app"

    val choices = listOf(
        "en", "es", "pt", "fr", "de", "it", "ru", "ja", "ko", "zh", "hi", "ar", "id", "fil", "th", "vi",
        "tr", "pl", "nl", "sv", "uk", "ta", "te", "bn", "ms", "he", "el", "cs", "hu", "ro", "fa", "ur"
    )

    private val bibliographic = mapOf(
        "fre" to "fra", "ger" to "deu", "chi" to "zho", "dut" to "nld", "per" to "fas", "cze" to "ces",
        "gre" to "ell", "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus",
        "bur" to "mya", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
        "tib" to "bod", "wel" to "cym", "tgl" to "fil"
    )

    fun iso3(code: String?): String? {
        val raw = code?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" && it != "unk" && it != "mis" } ?: return null
        val primary = raw.split('-', '_').first()
        val mapped = bibliographic[primary] ?: primary
        val resolved = runCatching { Locale.forLanguageTag(mapped).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: mapped
        return bibliographic[resolved] ?: resolved
    }

    fun appLocale(context: Context): Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun preferred(context: Context, choice: String?): String? = when (choice) {
        null, FILE_DEFAULT -> null
        APP -> iso3(appLocale(context).language)
        else -> iso3(choice)
    }

    fun pick(tracks: List<TrackInfo>, preferred: String): TrackInfo? {
        val matches = tracks.filter { iso3(it.language) == preferred }
        return matches.firstOrNull { it.isDefault && !it.isForced } ?: matches.firstOrNull { !it.isForced } ?: matches.firstOrNull()
    }

    fun match(tracks: List<TrackInfo>, saved: TrackChoice): TrackInfo? {
        val language = iso3(saved.language)
        if (language == null) return tracks.firstOrNull { it.index == saved.index && it.codec == saved.codec }
        val pool = tracks.filter { iso3(it.language) == language }
        return pool.firstOrNull { it.index == saved.index && it.codec == saved.codec }
            ?: pool.firstOrNull { it.codec == saved.codec }
            ?: pick(pool, language)
    }

    fun displayName(tag: String, displayLocale: Locale): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(displayLocale).replaceFirstChar { it.titlecase(displayLocale) }
}
