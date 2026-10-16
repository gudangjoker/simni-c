package com.chatrok.app.feature.call

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.designsystem.component.ChatrokAvatar
import com.chatrok.app.core.designsystem.theme.StatusError
import com.chatrok.app.core.designsystem.theme.StatusOnline
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallState
import com.chatrok.app.core.model.CallType

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    modifier: Modifier = Modifier
) {
    val currentCall by viewModel.currentCall.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()

    if (currentCall == null) return

    val session = currentCall!!

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .testTag("call_screen_container")
    ) {
        when {
            session.state == CallState.INCOMING && session.type == CallType.VOICE -> {
                IncomingVoiceCallView(
                    session = session,
                    onAccept = { viewModel.answerCall() },
                    onDecline = { viewModel.endCall() }
                )
            }
            session.state == CallState.INCOMING && session.type == CallType.VIDEO -> {
                IncomingVideoCallView(
                    session = session,
                    onAccept = { viewModel.answerCall() },
                    onDecline = { viewModel.endCall() }
                )
            }
            session.type == CallType.VIDEO -> {
                ActiveVideoCallView(
                    session = session,
                    controls = controls,
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleVideo = { viewModel.toggleVideo() },
                    onSwitchCamera = { viewModel.switchCamera() },
                    onEndCall = { viewModel.endCall() }
                )
            }
            else -> {
                ActiveVoiceCallView(
                    session = session,
                    controls = controls,
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleSpeaker = { viewModel.toggleSpeaker() },
                    onEndCall = { viewModel.endCall() }
                )
            }
        }
    }
}

@Composable
private fun IncomingVoiceCallView(
    session: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("incoming_voice_call_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            SecurityCallBadge()
            Spacer(modifier = Modifier.height(24.dp))
            ChatrokAvatar(name = session.otherUser.displayName, size = 110.dp)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = session.otherUser.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.incoming_voice_call_format, session.otherUser.username),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CallActionButton(
                icon = Icons.Default.CallEnd,
                label = stringResource(R.string.decline),
                backgroundColor = StatusError,
                tag = "decline_call_button",
                onClick = onDecline
            )

            CallActionButton(
                icon = Icons.Default.Call,
                label = stringResource(R.string.accept),
                backgroundColor = StatusOnline,
                tag = "accept_call_button",
                onClick = onAccept
            )
        }
    }
}

@Composable
private fun IncomingVideoCallView(
    session: CallSession,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("incoming_video_call_view")
    ) {
        // Simulated camera background preview
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF090D16))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.size(160.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                SecurityCallBadge()
                Spacer(modifier = Modifier.height(24.dp))
                ChatrokAvatar(name = session.otherUser.displayName, size = 100.dp)
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = session.otherUser.displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.incoming_video_call),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallActionButton(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(R.string.decline),
                    backgroundColor = StatusError,
                    tag = "decline_video_call_button",
                    onClick = onDecline
                )

                CallActionButton(
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.answer_video),
                    backgroundColor = StatusOnline,
                    tag = "accept_video_call_button",
                    onClick = onAccept
                )
            }
        }
    }
}

@Composable
private fun ActiveVoiceCallView(
    session: CallSession,
    controls: CallUiControls,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("active_voice_call_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            SecurityCallBadge()
            Spacer(modifier = Modifier.height(28.dp))
            ChatrokAvatar(name = session.otherUser.displayName, size = 114.dp)
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = session.otherUser.displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "@${session.otherUser.username}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = if (session.state == CallState.CONNECTING) stringResource(R.string.connecting) else DateTimeUtils.formatDuration(controls.callDuration),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (session.state == CallState.CONNECTING) Color.Yellow else Color.White
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CallActionButton(
                    icon = if (controls.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (controls.isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                    backgroundColor = if (controls.isMuted) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f),
                    tag = "toggle_mute_button",
                    onClick = onToggleMute
                )

                CallActionButton(
                    icon = if (controls.isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    label = if (controls.isSpeakerOn) stringResource(R.string.speaker) else stringResource(R.string.earpiece),
                    backgroundColor = if (controls.isSpeakerOn) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f),
                    tag = "toggle_speaker_button",
                    onClick = onToggleSpeaker
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            CallActionButton(
                icon = Icons.Default.CallEnd,
                label = stringResource(R.string.end_call),
                backgroundColor = StatusError,
                tag = "end_call_button",
                onClick = onEndCall
            )
        }
    }
}

@Composable
private fun ActiveVideoCallView(
    session: CallSession,
    controls: CallUiControls,
    onToggleMute: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("active_video_call_view")
    ) {
        // Full screen remote video stream simulation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ChatrokAvatar(name = session.otherUser.displayName, size = 90.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.video_call_name_format, session.otherUser.displayName),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Local camera PiP (Picture in Picture) preview in top right
        Box(
            modifier = Modifier
                .padding(top = 48.dp, end = 20.dp)
                .size(width = 96.dp, height = 138.dp)
                .align(Alignment.TopEnd)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1E293B))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (controls.isVideoEnabled) {
                Text(
                    text = if (controls.isFrontCamera) stringResource(R.string.you_front) else stringResource(R.string.you_back),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Text(text = stringResource(R.string.camera_off), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }

        // Header overlay with duration and security
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp, start = 20.dp)
        ) {
            Text(
                text = session.otherUser.displayName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = DateTimeUtils.formatDuration(controls.callDuration),
                color = Color.LightGray,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Bottom control bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                .padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallActionButton(
                icon = if (controls.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (controls.isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                backgroundColor = if (controls.isMuted) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f),
                tag = "video_toggle_mute_button",
                onClick = onToggleMute
            )

            CallActionButton(
                icon = if (controls.isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                label = if (controls.isVideoEnabled) stringResource(R.string.stop_video) else stringResource(R.string.start_video),
                backgroundColor = if (controls.isVideoEnabled) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.35f),
                tag = "toggle_video_camera_button",
                onClick = onToggleVideo
            )

            CallActionButton(
                icon = Icons.Default.Cameraswitch,
                label = stringResource(R.string.flip_camera),
                backgroundColor = Color.White.copy(alpha = 0.15f),
                tag = "switch_camera_button",
                onClick = onSwitchCamera
            )

            CallActionButton(
                icon = Icons.Default.CallEnd,
                label = stringResource(R.string.end),
                backgroundColor = StatusError,
                tag = "end_video_call_button",
                onClick = onEndCall
            )
        }
    }
}

@Composable
private fun SecurityCallBadge() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.e2ee_pending_badge),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    backgroundColor: Color,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onClick() }
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = Color.White
        )
    }
}
