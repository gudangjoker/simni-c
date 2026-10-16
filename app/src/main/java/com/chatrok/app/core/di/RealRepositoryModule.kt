package com.chatrok.app.core.di

import androidx.room.Room
import com.chatrok.app.core.security.ChatCryptoEngine
import com.chatrok.app.core.security.SecureIdentityStorage
import com.chatrok.app.data.auth.FirebaseAuthRepository
import com.chatrok.app.data.call.WebRtcCallRepository
import com.chatrok.app.data.conversation.FirestoreConversationRepository
import com.chatrok.app.data.local.ChatrokDatabase
import com.chatrok.app.data.local.dao.ConversationDao
import com.chatrok.app.data.local.dao.DraftDao
import com.chatrok.app.data.local.dao.LocalVisibilityDao
import com.chatrok.app.data.local.dao.MessageDao
import com.chatrok.app.data.local.dao.UserDao
import com.chatrok.app.data.media.R2MediaRepository
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import com.chatrok.app.data.presence.FirestorePresenceRepository
import com.chatrok.app.data.security.FirestoreSecurityRepository
import com.chatrok.app.data.unavailable.UnavailableNotificationRepository
import com.chatrok.app.data.unavailable.UnavailablePresenceRepository
import com.chatrok.app.data.user.FirestoreUserRepository
import com.chatrok.app.domain.repository.AuthRepository
import com.chatrok.app.domain.repository.CallRepository
import com.chatrok.app.domain.repository.ConversationRepository
import com.chatrok.app.domain.repository.MediaRepository
import com.chatrok.app.domain.repository.MessageRepository
import android.content.Context
import com.chatrok.app.domain.repository.NotificationRepository
import com.chatrok.app.domain.repository.PresenceRepository
import com.chatrok.app.domain.repository.PushTokenRepository
import com.chatrok.app.domain.repository.SecurityRepository
import com.chatrok.app.domain.repository.UserRepository
import com.chatrok.app.data.push.FirestorePushTokenRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Modul Dagger Hilt resmi untuk SIMNI-C (CHATROK ENGINE).
 *
 * Mengikat semua repositori riil:
 * - AuthRepository -> FirebaseAuthRepository
 * - UserRepository -> FirestoreUserRepository
 * - ConversationRepository -> FirestoreConversationRepository
 * - MessageRepository -> FirestoreMessageRepository
 * - SecurityRepository -> FirestoreSecurityRepository
 * - MediaRepository -> R2MediaRepository (Cloudflare Workers Gateway)
 * - CallRepository -> WebRtcCallRepository (Firestore Signaling Channel)
 */
