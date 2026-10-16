package com.chatrok.app.feature.chat

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.designsystem.component.WaveformVisualizer
import com.chatrok.app.core.designsystem.theme.StatusError
import com.chatrok.app.core.model.ReplyPreview

@Composable
fun ChatComposer(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    replyingTo: ReplyPreview?,
    onCancelReply: () -> Unit,
    recordingState: VoiceNoteRecording,
    onStartRecording: () -> Unit,
    onLockRecording: () -> Unit,
    onStopRecordingForPreview: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendVoiceNote: () -> Unit,
    onTogglePreviewPlayback: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Reply banner if active
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (replyingTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.replying_to_format, replyingTo.senderName),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = replyingTo.snippet,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = onCancelReply) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.cancel_reply))
                        }
                    }
                }
            }
        }

        // Check if recording is active or in preview
        when (recordingState.state) {
            RecordingState.RECORDING -> {
                ActiveRecordingBar(
                    durationSeconds = recordingState.durationSeconds,
                    waveform = recordingState.waveformPoints,
                    onCancel = onCancelRecording,
                    onLock = onLockRecording,
                    onStop = onStopRecordingForPreview,
                    onSend = onSendVoiceNote
                )
            }
            RecordingState.LOCKED -> {
                LockedRecordingBar(
                    durationSeconds = recordingState.durationSeconds,
                    waveform = recordingState.waveformPoints,
                    onCancel = onCancelRecording,
                    onStop = onStopRecordingForPreview,
                    onSend = onSendVoiceNote
                )
            }
            RecordingState.PREVIEW -> {
                PreviewVoiceNoteBar(
                    durationSeconds = recordingState.durationSeconds,
                    waveform = recordingState.waveformPoints,
                    isPlaying = recordingState.isPlayingPreview,
                    progress = recordingState.previewProgress,
                    onTogglePlay = onTogglePreviewPlayback,
                    onCancel = onCancelRecording,
                    onSend = onSendVoiceNote
                )
            }
            RecordingState.IDLE -> {
                StandardComposerBar(
                    text = text,
                    onTextChange = onTextChange,
                    onSendClick = onSendClick,
                    onAttachmentClick = onAttachmentClick,
                    onCameraClick = onCameraClick,
                    onStartRecording = onStartRecording
                )
            }
        }
    }
}

@Composable
private fun StandardComposerBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onAttachmentClick: () -> Unit,
    onCameraClick: () -> Unit,
    onStartRecording: () -> Unit
) {
    val context = LocalContext.current
    var showQuickEmojis by remember { mutableStateOf(false) }
    val enterIsSend = remember {
        context.getSharedPreferences("simni_settings_prefs", Context.MODE_PRIVATE)
            .getBoolean("enter_is_send", false)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = showQuickEmojis,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 2.dp
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val quickEmojis = listOf(
                        "😀", "😂", "🤣", "😍", "🥰", "😎", "🤩", "😊",
                        "👍", "👎", "❤️", "🔥", "🎉", "🙏", "👏", "💪",
                        "😭", "🥺", "🤔", "😱", "💯", "✨", "🚀", "🤝",
                        "☕", "🎂", "🥳", "👀", "🫡", "🆗"
                    )
                    items(quickEmojis) { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 24.sp,
                            modifier = Modifier
                                .clickable {
                                    onTextChange(text + emoji)
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showQuickEmojis = !showQuickEmojis }) {
                        Icon(
                            imageVector = if (showQuickEmojis) Icons.Default.Close else Icons.Default.SentimentSatisfied,
                            contentDescription = stringResource(R.string.emojis),
                            tint = if (showQuickEmojis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.message_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = if (enterIsSend) 1 else 5,
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (enterIsSend) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (text.isNotBlank()) onSendClick()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chat_input_field")
                    )
                }

                IconButton(
                    onClick = onAttachmentClick,
                    modifier = Modifier.testTag("attachment_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = stringResource(R.string.attachments),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (text.isEmpty()) {
                    IconButton(
                        onClick = onCameraClick,
                        modifier = Modifier.testTag("camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.camera),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Action button: Send if text is non-empty, Mic if empty
        if (text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onSendClick() }
                    .testTag("send_message_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = stringResource(R.string.send_message),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onStartRecording() }
                    .testTag("voice_note_record_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = stringResource(R.string.record_voice_note),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
}

@Composable
private fun ActiveRecordingBar(
    durationSeconds: Int,
    waveform: List<Float>,
    onCancel: () -> Unit,
    onLock: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("active_recording_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.testTag("cancel_recording_button")) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.cancel_recording), tint = StatusError)
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(StatusError)
        )
        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = DateTimeUtils.formatDuration(durationSeconds),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(12.dp))

        WaveformVisualizer(
            waveform = waveform,
            progress = 1f,
            activeColor = StatusError,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onLock, modifier = Modifier.testTag("lock_recording_button")) {
            Icon(imageVector = Icons.Default.Lock, contentDescription = stringResource(R.string.lock_recording), tint = MaterialTheme.colorScheme.primary)
        }

        IconButton(onClick = onStop) {
            Icon(imageVector = Icons.Default.Stop, contentDescription = stringResource(R.string.stop_recording), tint = MaterialTheme.colorScheme.onSurface)
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onSend() }
                .testTag("send_voice_note_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LockedRecordingBar(
    durationSeconds: Int,
    waveform: List<Float>,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("locked_recording_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.cancel), tint = StatusError)
        }

        Text(
            text = stringResource(R.string.locked_duration_format, DateTimeUtils.formatDuration(durationSeconds)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(8.dp))

        WaveformVisualizer(
            waveform = waveform,
            progress = 1f,
            activeColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onStop, modifier = Modifier.testTag("stop_recording_for_preview")) {
            Icon(imageVector = Icons.Default.Stop, contentDescription = stringResource(R.string.stop), tint = MaterialTheme.colorScheme.onSurface)
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onSend() },
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PreviewVoiceNoteBar(
    durationSeconds: Int,
    waveform: List<Float>,
    isPlaying: Boolean,
    progress: Float,
    onTogglePlay: () -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("preview_recording_bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.discard), tint = StatusError)
        }

        IconButton(onClick = onTogglePlay, modifier = Modifier.testTag("play_preview_button")) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.pause_preview) else stringResource(R.string.play_preview),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            WaveformVisualizer(
                waveform = waveform,
                progress = progress,
                activeColor = MaterialTheme.colorScheme.primary
            )
            Text(
                text = DateTimeUtils.formatDuration(durationSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { onSend() }
                .testTag("send_preview_voice_note_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Send, contentDescription = stringResource(R.string.send), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        }
    }
}
