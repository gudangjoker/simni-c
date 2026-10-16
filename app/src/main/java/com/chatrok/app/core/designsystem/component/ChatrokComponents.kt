package com.chatrok.app.core.designsystem.component

import androidx.compose.ui.res.stringResource
import com.chatrok.app.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.chatrok.app.core.designsystem.theme.ReceiptDelivered
import com.chatrok.app.core.designsystem.theme.ReceiptDeliveredDark
import com.chatrok.app.core.designsystem.theme.ReceiptRead
import com.chatrok.app.core.designsystem.theme.ReceiptReadDark
import com.chatrok.app.core.designsystem.theme.ReceiptSent
import com.chatrok.app.core.designsystem.theme.StatusError
import com.chatrok.app.core.designsystem.theme.StatusOnline
import com.chatrok.app.core.model.MessageStatus
import kotlin.math.abs

private val avatarColors = listOf(
    Color(0xFF0D9488),
    Color(0xFF0284C7),
    Color(0xFF6366F1),
    Color(0xFF8B5CF6),
    Color(0xFFD97706),
    Color(0xFF059669),
    Color(0xFF2563EB),
    Color(0xFF7C3AED)
)

@Composable
fun ChatrokAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    showOnlineIndicator: Boolean = false,
    isOnline: Boolean = false,
    avatarUrl: String? = null
) {
    val initials = remember(name) {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
            parts.isNotEmpty() -> parts[0].take(2).uppercase()
            else -> "U"
        }
    }

    val backgroundColor = remember(name) {
        val hash = abs(name.hashCode())
        avatarColors[hash % avatarColors.size]
    }

    Box(modifier = modifier, contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            if (!avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = initials,
                    color = Color.White,
                    fontSize = (size.value * 0.4f).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showOnlineIndicator && isOnline) {
            val indicatorSize = (size * 0.28f).coerceAtLeast(10.dp)
            Box(
                modifier = Modifier
                    .size(indicatorSize)
                    .clip(CircleShape)
                    .background(StatusOnline)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .testTag("online_indicator")
            )
        }
    }
}

@Composable
fun MessageReceiptIcon(
    status: MessageStatus,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val baseModifier = modifier.testTag("receipt_indicator")

    when (status) {
        MessageStatus.SENDING -> {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.sending),
                modifier = baseModifier
                    .size(12.dp)
                    .testTag("receipt_sending"),
                tint = if (isDark) Color(0xFF94A3B8).copy(alpha = 0.6f) else Color(0xFF64748B).copy(alpha = 0.7f)
            )
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.sent),
                modifier = baseModifier
                    .size(14.dp)
                    .testTag("receipt_sent")
                    .testTag("sent_indicator"),
                tint = if (isDark) ReceiptDeliveredDark else ReceiptSent
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.delivered),
                modifier = baseModifier
                    .size(15.dp)
                    .testTag("receipt_delivered")
                    .testTag("delivered_indicator"),
                tint = if (isDark) ReceiptDeliveredDark else ReceiptDelivered
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Default.DoneAll,
                contentDescription = stringResource(R.string.read),
                modifier = baseModifier
                    .size(15.dp)
                    .testTag("receipt_read")
                    .testTag("read_indicator"),
                tint = if (isDark) ReceiptReadDark else ReceiptRead
            )
        }
        MessageStatus.FAILED -> {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.failed),
                modifier = baseModifier
                    .size(14.dp)
                    .testTag("receipt_failed")
                    .testTag("failed_indicator"),
                tint = StatusError
            )
        }
    }
}

@Composable
fun ConnectivityBanner(
    isConnected: Boolean,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = !isConnected,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = modifier
                .fillMaxWidth()
                .clickable { onRetryClick() }
                .testTag("connectivity_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.waiting_network),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun SecurityPillBadge(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .clickable { onClick() }
            .testTag("security_pill_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.e2ee_pending_badge),
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionButtonText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("empty_state_action")
            ) {
                Text(text = actionButtonText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun WaveformVisualizer(
    waveform: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
) {
    val points = remember(waveform) {
        if (waveform.isEmpty()) {
            listOf(0.2f, 0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 0.3f, 0.8f, 0.4f, 0.2f)
        } else waveform
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        points.forEachIndexed { index, heightFactor ->
            val isPlayed = (index.toFloat() / points.size) <= progress
            val barHeight = (heightFactor.coerceIn(0.15f, 1.0f) * 22).dp
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isPlayed) activeColor else inactiveColor)
            )
            if (index < points.size - 1) {
                Spacer(modifier = Modifier.width(barSpacing))
            }
        }
    }
}