@Module
@InstallIn(SingletonComponent::class)
object RealRepositoryModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Firebase Singletons
    @Provides
    @Singleton
    fun provideFirebaseApp(@ApplicationContext context: Context): FirebaseApp {
        val apps = FirebaseApp.getApps(context)
        if (apps.isNotEmpty()) {
            return apps.first()
        }
        val options = FirebaseOptions.fromResource(context)
        if (options != null) {
            return try {
                FirebaseApp.initializeApp(context, options)
            } catch (e: Exception) {
                FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context, options, "chatrok")
            }
        }
        val fallbackOptions = FirebaseOptions.Builder()
            .setApplicationId("1:827350687574:android:e556831d725d637c7840f7")
            .setApiKey("AIzaSyCKC94pHAPW16iotmPOaVKbmJzKsYmbrsM")
            .setProjectId("chatrok-4b121")
            .setStorageBucket("chatrok-4b121.firebasestorage.app")
            .setGcmSenderId("827350687574")
            .build()
        return try {
            FirebaseApp.initializeApp(context, fallbackOptions)
        } catch (e: Exception) {
            FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context, fallbackOptions, "chatrok")
        }
    }

    @Provides
    @Singleton
    fun provideFirebaseAuth(app: FirebaseApp): FirebaseAuth = FirebaseAuth.getInstance(app)

    @Provides
    @Singleton
    fun provideFirebaseFirestore(app: FirebaseApp): FirebaseFirestore = FirebaseFirestore.getInstance(app)

    // HTTP Client untuk R2 Media Gateway
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Room Offline Cache Database & DAOs
    @Provides
    @Singleton
    fun provideChatrokDatabase(@ApplicationContext context: Context): ChatrokDatabase =
        Room.databaseBuilder(
            context,
            ChatrokDatabase::class.java,
            ChatrokDatabase.DATABASE_NAME
        )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun provideMessageDao(db: ChatrokDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: ChatrokDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideUserDao(db: ChatrokDatabase): UserDao = db.userDao()

    @Provides
    fun provideLocalVisibilityDao(db: ChatrokDatabase): LocalVisibilityDao = db.localVisibilityDao()

    @Provides
    fun provideDraftDao(db: ChatrokDatabase): DraftDao = db.draftDao()

    @Provides
    @Singleton
    fun provideMessagingLocalVisibilityStore(localVisibilityDao: LocalVisibilityDao): MessagingLocalVisibilityStore =
        MessagingLocalVisibilityStore(localVisibilityDao)

    // Real Repositories
    @Provides
    @Singleton
    fun provideFirebaseAuthRepository(
        auth: FirebaseAuth,
        firestore: FirebaseFirestore,
        scope: CoroutineScope
    ): FirebaseAuthRepository = FirebaseAuthRepository(auth, firestore, scope)

    @Provides
    @Singleton
    fun provideFirestoreUserRepository(
        firestore: FirebaseFirestore,
        userDao: UserDao
    ): FirestoreUserRepository = FirestoreUserRepository(firestore, userDao)

    @Provides
    @Singleton
    fun provideFirestoreConversationRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        conversationDao: ConversationDao
    ): FirestoreConversationRepository = FirestoreConversationRepository(firestore, auth, visibilityStore, conversationDao)

    @Provides
    @Singleton
    fun provideR2MediaRepository(
        @ApplicationContext context: Context,
        auth: FirebaseAuth,
        okHttpClient: OkHttpClient,
        securityRepository: FirestoreSecurityRepository,
        storage: SecureIdentityStorage,
        cryptoEngine: com.chatrok.app.core.crypto.E2eeCryptoEngine
    ): R2MediaRepository = R2MediaRepository(
        context,
        auth,
        okHttpClient,
        securityRepository,
        storage,
        cryptoEngine
    )

    @Provides
    @Singleton
    fun provideFirestoreMessageRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        visibilityStore: MessagingLocalVisibilityStore,
        securityRepository: FirestoreSecurityRepository,
        storage: SecureIdentityStorage,
        cryptoEngine: com.chatrok.app.core.crypto.E2eeCryptoEngine,
        mediaRepository: R2MediaRepository,
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        draftDao: DraftDao
    ): FirestoreMessageRepository = FirestoreMessageRepository(
        firestore,
        auth,
        visibilityStore,
        securityRepository,
        storage,
        cryptoEngine,
        mediaRepository,
        context,
        messageDao,
        draftDao
    )

    @Provides
    @Singleton
    fun provideFirestoreSecurityRepository(
        firestore: FirebaseFirestore,
        storage: SecureIdentityStorage,
        cryptoEngine: com.chatrok.app.core.crypto.E2eeCryptoEngine
    ): FirestoreSecurityRepository = FirestoreSecurityRepository(firestore, storage, cryptoEngine)

    @Provides
    @Singleton
    fun provideWebRtcCallRepository(
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): WebRtcCallRepository = WebRtcCallRepository(firestore, auth)

    @Provides
    @Singleton
    fun provideFirestorePresenceRepository(
        @ApplicationContext context: Context,
        firestore: FirebaseFirestore,
        auth: FirebaseAuth,
        scope: CoroutineScope
    ): FirestorePresenceRepository = FirestorePresenceRepository(context, firestore, auth, scope)

    @Provides
    @Singleton
    fun provideUnavailablePresenceRepository(): UnavailablePresenceRepository =
        UnavailablePresenceRepository()

    @Provides
    @Singleton
    fun providePersistentNotificationRepository(
        @ApplicationContext context: Context
    ): com.chatrok.app.data.notification.PersistentNotificationRepository =
        com.chatrok.app.data.notification.PersistentNotificationRepository(context)

    @Provides
    @Singleton
    fun provideUnavailableNotificationRepository(): UnavailableNotificationRepository =
        UnavailableNotificationRepository()

    fun provideUnavailableMediaRepository(): com.chatrok.app.data.unavailable.UnavailableMediaRepository =
        com.chatrok.app.data.unavailable.UnavailableMediaRepository()

    fun provideUnavailableSecurityRepository(): com.chatrok.app.data.unavailable.UnavailableSecurityRepository =
        com.chatrok.app.data.unavailable.UnavailableSecurityRepository()

    fun provideUnavailableCallRepository(): com.chatrok.app.data.unavailable.UnavailableCallRepository =
        com.chatrok.app.data.unavailable.UnavailableCallRepository()

    // Interface Bindings: Production Real Implementations
    @Provides
    fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository = impl

    @Provides
    fun bindUserRepository(impl: FirestoreUserRepository): UserRepository = impl

    @Provides
    fun bindConversationRepository(impl: FirestoreConversationRepository): ConversationRepository = impl

    @Provides
    fun bindMessageRepository(impl: FirestoreMessageRepository): MessageRepository = impl

    @Provides
    fun bindSecurityRepository(impl: FirestoreSecurityRepository): SecurityRepository = impl

    @Provides
    fun bindMediaRepository(impl: R2MediaRepository): MediaRepository = impl

    @Provides
    fun bindCallRepository(impl: WebRtcCallRepository): CallRepository = impl

    fun bindSecurityRepository(impl: com.chatrok.app.data.unavailable.UnavailableSecurityRepository): SecurityRepository = impl

    fun bindMediaRepository(impl: com.chatrok.app.data.unavailable.UnavailableMediaRepository): MediaRepository = impl

    fun bindCallRepository(impl: com.chatrok.app.data.unavailable.UnavailableCallRepository): CallRepository = impl

    @Provides
    fun bindPresenceRepository(impl: FirestorePresenceRepository): PresenceRepository = impl

    fun bindPresenceRepository(impl: UnavailablePresenceRepository): PresenceRepository = impl

    @Provides
    fun bindNotificationRepository(impl: com.chatrok.app.data.notification.PersistentNotificationRepository): NotificationRepository = impl

    fun bindNotificationRepository(impl: UnavailableNotificationRepository): NotificationRepository = impl

    @Provides
    fun bindPushTokenRepository(impl: FirestorePushTokenRepository): PushTokenRepository = impl
}
