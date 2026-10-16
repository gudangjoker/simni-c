package com.chatrok.app.core.security

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.KeyPairGenerator
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class E2eeIdentityManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: SecureIdentityStorage
) {
    suspend fun warmUpIdentity(uid: String) = withContext(Dispatchers.IO) {
        var pubKey = storage.getPublicKey(uid)
        var privKey = storage.getPrivateKey(uid)

        if (pubKey == null || privKey == null) {
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(256, SecureRandom())
            val keyPair = keyPairGen.generateKeyPair()

            pubKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            privKey = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

            storage.savePublicKey(uid, pubKey)
            storage.savePrivateKey(uid, privKey)
        }

        val identityDoc = mapOf(
            "identityKey" to pubKey,
            "signedPreKey" to pubKey,
            "registrationId" to uid.hashCode(),
            "updatedAt" to System.currentTimeMillis()
        )

        firestore.collection("users")
            .document(uid)
            .collection("security")
            .document("e2ee_identity")
            .set(identityDoc, SetOptions.merge())
            .await()
    }
}
