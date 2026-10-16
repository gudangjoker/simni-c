package com.chatrok.app.data.auth

import com.chatrok.app.core.common.RegistrationRollbackFailedException
import com.chatrok.app.core.common.UsernameAlreadyTakenException
import com.chatrok.app.core.common.UsernamePrecheckFailedException
import com.chatrok.app.core.model.DEFAULT_USER_BIO
import com.chatrok.app.core.model.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

data class AuthAccount(
    val uid: String,
    val email: String?
)

interface RegistrationAuthGateway {
    suspend fun createAccount(email: String, pass: String): AuthAccount
    suspend fun deleteAccount(targetUid: String, currentSessionUid: String?)
}

interface RegistrationFirestoreGateway {
    suspend fun checkUsernameExists(usernameLower: String): Boolean
    suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    )
}

@Singleton
class RegistrationCoordinator @Inject constructor(
    private val authGateway: RegistrationAuthGateway,
    private val firestoreGateway: RegistrationFirestoreGateway
) {
    suspend fun registerUser(
        email: String,
        password: String,
        username: String,
        displayName: String
    ): Result<User> {
        val cleanEmail = email.trim()
        val rawUsername = username.trim()
        val cleanUsernameLower = rawUsername.lowercase()
        val cleanDisplayName = displayName.trim()

        if (cleanEmail.isBlank() || password.isEmpty() || rawUsername.isBlank() || cleanDisplayName.isBlank()) {
            return Result.failure(IllegalArgumentException("Semua kolom wajib diisi."))
        }

        if (!Regex("^[a-z0-9._]{3,30}$").matches(cleanUsernameLower)) {
            return Result.failure(IllegalArgumentException("chatrok/username-invalid"))
        }

        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("auth/weak-password"))
        }

        // 1. Pre-check ketersediaan username (UX non-authoritative, fail-closed pada error)
        try {
            val exists = firestoreGateway.checkUsernameExists(cleanUsernameLower)
            if (exists) {
                return Result.failure(UsernameAlreadyTakenException(cleanUsernameLower))
            }
        } catch (e: Exception) {
            // FAIL CLOSED: jika pre-check gagal (network, timeout, Firestore exception, permission, dsb.),
            // pendaftaran segera digagalkan secara typed dan TIDAK BOLEH memanggil createAccount!
            if (e is UsernameAlreadyTakenException) {
                return Result.failure(e)
            }
            return Result.failure(UsernamePrecheckFailedException(e))
        }

        // 2. Buat akun Auth
        val createdAccount = try {
            authGateway.createAccount(cleanEmail, password)
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        // 3. Eksekusi Firestore Transaction sebagai authoritative source of truth
        try {
            firestoreGateway.executeRegistrationTransaction(
                uid = createdAccount.uid,
                email = cleanEmail,
                usernameLower = cleanUsernameLower,
                displayName = cleanDisplayName
            )
        } catch (transactionErr: Throwable) {
            // 4. Kompensasi Rollback terarah saat transaksi gagal
            try {
                authGateway.deleteAccount(
                    targetUid = createdAccount.uid,
                    currentSessionUid = createdAccount.uid
                )
            } catch (rollbackErr: Throwable) {
                // Rollback failure tidak boleh ditelan secara diam-diam
                return Result.failure(
                    RegistrationRollbackFailedException(
                        originalError = transactionErr,
                        rollbackError = rollbackErr
                    )
                )
            }

            // Return original transaction error secara typed
            return Result.failure(transactionErr)
        }

        val user = User(
            id = createdAccount.uid,
            username = cleanUsernameLower,
            displayName = cleanDisplayName,
            bio = DEFAULT_USER_BIO,
            isOnline = true,
            lastSeen = System.currentTimeMillis()
        )
        return Result.success(user)
    }
}

class DefaultRegistrationAuthGateway @Inject constructor(
    private val auth: FirebaseAuth
) : RegistrationAuthGateway {
    override suspend fun createAccount(email: String, pass: String): AuthAccount {
        val authResult = auth.createUserWithEmailAndPassword(email, pass).await()
        val user = authResult.user ?: throw IllegalStateException("Gagal membuat akun Firebase.")
        return AuthAccount(uid = user.uid, email = user.email)
    }

    override suspend fun deleteAccount(targetUid: String, currentSessionUid: String?) {
        val currentAuthUser = auth.currentUser
        if (currentAuthUser != null && currentAuthUser.uid == targetUid) {
            currentAuthUser.delete().await()
        } else {
            throw SecurityException(
                "Rollback dibatalkan: UID sesi aktif () tidak cocok dengan target rollback ()."
            )
        }
    }
}

class DefaultRegistrationFirestoreGateway @Inject constructor(
    private val firestore: FirebaseFirestore
) : RegistrationFirestoreGateway {
    override suspend fun checkUsernameExists(usernameLower: String): Boolean {
        val snapshot = firestore.collection("usernames").document(usernameLower).get().await()
        return snapshot.exists()
    }

    override suspend fun executeRegistrationTransaction(
        uid: String,
        email: String,
        usernameLower: String,
        displayName: String
    ) {
        val usernameRef = firestore.collection("usernames").document(usernameLower)
        val userRef = firestore.collection("users").document(uid)
        val profileRef = firestore.collection("publicProfiles").document(uid)

        val usernameData = buildUsernamePayload(uid, usernameLower)
        val userData = buildUserPayload(uid, email, usernameLower, displayName)
        val profileData = buildPublicProfilePayload(uid, usernameLower, displayName)

        firestore.runTransaction { transaction ->
            val usernameSnapshot = transaction.get(usernameRef)
            if (usernameSnapshot.exists()) {
                throw UsernameAlreadyTakenException(usernameLower)
            }
            transaction.set(usernameRef, usernameData)
            transaction.set(userRef, userData)
            transaction.set(profileRef, profileData)
        }.await()
    }

    companion object {
        fun buildUsernamePayload(
            uid: String,
            usernameLower: String,
            timestamp: Any = FieldValue.serverTimestamp()
        ): Map<String, Any> = hashMapOf(
            "uid" to uid,
            "username" to usernameLower,
            "createdAt" to timestamp
        )

        fun buildUserPayload(
            uid: String,
            email: String,
            usernameLower: String,
            displayName: String,
            timestamp: Any = FieldValue.serverTimestamp()
        ): Map<String, Any> = hashMapOf(
            "uid" to uid,
            "email" to email,
            "username" to usernameLower,
            "usernameLower" to usernameLower,
            "displayName" to displayName,
            "createdAt" to timestamp,
            "updatedAt" to timestamp
        )

        fun buildPublicProfilePayload(
            uid: String,
            usernameLower: String,
            displayName: String,
            timestamp: Any = FieldValue.serverTimestamp()
        ): Map<String, Any> = hashMapOf(
            "uid" to uid,
            "username" to usernameLower,
            "usernameLower" to usernameLower,
            "displayName" to displayName,
            "avatarUrl" to "",
            "bio" to DEFAULT_USER_BIO,
            "createdAt" to timestamp,
            "updatedAt" to timestamp
        )
    }
}
