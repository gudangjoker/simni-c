package com.chatrok.app.data.security

import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.crypto.JwkKey
import com.chatrok.app.core.crypto.LocalE2eeIdentity
import com.chatrok.app.core.model.SecurityInfo
import com.chatrok.app.core.model.SecurityIntegrationState
import com.chatrok.app.core.security.SecureIdentityStorage
import com.chatrok.app.domain.repository.SecurityRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreSecurityRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: SecureIdentityStorage,
    private val cryptoEngine: E2eeCryptoEngine
) : SecurityRepository {

    private val peerPublicKeyCache = ConcurrentHashMap<String, JwkKey>()

    /**
     * Memastikan public key pengguna terdaftar di chatPublicKeys/{uid}.
     * Idempotent: Jika belum ada, buat baru dan setDoc.
     */
    suspend fun ensureRegisteredPublicKey(uid: String): LocalE2eeIdentity {
        val identity = storage.getOrCreateIdentity(uid)
        val docRef = firestore.collection("chatPublicKeys").document(uid)
        val doc = docRef.get().await()
        if (!doc.exists()) {
            val payload = mapOf(
                "uid" to uid,
                "version" to 1,
                "fingerprint" to identity.fingerprint,
                "publicJwk" to identity.publicJwk.toMap(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        }
        return identity
    }

    /**
     * Mengambil public key rekan chat dari chatPublicKeys/{peerUid}
     */
    suspend fun getPeerPublicKey(peerUid: String): JwkKey? {
        peerPublicKeyCache[peerUid]?.let { return it }
        val doc = firestore.collection("chatPublicKeys").document(peerUid).get().await()
        if (!doc.exists()) return null
        val publicJwkMap = doc.get("publicJwk") as? Map<*, *> ?: return null
        val jwk = JwkKey.fromMap(publicJwkMap)
        peerPublicKeyCache[peerUid] = jwk
        return jwk
    }

    override fun getSecurityInfo(userId: String): Flow<SecurityInfo> = callbackFlow {
        val listener = firestore.collection("chatPublicKeys")
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(SecurityInfo(userId = userId))
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val fingerprint = snapshot.getString("fingerprint") ?: ""
                    val publicJwkMap = snapshot.get("publicJwk") as? Map<*, *>
                    val qrPayload = publicJwkMap?.toString() ?: fingerprint
                    trySend(
                        SecurityInfo(
                            userId = userId,
                            integrationState = SecurityIntegrationState.PENDING,
                            fingerprint = fingerprint,
                            qrPayload = qrPayload,
                            isVerified = true
                        )
                    )
                } else {
                    trySend(SecurityInfo(userId = userId))
                }
            }
        awaitClose { listener.remove() }
    }

    override suspend fun verifyIdentity(userId: String, isVerified: Boolean): Result<Boolean> {
        return try {
            val peerKey = getPeerPublicKey(userId)
            if (peerKey != null) {
                Result.success(isVerified)
            } else {
                Result.failure(IllegalStateException("Kunci publik rekan chat tidak ditemukan."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
