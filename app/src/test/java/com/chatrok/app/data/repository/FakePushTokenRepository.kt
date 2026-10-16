package com.chatrok.app.data.repository

import com.chatrok.app.domain.repository.PushTokenRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakePushTokenRepository @Inject constructor() : PushTokenRepository {

    private val _registeredTokens = MutableStateFlow<Map<String, String>>(emptyMap()) // uid -> token
    val registeredTokens: StateFlow<Map<String, String>> = _registeredTokens.asStateFlow()

    var lastRegisteredToken: String? = null
    var lastUnregisteredToken: String? = null
    var lastDeactivatedUid: String? = null

    override suspend fun registerCurrentToken(token: String): Result<Unit> {
        lastRegisteredToken = token
        _registeredTokens.value = _registeredTokens.value + ("mock_uid" to token)
        return Result.success(Unit)
    }

    override suspend fun unregisterToken(token: String): Result<Unit> {
        lastUnregisteredToken = token
        _registeredTokens.value = _registeredTokens.value - "mock_uid"
        return Result.success(Unit)
    }

    override suspend fun deactivateUserTokens(uid: String): Result<Unit> {
        lastDeactivatedUid = uid
        _registeredTokens.value = _registeredTokens.value - uid
        return Result.success(Unit)
    }
}
