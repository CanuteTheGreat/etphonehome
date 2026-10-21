package com.etphonehome.pro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixed Play-style purchase vector: a purchase JSON signed once (SHA1withRSA, the scheme Play uses)
 * with a dedicated 2048-bit TEST key whose public half is below. Unlike PurchaseVerifierTest (fresh
 * keys per run) these bytes never change, so a regression in decoding/verifying is caught against
 * a known-good signature. The test key is not the app's Play licence key.
 */
class PurchaseVectorTest {
    companion object {
        const val TEST_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApR37VcwR2y0p0Iq0+RqLdJ8XRbmVgAqn2S2bnaulOAt4yJGsI9Cv" +
            "GcyCYxTrLm7JgpYohbf3KCwK47wlpFYl8MAHT32dPcDyLkdTW9UslS/h+LsUkhzJcIqm0LeZZT6TyBSIc1TwgXyNJxTD/QNF" +
            "RsjwVwdA7SdcBgWWlQmOolUZSPwyW0troWgiXTa5ygMzim9hzdvbGuR5vwyCntG3xbcGA0FvaNUq2eh8oDcGnmVQ6l3hSyWN" +
            "VXj/H5mMoLP53sY89DD1jWg9YUIdrKWBnpPGyznZkhKowY9qBVj8BFezazX0yvMMfefvGxPdCDdoIDBsqPkNYWfWPPxBStCY" +
            "DQIDAQAB"
        const val PURCHASE_JSON =
            "{\"orderId\":\"GPA.3300-0000-0000-00000\",\"packageName\":\"solutions.errant.etph\",\"productId\":\"pro\",\"purchaseTime\":1792540000000,\"purchaseState\":0,\"purchaseToken\":\"etph-test-vector-token\",\"quantity\":1,\"acknowledged\":true}"
        const val SIGNATURE =
            "Sy26J3NvTzYxP6ejtp6a9C96P4Rl5kDFCIY1JwFfDqGbU1/cZTG3iNa/DM8DL3WNpDHXLm73VLvS6SS3q4jitawyKxsBbYYA" +
            "+q9f56g1LQjpK1RjpiKKaNMKHAi2jJcPRTa5ydEtrF9cF2IADiR/ypnjqRrIGSLQ1PLiFNx4sEhoYeraKL5GNs9KHgiLo/q5" +
            "X6piau2Y+M/NABo8KFHKd1lNJBV3Bx+tXHOaHv8Nk++hT8vi+wFICgah0P+AXObntjBGqmRnfJguOoKVv8BmTK4Vyq/YrJY+" +
            "6xzcukFDekhh8Wl1/tJwDJArGZ2uSgHjDLNpxPoqSxfiFV3ZAsE+kA=="
    }

    private val v = PurchaseVerifier(TEST_PUBLIC_KEY)
    private fun check(json: String, sig: String?) = v.verify(json, sig, "solutions.errant.etph", "pro")

    @Test
    fun knownGoodVectorVerifies() {
        assertTrue(v.enforcing)
        assertEquals(
            PurchaseVerifier.Result.Purchased("etph-test-vector-token", "GPA.3300-0000-0000-00000", true),
            check(PURCHASE_JSON, SIGNATURE),
        )
    }

    @Test
    fun knownBadVectorsAreRejected() {
        // One changed byte in the signed JSON (state 0 -> 0 but another token), a flipped signature
        // bit, a truncated signature, garbage, an empty and a missing signature.
        val edited = PURCHASE_JSON.replace("etph-test-vector-token", "etph-test-vector-tokeN")
        val raw = java.util.Base64.getDecoder().decode(SIGNATURE).also { it[10] = (it[10].toInt() xor 1).toByte() }
        val flipped = java.util.Base64.getEncoder().encodeToString(raw)
        for ((what, json, sig) in listOf(
            Triple("edited json", edited, SIGNATURE),
            Triple("flipped bit", PURCHASE_JSON, flipped),
            Triple("truncated", PURCHASE_JSON, SIGNATURE.substring(0, 40)),
            Triple("not base64", PURCHASE_JSON, "%%%not-a-signature%%%"),
            Triple("empty", PURCHASE_JSON, ""),
            Triple("missing", PURCHASE_JSON, null),
        )) {
            assertTrue(what, check(json, sig) is PurchaseVerifier.Result.Invalid)
        }
    }
}
