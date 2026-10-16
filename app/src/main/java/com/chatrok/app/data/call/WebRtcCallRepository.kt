package com.chatrok.app.data.call

import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallState
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.IceCandidatePayload
import com.chatrok.app.core.model.SignalingCallSession
import com.chatrok.app.core.model.User
import com.chatrok.app.domain.repository.CallRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcCallRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) : CallRepository {

    private val callsCollection = firestore.collection("calls")

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    override val currentCall: StateFlow<CallSession?> = _currentCall.asStateFlow()

    private val _callHistory = MutableStateFlow<List<CallSession>>(emptyList())
    override val callHistory: Flow<List<CallSession>> = _callHistory.asStateFlow()

    // --- WebRTC Signaling Channel Methods (Firestore /calls & subkoleksi /candidates) ---

    override suspend fun startCallSignaling(conversationId: String, calleeId: String, callType: String): String {
        val callerUid = auth.currentUser?.uid ?: "unknown"
        val callId = "call_${System.currentTimeMillis()}_$callerUid"
        val session = SignalingCallSession(
            callId = callId,
            conversationId = conversationId,
            callerId = callerUid,
            calleeId = calleeId,
            callType = callType,
            status = "ringing",
            sdpOffer = "OFFER_INIT_SDP",
            createdAt = System.currentTimeMillis()
        )
        callsCollection.document(callId).set(session).await()
        return callId
    }

    override suspend fun answerCallSignaling(callId: String, sdpAnswer: String) {
        callsCollection.document(callId)
            .update(
                mapOf(
                    "status" to "active",
                    "sdpAnswer" to sdpAnswer
                )
            ).await()
    }

    override suspend fun rejectCallSignaling(callId: String) {
        callsCollection.document(callId)
            .update("status", "rejected")
            .await()
    }

    override suspend fun endCallSignaling(callId: String) {
        callsCollection.document(callId)
            .update("status", "ended")
            .await()
    }

    override fun observeIncomingCalls(myUid: String): Flow<SignalingCallSession?> = callbackFlow {
        val registration = callsCollection
            .whereEqualTo("calleeId", myUid)
            .whereEqualTo("status", "ringing")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val latest = snapshot?.documents?.firstOrNull()?.toObject(SignalingCallSession::class.java)
                trySend(latest)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun sendIceCandidate(callId: String, candidate: IceCandidatePayload) {
        callsCollection.document(callId)
            .collection("candidates")
            .add(candidate)
            .await()
    }

    override fun observeIceCandidates(callId: String, remoteUid: String): Flow<IceCandidatePayload> = callbackFlow {
        val registration = callsCollection.document(callId)
            .collection("candidates")
            .whereEqualTo("senderId", remoteUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type == DocumentChange.Type.ADDED) {
                        val item = change.document.toObject(IceCandidatePayload::class.java)
                        trySend(item)
                    }
                }
            }
        awaitClose { registration.remove() }
    }

    // --- In-App UI Call State Methods ---

    override suspend fun startCall(userId: String, type: CallType) {
        val myUid = auth.currentUser?.uid ?: "user_me"
        val convId = if (myUid < userId) "${myUid}_$userId" else "${userId}_$myUid"
        val signalingType = if (type == CallType.VIDEO) "video" else "audio"
        val callId = startCallSignaling(convId, userId, signalingType)

        val session = CallSession(
            callId = callId,
            otherUser = User(id = userId, username = userId, displayName = "Pengguna $userId"),
            type = type,
            state = CallState.CONNECTING,
            isOutgoing = true
        )
        _currentCall.value = session
    }

    override suspend fun answerCall() {
        val current = _currentCall.value ?: return
        answerCallSignaling(current.callId, "SDP_ANSWER_LOCAL")
        _currentCall.value = current.copy(state = CallState.CONNECTED)
    }

    override suspend fun rejectCall() {
        val current = _currentCall.value ?: return
        rejectCallSignaling(current.callId)
        _currentCall.value = current.copy(state = CallState.REJECTED)
        _currentCall.value = null
    }

    override suspend fun endCall() {
        val current = _currentCall.value ?: return
        endCallSignaling(current.callId)
        val ended = current.copy(state = CallState.ENDED)
        _callHistory.value = listOf(ended) + _callHistory.value
        _currentCall.value = null
    }

    override suspend fun toggleMute() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isMuted = !current.isMuted)
    }

    override suspend fun toggleSpeaker() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isSpeakerOn = !current.isSpeakerOn)
    }

    override suspend fun toggleCamera() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isCameraOff = !current.isCameraOff)
    }

    override suspend fun switchCamera() {
        val current = _currentCall.value ?: return
        _currentCall.value = current.copy(isFrontCamera = !current.isFrontCamera)
    }

    override suspend fun simulateIncomingCall(callerId: String, type: CallType) {
        val session = CallSession(
            callId = "call_${System.currentTimeMillis()}_$callerId",
            otherUser = User(id = callerId, username = callerId, displayName = "Pengguna $callerId"),
            type = type,
            state = CallState.INCOMING,
            isOutgoing = false
        )
        _currentCall.value = session
    }
}
