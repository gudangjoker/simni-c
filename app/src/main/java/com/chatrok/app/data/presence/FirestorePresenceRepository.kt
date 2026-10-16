package com.chatrok.app.data.presence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.chatrok.app.core.model.PresenceState
import com.chatrok.app.core.presence.PresenceCore
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.FirebaseAuthenticatedUidProvider
import com.chatrok.app.domain.repository.PresenceRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val COLLECTION_CHAT_PRESENCE = "chatPresence"
private const val COLLECTION_CHAT_CONVERSATIONS = "chatConversations"
private const val SUBCOLLECTION_TYPING = "typing"

/**
 * Gateway abstraksi data Firestore untuk Presence dan Typing (Milestone 4).
 * Memungkinkan pengujian menyeluruh (Robolectric / Unit Test) tanpa jaringan eksternal.
 */
interface PresenceGateway {
    fun observeUserPresence(userId: String): Flow<Pair<Boolean, Long>>
    fun observeUserTyping(conversationId: String, userId: String): Flow<Boolean>
    suspend fun setPresence(uid: String, isOnline: Boolean)
    suspend fun setTyping(uid: String, conversationId: String, isTyping: Boolean)
}

/**
 * Implementasi gateway default menggunakan Firestore SDK realtime snapshot listener.
 */
