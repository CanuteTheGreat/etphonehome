package com.etphonehome.billing

import com.etphonehome.BuildConfig
import com.etphonehome.pro.PurchaseVerifier
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's real Play licence key (android/app/play-license-key.txt, compiled into BuildConfig) is
 * present, is a 2048-bit RSA public key, and makes the app enforce purchase signatures: unsigned
 * purchases and purchases signed with any other key are refused.
 */
class PlayLicenseKeyTest {
    private val json = """{"orderId":"GPA.3300-0000-0000-00000","packageName":"solutions.errant.etph",""" +
        """"productId":"pro","purchaseTime":1792540000000,"purchaseState":0,"purchaseToken":"t","quantity":1,"acknowledged":true}"""

    @Test
    fun licenceKeyIsCompiledInAndIsRsa2048() {
        assertTrue("android/app/play-license-key.txt missing", BuildConfig.PLAY_LICENSE_KEY.isNotBlank())
        val key = PurchaseVerifier.decodeKey(BuildConfig.PLAY_LICENSE_KEY) as RSAPublicKey
        assertEquals(2048, key.modulus.bitLength())
        assertTrue(PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY).enforcing)
    }

    @Test
    fun withTheRealKeyUnsignedOrForeignSignedPurchasesAreRefused() {
        val v = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY)
        val other = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val foreign = java.util.Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("SHA1withRSA").run {
                initSign(other.private)
                update(json.toByteArray())
                sign()
            },
        )
        for (sig in listOf(null, "", foreign)) {
            assertTrue("sig=$sig", v.verify(json, sig, "solutions.errant.etph", "pro") is PurchaseVerifier.Result.Invalid)
        }
    }
}
