package com.chatrok.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chatrok.app.core.crypto.E2eeCryptoEngine
import com.chatrok.app.core.security.SecureIdentityStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecureIdentityStorageTest {

    private lateinit var context: Context
    private lateinit var cryptoEngine: E2eeCryptoEngine
    private lateinit var storage: SecureIdentityStorage

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        cryptoEngine = E2eeCryptoEngine()
        storage = SecureIdentityStorage(context, cryptoEngine)
    }

    @Test
    fun testGetOrCreateIdentityPersists() {
        val uid = "user_test_123"
        storage.clearIdentity(uid)
        assertNull(storage.getIdentity(uid))

        val identity1 = storage.getOrCreateIdentity(uid)
        assertNotNull(identity1.publicJwk)
        assertNotNull(identity1.privateJwk)
        assertNotNull(identity1.fingerprint)

        // Calling again retrieves exact same identity
        val identity2 = storage.getOrCreateIdentity(uid)
        assertEquals(identity1.fingerprint, identity2.fingerprint)
        assertEquals(identity1.publicJwk.x, identity2.publicJwk.x)
        assertEquals(identity1.privateJwk.d, identity2.privateJwk.d)

        // Clear removes it
        storage.clearIdentity(uid)
        assertNull(storage.getIdentity(uid))
    }
}
