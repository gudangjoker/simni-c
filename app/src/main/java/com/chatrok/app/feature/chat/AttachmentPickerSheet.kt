package com.chatrok.app.feature.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chatrok.app.R
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onAttachmentSelected: (Attachment, String?) -> Unit,
    onLaunchCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size, mime) = getFileInfo(context, uri, "photo.jpg", "image/jpeg")
            val attachment = Attachment(
                id = "img_" + System.currentTimeMillis(),
                type = AttachmentType.IMAGE,
                title = name,
                sizeBytes = size,
                uriOrUrl = uri.toString(),
                mimeType = mime
            )
            onAttachmentSelected(attachment, null)
            onDismiss()
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size, mime) = getFileInfo(context, uri, "video.mp4", "video/mp4")
            val attachment = Attachment(
                id = "vid_" + System.currentTimeMillis(),
                type = AttachmentType.VIDEO,
                title = name,
                sizeBytes = size,
                uriOrUrl = uri.toString(),
                mimeType = mime
            )
            onAttachmentSelected(attachment, null)
            onDismiss()
        }
    }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size, mime) = getFileInfo(context, uri, "document.pdf", "application/pdf")
            val attachment = Attachment(
                id = "doc_" + System.currentTimeMillis(),
                type = AttachmentType.DOCUMENT,
                title = name,
                sizeBytes = size,
                uriOrUrl = uri.toString(),
                mimeType = mime
            )
            onAttachmentSelected(attachment, null)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.testTag("attachment_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.share_content),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AttachmentOptionItem(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.photo),
                    color = Color(0xFF0284C7),
                    tag = "pick_photo_option",
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )

                AttachmentOptionItem(
                    icon = Icons.Default.Videocam,
                    label = stringResource(R.string.video),
                    color = Color(0xFF8B5CF6),
                    tag = "pick_video_option",
                    onClick = {
                        videoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    }
                )

                AttachmentOptionItem(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    label = stringResource(R.string.document),
                    color = Color(0xFF0D9488),
                    tag = "pick_doc_option",
                    onClick = {
                        docPickerLauncher.launch("*/*")
                    }
                )

                AttachmentOptionItem(
                    icon = Icons.Default.CameraAlt,
                    label = stringResource(R.string.camera),
                    color = Color(0xFFD97706),
                    tag = "pick_camera_option",
                    onClick = {
                        onDismiss()
                        onLaunchCamera()
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun getFileInfo(
    context: Context,
    uri: Uri,
    fallbackName: String,
    fallbackMime: String
): Triple<String, Long, String> {
    var name = fallbackName
    var size = 0L
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) {
                    name = cursor.getString(nameIndex) ?: fallbackName
                }
                if (sizeIndex != -1) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (_: Exception) {}
    val mime = context.contentResolver.getType(uri) ?: fallbackMime
    return Triple(name, size, mime)
}

@Composable
private fun AttachmentOptionItem(
    icon: ImageVector,
    label: String,
    color: Color,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp)
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
