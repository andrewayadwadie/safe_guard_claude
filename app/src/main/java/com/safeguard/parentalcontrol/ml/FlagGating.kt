package com.safeguard.parentalcontrol.ml

/**
 * App-context gating + per-category thresholds for Stage-2 (AI model) flag decisions.
 *
 * WHY: the AI model false-flags ~20% of benign gaming/casual chat ("I'll kill you in the
 * game", "get rekt", "روح موت في اللعبة"). A flat threshold is context-blind. This layer
 * decides per category, adjusting the bar by which app the text came from.
 *
 * PRINCIPLE — asymmetric cost: missing grooming/self-harm/sexual is catastrophic; a false
 * "violence" alarm in a game is merely annoying. So we RAISE the bar for low-cost categories
 * (violence/profanity/bullying) in game/social contexts and NEVER relax the high-cost
 * child-safety categories (predator_grooming/self_harm/sexual) in any context.
 *
 * Stage-1 regex is intentionally NOT gated by this — it is deterministic and high-precision
 * (parent blocklists, explicit dialect slurs) and returns before Stage 2.
 *
 * Pure logic, no Android deps — mirrors scratchpad/gating.py and the Flutter port.
 */
object FlagGating {

    enum class AppContext { GAME, MESSAGING, SOCIAL, BROWSER, UNKNOWN }

    // severity tiers map to backend AlertSeverity. CRITICAL/HIGH high-cost categories are
    // never relaxed by context.
    enum class Severity(val rank: Int, val label: String) {
        LOW(0, "low"), MEDIUM(1, "medium"), HIGH(2, "high"), CRITICAL(3, "critical")
    }

    private data class CategoryRule(val baseThreshold: Float, val severity: Severity)

    // App category taxonomy (see TextPatternMatcher + TFLiteTextClassifier output adapters).
    // baseThreshold = how readily a category fires (low-cost ones gate UP in games);
    // severity = alert priority, and MIRRORS AlertRepository.mapCategoryToSeverity so that
    // threading this severity to alerts does not change established severities. Cost and
    // severity are orthogonal: bullying is low-cost (relaxable in games) yet HIGH severity.
    private val CATEGORY = mapOf(
        // high-cost child-safety — sensitive bases, NEVER relaxed by context
        "predator_grooming" to CategoryRule(0.35f, Severity.CRITICAL),
        "self_harm" to CategoryRule(0.40f, Severity.CRITICAL),
        "sexual" to CategoryRule(0.40f, Severity.HIGH),
        // low-cost — false-positive heavy in games/banter, safe to gate up by context
        "violence" to CategoryRule(0.70f, Severity.HIGH),
        "bullying" to CategoryRule(0.55f, Severity.HIGH),
        "drugs" to CategoryRule(0.55f, Severity.MEDIUM),
        "harmful_behavior" to CategoryRule(0.55f, Severity.MEDIUM),
        "profanity" to CategoryRule(0.65f, Severity.MEDIUM),
    )

    // Unknown categories get a safe medium default and are never relaxed.
    private val DEFAULT_RULE = CategoryRule(0.55f, Severity.MEDIUM)

    // Per-context threshold DELTAS (positive = stricter / harder to flag). Absent => 0.
    // High-cost categories deliberately never receive a positive delta. Messaging makes
    // grooming MORE sensitive (1:1 DM is the grooming vector).
    private val CONTEXT_DELTA = mapOf(
        AppContext.GAME to mapOf("violence" to 0.25f, "profanity" to 0.20f, "bullying" to 0.10f),
        AppContext.MESSAGING to mapOf("predator_grooming" to -0.05f),
        AppContext.SOCIAL to mapOf("violence" to 0.10f, "profanity" to 0.10f),
        AppContext.BROWSER to mapOf("profanity" to 0.10f),
        AppContext.UNKNOWN to emptyMap(),
    )

    // Known package -> context. Substring fallbacks in classifyApp() catch the rest.
    private val PACKAGE_CONTEXT = mapOf(
        "com.epicgames.fortnite" to AppContext.GAME,
        "com.roblox.client" to AppContext.GAME,
        "com.mojang.minecraftpe" to AppContext.GAME,
        "com.activision.callofduty.shooter" to AppContext.GAME,
        "com.supercell.clashofclans" to AppContext.GAME,
        "com.innersloth.spacemafia" to AppContext.GAME,
        "com.tencent.ig" to AppContext.GAME,
        "com.whatsapp" to AppContext.MESSAGING,
        "org.telegram.messenger" to AppContext.MESSAGING,
        "com.facebook.orca" to AppContext.MESSAGING,
        "com.snapchat.android" to AppContext.MESSAGING,
        "com.discord" to AppContext.MESSAGING,
        "com.instagram.android" to AppContext.SOCIAL,
        "com.zhiliaoapp.musically" to AppContext.SOCIAL,
        "com.facebook.katana" to AppContext.SOCIAL,
        "com.twitter.android" to AppContext.SOCIAL,
        "com.android.chrome" to AppContext.BROWSER,
        "org.mozilla.firefox" to AppContext.BROWSER,
    )

    fun classifyApp(packageName: String): AppContext {
        PACKAGE_CONTEXT[packageName]?.let { return it }
        val p = packageName.lowercase()
        return if ("game" in p || "play" in p) AppContext.GAME else AppContext.UNKNOWN
    }

    private fun effectiveThreshold(category: String, context: AppContext): Float {
        val rule = CATEGORY[category] ?: DEFAULT_RULE
        val delta = CONTEXT_DELTA[context]?.get(category) ?: 0f
        return (rule.baseThreshold + delta).coerceIn(0.05f, 0.99f)
    }

    /** Result of gating a per-category score map for a given source app. */
    data class GateResult(
        val flagged: Boolean,
        val category: String?,
        val severity: String?,
        val confidence: Float,
    )

    /**
     * Apply per-category thresholds (adjusted by app context) to Stage-2 scores.
     *
     * @param categoryScores app-category -> model confidence (0..1)
     * @param packageName    source app; "" / unknown => no relaxation (safe default)
     */
    fun decide(categoryScores: Map<String, Float>, packageName: String): GateResult {
        if (categoryScores.isEmpty()) return GateResult(false, null, null, 0f)
        val context = classifyApp(packageName)

        var top: Triple<String, Severity, Float>? = null  // category, severity, margin
        var topScore = 0f
        for ((category, score) in categoryScores) {
            val eff = effectiveThreshold(category, context)
            if (score < eff) continue
            val rule = CATEGORY[category] ?: DEFAULT_RULE
            val margin = score - eff
            // pick highest severity, tie-break by margin over threshold
            if (top == null || rule.severity.rank > top.second.rank ||
                (rule.severity.rank == top.second.rank && margin > top.third)
            ) {
                top = Triple(category, rule.severity, margin)
                topScore = score
            }
        }
        return if (top == null) {
            GateResult(false, null, null, 0f)
        } else {
            GateResult(true, top.first, top.second.label, topScore)
        }
    }
}
