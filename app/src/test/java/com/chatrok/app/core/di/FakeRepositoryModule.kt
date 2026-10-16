package com.chatrok.app.core.di

import com.chatrok.app.data.repository.FakeAuthRepository
import com.chatrok.app.data.repository.FakeCallRepository
import com.chatrok.app.data.repository.FakeConversationRepository
import com.chatrok.app.data.repository.FakeMediaRepository
import com.chatrok.app.data.repository.FakeMessageRepository
import com.chatrok.app.data.repository.FakeNotificationRepository
import com.chatrok.app.data.repository.FakePresenceRepository
import com.chatrok.app.data.repository.FakePushTokenRepository
import com.chatrok.app.data.repository.FakeSecurityRepository
import com.chatrok.app.data.repository.FakeUserRepository
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.chatrok.app.domain.repository.NotificationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.PushTokenRepository
import com.chatrok.app.domain.repository.SecurityRepository
import com.chatrok.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Modul untuk testing dan UI Preview.
 * Modul ini TIDAK di-install ke SingletonComponent produksi agar tidak mencemari runtime produksi.
 */
object FakeRepositoryModule {

    fun provideFakeUserRepository(): FakeUserRepository = FakeUserRepository()

    fun provideFakeAuthRepository(scope: CoroutineScope): FakeAuthRepository = FakeAuthRepository(scope)

    fun provideFakePresenceRepository(): FakePresenceRepository = FakePresenceRepository()

    fun provideFakeConversationRepository(
        userRepository: UserRepository,
        scope: CoroutineScope
    ): FakeConversationRepository = FakeConversationRepository(userRepository, scope)

    fun provideFakeMessageRepository(
        conversationRepository: FakeConversationRepository,
        presenceRepository: PresenceRepository,
        scope: CoroutineScope
    ): FakeMessageRepository = FakeMessageRepository(conversationRepository, presenceRepository, scope)

    fun provideFakeMediaRepository(): FakeMediaRepository = FakeMediaRepository()

    fun provideFakeSecurityRepository(): FakeSecurityRepository = FakeSecurityRepository()

    fun provideFakeCallRepository(scope: CoroutineScope): FakeCallRepository = FakeCallRepository(scope)

    fun provideFakeNotificationRepository(): FakeNotificationRepository = FakeNotificationRepository()

    fun provideFakePushTokenRepository(): FakePushTokenRepository = FakePushTokenRepository()

    fun bindAuthRepository(impl: FakeAuthRepository): AuthRepository = impl
    fun bindUserRepository(impl: FakeUserRepository): UserRepository = impl
    fun bindConversationRepository(impl: FakeConversationRepository): ConversationRepository = impl
    fun bindMessageRepository(impl: FakeMessageRepository): MessageRepository = impl
    fun bindPresenceRepository(impl: FakePresenceRepository): PresenceRepository = impl
    fun bindMediaRepository(impl: FakeMediaRepository): MediaRepository = impl
    fun bindSecurityRepository(impl: FakeSecurityRepository): SecurityRepository = impl
    fun bindCallRepository(impl: FakeCallRepository): CallRepository = impl
    fun bindNotificationRepository(impl: FakeNotificationRepository): NotificationRepository = impl
    fun bindPushTokenRepository(impl: FakePushTokenRepository): PushTokenRepository = impl
}
