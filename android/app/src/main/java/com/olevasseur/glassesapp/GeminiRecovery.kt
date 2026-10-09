package com.olevasseur.glassesapp

import kotlin.random.Random

/** Reasons are inspected locally, never echoed into logs/UI (they can contain credentials). */
data class GeminiFailure(val message: String, val fatal: Boolean = false,
    val refreshToken: Boolean = false, val clearHandle: Boolean = false)

object GeminiRecovery {
    fun backendFailure(error: Exception): GeminiFailure = when (error) {
        is BackendHttpException -> classify(error.code, error.detail, backend = true)
        is java.io.IOException -> GeminiFailure("Can't reach your Mac")
        else -> GeminiFailure("Unexpected backend response; retrying")
    }

    fun announcement(status: String): String = status.substringBefore(" · retry in")

    fun classify(code: Int? = null, reason: String = "", backend: Boolean = false): GeminiFailure {
        val text = reason.lowercase()
        fun mentions(vararg words: String) = words.any(text::contains)
        return when {
            backend && mentions("not configured") -> GeminiFailure("Backend credentials are not configured; check server settings", fatal = true)
            (mentions("resource_exhausted", "quota", "rate limit", "high demand", "overload", "unavailable") &&
                (!backend || mentions("gemini"))) || (!backend && code in setOf(429, 503, 1013)) -> GeminiFailure("Gemini is busy, retrying")
            mentions("resum", "session handle") && mentions("invalid", "expired", "not found", "cannot") ->
                GeminiFailure("Previous session expired; reconnecting", refreshToken = true, clearHandle = true)
            !backend && (code == 401 || mentions("unauthenticated", "credential expired") ||
                (mentions("token") && mentions("expired", "expiration", "already used", "already been used"))) ->
                GeminiFailure("Refreshing Gemini access", refreshToken = true)
            code in setOf(400, 403, 404, 422, 1007) || (backend && code == 401) ||
                mentions("invalid_argument", "field mask", "fieldmask", "constraint", "permission_denied", "api key", "not supported", "unsupported", "invalid setup", "invalid model", "malformed", "policy violation") ->
                GeminiFailure(if (backend && code == 401) "Backend authentication rejected; check the app shared secret"
                    else "Gemini configuration or access rejected; check model, credentials and token constraints", fatal = true)
            code == 1008 -> GeminiFailure("Gemini policy close; retrying with fresh access", refreshToken = true)
            else -> GeminiFailure(if (backend) "Mac backend error; retrying" else "Connection lost; retrying")
        }
    }

    /** Equal jitter: 0.5–1 s, 1–2 s, … 30–60 s. Never overflows on long outages. */
    fun backoff(attempt: Int, random: Double = Random.nextDouble()): Long {
        val ceiling = minOf(60_000L, 1000L shl (attempt - 1).coerceIn(0, 6))
        return (ceiling * (0.5 + 0.5 * random.coerceIn(0.0, 1.0))).toLong()
    }
}
