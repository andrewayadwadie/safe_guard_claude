package com.safeguard.parentalcontrol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity lock for [Arabizi]. The `normalize` expectations are exact ground truth captured from
 * the reference prototype (scratchpad/arabizi.py) — a drift here changes what MARBERT sees, so
 * keep these strings byte-exact unless the prototype itself changes.
 */
class ArabiziTest {

    @Test
    fun normalizeMatchesPrototypeGroundTruth() {
        assertEquals("روح عاليك", Arabizi.normalize("rou7 3aleek"))
        assertEquals("كوس وماك يا يبن ل متناكا", Arabizi.normalize("kos omak ya ibn el metnaka"))
        assertEquals("شاخساك وخ", Arabizi.normalize("sha5sak we5"))
        assertEquals("بعاتيلي سووار عاريانا", Arabizi.normalize("eb3atili sowar 3aryana"))
        assertEquals("انا حابيبي نلعاب كورا", Arabizi.normalize("ana 7abibi nel3ab kora"))
    }

    @Test
    fun normalizeLeavesArabicTokensUntouched() {
        // The Arabic-script token passes through; the arabizi tokens around it transliterate.
        assertEquals("روح روح نوو", Arabizi.normalize("rou7 روح now"))
    }

    @Test
    fun looksLikeArabiziFiresOnDigitLetterAdjacency() {
        assertTrue(Arabizi.looksLikeArabizi("rou7 3aleek"))
        assertTrue(Arabizi.looksLikeArabizi("3aleek"))
        assertTrue(Arabizi.looksLikeArabizi("sha5sak"))
    }

    @Test
    fun looksLikeArabiziDoesNotFireOnPlainEnglishOrStandaloneNumbers() {
        assertFalse(Arabizi.looksLikeArabizi("hello how are you"))
        assertFalse(Arabizi.looksLikeArabizi("meet me at 7 pm"))   // standalone number
        assertFalse(Arabizi.looksLikeArabizi("call 911 now"))      // pure-digit token
        // digit-less arabizi is intentionally NOT detected (known gap → routes to EN)
        assertFalse(Arabizi.looksLikeArabizi("kos omak"))
    }
}
