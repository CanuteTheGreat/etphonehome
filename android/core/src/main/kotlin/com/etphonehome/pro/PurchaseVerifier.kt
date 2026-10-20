package com.etphonehome.pro

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Local verification of a Google Play purchase: Play signs every purchase's JSON (`originalJson`)
 * with the app's RSA licence key (Play Console > Monetize with Play > Monetization setup), and
 * the app checks that signature with the public half compiled into it. A purchase that was made
 * up or edited on the device (a patched Play Store, a hooked billing service) fails here.
 *
 * Why no backend (see docs/pro.md): ETPH has no accounts and no servers in the data path, the
 * product is a one-time unlock of features that run entirely on the phone, and anyone can build
 * the MIT-licensed source without the gate anyway. A server check would add a privacy-relevant
 * service, a failure mode for an offline tethering app, and cost, against very little abuse it
 * could stop. Play itself is re-asked on every app start and every Start (refunds and
 * revocations disappear from its answer), which is the periodic server check.
 */
class PurchaseVerifier(base64PublicKey: String?) {

    private val key: PublicKey? = base64PublicKey?.trim()?.takeIf { it.isNotEmpty() }?.let { decodeKey(it) }

    /** True when a licence key is compiled in, so signatures are really checked. */
    val enforcing: Boolean get() = key != null

    sealed class Result {
        /** Signature valid (or no key compiled in), right app and product, state purchased. */
        data class Purchased(val token: String, val orderId: String?, val acknowledged: Boolean) : Result()
        data class Pending(val token: String) : Result()
        data class Invalid(val why: String) : Result()
    }

    fun verify(originalJson: String, signature: String?, packageName: String, productId: String): Result {
        val k = key
        if (k != null) {
            if (signature.isNullOrBlank()) return Result.Invalid("no signature")
            val ok = try {
                Signature.getInstance("SHA1withRSA").run {
                    initVerify(k)
                    update(originalJson.toByteArray(Charsets.UTF_8))
                    verify(Base64.getDecoder().decode(signature.trim()))
                }
            } catch (e: Exception) {
                false
            }
            if (!ok) return Result.Invalid("signature does not match the app's Play licence key")
        }
        val json = FlatJson(originalJson)
        if (json.string("packageName") != packageName) return Result.Invalid("purchase is for another app")
        val products = json.stringArray("productIds") + listOfNotNull(json.string("productId"))
        if (productId !in products) return Result.Invalid("purchase is for another product")
        val token = json.string("purchaseToken") ?: return Result.Invalid("no purchase token")
        // Play's JSON: purchaseState 0 = purchased, 4 = pending (anything else is not owned).
        return when (json.number("purchaseState") ?: 0L) {
            0L -> Result.Purchased(token, json.string("orderId"), json.bool("acknowledged") ?: false)
            4L -> Result.Pending(token)
            else -> Result.Invalid("purchase state ${json.number("purchaseState")}")
        }
    }

    companion object {
        fun decodeKey(b64: String): PublicKey =
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))
    }
}

/**
 * Just enough JSON for Play's flat purchase object (top-level strings, numbers, booleans and
 * string arrays). The core has no JSON library and must stay plain JVM.
 */
internal class FlatJson(private val s: String) {
    private fun valueStart(key: String): Int? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*").find(s) ?: return null
        return m.range.last + 1
    }

    fun string(key: String): String? {
        val i = valueStart(key) ?: return null
        if (i >= s.length || s[i] != '"') return null
        return readString(i).first
    }

    fun number(key: String): Long? {
        val i = valueStart(key) ?: return null
        return Regex("-?\\d+").matchAt(s, i)?.value?.toLongOrNull()
    }

    fun bool(key: String): Boolean? {
        val i = valueStart(key) ?: return null
        return when {
            s.startsWith("true", i) -> true
            s.startsWith("false", i) -> false
            else -> null
        }
    }

    fun stringArray(key: String): List<String> {
        var i = valueStart(key) ?: return emptyList()
        if (i >= s.length || s[i] != '[') return emptyList()
        i++
        val out = mutableListOf<String>()
        while (i < s.length) {
            when (s[i]) {
                ']' -> return out
                '"' -> {
                    val (v, end) = readString(i)
                    out += v
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    /** The string starting at the quote at [start]; returns it and the index after its closing quote. */
    private fun readString(start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return out.toString() to i + 1
                c == '\\' && i + 1 < s.length -> {
                    when (val e = s[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> if (i + 5 < s.length) {
                            out.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString() to i
    }
}
