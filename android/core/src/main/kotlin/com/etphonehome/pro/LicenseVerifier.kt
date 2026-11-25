package com.etphonehome.pro

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.util.Base64

/**
 * ETPH Pro license keys for copies installed outside Google Play ([InstallChannel.DIRECT]), bought
 * on errant.solutions (docs/PRO-DIRECT.md) or given out by Errant Solutions (comp keys).
 *
 * Key = `ETPH1.<base64url(payload JSON)>.<base64url(Ed25519 signature)>`; the signature covers
 * `etph-license-v1.` + the payload part exactly as written, so nothing is re-encoded before
 * checking. Payload: `{"v":1,"prod":"etph-pro","id":"EP-...","eh":"...","iat":"YYYY-MM-DD","mode":"live"}`.
 * The public keys are compiled into the app per mode: release builds carry only the live key,
 * debug/QA builds also the test key (test-mode Stripe purchases), so a test key never unlocks a
 * release build. Checked fully offline.
 *
 * Revocation (refunds, chargebacks, revoked comp keys): `ETPHREV1.<payload>.<sig>` with payload
 * `{"v":1,"prod":..,"mode":..,"iat":<unix s>,"ids":[..]}`, signed over `etph-revocations-v1.` +
 * payload by the same key, fetched by the app when it is online. The two signing domains keep a
 * revocation list from ever being accepted as a key and the other way round.
 *
 * The server side (signing, issuing, the list) is ctg-infrastructure services/errant-pro; both
 * sides check the same vectors (src/test/resources/pro-license-vectors.txt).
 */
class LicenseVerifier(
    /** mode ("live" / "test") -> base64 32-byte Ed25519 public keys accepted for that mode. */
    keys: Map<String, List<String>>,
    private val productId: String = PRODUCT_ID,
) {
    private val keys: Map<String, List<ByteArray>> = keys.mapValues { (_, l) ->
        l.map { it.trim() }.filter { it.isNotEmpty() }.map { Base64.getDecoder().decode(it) }.filter { it.size == Ed25519.PUBLIC_KEY_SIZE }
    }.filterValues { it.isNotEmpty() }

    /** At least one key is compiled in (else every key is refused). */
    val enabled: Boolean get() = keys.isNotEmpty()

    data class License(val id: String, val mode: String, val issued: String, val emailHash: String, val token: String) {
        val isComp: Boolean get() = id.startsWith("comp-")
    }

    sealed class Result {
        data class Valid(val license: License) : Result()
        data class Invalid(val why: String) : Result()
    }

    data class RevocationList(val ids: Set<String>, val issuedAtSec: Long, val mode: String)

    fun verify(input: String): Result {
        val token = normalize(input)
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return Result.Invalid("that isn't an ETPH license key")
        val payloadBytes = decode(parts[1]) ?: return Result.Invalid("the key is damaged (copy it again in full)")
        val sig = decode(parts[2]) ?: return Result.Invalid("the key is damaged (copy it again in full)")
        val json = FlatJson(String(payloadBytes, Charsets.UTF_8))
        val mode = json.string("mode") ?: return Result.Invalid("the key is damaged")
        val modeKeys = keys[mode] ?: return Result.Invalid(if (mode == "test") "this is a test key, it doesn't work in this build" else "this key isn't for this app")
        if (!modeKeys.any { checks(it, SIG_DOMAIN + parts[1], sig) }) return Result.Invalid("the key's signature doesn't match (copy it again in full)")
        if (json.number("v") != 1L) return Result.Invalid("this key needs a newer ETPH")
        if (json.string("prod") != productId) return Result.Invalid("this key is for another product")
        val id = json.string("id") ?: return Result.Invalid("the key is damaged")
        return Result.Valid(License(id, mode, json.string("iat").orEmpty(), json.string("eh").orEmpty(), token))
    }

    /** A verified list for [mode] (the license's mode), or null if it is unsigned, tampered or for something else. */
    fun verifyRevocations(text: String, mode: String): RevocationList? {
        val parts = text.trim().split('.')
        if (parts.size != 3 || parts[0] != REVOCATION_PREFIX) return null
        val payload = decode(parts[1]) ?: return null
        val sig = decode(parts[2]) ?: return null
        val modeKeys = keys[mode] ?: return null
        if (!modeKeys.any { checks(it, REVOCATION_DOMAIN + parts[1], sig) }) return null
        val json = FlatJson(String(payload, Charsets.UTF_8))
        if (json.number("v") != 1L || json.string("prod") != productId || json.string("mode") != mode) return null
        return RevocationList(json.stringArray("ids").orEmpty().toSet(), json.number("iat") ?: return null, mode)
    }

    private fun checks(pub: ByteArray, message: String, sig: ByteArray): Boolean {
        if (sig.size != Ed25519.SIGNATURE_SIZE) return false
        val m = message.toByteArray(Charsets.US_ASCII)
        return try {
            Ed25519.verify(sig, 0, pub, 0, m, 0, m.size)
        } catch (e: Exception) {
            false
        }
    }

    private fun decode(s: String): ByteArray? = try {
        if (s.isEmpty() || !s.all { it.isLetterOrDigit() || it == '-' || it == '_' }) null else Base64.getUrlDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val PRODUCT_ID = "etph-pro"
        const val PREFIX = "ETPH1"
        const val REVOCATION_PREFIX = "ETPHREV1"
        private const val SIG_DOMAIN = "etph-license-v1."
        private const val REVOCATION_DOMAIN = "etph-revocations-v1."

        /**
         * What people paste: the key, possibly wrapped over several lines by a mail program, with
         * spaces, quotes or angle brackets around it, or the whole activation link
         * (`https://errant.solutions/etphonehome/pro/activate#ETPH1...` / `etph://activate?key=...`).
         */
        fun normalize(input: String): String {
            var s = input.replace(Regex("\\s+"), "")
            val at = s.indexOf("$PREFIX.")
            if (at > 0) s = s.substring(at)
            return s.trim('"', '\'', '<', '>', '(', ')', '[', ']', '.', ',', ';')
        }

        /** "live:<b64>,test:<b64>" (BuildConfig) -> mode map; several keys per mode allowed (rotation). */
        fun parseKeys(spec: String): Map<String, List<String>> =
            spec.split(',').mapNotNull { e ->
                val i = e.indexOf(':')
                if (i <= 0) null else e.substring(0, i).trim() to e.substring(i + 1).trim()
            }.filter { it.second.isNotEmpty() }.groupBy({ it.first }, { it.second })
    }
}
