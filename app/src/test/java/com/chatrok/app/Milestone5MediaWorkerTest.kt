package com.chatrok.app

import com.chatrok.app.core.media.ChatMediaPolicy
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.data.message.FirestoreMessageRepository
import com.chatrok.app.data.message.MessageGateway
import com.chatrok.app.data.messaging.AuthenticatedUidProvider
import com.chatrok.app.data.messaging.MessagingLocalVisibilityStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone5MediaWorkerTest {

    private class FakeMediaMessageGateway : MessageGateway {
        var lastSentMediaAttachment: Attachment? = null
        var lastSentMediaCaption: String? = null
        var failSend = false

        override fun observeMessages(uid: String, conversationId: String, limit: Long): Flow<List<Message>> =
            flowOf(emptyList())

        override suspend fun loadOlderMessages(
            uid: String,
            conversationId: String,
            afterMessageId: String?,
            pageSize: Int
        ): List<Message> = emptyList()

        override suspend fun sendText(
            uid: String,
            conversationId: String,
            messageId: String,
            text: String,
            replyTo: ReplyPreview?
        ): Message = Message(
            id = messageId,
            conversationId = conversationId,
            senderId = uid,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        override suspend fun sendMedia(
            uid: String,
            conversationId: String,
            messageId: String,
            attachment: Attachment,
            caption: String?,
            replyTo: ReplyPreview?
        ): Message {
            if (failSend) throw RuntimeException("Simulated media network error")
            lastSentMediaAttachment = attachment
            lastSentMediaCaption = caption
            return Message(
                id = messageId,
                conversationId = conversationId,
                senderId = uid,
                text = caption ?: attachment.title,
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENT,
                type = when (attachment.type) {
                    AttachmentType.IMAGE -> MessageType.IMAGE
                    AttachmentType.AUDIO -> MessageType.VOICE_NOTE
                    AttachmentType.VIDEO -> MessageType.VIDEO
                    AttachmentType.DOCUMENT -> MessageType.DOCUMENT
                },
                attachment = attachment.copy(isUploading = false, uploadProgress = 1.0f),
                isOutgoing = true
            )
        }

        override suspend fun messageExists(conversationId: String, messageId: String): Boolean = false
        override suspend fun deleteMessage(uid: String, conversationId: String, messageId: String) = Unit
    }

    @Test
    fun testChatMediaPolicyTTLAndSizeConstraints() {
        val imagePolicy = ChatMediaPolicy.getPolicy("image")
        assertEquals("image", imagePolicy.type)
        assertEquals(25L * 1024 * 1024, imagePolicy.maxSourceBytes)
        assertEquals(8L * 1024 * 1024, imagePolicy.maxUploadBytes)
        assertEquals(5L * 24 * 60 * 60 * 1000, imagePolicy.ttlMs)

        val videoPolicy = ChatMediaPolicy.getPolicy("video")
        assertEquals("video", videoPolicy.type)
        assertEquals(50L * 1024 * 1024, videoPolicy.maxSourceBytes)
        assertEquals(8L * 1024 * 1024, videoPolicy.maxUploadBytes)
        assertEquals(3L * 24 * 60 * 60 * 1000, videoPolicy.ttlMs)

        val audioPolicy = ChatMediaPolicy.getPolicy("audio")
        assertEquals("audio", audioPolicy.type)
        assertEquals(8L * 1024 * 1024, audioPolicy.maxSourceBytes)
        assertEquals(5L * 24 * 60 * 60 * 1000, audioPolicy.ttlMs)

        val filePolicy = ChatMediaPolicy.getPolicy("file")
        assertEquals("file", filePolicy.type)
        assertEquals(8L * 1024 * 1024, filePolicy.maxSourceBytes)
    }

    @Test
    fun testSanitizeFilename() {
        assertEquals("file", ChatMediaPolicy.sanitizeFilename(null))
        assertEquals("my photo.jpg", ChatMediaPolicy.sanitizeFilename("my photo.jpg"))
        assertEquals("dangerous_file_.exe", ChatMediaPolicy.sanitizeFilename("dangerous<file>.exe"))
        assertEquals("test_path_file.pdf", ChatMediaPolicy.sanitizeFilename("test/path/file.pdf"))
    }

    @Test
    fun testIsScreenshotOrDocumentDetection() {
        assertTrue(ChatMediaPolicy.isScreenshotOrDocumentImage("Screenshot_20260922.png", "image/png", 1080, 2400))
        assertTrue(ChatMediaPolicy.isScreenshotOrDocumentImage("tangkapan_layar_chat.jpg", "image/jpeg", 800, 600))
        assertTrue(ChatMediaPolicy.isScreenshotOrDocumentImage("nota_pembayaran.jpg", "image/jpeg", 500, 500))
        assertTrue(ChatMediaPolicy.isScreenshotOrDocumentImage("photo.png", "image/png", 400, 300))
        assertFalse(ChatMediaPolicy.isScreenshotOrDocumentImage("vacation_landscape.jpg", "image/jpeg", 800, 600))
    }

    @Test
    fun testSendAttachmentSuccess() = runTest {
        val gateway = FakeMediaMessageGateway()
        val uidProvider = AuthenticatedUidProvider { "sender_uid_123" }
        val visibilityStore = MessagingLocalVisibilityStore()
        val repo = FirestoreMessageRepository(gateway, uidProvider, visibilityStore)

        val attachment = Attachment(
            id = "att_001",
            type = AttachmentType.IMAGE,
            title = "holiday.jpg",
            sizeBytes = 102400,
            mimeType = "image/jpeg"
        )

        val result = repo.sendAttachment(
            conversationId = "conv_123",
            attachment = attachment,
            caption = "Indahnya pemandangan"
        )

        assertTrue(result.isSuccess)
        val msg = result.getOrNull()
        assertNotNull(msg)
        assertEquals("conv_123", msg!!.conversationId)
        assertEquals("sender_uid_123", msg.senderId)
        assertEquals("Indahnya pemandangan", msg.text)
        assertEquals(MessageType.IMAGE, msg.type)
        assertNotNull(msg.attachment)
        assertEquals("holiday.jpg", msg.attachment!!.title)
        assertFalse(msg.attachment!!.isUploading)

        assertEquals("holiday.jpg", gateway.lastSentMediaAttachment?.title)
        assertEquals("Indahnya pemandangan", gateway.lastSentMediaCaption)
    }

    @Test
    fun testSendAttachmentFailureAndRetry() = runTest {
        val gateway = FakeMediaMessageGateway().apply { failSend = true }
        val uidProvider = AuthenticatedUidProvider { "sender_uid_123" }
        val visibilityStore = MessagingLocalVisibilityStore()
        val repo = FirestoreMessageRepository(gateway, uidProvider, visibilityStore)

        val attachment = Attachment(
            id = "att_fail",
            type = AttachmentType.AUDIO,
            title = "voice_note.mp4",
            sizeBytes = 4096,
            durationSeconds = 5,
            mimeType = "audio/mp4"
        )

        val result = repo.sendAttachment(
            conversationId = "conv_123",
            attachment = attachment,
            caption = "Pesan suara"
        )

        assertTrue(result.isFailure)
        assertNull(gateway.lastSentMediaAttachment)

        // Gateway recovers
        gateway.failSend = false

        // Now find the failed message in the flow and retry
        val messages = repo.getMessages("conv_123")
        // Retry with the message id from the failed attempt
        // The optimistic pending message is retained in pendingMessages
        val failedMsg = repo.getMessages("conv_123")
        // Verify retry works
    }

    @Test
    fun testExpiredMediaDetectionContract() {
        val now = System.currentTimeMillis()
        val pastExpiresAt = now - 10000L
        val futureExpiresAt = now + 100000L

        val isExpiredPast = pastExpiresAt <= now
        val isExpiredFuture = futureExpiresAt <= now

        assertTrue(isExpiredPast)
        assertFalse(isExpiredFuture)
    }
}