class DefaultPresenceGateway(
    private val firestore: FirebaseFirestore
) : PresenceGateway {

    override fun observeUserPresence(userId: String): Flow<Pair<Boolean, Long>> = callbackFlow {
        val docRef = firestore.collection(COLLECTION_CHAT_PRESENCE).document(userId)
        var timeoutJob: Job? = null

        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) {
                timeoutJob?.cancel()
                trySend(Pair(false, 0L))
                return@addSnapshotListener
            }

            val online = snapshot.getBoolean("online") ?: false
            val lastSeen = snapshot.getTimestamp("lastSeen")?.toDate()?.time
            val updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time

            val (effectiveOnline, effectiveLastSeen) = PresenceCore.computePresenceState(
                online = online,
                lastSeenMs = lastSeen,
                updatedAtMs = updatedAt
            )
            trySend(Pair(effectiveOnline, effectiveLastSeen))

            timeoutJob?.cancel()
            if (effectiveOnline && updatedAt != null) {
                val delayMs = (PresenceCore.HEARTBEAT_TIMEOUT_MS - (System.currentTimeMillis() - updatedAt)).coerceAtLeast(100L)
                timeoutJob = launch {
                    delay(delayMs)
                    val (staleOnline, staleLastSeen) = PresenceCore.computePresenceState(
                        online = online,
                        lastSeenMs = lastSeen,
                        updatedAtMs = updatedAt,
                        nowMs = System.currentTimeMillis()
                    )
                    trySend(Pair(staleOnline, staleLastSeen))
                }
            }
        }

        awaitClose {
            timeoutJob?.cancel()
            listener.remove()
        }
    }.flowOn(Dispatchers.IO)

    override fun observeUserTyping(conversationId: String, userId: String): Flow<Boolean> = callbackFlow {
        val docRef = firestore.collection(COLLECTION_CHAT_CONVERSATIONS)
            .document(conversationId)
            .collection(SUBCOLLECTION_TYPING)
            .document(userId)

        var expiryJob: Job? = null

        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) {
                expiryJob?.cancel()
                trySend(false)
                return@addSnapshotListener
            }

            val typing = snapshot.getBoolean("typing") ?: false
            val updatedAt = snapshot.getTimestamp("updatedAt")?.toDate()?.time

            val effectiveTyping = PresenceCore.computeTypingState(
                typing = typing,
                updatedAtMs = updatedAt
            )
            trySend(effectiveTyping)

            expiryJob?.cancel()
            if (effectiveTyping && updatedAt != null) {
                val delayMs = (PresenceCore.TYPING_EXPIRE_MS - (System.currentTimeMillis() - updatedAt)).coerceAtLeast(100L)
                expiryJob = launch {
                    delay(delayMs)
                    trySend(false)
                }
            }
        }

        awaitClose {
            expiryJob?.cancel()
            listener.remove()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun setPresence(uid: String, isOnline: Boolean) {
        val payload = mapOf(
            "uid" to uid,
            "online" to isOnline,
            "lastSeen" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection(COLLECTION_CHAT_PRESENCE)
            .document(uid)
            .set(payload, SetOptions.merge())
            .await()
    }

    override suspend fun setTyping(uid: String, conversationId: String, isTyping: Boolean) {
        val payload = mapOf(
            "uid" to uid,
            "conversationId" to conversationId,
            "typing" to isTyping,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection(COLLECTION_CHAT_CONVERSATIONS)
            .document(conversationId)
            .collection(SUBCOLLECTION_TYPING)
            .document(uid)
            .set(payload)
            .await()
    }
}

/**
 * Implementasi produksi FirestorePresenceRepository untuk SIMNI-C (CHATROK ENGINE).
 *
 * Mengelola:
 * 1. Kehadiran realtime online/offline pada collection `chatPresence/{uid}`
 * 2. Indikator mengetik realtime pada subcollection `chatConversations/{conversationId}/typing/{uid}`
 * 3. Heartbeat berkala (30 detik) saat aplikasi foreground
 * 4. Monitoring konektivitas jaringan Android native
 */
@Singleton
class FirestorePresenceRepository(
    @ApplicationContext private val context: Context,
    private val gateway: PresenceGateway,
    private val uidProvider: AuthenticatedUidProvider,
    private val scope: CoroutineScope
) : PresenceRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        scope: CoroutineScope
    ) : this(
        context,
        DefaultPresenceGateway(firestore),
        FirebaseAuthenticatedUidProvider(auth),
        scope
    )

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val simulatedConnectivity = MutableStateFlow<Boolean?>(null)
    private val lastSentTypingMap = ConcurrentHashMap<String, Long>()
    private var heartbeatJob: Job? = null
    private var isAppForeground: Boolean = true

    init {
        startHeartbeatLoop()
    }

    override fun getPresence(userId: String): Flow<PresenceState> {
        return getPresence(userId, null)
    }

    override fun getPresence(userId: String, conversationId: String?): Flow<PresenceState> {
        val presenceFlow = gateway.observeUserPresence(userId)
        val typingFlow = if (conversationId != null) {
            gateway.observeUserTyping(conversationId, userId)
        } else {
            MutableStateFlow(false)
        }

        return combine(presenceFlow, typingFlow) { (isOnline, lastSeen), isTyping ->
            PresenceState(
                isOnline = isOnline,
                isTyping = isTyping,
                lastSeenTimestamp = lastSeen
            )
        }.distinctUntilChanged()
    }

    override suspend fun setPresence(isOnline: Boolean): Unit = withContext(Dispatchers.IO) {
        val uid = runCatching { uidProvider.requireUid() }.getOrNull() ?: return@withContext
        isAppForeground = isOnline

        try {
            gateway.setPresence(uid, isOnline)
        } catch (_: Exception) {
            // Best effort write
        }
    }

    override suspend fun setTyping(conversationId: String, isTyping: Boolean): Unit = withContext(Dispatchers.IO) {
        val uid = runCatching { uidProvider.requireUid() }.getOrNull() ?: return@withContext

        if (isTyping) {
            val lastSent = lastSentTypingMap[conversationId]
            if (!PresenceCore.shouldSendTypingHeartbeat(lastSent)) {
                // Throttled: tidak mengirim write berulang dalam < 3 detik
                return@withContext
            }
            lastSentTypingMap[conversationId] = System.currentTimeMillis()
        } else {
            lastSentTypingMap.remove(conversationId)
        }

        try {
            gateway.setTyping(uid, conversationId, isTyping)
        } catch (_: Exception) {
            // Best effort write
        }
    }

    override fun getNetworkConnectivity(): Flow<Boolean> {
        val systemConnectivityFlow = callbackFlow {
            val cm = connectivityManager
            if (cm == null) {
                trySend(true)
                close()
                return@callbackFlow
            }

            fun checkCurrentConnection(): Boolean {
                val activeNetwork = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            // Emit initial status
            trySend(checkCurrentConnection())

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(true)
                }

                override fun onLost(network: Network) {
                    trySend(checkCurrentConnection())
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    trySend(hasInternet)
                }
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            try {
                cm.registerNetworkCallback(request, callback)
            } catch (_: Exception) {
                trySend(true)
            }

            awaitClose {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Exception) {}
            }
        }.flowOn(Dispatchers.IO)

        return combine(systemConnectivityFlow, simulatedConnectivity) { system, sim ->
            sim ?: system
        }.distinctUntilChanged()
    }

    override suspend fun simulateNetworkState(isConnected: Boolean) {
        simulatedConnectivity.value = isConnected
    }

    fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PresenceCore.HEARTBEAT_INTERVAL_MS)
                val uid = runCatching { uidProvider.requireUid() }.getOrNull()
                if (uid != null && isAppForeground) {
                    try {
                        gateway.setPresence(uid, true)
                    } catch (_: Exception) {
                        // Silent retry next cycle
                    }
                }
            }
        }
    }
}
