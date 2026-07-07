package com.safeguard.parentalcontrol.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Covers the resolution/validation logic in [LocaleHelper] that doesn't require a real
 * Android Context (no Robolectric in this project): default-language resolution order,
 * the Arabic Western-digit locale tag, and setLanguage's input validation.
 */
class LocaleHelperTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val context = mockk<Context>().also {
        every { it.getSharedPreferences(any(), any()) } returns prefs
    }

    private lateinit var originalDefaultLocale: Locale

    @Before
    fun captureDefaultLocale() {
        originalDefaultLocale = Locale.getDefault()
    }

    @After
    fun restoreDefaultLocale() {
        Locale.setDefault(originalDefaultLocale)
    }

    @Test
    fun `no stored preference and Arabic system locale resolves to Arabic`() {
        every { prefs.getString("app_language", null) } returns null
        Locale.setDefault(Locale.forLanguageTag("ar"))

        assertEquals(LocaleHelper.LANGUAGE_ARABIC, LocaleHelper.getLanguage(context))
    }

    @Test
    fun `no stored preference and non-Arabic system locale resolves to English`() {
        every { prefs.getString("app_language", null) } returns null
        Locale.setDefault(Locale.forLanguageTag("fr"))

        assertEquals(LocaleHelper.LANGUAGE_ENGLISH, LocaleHelper.getLanguage(context))
    }

    @Test
    fun `stored preference takes priority over system locale`() {
        every { prefs.getString("app_language", null) } returns "en"
        Locale.setDefault(Locale.forLanguageTag("ar"))

        assertEquals(LocaleHelper.LANGUAGE_ENGLISH, LocaleHelper.getLanguage(context))
    }

    @Test
    fun `unsupported stored value falls back to system locale resolution`() {
        every { prefs.getString("app_language", null) } returns "fr"
        Locale.setDefault(Locale.forLanguageTag("ar"))

        assertEquals(LocaleHelper.LANGUAGE_ARABIC, LocaleHelper.getLanguage(context))
    }

    @Test
    fun `arabic maps to the Western-digit locale tag`() {
        assertEquals("ar-u-nu-latn", LocaleHelper.localeTagFor(LocaleHelper.LANGUAGE_ARABIC))
    }

    @Test
    fun `english maps to the plain English tag`() {
        assertEquals("en", LocaleHelper.localeTagFor(LocaleHelper.LANGUAGE_ENGLISH))
    }

    @Test
    fun `setLanguage rejects unsupported language codes`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocaleHelper.setLanguage(context, "fr")
        }
    }

    @Test
    fun `setLanguage persists a supported language choice`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor

        LocaleHelper.setLanguage(context, LocaleHelper.LANGUAGE_ARABIC)

        verify { editor.putString("app_language", LocaleHelper.LANGUAGE_ARABIC) }
        verify { editor.apply() }
    }
}
