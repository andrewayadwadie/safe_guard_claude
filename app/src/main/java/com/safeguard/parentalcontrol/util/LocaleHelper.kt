package com.safeguard.parentalcontrol.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app locale switching without androidx.appcompat (see research.md R1).
 * Must stay synchronous and dependency-free: [wrap] runs inside `attachBaseContext`,
 * before Hilt injection completes, so a raw SharedPreferences read is used instead of
 * the app's DataStore/EncryptedSharedPreferences.
 */
object LocaleHelper {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "app_language"

    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_ARABIC = "ar"

    private val SUPPORTED_LANGUAGES = setOf(LANGUAGE_ENGLISH, LANGUAGE_ARABIC)

    /** Returns the stored language, or the system-default resolution per FR-007 if none chosen yet. */
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE, null)
        if (stored in SUPPORTED_LANGUAGES) return stored!!
        return if (Locale.getDefault().language == LANGUAGE_ARABIC) LANGUAGE_ARABIC else LANGUAGE_ENGLISH
    }

    /** Persists the explicit user choice. Does NOT recreate the activity — caller's job. */
    fun setLanguage(context: Context, lang: String) {
        require(lang in SUPPORTED_LANGUAGES) { "Unsupported language: $lang" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, lang)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(lang)
        }
    }

    fun isRtl(context: Context): Boolean = getLanguage(context) == LANGUAGE_ARABIC

    /** Wraps an Activity's base context. Call from `attachBaseContext(LocaleHelper.wrap(newBase))`. */
    fun wrap(base: Context): Context = applyLocale(base, getLanguage(base))

    /** Wraps an application/background context for workers, services, and notification builders. */
    fun localizedContext(context: Context): Context = applyLocale(context, getLanguage(context))

    /** Maps a supported language code to its BCP-47 tag (Arabic forces Western/Latin digits). */
    internal fun localeTagFor(lang: String): String =
        if (lang == LANGUAGE_ARABIC) "ar-u-nu-latn" else LANGUAGE_ENGLISH

    private fun applyLocale(context: Context, lang: String): Context {
        val locale = Locale.forLanguageTag(localeTagFor(lang))
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
