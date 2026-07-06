package com.safeguard.parentalcontrol.ml

import com.safeguard.parentalcontrol.ml.FlagGating.AppContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the app-context gating contract.
 *
 * The load-bearing guarantee is the SAFETY INVARIANT: the high-cost child-safety categories
 * (predator_grooming / self_harm / sexual) must flag in EVERY app context — they are never
 * relaxed by gating. A regression that adds a positive context delta to one of them (e.g.
 * "suppress sexual in games") would silence real grooming/solicitation, so it must fail here.
 *
 * The rest pin the intended behaviour: borderline violence/profanity is gated DOWN in games
 * (the ~20% false-positive fix), preserved in messaging, and high-confidence model errors are
 * deliberately NOT rescued by thresholds.
 */
class FlagGatingTest {

    // one representative package per context
    private val GAME_PKG = "com.roblox.client"
    private val MSG_PKG = "com.whatsapp"
    private val SOCIAL_PKG = "com.instagram.android"
    private val BROWSER_PKG = "com.android.chrome"
    private val UNKNOWN_PKG = "com.acme.notes"

    private val allPackages = listOf(GAME_PKG, MSG_PKG, SOCIAL_PKG, BROWSER_PKG, UNKNOWN_PKG, "")

    // ---- SAFETY INVARIANT ----

    @Test
    fun highCostCategoriesFlagInEveryContext() {
        // score just above each base threshold; if any context relaxed it, the bar would
        // rise above this score and the assert would fail.
        val highCost = mapOf(
            "predator_grooming" to 0.40f,  // base 0.35
            "self_harm" to 0.45f,          // base 0.40
            "sexual" to 0.45f,             // base 0.40
        )
        for ((category, score) in highCost) {
            for (pkg in allPackages) {
                val r = FlagGating.decide(mapOf(category to score), pkg)
                assertTrue(
                    "$category ($score) must flag in context for '$pkg' — never relax high-cost",
                    r.flagged
                )
                assertEquals(category, r.category)
            }
        }
    }

    @Test
    fun groomingFlagsEvenInsideAGame() {
        val r = FlagGating.decide(mapOf("predator_grooming" to 1.0f), GAME_PKG)
        assertTrue(r.flagged)
        assertEquals("predator_grooming", r.category)
        assertEquals("critical", r.severity)
    }

    // ---- THE FALSE-POSITIVE FIX ----

    @Test
    fun borderlineViolenceIsGatedOutInGames() {
        // "I'll kill you in the game" -> violence 0.78; game eff threshold 0.70 + 0.25 = 0.95
        val r = FlagGating.decide(mapOf("violence" to 0.78f), GAME_PKG)
        assertFalse("borderline game violence should be suppressed", r.flagged)
    }

    @Test
    fun borderlineProfanityIsGatedOutInGames() {
        // "get rekt" register; profanity 0.62, game eff 0.65 + 0.20 = 0.85
        val r = FlagGating.decide(mapOf("profanity" to 0.62f), GAME_PKG)
        assertFalse(r.flagged)
    }

    @Test
    fun sameViolenceStillFlagsInMessaging() {
        // a real threat in a DM is not relaxed: eff threshold stays 0.70
        val r = FlagGating.decide(mapOf("violence" to 0.78f), MSG_PKG)
        assertTrue(r.flagged)
        assertEquals("violence", r.category)
        assertEquals("high", r.severity)
    }

    // ---- LIMITS: high-confidence model errors are NOT rescued by thresholds ----

    @Test
    fun highConfidenceViolenceStillFlagsInGameByDesign() {
        // gating caps the raise at +0.25 (eff 0.95); a 1.0 model error survives -> needs a
        // better model, not more suppression. This documents the residual.
        val r = FlagGating.decide(mapOf("violence" to 1.0f), GAME_PKG)
        assertTrue(r.flagged)
    }

    // ---- selection & severity ----

    @Test
    fun highestSeverityCategoryWins() {
        // both fire; sexual (HIGH) must outrank profanity (LOW)
        val r = FlagGating.decide(mapOf("profanity" to 0.9f, "sexual" to 0.9f), MSG_PKG)
        assertTrue(r.flagged)
        assertEquals("sexual", r.category)
        assertEquals("high", r.severity)
    }

    @Test
    fun unknownCategoryUsesSafeMediumDefaultAndIsNeverRelaxed() {
        // an unforeseen category at 0.60 (default base 0.55) flags, in a game too
        val r = FlagGating.decide(mapOf("some_new_category" to 0.60f), GAME_PKG)
        assertTrue(r.flagged)
        assertEquals("medium", r.severity)
    }

    // ---- edge cases & routing ----

    @Test
    fun severityLabelsMirrorAlertRepositoryMapping() {
        // these MUST match AlertRepository.mapCategoryToSeverity, since gating's severity is
        // now threaded straight to the alert. If they drift, alert severities change silently.
        assertEquals("critical", FlagGating.decide(mapOf("self_harm" to 0.9f), MSG_PKG).severity)
        assertEquals("critical", FlagGating.decide(mapOf("predator_grooming" to 0.9f), MSG_PKG).severity)
        assertEquals("high", FlagGating.decide(mapOf("violence" to 0.9f), MSG_PKG).severity)
        assertEquals("high", FlagGating.decide(mapOf("sexual" to 0.9f), MSG_PKG).severity)
        assertEquals("high", FlagGating.decide(mapOf("bullying" to 0.9f), MSG_PKG).severity)
        assertEquals("medium", FlagGating.decide(mapOf("drugs" to 0.9f), MSG_PKG).severity)
        assertEquals("medium", FlagGating.decide(mapOf("profanity" to 0.9f), MSG_PKG).severity)
    }

    @Test
    fun emptyScoresNeverFlag() {
        assertFalse(FlagGating.decide(emptyMap(), MSG_PKG).flagged)
    }

    @Test
    fun unknownPackageGetsNoRelaxation() {
        // empty/unknown package -> UNKNOWN context -> base thresholds, fails safe (stricter)
        val r = FlagGating.decide(mapOf("violence" to 0.72f), "")
        assertTrue(r.flagged)
    }

    @Test
    fun classifyAppRoutesKnownAndHeuristicPackages() {
        assertEquals(AppContext.GAME, FlagGating.classifyApp(GAME_PKG))
        assertEquals(AppContext.MESSAGING, FlagGating.classifyApp(MSG_PKG))
        assertEquals(AppContext.SOCIAL, FlagGating.classifyApp(SOCIAL_PKG))
        assertEquals(AppContext.BROWSER, FlagGating.classifyApp(BROWSER_PKG))
        assertEquals(AppContext.UNKNOWN, FlagGating.classifyApp("com.acme.notes"))
        assertEquals(AppContext.UNKNOWN, FlagGating.classifyApp(""))
        // substring heuristic catches unlisted games
        assertEquals(AppContext.GAME, FlagGating.classifyApp("com.indie.puzzlegame"))
    }

    @Test
    fun notFlaggedResultHasNullCategory() {
        val r = FlagGating.decide(mapOf("violence" to 0.10f), MSG_PKG)
        assertFalse(r.flagged)
        assertNull(r.category)
        assertNull(r.severity)
    }
}
