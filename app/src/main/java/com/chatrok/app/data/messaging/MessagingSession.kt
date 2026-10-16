package com.chatrok.app.data.messaging

import com.chatrok.app.data.local.dao.LocalVisibilityDao
import com.chatrok.app.data.local.entity.LocalVisibilityEntity
import com.google.firebase.auth.FirebaseAuth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Provides the authenticated Firebase UID to messaging repositories without coupling tests to FirebaseAuth. */
fun interface AuthenticatedUidProvider {
    fun requireUid(): String
    fun getUid(): String? = runCatching { requireUid() }.getOrNull()
    fun observeUid(): Flow<String?> = flow {
        emit(getUid())
    }
}

class FirebaseAuthenticatedUidProvider @Inject constructor(
    private val auth: FirebaseAuth
) : AuthenticatedUidProvider {
    override fun requireUid(): String = auth.currentUser?.uid
        ?: throw IllegalStateException("Sesi pengguna tidak tersedia.")

    override fun getUid(): String? = auth.currentUser?.uid

    override fun observeUid(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { fbAuth ->
            trySend(fbAuth.currentUser?.uid)
        }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser?.uid)
        awaitClose { auth.removeAuthStateListener(listener) }
    }
}

/**
 * User-local visibility state persisted with Room for Milestone 8 (Offline Cache).
 * Supports both standalone in-memory operation (for lightweight unit tests)
 * and persistent Room-backed storage across app restarts.
 */
@Singleton
class MessagingLocalVisibilityStore(
    private val localVisibilityDao: LocalVisibilityDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    @Inject
    constructor(localVisibilityDao: LocalVisibilityDao) : this(
        localVisibilityDao,
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    )

    constructor() : this(null)

    private val _hiddenConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenConversationIds: StateFlow<Set<String>> = _hiddenConversationIds.asStateFlow()

    private val _locallyReadConversationIds = MutableStateFlow<Set<String>>(emptySet())
    val locallyReadConversationIds: StateFlow<Set<String>> = _locallyReadConversationIds.asStateFlow()

    private val _clearCutoffs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val clearCutoffs: StateFlow<Map<String, Long>> = _clearCutoffs.asStateFlow()

    private val _hiddenMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenMessageIds: StateFlow<Set<String>> = _hiddenMessageIds.asStateFlow()

    var initJob: Job? = null
        private set

    init {
        if (localVisibilityDao != null) {
            initJob = scope.launch {
                restoreFromDatabase()
            }
        }
    }

    suspend fun restoreFromDatabase() {
        val dao = localVisibilityDao ?: return
        try {
            val entries = dao.getAllVisibility()
            val hiddenConvs = mutableSetOf<String>()
            val readConvs = mutableSetOf<String>()
            val cutoffs = mutableMapOf<String, Long>()
            val hiddenMsgs = mutableSetOf<String>()

            for (entry in entries) {
                when (entry.type) {
                    LocalVisibilityEntity.TYPE_HIDDEN_CONVERSATION -> hiddenConvs.add(entry.targetId)
                    LocalVisibilityEntity.TYPE_LOCALLY_READ_CONVERSATION -> readConvs.add(entry.targetId)
                    LocalVisibilityEntity.TYPE_CLEAR_CUTOFF -> cutoffs[entry.targetId] = entry.cutoffMs
                    LocalVisibilityEntity.TYPE_HIDDEN_MESSAGE -> hiddenMsgs.add(entry.targetId)
                }
            }

            _hiddenConversationIds.value = _hiddenConversationIds.value + hiddenConvs
            _locallyReadConversationIds.value = _locallyReadConversationIds.value + readConvs
            _clearCutoffs.value = _clearCutoffs.value + cutoffs
            _hiddenMessageIds.value = _hiddenMessageIds.value + hiddenMsgs
        } catch (_: Exception) {}
    }

    fun hideConversation(id: String): Job {
        _hiddenConversationIds.value = _hiddenConversationIds.value + id
        return localVisibilityDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertVisibility(
                        LocalVisibilityEntity(
                            type = LocalVisibilityEntity.TYPE_HIDDEN_CONVERSATION,
                            targetId = id
                        )
                    )
                } catch (_: Exception) {}
            }
        } ?: Job().apply { complete() }
    }

    fun markConversationRead(id: String): Job {
        _locallyReadConversationIds.value = _locallyReadConversationIds.value + id
        return localVisibilityDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertVisibility(
                        LocalVisibilityEntity(
                            type = LocalVisibilityEntity.TYPE_LOCALLY_READ_CONVERSATION,
                            targetId = id
                        )
                    )
                } catch (_: Exception) {}
            }
        } ?: Job().apply { complete() }
    }

    fun clearConversation(id: String, cutoffMs: Long = System.currentTimeMillis()): Job {
        _clearCutoffs.value = _clearCutoffs.value + (id to cutoffMs)
        markConversationRead(id)
        return localVisibilityDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertVisibility(
                        LocalVisibilityEntity(
                            type = LocalVisibilityEntity.TYPE_CLEAR_CUTOFF,
                            targetId = id,
                            cutoffMs = cutoffMs
                        )
                    )
                    dao.insertVisibility(
                        LocalVisibilityEntity(
                            type = LocalVisibilityEntity.TYPE_LOCALLY_READ_CONVERSATION,
                            targetId = id
                        )
                    )
                } catch (_: Exception) {}
            }
        } ?: Job().apply { complete() }
    }

    fun hideMessage(id: String): Job {
        _hiddenMessageIds.value = _hiddenMessageIds.value + id
        return localVisibilityDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertVisibility(
                        LocalVisibilityEntity(
                            type = LocalVisibilityEntity.TYPE_HIDDEN_MESSAGE,
                            targetId = id
                        )
                    )
                } catch (_: Exception) {}
            }
        } ?: Job().apply { complete() }
    }

    fun clearLocalVisibility(): Job {
        initJob?.cancel()
        _hiddenConversationIds.value = emptySet()
        _locallyReadConversationIds.value = emptySet()
        _clearCutoffs.value = emptyMap()
        _hiddenMessageIds.value = emptySet()
        return localVisibilityDao?.let { dao ->
            scope.launch {
                try {
                    dao.clearAllVisibility()
                } catch (_: Exception) {}
            }
        } ?: Job().apply { complete() }
    }
}
