package com.chatrok.app.core.crypto

import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class JwkKey(
    val kty: String = "EC",
    val crv: String = "P-256",
    val x: String,
    val y: String,
    val d: String? = null
) {
    fun toPublicJwk(): JwkKey = copy(d = null)

    fun toMap(): Map<String, Any> {
        val map = linkedMapOf<String, Any>(
            "crv" to crv,
            "kty" to kty,
            "x" to x,
            "y" to y
        )
        if (d != null) map["d"] = d
        return map
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("crv", crv)
        obj.put("kty", kty)
        obj.put("x", x)
        obj.put("y", y)
        if (d != null) obj.put("d", d)
        return obj.toString()
    }

    companion object {
        fun fromMap(map: Map<*, *>): JwkKey {
            return JwkKey(
                kty = map["kty"]?.toString() ?: "EC",
                crv = map["crv"]?.toString() ?: "P-256",
                x = map["x"]?.toString().orEmpty(),
                y = map["y"]?.toString().orEmpty(),
                d = map["d"]?.toString()
            )
        }

        fun fromJson(jsonStr: String): JwkKey {
            val obj = JSONObject(jsonStr)
            return JwkKey(
                kty = obj.optString("kty", "EC"),
                crv = obj.optString("crv", "P-256"),
                x = obj.optString("x", ""),
                y = obj.optString("y", ""),
                d = if (obj.has("d")) obj.optString("d") else null
            )
        }
    }
}

data class EncryptedMessagePayload(
    val cryptoVersion: Int = 1,
    val algorithm: String = "ECDH-P256+HKDF-SHA256+AES-256-GCM",
    val iv: String,
    val ciphertext: String,
    val aad: String = ""
)

data class LocalE2eeIdentity(
    val version: Int = 1,
    val privateJwk: JwkKey,
    val publicJwk: JwkKey,
    val fingerprint: String
)

@Singleton
class E2eeCryptoEngine @Inject constructor() {

    private val sharedKeyCache = ConcurrentHashMap<String, SecretKeySpec>()
    private val secureRandom = SecureRandom()

    companion object {
        const val CHAT_CRYPTO_VERSION = 1
        private const val C_CONSONANTS = "bcdfghjklmnpqrst"
        private val V_VOWELS = listOf("a", "e", "i", "o", "u", "ai", "au", "ea", "ia", "io", "oa", "ui", "ya", "yo", "ra", "ri")
    }

    fun base64Encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    fun base64Decode(str: String): ByteArray =
        java.util.Base64.getDecoder().decode(str.trim())

    fun base64UrlEncode(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(str: String): ByteArray {
        var normalized = str.trim().replace('-', '+').replace('_', '/')
        val rem = normalized.length % 4
        if (rem == 2) normalized += "=="
        else if (rem == 3) normalized += "="
        return java.util.Base64.getDecoder().decode(normalized)
    }

    private fun fixedLengthBytes(bi: BigInteger, length: Int): ByteArray {
        val raw = bi.toByteArray()
        if (raw.size == length) return raw
        val out = ByteArray(length)
        if (raw.size > length) {
            System.arraycopy(raw, raw.size - length, out, 0, length)
        } else {
            System.arraycopy(raw, 0, out, length - raw.size, raw.size)
        }
        return out
    }

    /**
     * Menghitung fingerprint SHA-256 dari Public JWK persis seperti Web/PWA:
     * JSON.stringify({ crv, kty, x, y }) -> SHA-256 digest -> Base64
     */
    fun fingerprintJwk(pub: JwkKey): String {
        val normalizedJson = "{\"crv\":\"${pub.crv}\",\"kty\":\"${pub.kty}\",\"x\":\"${pub.x}\",\"y\":\"${pub.y}\"}"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(normalizedJson.toByteArray(StandardCharsets.UTF_8))
        return base64Encode(digest)
    }

    /**
     * Membentuk frasa pemulihan 12 kata deterministik persis sesuai algoritma js/chat-crypto.js
     */
    fun createRecoveryPhrase(): String {
        val bytes = ByteArray(24).also { secureRandom.nextBytes(it) }
        val words = mutableListOf<String>()
        for (i in 0 until bytes.size step 2) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = bytes[i + 1].toInt() and 0xFF
            val w1 = "${C_CONSONANTS[(b1 ushr 4) and 15]}${V_VOWELS[b1 and 15]}"
            val w2 = "${C_CONSONANTS[(b2 ushr 4) and 15]}${V_VOWELS[b2 and 15]}"
            words.add("$w1$w2")
        }
        return words.joinToString(" ")
    }

    /**
     * Generate pasangan kunci ECDH P-256 (secp256r1)
     */
    fun generateKeyPair(): Pair<JwkKey, JwkKey> {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        kpg.initialize(ecSpec, secureRandom)
        val kp: KeyPair = kpg.generateKeyPair()

        val pub = kp.public as ECPublicKey
        val priv = kp.private as ECPrivateKey

        val xBytes = fixedLengthBytes(pub.w.affineX, 32)
        val yBytes = fixedLengthBytes(pub.w.affineY, 32)
        val dBytes = fixedLengthBytes(priv.s, 32)

        val xBase64Url = base64UrlEncode(xBytes)
        val yBase64Url = base64UrlEncode(yBytes)
        val dBase64Url = base64UrlEncode(dBytes)

        val publicJwk = JwkKey(kty = "EC", crv = "P-256", x = xBase64Url, y = yBase64Url)
        val privateJwk = JwkKey(kty = "EC", crv = "P-256", x = xBase64Url, y = yBase64Url, d = dBase64Url)
        return Pair(privateJwk, publicJwk)
    }

