package com.chatrok.app.core.receipt

import com.chatrok.app.core.common.MessageIdUtils
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageReceiptCoreTest {

    @Test
    fun testStatusProgressionMonotonicity() {
        // Progression forward is allowed
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.SENDING, MessageStatus.SENT))
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.SENT, MessageStatus.DELIVERED))
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.DELIVERED, MessageStatus.READ))

        // Same status is allowed
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.READ))
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.DELIVERED, MessageStatus.DELIVERED))

        // Regression is strictly rejected
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.DELIVERED))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.SENT))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.DELIVERED, MessageStatus.SENT))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.SENT, MessageStatus.SENDING))

        // FAILED is only allowed from SENDING
        assertTrue(MessageReceiptCore.canAdvanceStatus(MessageStatus.SENDING, MessageStatus.FAILED))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.SENT, MessageStatus.FAILED))
        assertFalse(MessageReceiptCore.canAdvanceStatus(MessageStatus.READ, MessageStatus.FAILED))
    }

    @Test
    fun testIsMessageAtOrBeforeWithSortableIds() {
        val t1 = 1700000000000L
        val t2 = 1700000060000L // 1 minute later

        val msgId1 = MessageIdUtils.generateMessageId(t1)
        val msgId2 = MessageIdUtils.generateMessageId(t2)

        // msgId1 is earlier than msgId2
        assertTrue(MessageReceiptCore.isMessageAtOrBefore(msgId1, msgId2))
        // Same ID is at or before
        assertTrue(MessageReceiptCore.isMessageAtOrBefore(msgId1, msgId1))
        // msgId2 is NOT at or before msgId1
        assertFalse(MessageReceiptCore.isMessageAtOrBefore(msgId2, msgId1))
    }

    @Test
    fun testResolveMessageStatusTransitions() {
        val t1 = 1700000000000L
        val t2 = 1700000010000L
        val t3 = 1700000020000L

        val id1 = MessageIdUtils.generateMessageId(t1)
        val id2 = MessageIdUtils.generateMessageId(t2)
        val id3 = MessageIdUtils.generateMessageId(t3)

        val msg1 = Message(
            id = id1,
            conversationId = "conv1",
            senderId = "userA",
            text = "Halo 1",
            timestamp = t1,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        val msg2 = Message(
            id = id2,
            conversationId = "conv1",
            senderId = "userA",
            text = "Halo 2",
            timestamp = t2,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        val msg3 = Message(
            id = id3,
            conversationId = "conv1",
            senderId = "userA",
            text = "Halo 3",
            timestamp = t3,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = true
        )

        // Scenario 1: No peer receipt yet -> remains SENT
        assertEquals(MessageStatus.SENT, MessageReceiptCore.resolveMessageStatus(msg1, null))

        // Scenario 2: Peer acknowledged receipt up to msg2 as delivered, msg1 as read
        val receipt = ReceiptData(
            uid = "userB",
            conversationId = "conv1",
            lastDeliveredMessageId = id2,
            lastDeliveredAt = t2,
            lastReadMessageId = id1,
            lastReadAt = t1,
            updatedAt = t2
        )

        // msg1 should be READ
        assertEquals(MessageStatus.READ, MessageReceiptCore.resolveMessageStatus(msg1, receipt))
        // msg2 should be DELIVERED
        assertEquals(MessageStatus.DELIVERED, MessageReceiptCore.resolveMessageStatus(msg2, receipt))
        // msg3 (sent after msg2) should still be SENT
        assertEquals(MessageStatus.SENT, MessageReceiptCore.resolveMessageStatus(msg3, receipt))
    }

    @Test
    fun testIncomingMessageDoesNotAlterReceiptStatus() {
        val incomingMsg = Message(
            id = "msg_in",
            conversationId = "conv1",
            senderId = "userB",
            text = "Incoming message",
            timestamp = 1000L,
            status = MessageStatus.SENT,
            type = MessageType.TEXT,
            isOutgoing = false
        )

        val receipt = ReceiptData(
            uid = "userB",
            conversationId = "conv1",
            lastReadMessageId = "msg_in",
            lastReadAt = 2000L
        )

        assertEquals(MessageStatus.SENT, MessageReceiptCore.resolveMessageStatus(incomingMsg, receipt))
    }
}
