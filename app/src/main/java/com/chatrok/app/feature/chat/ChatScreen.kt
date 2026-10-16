package com.chatrok.app.feature.chat

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatrok.app.core.common.DateTimeUtils
import com.chatrok.app.core.designsystem.component.ChatrokAvatar
import com.chatrok.app.core.designsystem.component.ConnectivityBanner
import com.chatrok.app.core.designsystem.component.SecurityPillBadge
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.model.Message

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateBack: () -> Unit,
    onViewProfile: (userId: String) -> Unit,
    onViewSecurity: (userId: String) -> Unit,
    onMediaClick: (title: String, type: String) -> Unit,
    onLaunchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var showMenu by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showDeleteMessageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.lastOrNull()?.id) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress && listState.firstVisibleItemIndex == 0 }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadOlderMessages() }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("chat_screen"),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                uiState.otherUser?.let { onViewProfile(it.id) }
                            }
                            .testTag("chat_header_profile_target")
                    ) {
                        ChatrokAvatar(
                            name = uiState.otherUser?.displayName ?: stringResource(R.string.contact),
                            size = 38.dp,
                            showOnlineIndicator = true,
                            isOnline = uiState.presence.isOnline,
                            avatarUrl = uiState.otherUser?.avatarUrl
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Text(
                                text = uiState.otherUser?.displayName ?: stringResource(R.string.contact),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val subtitle = when {
                                uiState.presence.isTyping -> stringResource(R.string.typing)
                                uiState.presence.isOnline -> stringResource(R.string.online)
                                else -> stringResource(R.string.last_seen_format, DateTimeUtils.formatConversationDate(uiState.presence.lastSeenTimestamp))
                            }

                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = if (uiState.presence.isTyping || uiState.presence.isOnline)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            android.widget.Toast.makeText(context, "Fitur belum tersedia (panggilan suara WebRTC ditangguhkan)", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("start_voice_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = stringResource(R.string.voice_call),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            android.widget.Toast.makeText(context, "Fitur belum tersedia (panggilan video WebRTC ditangguhkan)", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("start_video_call_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = stringResource(R.string.video_call),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.testTag("chat_overflow_menu_button")
                        ) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_profile)) },
                                onClick = {
                                    showMenu = false
                                    uiState.otherUser?.let { onViewProfile(it.id) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.security_encryption)) },
                                onClick = {
                                    showMenu = false
                                    uiState.otherUser?.let { onViewSecurity(it.id) }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.clear_chat)) },
                                onClick = {
                                    showMenu = false
                                    showClearChatDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (uiState.isBlocked) stringResource(R.string.unblock_user) else stringResource(R.string.block_user)) },
                                onClick = {
                                    showMenu = false
                                    viewModel.toggleBlockUser()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            if (uiState.isBlocked) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.blocked_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                ChatComposer(
                    text = uiState.draftText,
                    onTextChange = { viewModel.onDraftChange(it) },
                    onSendClick = { viewModel.sendMessage() },
                    onAttachmentClick = { viewModel.setAttachmentSheetVisible(true) },
                    onCameraClick = onLaunchCamera,
                    replyingTo = uiState.replyingTo,
                    onCancelReply = { viewModel.clearReply() },
                    recordingState = uiState.recordingState,
                    onStartRecording = { viewModel.startRecording() },
                    onLockRecording = { viewModel.lockRecording() },
                    onStopRecordingForPreview = { viewModel.stopRecordingForPreview() },
                    onCancelRecording = { viewModel.cancelRecording() },
                    onSendVoiceNote = { viewModel.sendVoiceNote() },
                    onTogglePreviewPlayback = { viewModel.togglePreviewPlayback() }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ConnectivityBanner(
                isConnected = isConnected,
                onRetryClick = { /* retry presence */ }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("chat-message-list")
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("messages_lazy_column")
                ) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        SecurityPillBadge(
                            text = stringResource(R.string.e2ee_phase2_notice),
                            onClick = {
                                uiState.otherUser?.let { onViewSecurity(it.id) }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                itemsIndexed(
                    items = uiState.messages,
                    key = { _, msg -> msg.id }
                ) { index, message ->
                    val showDateSeparator = if (index == 0) {
                        true
                    } else {
                        val prevMessage = uiState.messages[index - 1]
                        val prevDate = DateTimeUtils.toZonedDateTime(prevMessage.timestamp).toLocalDate()
                        val currDate = DateTimeUtils.toZonedDateTime(message.timestamp).toLocalDate()
                        !prevDate.isEqual(currDate)
                    }

                    if (showDateSeparator) {
                        DateSeparatorBadge(dateText = DateTimeUtils.formatDateSeparator(message.timestamp))
                    }

                    ChatMessageItem(
                        message = message,
                        onLongClick = { viewModel.selectMessage(message) },
                        onRetryClick = { viewModel.retryMessage(message.id) },
                        onMediaClick = onMediaClick
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Scroll to bottom floating button
            val coroutineScope = rememberCoroutineScope()
            val showScrollToBottom by remember {
                derivedStateOf {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val total = listState.layoutInfo.totalItemsCount
                    total > 5 && lastVisible < total - 2
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll ke bawah"
                    )
                }
            }
        }
    }
}

    // Attachment Picker Bottom Sheet
    if (uiState.showAttachmentSheet) {
        AttachmentPickerSheet(
            onDismiss = { viewModel.setAttachmentSheetVisible(false) },
            onAttachmentSelected = { attachment, caption ->
                viewModel.sendAttachment(attachment, caption)
            },
            onLaunchCamera = {
                viewModel.setAttachmentSheetVisible(false)
                onLaunchCamera()
            }
        )
    }

    // Message context menu bottom sheet on long press
    if (uiState.selectedMessage != null) {
        val selected = uiState.selectedMessage!!
        ModalBottomSheet(
            onDismissRequest = { viewModel.selectMessage(null) },
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("message_context_menu")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.message_actions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setReplyTo(selected)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Reply, contentDescription = stringResource(R.string.reply), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.reply_to_message), style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("CHATROK", selected.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Pesan disalin ke papan klip", Toast.LENGTH_SHORT).show()
                            viewModel.selectMessage(null)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.copy_text), style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDeleteMessageDialog = true
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.delete_message), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteMessageDialog && uiState.selectedMessage != null) {
        AlertDialog(
            onDismissRequest = { showDeleteMessageDialog = false },
            title = { Text(stringResource(R.string.delete_message_title)) },
            text = { Text(stringResource(R.string.delete_message_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uiState.selectedMessage?.let { viewModel.deleteMessage(it.id) }
                        showDeleteMessageDialog = false
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMessageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = { Text(stringResource(R.string.clear_chat_title)) },
            text = { Text(stringResource(R.string.clear_chat_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearChatHistory()
                        showClearChatDialog = false
                    }
                ) {
                    Text(stringResource(R.string.clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DateSeparatorBadge(dateText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            shadowElevation = 0.5.dp
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
