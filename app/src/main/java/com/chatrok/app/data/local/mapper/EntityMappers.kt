package com.chatrok.app.data.local.mapper

import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.Conversation
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import com.chatrok.app.core.model.ReplyPreview
import com.chatrok.app.core.model.User
import com.chatrok.app.data.local.entity.ConversationEntity
import com.chatrok.app.data.local.entity.MessageEntity
import com.chatrok.app.data.local.entity.UserEntity

object EntityMappers {

    fun MessageEntity.toDomain(): Message {
        val domainStatus = try {
            MessageStatus.valueOf(status)
        } catch (_: Exception) {
            MessageStatus.SENT
        }
        val domainType = try {
            MessageType.valueOf(type)
        } catch (_: Exception) {
            MessageType.TEXT
        }

        val domainAttachment = if (!attachmentId.isNullOrBlank()) {
            val attType = try {
                AttachmentType.valueOf(attachmentType ?: AttachmentType.DOCUMENT.name)
            } catch (_: Exception) {
                AttachmentType.DOCUMENT
            }
            Attachment(
                id = attachmentId,
                type = attType,
                title = attachmentTitle ?: "",
                sizeBytes = attachmentSizeBytes,
                durationSeconds = attachmentDurationSeconds,
                uriOrUrl = attachmentUriOrUrl,
                mimeType = attachmentMimeType,
                objectKey = attachmentObjectKey,
                mediaIv = attachmentMediaIv,
                localFilePath = attachmentLocalFilePath
            )
        } else {
            null
        }

        val domainReply = if (!replyMessageId.isNullOrBlank()) {
            val rType = try {
                MessageType.valueOf(replyType ?: MessageType.TEXT.name)
            } catch (_: Exception) {
                MessageType.TEXT
            }
            ReplyPreview(
                messageId = replyMessageId,
                senderName = replySenderName ?: "",
                snippet = replySnippet ?: "",
                type = rType,
                senderId = replySenderId
            )
        } else {
            null
        }

        return Message(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            text = text,
            timestamp = timestamp,
            status = domainStatus,
            type = domainType,
            attachment = domainAttachment,
            replyTo = domainReply,
            isOutgoing = isOutgoing
        )
    }

    fun Message.toEntity(): MessageEntity {
        return MessageEntity(
            id = id,
            conversationId = conversationId,
            senderId = senderId,
            text = text,
            timestamp = timestamp,
            status = status.name,
            type = type.name,
            isOutgoing = isOutgoing,
            attachmentId = attachment?.id,
            attachmentType = attachment?.type?.name,
            attachmentTitle = attachment?.title,
            attachmentSizeBytes = attachment?.sizeBytes ?: 0L,
            attachmentDurationSeconds = attachment?.durationSeconds,
            attachmentUriOrUrl = attachment?.uriOrUrl ?: "",
            attachmentMimeType = attachment?.mimeType ?: "",
            attachmentObjectKey = attachment?.objectKey,
            attachmentMediaIv = attachment?.mediaIv,
            attachmentLocalFilePath = attachment?.localFilePath,
            replyMessageId = replyTo?.messageId,
            replySenderName = replyTo?.senderName,
            replySnippet = replyTo?.snippet,
            replyType = replyTo?.type?.name,
            replySenderId = replyTo?.senderId
        )
    }

    fun ConversationEntity.toDomain(): Conversation {
        val otherUser = User(
            id = otherUserId,
            username = otherUsername,
            displayName = otherDisplayName,
            avatarUrl = otherAvatarUrl,
            bio = otherBio,
            phoneNumber = otherPhoneNumber,
            isOnline = otherIsOnline,
            lastSeen = otherLastSeen,
            isBlocked = false
        )

        val domainLastMessage = if (!lastMessageId.isNullOrBlank()) {
            val msgStatus = try {
                MessageStatus.valueOf(lastMessageStatus ?: MessageStatus.SENT.name)
            } catch (_: Exception) {
                MessageStatus.SENT
            }
            val msgType = try {
                MessageType.valueOf(lastMessageType ?: MessageType.TEXT.name)
            } catch (_: Exception) {
                MessageType.TEXT
            }
            Message(
                id = lastMessageId,
                conversationId = id,
                senderId = lastMessageSenderId ?: "",
                text = lastMessageText ?: "",
                timestamp = lastMessageTimestamp ?: lastUpdated,
                status = msgStatus,
                type = msgType,
                isOutgoing = lastMessageSenderId != otherUserId
            )
        } else {
            null
        }

        return Conversation(
            id = id,
            otherUser = otherUser,
            lastMessage = domainLastMessage,
            unreadCount = unreadCount,
            lastUpdated = lastUpdated,
            isPinned = isPinned,
            isMuted = isMuted
        )
    }

    fun Conversation.toEntity(): ConversationEntity {
        return ConversationEntity(
            id = id,
            otherUserId = otherUser.id,
            otherUsername = otherUser.username,
            otherDisplayName = otherUser.displayName,
            otherAvatarUrl = otherUser.avatarUrl,
            otherBio = otherUser.bio,
            otherPhoneNumber = otherUser.phoneNumber,
            otherIsOnline = otherUser.isOnline,
            otherLastSeen = otherUser.lastSeen,
            lastMessageId = lastMessage?.id,
            lastMessageText = lastMessage?.text,
            lastMessageSenderId = lastMessage?.senderId,
            lastMessageTimestamp = lastMessage?.timestamp,
            lastMessageStatus = lastMessage?.status?.name,
            lastMessageType = lastMessage?.type?.name,
            unreadCount = unreadCount,
            lastUpdated = lastUpdated,
            isPinned = isPinned,
            isMuted = isMuted
        )
    }

    fun UserEntity.toDomain(): User {
        return User(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
            phoneNumber = phoneNumber,
            isOnline = isOnline,
            lastSeen = lastSeen,
            isBlocked = isBlocked
        )
    }

    fun User.toEntity(): UserEntity {
        return UserEntity(
            id = id,
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            bio = bio,
            phoneNumber = phoneNumber,
            isOnline = isOnline,
            lastSeen = lastSeen,
            isBlocked = isBlocked
        )
    }
}
