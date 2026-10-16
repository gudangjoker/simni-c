package com.chatrok.app.feature.inbox

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.designsystem.component.ChatrokAvatar
import com.chatrok.app.core.designsystem.component.EmptyStateView
import com.chatrok.app.core.designsystem.theme.StatusError
import com.chatrok.app.core.model.CallSession
import com.chatrok.app.core.model.CallState
import com.chatrok.app.core.model.CallType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    viewModel: InboxViewModel,
    onStartCall: (userId: String, type: CallType) -> Unit,
    onSimulateIncomingCall: (userId: String, type: CallType) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callHistory by viewModel.callHistory.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("call_history_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_logs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("call_history_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.call_logs_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            onSimulateIncomingCall("usr_1", CallType.VOICE)
                        },
                        modifier = Modifier.testTag("simulate_incoming_call_button")
                    ) {
                        Text(stringResource(R.string.test_incoming_call), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (callHistory.isEmpty()) {
                EmptyStateView(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = stringResource(R.string.no_call_logs),
                    description = stringResource(R.string.call_logs_empty_desc),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(callHistory) { session ->
                        CallHistoryItem(
                            session = session,
                            onCallback = {
                                onStartCall(session.otherUser.id, session.type)
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryItem(
    session: CallSession,
    onCallback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMissed = session.state == CallState.MISSED || session.state == CallState.REJECTED

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCallback() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("call_item_${session.callId}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChatrokAvatar(name = session.otherUser.displayName, size = 46.dp)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.otherUser.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (isMissed) StatusError else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isMissed -> Icons.Default.PhoneMissed
                        session.isOutgoing -> Icons.Default.CallMade
                        else -> Icons.Default.CallReceived
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isMissed) StatusError else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                val stateText = when {
                    isMissed -> if (session.type == CallType.VIDEO) stringResource(R.string.missed_video_call) else stringResource(R.string.missed_voice_call)
                    session.durationSeconds > 0 -> stringResource(R.string.duration_format, DateTimeUtils.formatDuration(session.durationSeconds))
                    else -> if (session.isOutgoing) stringResource(R.string.outgoing) else stringResource(R.string.incoming)
                }
                Text(
                    text = "$stateText • ${DateTimeUtils.formatConversationDate(session.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(
            onClick = onCallback,
            modifier = Modifier.testTag("callback_button_${session.callId}")
        ) {
            Icon(
                imageVector = if (session.type == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                contentDescription = stringResource(R.string.call_back),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
