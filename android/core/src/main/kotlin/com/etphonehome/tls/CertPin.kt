package com.etphonehome.tls

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The phone side of ET Phone Home's cert-pinned TLS 1.3
 * (desktop/common/src/tls/certpin.rs is the reference; keep them in step).
 *
 * Both peers derive the same Ed25519 key pair from the pairing token:
 * seed = HKDF-SHA256(salt "ETPhoneHome-TLS-Salt", ikm token,
 * info "etphonehome-certpin-identity-v1", 32 bytes). Each side presents a
 * self-signed certificate for that key (mutual TLS) and accepts the peer
 * only if the peer's certificate carries exactly the key it derived
 * itself. A wrong token makes the handshake fail; there is no plaintext
 * fallback once TLS has started.
 *
 * Android's platform TLS stack cannot use Ed25519 keys, so this uses the
 * Bouncy Castle JSSE provider, the same on the phone and in the JVM
 * stand-in the CI end-to-end test runs.
 */
object CertPin {
    private const val SALT = "ETPhoneHome-TLS-Salt"
    private const val INFO = "etphonehome-certpin-identity-v1"

    private val ED25519_PKCS8_PREFIX = byteArrayOf(
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
    )
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )

    val bcProvider by lazy { BouncyCastleProvider() }
    private val jsseProvider by lazy { BouncyCastleJsseProvider(bcProvider) }

    /** The 32-byte Ed25519 seed derived from [token] (matches derive_cert_seed in Rust). */
    fun deriveSeed(token: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(token.toByteArray(Charsets.UTF_8))
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO.toByteArray())
        mac.update(1.toByte())
        return mac.doFinal().copyOf(32)
    }

    /** The raw 32-byte Ed25519 public key both peers pin for [token]. */
    fun expectedPublicKey(token: String): ByteArray =
        Ed25519PrivateKeyParameters(deriveSeed(token), 0).generatePublicKey().encoded

    /** Raw Ed25519 key of [cert], or null if it isn't an Ed25519 certificate. */
    fun rawEd25519Key(cert: X509Certificate): ByteArray? {
        val spki = cert.publicKey.encoded
        if (spki.size != ED25519_SPKI_PREFIX.size + 32) return null
        if (!spki.copyOf(ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) return null
        return spki.copyOfRange(ED25519_SPKI_PREFIX.size, spki.size)
    }

    /** A TLS 1.3 context whose identity and peer pin both come from [token]. */
    fun serverContext(token: String): SSLContext =
        context(token, PinningTrustManager(expectedPublicKey(token)))

    /**
     * Test hook: a peer that presents the identity derived from
     * [identityToken] (none if null) and trusts ANY certificate -- i.e. a
     * client that skips its own pin check, to prove the phone's pin check
     * (and its demand for a client certificate) holds on its own.
     */
    internal fun permissiveContext(identityToken: String?): SSLContext {
        if (identityToken != null) return context(identityToken, TrustAnything)
        val ctx = SSLContext.getInstance("TLSv1.3", jsseProvider)
        ctx.init(null, arrayOf(TrustAnything), SecureRandom())
        return ctx
    }

    private object TrustAnything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun context(token: String, trust: X509TrustManager): SSLContext {
        val seed = deriveSeed(token)
        val kf = KeyFactory.getInstance("Ed25519", bcProvider)
        val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8_PREFIX + seed))
        val publicRaw = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val spki = SubjectPublicKeyInfo.getInstance(ED25519_SPKI_PREFIX + publicRaw)
        val now = System.currentTimeMillis()
        val name = X500Name("CN=etphonehome")
        val holder = X509v3CertificateBuilder(
            name, BigInteger(64, SecureRandom()), Date(now - 86_400_000L), Date(now + 3650L * 86_400_000L),
            name, spki,
        ).build(JcaContentSignerBuilder("Ed25519").setProvider(bcProvider).build(privateKey))
        val cert = JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(holder)

        val password = CharArray(0)
        val ks = KeyStore.getInstance("PKCS12", bcProvider)
        ks.load(null, null)
        ks.setKeyEntry("etphonehome", privateKey, password, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance("PKIX", jsseProvider)
        kmf.init(ks, password)

        val ctx = SSLContext.getInstance("TLSv1.3", jsseProvider)
        ctx.init(kmf.keyManagers, arrayOf(trust), SecureRandom())
        return ctx
    }

    /**
     * Run the server side of the TLS handshake on [plain], whose first
     * byte(s) [consumed] were already read (to tell TLS from plaintext).
     * Throws on a pin mismatch (wrong token) or any handshake failure.
     */
    fun accept(plain: Socket, consumed: ByteArray, token: String): SSLSocket {
        val factory = serverContext(token).socketFactory
        val consumedStream: InputStream = ByteArrayInputStream(consumed)
        val tls = layerOver(factory, plain, consumedStream)
        tls.enabledProtocols = arrayOf("TLSv1.3")
        tls.needClientAuth = true
        tls.startHandshake()
        return tls
    }

    /**
     * `factory.createSocket(plain, consumed, true)`, called on Bouncy Castle's
     * own factory class. That overload is Java 8 API that Android's
     * javax.net.ssl.SSLSocketFactory lacks on Android 8/9, so a direct
     * (virtual) call through the framework type fails to link there with
     * NoSuchMethodError and crashed the app on the first WiFi TLS connection
     * (found by the API 26 emulator test). BC's class implements it on every
     * version; reflection binds to that implementation.
     */
    internal fun layerOver(factory: SSLSocketFactory, plain: Socket, consumed: InputStream): SSLSocket {
        val method = factory.javaClass.getMethod(
            "createSocket", Socket::class.java, InputStream::class.java, java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        return try {
            method.invoke(factory, plain, consumed, true) as SSLSocket
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Accept only a peer certificate carrying exactly [expected] (constant-time compare). */
    private class PinningTrustManager(private val expected: ByteArray) : X509TrustManager {
        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("peer sent no certificate")
            val key = rawEd25519Key(leaf) ?: throw CertificateException("peer certificate is not Ed25519")
            if (!MessageDigest.isEqual(key, expected)) {
                throw CertificateException("peer certificate does not match the pairing token")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
