package com.chatrok.app.data.user

import com.chatrok.app.core.common.FeatureUnavailableException
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.User
import com.chatrok.app.data.local.dao.UserDao
import com.chatrok.app.data.local.mapper.EntityMappers.toDomain
import com.chatrok.app.data.local.mapper.EntityMappers.toEntity
import com.chatrok.app.domain.repository.UserRepository
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Gateway contract isolating raw Firestore operations for [FirestoreUserRepository].
 */
interface FirestoreUserGateway {
    fun getUsers(): Flow<List<User>>
    fun getUserById(userId: String): Flow<User?>
    suspend fun updateUser(user: User): Result<Unit>
}

class DefaultFirestoreUserGateway(
    private val firestore: FirebaseFirestore
) : FirestoreUserGateway {

    private fun DocumentSnapshot.toUser(): User? {
        val uid = id
        val username = getString("username") ?: getString("usernameLower") ?: return null
        val displayName = getString("displayName") ?: username
        val avatarUrl = getString("avatarUrl")?.ifBlank { null }
        val bio = getString("bio") ?: DEFAULT_USER_BIO

        return User(
            id = uid,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
            isOnline = false,
            lastSeen = System.currentTimeMillis(),
            isBlocked = false
        )
    }

    override fun getUsers(): Flow<List<User>> = callbackFlow {
        val listener = firestore.collection("publicProfiles")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val users = snapshot?.documents?.mapNotNull { it.toUser() } ?: emptyList()
                trySend(users)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO)

    override fun getUserById(userId: String): Flow<User?> = callbackFlow {
        val listener = firestore.collection("publicProfiles").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }
                val user = snapshot.toUser()
                trySend(user)
            }
        awaitClose { listener.remove() }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateUser(user: User): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updateMap = hashMapOf<String, Any>(
                "displayName" to user.displayName,
                "bio" to user.bio,
                "avatarUrl" to (user.avatarUrl ?: ""),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            firestore.collection("publicProfiles").document(user.id)
                .set(updateMap, SetOptions.merge())
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Singleton
class FirestoreUserRepository(
    private val gateway: FirestoreUserGateway,
    private val userDao: UserDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : UserRepository {

    @Inject
    constructor(
        firestore: FirebaseFirestore,
        userDao: UserDao
    ) : this(
        DefaultFirestoreUserGateway(firestore),
        userDao
    )

    constructor(firestore: FirebaseFirestore) : this(DefaultFirestoreUserGateway(firestore), null)

    constructor(gateway: FirestoreUserGateway) : this(gateway, null)

    override fun getUsers(): Flow<List<User>> {
        val remoteFlow = gateway.getUsers().onEach { remoteList ->
            if (userDao != null && remoteList.isNotEmpty()) {
                scope.launch {
                    try {
                        userDao.insertUsers(remoteList.map { it.toEntity() })
                    } catch (_: Exception) {}
                }
            }
        }
        val cachedFlow = userDao?.getAllUsersFlow()?.map { list -> list.map { it.toDomain() } } ?: flowOf(emptyList())

        return combine(remoteFlow, cachedFlow) { remote, cached ->
            if (remote.isNotEmpty()) remote else cached
        }
    }

    override fun getUserById(userId: String): Flow<User?> {
        val remoteFlow = gateway.getUserById(userId).onEach { user ->
            if (userDao != null && user != null) {
                scope.launch {
                    try {
                        userDao.insertUser(user.toEntity())
                    } catch (_: Exception) {}
                }
            }
        }
        val cachedFlow = userDao?.getUserByIdFlow(userId)?.map { it?.toDomain() } ?: flowOf(null)

        return combine(remoteFlow, cachedFlow) { remote, cached ->
            remote ?: cached
        }
    }

    override fun searchUsers(query: String): Flow<List<User>> {
        val cleanQuery = query.trim().lowercase()
        return getUsers().map { list ->
            if (cleanQuery.isBlank()) {
                list
            } else {
                list.filter {
                    it.username.lowercase().contains(cleanQuery) ||
                            it.displayName.lowercase().contains(cleanQuery)
                }
            }
        }
    }

    override suspend fun blockUser(userId: String, isBlocked: Boolean): Result<Unit> {
        // Milestone 1: User blocking schema is not part of Milestone 1.
        // Return honest typed failure without mutating local state or simulating fake persistence.
        return Result.failure(
            FeatureUnavailableException(
                "block_user",
                "Fitur pemblokiran pengguna dijadwalkan pada milestone berikutnya."
            )
        )
    }

    override suspend fun updateUser(user: User): Result<Unit> {
        userDao?.let { dao ->
            scope.launch {
                try {
                    dao.insertUser(user.toEntity())
                } catch (_: Exception) {}
            }
        }
        return gateway.updateUser(user)
    }
}
