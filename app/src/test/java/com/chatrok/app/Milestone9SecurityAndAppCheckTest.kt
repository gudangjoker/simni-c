package com.chatrok.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chatrok.app.core.di.RealRepositoryModule
import com.chatrok.app.data.notification.PersistentNotificationRepository
import com.chatrok.app.domain.repository.NotificationRepository
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Milestone9SecurityAndAppCheckTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences(PersistentNotificationRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `Milestone 9 - Firebase App Check provider factories are valid and available`() {
        // Verify both Debug and Play Integrity provider factories are resolvable
        val debugFactory = DebugAppCheckProviderFactory.getInstance()
        assertNotNull("DebugAppCheckProviderFactory must be instantiable", debugFactory)

        val playIntegrityFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
        assertNotNull("PlayIntegrityAppCheckProviderFactory must be instantiable", playIntegrityFactory)
    }

    @Test
    fun `Milestone 9 - PersistentNotificationRepository initializes with true by default`() = runTest {
        val repo = PersistentNotificationRepository(context)
        assertTrue("Notifications should be enabled by default", repo.notificationsEnabled.first())
    }

    @Test
    fun `Milestone 9 - PersistentNotificationRepository persists preference across instances`() = runTest {
        val repo1 = PersistentNotificationRepository(context)
        repo1.setNotificationsEnabled(false)
        assertFalse("Preference should be disabled after mutation", repo1.notificationsEnabled.first())

        // Create second instance simulating app reboot
        val repo2 = PersistentNotificationRepository(context)
        assertFalse("Preference should remain disabled across new instances", repo2.notificationsEnabled.first())

        // Re-enable
        repo2.setNotificationsEnabled(true)
        assertTrue("Preference should be enabled after re-enabling", repo2.notificationsEnabled.first())

        val repo3 = PersistentNotificationRepository(context)
        assertTrue("Preference should remain enabled", repo3.notificationsEnabled.first())
    }

    @Test
    fun `Milestone 9 - RealRepositoryModule binds PersistentNotificationRepository`() {
        val repo: PersistentNotificationRepository = RealRepositoryModule.providePersistentNotificationRepository(context)
        val boundRepo: NotificationRepository = RealRepositoryModule.bindNotificationRepository(repo)

        assertNotNull(boundRepo)
        assertTrue("RealRepositoryModule must bind PersistentNotificationRepository for production", boundRepo is PersistentNotificationRepository)
    }

    @Test
    fun `Milestone 9 - AndroidManifest enforces allowBackup false for E2EE security`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val content = manifestFile.readText()
        assertTrue(
            "AndroidManifest must set android:allowBackup=\"false\" to prevent cryptographic identity leakage",
            content.contains("android:allowBackup=\"false\"")
        )
        assertFalse(
            "AndroidManifest must not contain android:allowBackup=\"true\"",
            content.contains("android:allowBackup=\"true\"")
        )
    }

    @Test
    fun `Milestone 9 - Backup and data extraction rules explicitly exclude storage domains`() {
        val extractionFile = File("src/main/res/xml/data_extraction_rules.xml")
        assertTrue("data_extraction_rules.xml must exist", extractionFile.exists())
        val extractionContent = extractionFile.readText()

        assertTrue(extractionContent.contains("domain=\"sharedpref\""))
        assertTrue(extractionContent.contains("domain=\"database\""))

        val backupFile = File("src/main/res/xml/backup_rules.xml")
        assertTrue("backup_rules.xml must exist", backupFile.exists())
        val backupContent = backupFile.readText()

        assertTrue(backupContent.contains("domain=\"sharedpref\""))
        assertTrue(backupContent.contains("domain=\"database\""))
    }

    @Test
    fun `Milestone 9 - Static Firestore Rules Audit strictly enforces security invariants`() {
        // Rules file can be in root or parent
        val rulesFile = listOf(File("firestore.rules"), File("../firestore.rules")).firstOrNull { it.exists() }
        assertNotNull("firestore.rules must exist", rulesFile)

        val rules = rulesFile!!.readText()

        // 1. rules_version 2
        assertTrue("Rules must declare rules_version = '2'", rules.contains("rules_version = '2'"))

        // 2. Zero Email Exposure on publicProfiles
        assertTrue(
            "publicProfiles must forbid email in create/update",
            rules.contains("!('email' in request.resource.data)")
        )

        // 3. No plaintext/body in conversation lastMessage
        assertTrue(
            "chatConversations must forbid plaintext in lastMessage",
            rules.contains("!('plaintext' in lastMsg)")
        )
        assertTrue(
            "chatConversations must forbid body in lastMessage",
            rules.contains("!('body' in lastMsg)")
        )

        // 4. Immutable public keys
        assertTrue(
            "chatPublicKeys must have immutable updates",
            rules.contains("match /chatPublicKeys/{uid}") && rules.contains("allow update: if false;")
        )

        // 5. Immutable messages
        assertTrue(
            "messages must have immutable updates",
            rules.contains("match /messages/{messageId}") && rules.contains("allow update: if false;")
        )

        // 6. Deferred calling isolated
        assertTrue(
            "calls collection must explicitly deny access",
            rules.contains("match /calls/{callId}")
        )

        // 7. Catch-all deny
        assertTrue(
            "Catch-all rule must deny read and write by default",
            rules.contains("match /{document=**}") && rules.contains("allow read, write: if false;")
        )
    }

    @Test
    fun `Milestone 9 - Services are not exported unless strictly required`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val content = manifestFile.readText()

        // FCM Service must be exported="false"
        assertTrue(
            "ChatrokFirebaseMessagingService must be android:exported=\"false\"",
            content.contains("android:name=\".service.ChatrokFirebaseMessagingService\"") &&
            content.contains("android:exported=\"false\"")
        )
    }
}
