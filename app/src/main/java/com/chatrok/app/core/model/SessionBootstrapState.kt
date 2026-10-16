package com.chatrok.app.core.model

sealed interface SessionBootstrapState {
    data object Unauthenticated : SessionBootstrapState
    data object Loading : SessionBootstrapState
    data class Ready(val user: User) : SessionBootstrapState
    data class Error(val cause: Throwable) : SessionBootstrapState
}
