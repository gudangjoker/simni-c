package com.chatrok.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.JwkKey
import com.chatrok.app.core.crypto.LocalE2eeIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureIdentityStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoEngine: E2eeCryptoEngine
) {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "chatrok_secure_identity",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("SecureIdentityStorage", "Fallback to standard SharedPreferences for test/emulator", e)
            context.getSharedPreferences("chatrok_secure_identity_fallback", Context.MODE_PRIVATE)
        }
    }

    fun savePrivateKey(uid: String, privateKeyBase64: String) {
        prefs.edit().putString("priv_key_$uid", privateKeyBase64).apply()
    }

    fun getPrivateKey(uid: String): String? {
        return prefs.getString("priv_key_$uid", null)
    }

    fun savePublicKey(uid: String, publicKeyBase64: String) {
        prefs.edit().putString("pub_key_$uid", publicKeyBase64).apply()
    }

    fun getPublicKey(uid: String): String? {
        return prefs.getString("pub_key_$uid", null)
    }

    fun saveIdentity(uid: String, identity: LocalE2eeIdentity) {
        prefs.edit()
            .putString("priv_jwk_$uid", identity.privateJwk.toJson())
            .putString("pub_jwk_$uid", identity.publicJwk.toJson())
            .putString("fp_$uid", identity.fingerprint)
            .apply()
    }

    fun getIdentity(uid: String): LocalE2eeIdentity? {
        val privJson = prefs.getString("priv_jwk_$uid", null) ?: return null
        val pubJson = prefs.getString("pub_jwk_$uid", null) ?: return null
        val fp = prefs.getString("fp_$uid", null) ?: return null
        return try {
            val privJwk = JwkKey.fromJson(privJson)
            val pubJwk = JwkKey.fromJson(pubJson)
            LocalE2eeIdentity(
                version = 1,
                privateJwk = privJwk,
                publicJwk = pubJwk,
                fingerprint = fp
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getOrCreateIdentity(uid: String): LocalE2eeIdentity {
        val existing = getIdentity(uid)
        if (existing != null) return existing

        val (priv, pub) = cryptoEngine.generateKeyPair()
        val fp = cryptoEngine.fingerprintJwk(pub)
        val identity = LocalE2eeIdentity(
            version = 1,
            privateJwk = priv,
            publicJwk = pub,
            fingerprint = fp
        )
        saveIdentity(uid, identity)
        return identity
    }

    fun clearIdentity(uid: String) {
        prefs.edit()
            .remove("priv_jwk_$uid")
            .remove("pub_jwk_$uid")
            .remove("fp_$uid")
            .remove("priv_key_$uid")
            .remove("pub_key_$uid")
            .apply()
    }
}
