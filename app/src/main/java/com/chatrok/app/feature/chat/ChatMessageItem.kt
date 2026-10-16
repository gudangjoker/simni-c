package com.chatrok.app.feature.chat

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.designsystem.component.MessageReceiptIcon
import com.chatrok.app.core.designsystem.component.WaveformVisualizer
import com.chatrok.app.core.designsystem.theme.LocalChatColors
import com.chatrok.app.core.designsystem.theme.StatusError
import com.chatrok.app.core.model.Message
import com.chatrok.app.core.model.MessageStatus
import com.chatrok.app.core.model.MessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: Message,
    onLongClick: () -> Unit,
    onRetryClick: () -> Unit,
    onMediaClick: (title: String, type: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.type == MessageType.SYSTEM) {
        SystemMessageBubble(message = message)
        return
    }

    val isOutgoing = message.isOutgoing
    val chatColors = LocalChatColors.current

    val bubbleColor = if (isOutgoing) chatColors.outgoingBubble else chatColors.incomingBubble
    val textColor = if (isOutgoing) chatColors.outgoingText else chatColors.incomingText

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (isOutgoing && message.status == MessageStatus.FAILED) {
            IconButton(
                onClick = onRetryClick,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(36.dp)
                    .testTag("retry_message_button_${message.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry_message),
                    tint = StatusError
                )
            }
        }

        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {
                        if (message.type == MessageType.IMAGE || message.type == MessageType.VIDEO || message.type == MessageType.DOCUMENT) {
                            onMediaClick(message.attachment?.title ?: message.text, message.type.name)
                        }
                    },
                    onLongClick = onLongClick
                )
                .testTag("message_bubble_${message.id}"),
            shadowElevation = 0.5.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                // Reply quote preview inside bubble
                if (message.replyTo != null) {
                    ReplyQuoteView(
                        replyTo = message.replyTo,
                        isOutgoing = isOutgoing
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Message Body Content
                when (message.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = formatStyledMessageText(message.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }

                    MessageType.IMAGE -> {
                        ImageMessageContent(
                            message = message,
                            onMediaClick = onMediaClick
                        )
                    }

                    MessageType.VIDEO -> {
                        VideoMessageContent(
                            message = message,
                            onMediaClick = onMediaClick
                        )
                    }

                    MessageType.DOCUMENT -> {
                        DocumentMessageContent(
                            message = message,
                            textColor = textColor
                        )
                    }

                    MessageType.VOICE_NOTE -> {
                        VoiceNoteMessageContent(
                            message = message,
                            textColor = textColor
                        )
                    }

                    MessageType.CALL_EVENT -> {
                        CallEventContent(
                            message = message,
                            textColor = textColor
                        )
                    }

                    MessageType.SYSTEM -> {
                        // handled above
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Timestamp and receipt
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateTimeUtils.formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = textColor.copy(alpha = 0.65f)
                    )
                    if (isOutgoing) {
                        Spacer(modifier = Modifier.width(4.dp))
                        MessageReceiptIcon(
                            status = message.status,
                            modifier = Modifier.testTag("receipt_${message.id}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemMessageBubble(message: Message) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun ReplyQuoteView(
    replyTo: com.chatrok.app.core.model.ReplyPreview,
    isOutgoing: Boolean
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = replyTo.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = replyTo.snippet,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ImageMessageContent(
    message: Message,
    onMediaClick: (title: String, type: String) -> Unit
) {
    val attachment = message.attachment
    val photoLabel = stringResource(R.string.photo)
    val imageModel = remember(attachment) {
        val path = attachment?.localFilePath
        if (path != null && java.io.File(path).exists()) {
            java.io.File(path)
        } else if (!attachment?.uriOrUrl.isNullOrBlank()) {
            attachment?.uriOrUrl
        } else {
            null
        }
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable {
                    onMediaClick(attachment?.title ?: photoLabel, "IMAGE")
                },
            contentAlignment = Alignment.Center
        ) {
            if (imageModel != null) {
                coil.compose.AsyncImage(
                    model = imageModel,
                    contentDescription = attachment?.title ?: photoLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = attachment?.title ?: stringResource(R.string.encrypted_image),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (attachment?.sizeBytes != null && attachment.sizeBytes > 0) {
                            Text(
                                text = DateTimeUtils.formatFileSize(attachment.sizeBytes),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (attachment?.isUploading == true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { attachment.uploadProgress },
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (message.text.isNotBlank() && message.text != attachment?.title) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun VideoMessageContent(
    message: Message,
    onMediaClick: (title: String, type: String) -> Unit
) {
    val attachment = message.attachment
    val photoLabel = stringResource(R.string.photo)
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0F172A))
                .clickable {
                    onMediaClick(attachment?.title ?: "Video", "VIDEO")
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.play_video),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        if (message.text.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DocumentMessageContent(
    message: Message,
    textColor: Color
) {
    val attachment = message.attachment
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment?.title ?: message.text,
                style = MaterialTheme.typography.titleSmall,
                color = textColor,
                maxLines = 1
            )
            val sizeStr = if (attachment?.sizeBytes != null) DateTimeUtils.formatFileSize(attachment.sizeBytes) else stringResource(R.string.pdf_document)
            Text(
                text = sizeStr,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = textColor.copy(alpha = 0.7f)
            )
        }
        IconButton(
            onClick = {
                downloadDocumentToStorage(context, attachment)
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Unduh Dokumen",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun downloadDocumentToStorage(context: android.content.Context, attachment: com.chatrok.app.core.model.Attachment?) {
    if (attachment == null) return
    val fileName = attachment.title.ifBlank { "file_${System.currentTimeMillis()}" }
    try {
        val targetDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "SIMNI-C/media")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = java.io.File(targetDir, fileName)
        val sourceFile = attachment.localFilePath?.let { java.io.File(it) }
        if (sourceFile != null && sourceFile.exists()) {
            sourceFile.copyTo(targetFile, overwrite = true)
        } else if (attachment.uriOrUrl.startsWith("content://")) {
            val uri = android.net.Uri.parse(attachment.uriOrUrl)
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            targetFile.writeText("SIMNI-C Media Document: $fileName")
        }
        android.widget.Toast.makeText(context, "Disimpan di folder SIMNI-C/media/$fileName", android.widget.Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        try {
            val fallbackDir = java.io.File(context.getExternalFilesDir(null), "SIMNI-C/media")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fallbackFile = java.io.File(fallbackDir, fileName)
            fallbackFile.writeText("SIMNI-C Media Document: $fileName")
            android.widget.Toast.makeText(context, "Disimpan di SIMNI-C/media/$fileName", android.widget.Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Gagal mengunduh: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun VoiceNoteMessageContent(
    message: Message,
    textColor: Color
) {
    val attachment = message.attachment
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var playbackProgress by remember { mutableFloatStateOf(0f) }

    val duration = attachment?.durationSeconds ?: 14
    val waveform = attachment?.waveformPoints ?: listOf(0.3f, 0.5f, 0.8f, 0.4f, 0.7f, 0.9f, 0.4f, 0.2f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    isPlaying = !isPlaying
                    if (isPlaying) {
                        scope.launch {
                            for (i in 1..20) {
                                delay((duration * 50).toLong())
                                if (!isPlaying) break
                                playbackProgress = i / 20f
                            }
                            isPlaying = false
                            playbackProgress = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            WaveformVisualizer(
                waveform = waveform,
                progress = playbackProgress,
                activeColor = MaterialTheme.colorScheme.primary,
                inactiveColor = textColor.copy(alpha = 0.35f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = DateTimeUtils.formatDuration(duration),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = textColor.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun CallEventContent(
    message: Message,
    textColor: Color
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PhoneMissed,
            contentDescription = null,
            tint = StatusError,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor
        )
    }
}

private fun formatStyledMessageText(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val regex = Regex("""(\*(.*?)\*)|(_(.*?)_)|(~(.*?)~)|(`(.*?)`)""")
        val matches = regex.findAll(text)
        for (match in matches) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val value = match.value
            when {
                value.startsWith("*") && value.endsWith("*") && value.length > 2 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(value.substring(1, value.length - 1))
                    }
                }
                value.startsWith("_") && value.endsWith("_") && value.length > 2 -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(value.substring(1, value.length - 1))
                    }
                }
                value.startsWith("~") && value.endsWith("~") && value.length > 2 -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(value.substring(1, value.length - 1))
                    }
                }
                value.startsWith("`") && value.endsWith("`") && value.length > 2 -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Black.copy(alpha = 0.08f))) {
                        append(value.substring(1, value.length - 1))
                    }
                }
                else -> append(value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
