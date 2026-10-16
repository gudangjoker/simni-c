package com.chatrok.app

import com.chatrok.app.core.common.FeatureUnavailableException
import com.chatrok.app.core.di.FakeRepositoryModule
import com.chatrok.app.core.di.RealRepositoryModule
import com.chatrok.app.data.auth.FirebaseAuthRepository
import com.chatrok.app.data.repository.FakeAuthRepository
import com.chatrok.app.data.repository.FakeCallRepository
import com.chatrok.app.data.repository.FakeConversationRepository
import com.chatrok.app.data.repository.FakeMediaRepository
import com.chatrok.app.data.repository.FakeMessageRepository
import com.chatrok.app.data.repository.FakeNotificationRepository
import com.chatrok.app.data.repository.FakePresenceRepository
import com.chatrok.app.data.repository.FakeSecurityRepository
import com.chatrok.app.data.repository.FakeUserRepository
import com.chatrok.app.data.unavailable.UnavailableCallRepository
import com.chatrok.app.data.unavailable.UnavailableMediaRepository
import com.chatrok.app.data.unavailable.UnavailableNotificationRepository
import com.chatrok.app.data.unavailable.UnavailablePresenceRepository
import com.chatrok.app.data.unavailable.UnavailableSecurityRepository
import com.chatrok.app.data.user.FirestoreUserRepository
import com.chatrok.app.data.conversation.FirestoreConversationRepository
import com.chatrok.app.data.conversation.ConversationGateway
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.message.MessageGateway
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.ReplyPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import com.chatrok.app.domain.repository.NotificationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.SecurityRepository
import com.chatrok.app.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiltModuleSeparationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    @Test
    fun testRealRepositoryModuleBindsFirebaseAuthRepositoryToAuthRepository() {
        // [Kriteria B]: AuthRepository production resolve ke FirebaseAuthRepository
        val coordinator = com.chatrok.app.data.auth.RegistrationCoordinator(
            FakeTestAuthGateway(),
            object : com.chatrok.app.data.auth.RegistrationFirestoreGateway {
                override suspend fun checkUsernameExists(usernameLower: String): Boolean = false
                override suspend fun executeRegistrationTransaction(
                    uid: String,
                    email: String,
                    usernameLower: String,
                    displayName: String
                ) {}
            }
        )
        val mockAuthRepo = FirebaseAuthRepository(
            scope = testScope,
            registrationCoordinator = coordinator,
            profileGateway = FakeUserProfileGateway(),
            authServiceGateway = FakeAuthServiceGateway()
        )

        val bound: AuthRepository = RealRepositoryModule.bindAuthRepository(mockAuthRepo)
        assertTrue("AuthRepository harus bertipe FirebaseAuthRepository", bound is FirebaseAuthRepository)
        assertFalse("AuthRepository tidak boleh bertipe FakeAuthRepository", bound is FakeAuthRepository)
    }

    @Test
    fun testRealRepositoryModuleBindsFirestoreUserRepositoryToUserRepository() {
        // [Kriteria C]: UserRepository production resolve ke FirestoreUserRepository
        val mockUserRepo = FirestoreUserRepository(com.chatrok.app.SpyFirestoreUserGateway())

        val bound: UserRepository = RealRepositoryModule.bindUserRepository(mockUserRepo)
        assertTrue("UserRepository harus bertipe FirestoreUserRepository", bound is FirestoreUserRepository)
        assertFalse("UserRepository tidak boleh bertipe FakeUserRepository", bound is FakeUserRepository)
    }

    @Test
    fun testRealRepositoryModuleBindsRealMessagingAndUnavailableDeferredRepositories() {
        val uidProvider = AuthenticatedUidProvider { "uid_a" }
        val visibility = MessagingLocalVisibilityStore()
        val conversationGateway = object : ConversationGateway {
            override fun observeConversations(uid: String): Flow<List<Conversation>> = flowOf(emptyList())
            override fun observeConversation(uid: String, conversationId: String): Flow<Conversation?> = flowOf(null)
            override suspend fun getOrCreateConversation(uid: String, peerUid: String): Conversation =
                throw UnsupportedOperationException()
            override suspend fun markAsRead(uid: String, conversationId: String) = Unit
        }
        val messageGateway = object : MessageGateway {
            override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> = flowOf(emptyList())
            override suspend fun loadOlderMessages(uid: String, conversationId: String, afterMessageId: String?, pageSize: Int): List<Message> = emptyList()
            override suspend fun sendText(uid: String, conversationId: String, messageId: String, text: String, replyTo: ReplyPreview?): Message =
                throw UnsupportedOperationException()
            override suspend fun messageExists(conversationId: String, messageId: String): Boolean = false
            override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) = Unit
        }
        val convRepo: ConversationRepository = RealRepositoryModule.bindConversationRepository(
            FirestoreConversationRepository(conversationGateway, uidProvider, visibility)
        )
        val msgRepo: MessageRepository = RealRepositoryModule.bindMessageRepository(
            FirestoreMessageRepository(messageGateway, uidProvider, visibility)
        )
        assertTrue(convRepo is FirestoreConversationRepository)
        assertFalse(convRepo is FakeConversationRepository)
        assertTrue(msgRepo is FirestoreMessageRepository)
        assertFalse(msgRepo is FakeMessageRepository)

        val presenceRepo: PresenceRepository = RealRepositoryModule.bindPresenceRepository(
            RealRepositoryModule.provideUnavailablePresenceRepository()
        )
        val mediaRepo: MediaRepository = RealRepositoryModule.bindMediaRepository(
            RealRepositoryModule.provideUnavailableMediaRepository()
        )
        val secRepo: SecurityRepository = RealRepositoryModule.bindSecurityRepository(
            RealRepositoryModule.provideUnavailableSecurityRepository()
        )
        val callRepo: CallRepository = RealRepositoryModule.bindCallRepository(
            RealRepositoryModule.provideUnavailableCallRepository()
        )
        val notifRepo: NotificationRepository = RealRepositoryModule.bindNotificationRepository(
            RealRepositoryModule.provideUnavailableNotificationRepository()
        )
        assertTrue(presenceRepo is UnavailablePresenceRepository)
        assertTrue(mediaRepo is UnavailableMediaRepository)
        assertTrue(secRepo is UnavailableSecurityRepository)
        assertTrue(callRepo is UnavailableCallRepository)
        assertTrue(notifRepo is UnavailableNotificationRepository)
    }

    @Test
    fun testUnavailableRepositoriesFailExplicitlyForDeferredMilestones() = runTest {
        val secRepo = UnavailableSecurityRepository()
        val callRepo = UnavailableCallRepository()

        val secInfo = secRepo.getSecurityInfo("user_99").first()
        assertFalse("Security info tidak boleh mengaku terverifikasi", secInfo.isVerified)
        val verifyResult = secRepo.verifyIdentity("user_99", true)
        assertTrue("verifyIdentity harus gagal", verifyResult.isFailure)
        assertTrue(verifyResult.exceptionOrNull() is FeatureUnavailableException)

        assertNull("currentCall awal harus null", callRepo.currentCall.value)
        assertTrue(callRepo.callHistory.first().isEmpty())
        try {
            callRepo.startCall("user_99", com.chatrok.app.core.model.CallType.VOICE)
            fail("startCall harus melempar FeatureUnavailableException")
        } catch (e: Exception) {
            assertTrue(e is FeatureUnavailableException)
        }
    }

    @Test
    fun testFakeRepositoryModuleProvidesFunctionalFakesForPreviewsAndTestsWithoutProductionPollution() = runTest {
        // [Kriteria E]: Fake module tetap dapat dipakai pada test/preview tanpa duplicate binding
        val fakeUserRepo = FakeRepositoryModule.provideFakeUserRepository()
        val fakeAuthRepo = FakeRepositoryModule.provideFakeAuthRepository(testScope)
        val fakePresenceRepo = FakeRepositoryModule.provideFakePresenceRepository()
        val fakeConvRepo = FakeRepositoryModule.provideFakeConversationRepository(fakeUserRepo, testScope)
        val fakeMsgRepo = FakeRepositoryModule.provideFakeMessageRepository(fakeConvRepo, fakePresenceRepo, testScope)
        val fakeMediaRepo = FakeRepositoryModule.provideFakeMediaRepository()
        val fakeSecRepo = FakeRepositoryModule.provideFakeSecurityRepository()
        val fakeCallRepo = FakeRepositoryModule.provideFakeCallRepository(testScope)
        val fakeNotifRepo = FakeRepositoryModule.provideFakeNotificationRepository()

        // Verifikasi fungsionalitas preview/test pada Fake path
        assertNotNull(fakeUserRepo.getUserById("usr_1").first())
        assertNotNull(FakeRepositoryModule.bindAuthRepository(fakeAuthRepo))
        assertNotNull(FakeRepositoryModule.bindUserRepository(fakeUserRepo))
        assertNotNull(FakeRepositoryModule.bindConversationRepository(fakeConvRepo))
        assertNotNull(FakeRepositoryModule.bindMessageRepository(fakeMsgRepo))
        assertNotNull(FakeRepositoryModule.bindPresenceRepository(fakePresenceRepo))
        assertNotNull(FakeRepositoryModule.bindMediaRepository(fakeMediaRepo))
        assertNotNull(FakeRepositoryModule.bindSecurityRepository(fakeSecRepo))
        assertNotNull(FakeRepositoryModule.bindCallRepository(fakeCallRepo))
        assertNotNull(FakeRepositoryModule.bindNotificationRepository(fakeNotifRepo))
    }
}