    fun jwkToPublicKey(jwk: JwkKey): PublicKey {
        val xBytes = base64UrlDecode(jwk.x)
        val yBytes = base64UrlDecode(jwk.y)
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val dummyKp = kpg.generateKeyPair()
        val ecParams: ECParameterSpec = (dummyKp.public as ECPublicKey).params

        val spec = ECPublicKeySpec(point, ecParams)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(spec)
    }

    fun jwkToPrivateKey(jwk: JwkKey): PrivateKey {
        requireNotNull(jwk.d) { "Private JWK must contain 'd'" }
        val dBytes = base64UrlDecode(jwk.d)
        val s = BigInteger(1, dBytes)

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val dummyKp = kpg.generateKeyPair()
        val ecParams: ECParameterSpec = (dummyKp.private as ECPrivateKey).params

        val spec = ECPrivateKeySpec(s, ecParams)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(spec)
    }

    /**
     * HKDF-Extract(salt, IKM)
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand(PRK, info, L=32)
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        val t1 = mac.doFinal()
        return t1.copyOf(outLen)
    }

    /**
     * Derivasi kunci simetris bersama (ECDH + HKDF-SHA-256) identik dengan chat-crypto.js
     */
    fun deriveSharedKey(privateJwk: JwkKey, peerPublicJwk: JwkKey, context: String): SecretKeySpec {
        val cacheKey = "${peerPublicJwk.x}:${peerPublicJwk.y}|$context"
        sharedKeyCache[cacheKey]?.let { return it }

        val myPriv = jwkToPrivateKey(privateJwk)
        val peerPub = jwkToPublicKey(peerPublicJwk)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPriv)
        ka.doPhase(peerPub, true)
        val secretBits = ka.generateSecret() // 32 bytes

        // Salt = SHA-256("SIMNI_CHAT_SALT|" + context)
        val saltInput = "SIMNI_CHAT_SALT|$context".toByteArray(StandardCharsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(saltInput)

        // Info = "SIMNI_CHAT_KEY|v" + CHAT_CRYPTO_VERSION + "|" + context
        val info = "SIMNI_CHAT_KEY|v$CHAT_CRYPTO_VERSION|$context".toByteArray(StandardCharsets.UTF_8)

        val prk = hkdfExtract(salt, secretBits)
        val derivedAesKeyBytes = hkdfExpand(prk, info, 32)
        val secretKey = SecretKeySpec(derivedAesKeyBytes, "AES")

        if (sharedKeyCache.size > 50) sharedKeyCache.clear()
        sharedKeyCache[cacheKey] = secretKey
        return secretKey
    }

    /**
     * Enkripsi data biner menggunakan ECDH P-256 + HKDF-SHA256 + AES-256-GCM
     */
    fun encryptBytes(
        bytes: ByteArray,
        privateJwk: JwkKey,
        peerPublicJwk: JwkKey,
        context: String,
        additionalData: String = ""
    ): EncryptedMessagePayload {
        val key = deriveSharedKey(privateJwk, peerPublicJwk, context)
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        if (additionalData.isNotEmpty()) {
            cipher.updateAAD(additionalData.toByteArray(StandardCharsets.UTF_8))
        }

        val cipherBytes = cipher.doFinal(bytes)

        return EncryptedMessagePayload(
            cryptoVersion = CHAT_CRYPTO_VERSION,
            algorithm = "ECDH-P256+HKDF-SHA256+AES-256-GCM",
            iv = base64Encode(iv),
            ciphertext = base64Encode(cipherBytes),
            aad = additionalData
        )
    }

    /**
     * Dekripsi data biner terenkripsi
     */
    fun decryptBytes(
        payload: EncryptedMessagePayload,
        privateJwk: JwkKey,
        peerPublicJwk: JwkKey,
        context: String
    ): ByteArray {
        require(payload.cryptoVersion == CHAT_CRYPTO_VERSION) { "Versi crypto tidak didukung: ${payload.cryptoVersion}" }
        val key = deriveSharedKey(privateJwk, peerPublicJwk, context)
        val ivBytes = base64Decode(payload.iv)
        val cipherBytes = base64Decode(payload.ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        if (payload.aad.isNotEmpty()) {
            cipher.updateAAD(payload.aad.toByteArray(StandardCharsets.UTF_8))
        }

        return cipher.doFinal(cipherBytes)
    }

    /**
     * Enkripsi teks menggunakan ECDH P-256 + HKDF-SHA256 + AES-256-GCM
     */
    fun encryptText(
        text: String,
        privateJwk: JwkKey,
        peerPublicJwk: JwkKey,
        context: String,
        additionalData: String = ""
    ): EncryptedMessagePayload {
        val plainBytes = text.toByteArray(StandardCharsets.UTF_8)
        return encryptBytes(plainBytes, privateJwk, peerPublicJwk, context, additionalData)
    }

    /**
     * Dekripsi pesan terenkripsi
     */
    fun decryptText(
        payload: EncryptedMessagePayload,
        privateJwk: JwkKey,
        peerPublicJwk: JwkKey,
        context: String
    ): String {
        val plainBytes = decryptBytes(payload, privateJwk, peerPublicJwk, context)
        return String(plainBytes, StandardCharsets.UTF_8)
    }
}
