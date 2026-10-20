package com.etphonehome.pro

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play-style signed purchases (SHA1withRSA over originalJson), made with a test key pair. */
class PurchaseVerifierTest {
    companion object {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicB64: String = Base64.getEncoder().encodeToString(keys.public.encoded)

        fun json(state: Int = 0, pkg: String = "solutions.errant.etph", product: String = "etph_pro_lifetime", ack: Boolean = false) =
            """{"orderId":"GPA.1234-5678","packageName":"$pkg","productId":"$product","purchaseTime":1760000000000,""" +
                """"purchaseState":$state,"purchaseToken":"tok-\"abc\"","quantity":1,"acknowledged":$ack}"""

        fun sign(s: String): String = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA1withRSA").run {
                initSign(keys.private)
                update(s.toByteArray())
                sign()
            },
        )
    }

    private val v = PurchaseVerifier(publicB64)
    private fun verify(j: String, sig: String? = sign(j)) = v.verify(j, sig, "solutions.errant.etph", "etph_pro_lifetime")

    @Test
    fun genuinePurchaseVerifies() {
        assertTrue(v.enforcing)
        val r = verify(json()) as PurchaseVerifier.Result.Purchased
        assertEquals("tok-\"abc\"", r.token)
        assertEquals("GPA.1234-5678", r.orderId)
        assertFalse(r.acknowledged)
        assertTrue((verify(json(ack = true)) as PurchaseVerifier.Result.Purchased).acknowledged)
    }

    @Test
    fun editedJsonOrForeignSignatureIsRejected() {
        val j = json()
        assertTrue(verify(j.replace("1760000000000", "1760000000001"), sign(j)) is PurchaseVerifier.Result.Invalid)
        assertTrue(verify(j, null) is PurchaseVerifier.Result.Invalid)
        assertTrue(verify(j, "bm90IGEgc2lnbmF0dXJl") is PurchaseVerifier.Result.Invalid)
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val foreign = Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA1withRSA").run { initSign(other.private); update(j.toByteArray()); sign() },
        )
        assertTrue(verify(j, foreign) is PurchaseVerifier.Result.Invalid)
    }

    @Test
    fun otherAppOrProductIsRejectedEvenWhenSigned() {
        assertTrue(verify(json(pkg = "app.ssherpa")) is PurchaseVerifier.Result.Invalid)
        assertTrue(verify(json(product = "something_else")) is PurchaseVerifier.Result.Invalid)
    }

    @Test
    fun pendingAndProductIdsArray() {
        assertTrue(verify(json(state = 4)) is PurchaseVerifier.Result.Pending)
        val multi = """{"packageName":"solutions.errant.etph","productIds":["a","etph_pro_lifetime"],"purchaseState":0,"purchaseToken":"t"}"""
        assertTrue(verify(multi) is PurchaseVerifier.Result.Purchased)
    }

    @Test
    fun withoutAKeyOnlyTheContentIsChecked() {
        val none = PurchaseVerifier("")
        assertFalse(none.enforcing)
        assertTrue(none.verify(json(), null, "solutions.errant.etph", "etph_pro_lifetime") is PurchaseVerifier.Result.Purchased)
        assertTrue(none.verify(json(pkg = "x"), null, "solutions.errant.etph", "etph_pro_lifetime") is PurchaseVerifier.Result.Invalid)
    }
}
