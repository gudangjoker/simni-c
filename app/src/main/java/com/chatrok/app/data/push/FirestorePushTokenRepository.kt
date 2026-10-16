package com.chatrok.app.data.push

import com.chatrok.app.core.push.PushConstants
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.FirebaseAuthenticatedUidProvider
import com.chatrok.app.domain.repository.PushTokenRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface PushTokenGateway {
    suspend fun saveToken(uid: String, tokenId: String, payload: Map<String, Any>)
    suspend fun deactivateToken(uid: String, tokenId: String)
    suspend fun deactivateAllTokens(uid: String)
}

class DefaultPushTokenGateway(
    private val firestore: FirebaseFirestore
) : PushTokenGateway {

    override suspend fun saveToken(uid: String, tokenId: String, payload: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        val data = payload.toMutableMap()
        data["updatedAt"] = FieldValue.serverTimestamp()
        firestore.collection("chatPushTokens").document(uid)
            .collection("tokens").document(tokenId)
            .set(data, SetOptions.merge())
            .await()
    }

    override suspend fun deactivateToken(uid: String, tokenId: String): Unit = withContext(Dispatchers.IO) {
        firestore.collection("chatPushTokens").document(uid)
            .collection("tokens").document(tokenId)
            .update(
                mapOf(
                    "active" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    override suspend fun deactivateAllTokens(uid: String): Unit = withContext(Dispatchers.IO) {
        val snapshot = firestore.collection("chatPushTokens").document(uid)
            .collection("tokens")
            .whereEqualTo("active", true)
            .get()
            .await()

        if (snapshot.isEmpty) return@withContext

        val batch = firestore.batch()
        for (doc in snapshot.documents) {
            batch.update(doc.reference, mapOf(
                "active" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            ))
        }
        batch.commit().await()
    }
}

@Singleton
class FirestorePushTokenRepository(
    private val gateway: PushTokenGateway,
    private val uidProvider: AuthenticatedUidProvider
) : PushTokenRepository {

    @Inject
    constructor(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ) : this(
        DefaultPushTokenGateway(firestore),
        FirebaseAuthenticatedUidProvider(auth)
    )

    override suspend fun registerCurrentToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = uidProvider.getUid()
            ?: return@withContext Result.failure(IllegalStateException("Pengguna belum diautentikasi"))

        runCatching {
            val cleanToken = token.trim()
            val tokenId = PushConstants.hashPushToken(cleanToken)
            val payload = PushConstants.buildTokenDocPayload(
                uid = uid,
                token = cleanToken,
                platform = "android"
            )
            gateway.saveToken(uid, tokenId, payload)
        }
    }

    override suspend fun unregisterToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = uidProvider.getUid()
            ?: return@withContext Result.failure(IllegalStateException("Pengguna belum diautentikasi"))

        runCatching {
            val cleanToken = token.trim()
            val tokenId = PushConstants.hashPushToken(cleanToken)
            gateway.deactivateToken(uid, tokenId)
        }
    }

    override suspend fun deactivateUserTokens(uid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUid = uid.trim()
        if (cleanUid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("UID tidak boleh kosong"))
        }

        runCatching {
            gateway.deactivateAllTokens(cleanUid)
        }
    }
}
