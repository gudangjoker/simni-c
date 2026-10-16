package com.chatrok.app.feature.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

@Composable
fun CropAvatarDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onCropSuccess: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessingCrop by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Load bitmap safely with downsampling
    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, options)

                    val maxDim = max(options.outWidth, options.outHeight)
                    var sampleSize = 1
                    while (maxDim / (sampleSize * 2) >= 1024) {
                        sampleSize *= 2
                    }

                    context.contentResolver.openInputStream(imageUri)?.use { actualStream ->
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        sourceBitmap = BitmapFactory.decodeStream(actualStream, null, decodeOpts)
                    }
                }
            } catch (_: Exception) {
                sourceBitmap = null
            } finally {
                isLoading = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isProcessingCrop) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "Pangkas Foto Profil",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Geser & cubit untuk menyesuaikan posisi di dalam lingkaran",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center
                    )
                }

                // Interactive Crop Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading || sourceBitmap == null) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else {
                        val bitmap = sourceBitmap!!
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(0.8f, 4f)
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    }
                                }
                        ) {
                            // Base image display
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Pratinjau Pangkas",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                    }
                            )

                            // Scrim overlay with circular transparent window
                            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                val canvasWidth = size.width
                                val canvasHeight = size.height
                                val radius = min(canvasWidth, canvasHeight) / 2f * 0.92f
                                val centerOffset = Offset(canvasWidth / 2f, canvasHeight / 2f)

                                val outerPath = Path().apply {
                                    addRect(Rect(0f, 0f, canvasWidth, canvasHeight))
                                }
                                val circlePath = Path().apply {
                                    addOval(
                                        Rect(
                                            centerOffset.x - radius,
                                            centerOffset.y - radius,
                                            centerOffset.x + radius,
                                            centerOffset.y + radius
                                        )
                                    )
                                }

                                val overlayPath = Path.combine(
                                    operation = androidx.compose.ui.graphics.PathOperation.Difference,
                                    path1 = outerPath,
                                    path2 = circlePath
                                )

                                drawPath(
                                    path = overlayPath,
                                    color = Color.Black.copy(alpha = 0.65f)
                                )

                                // White circular guide border
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.85f),
                                    radius = radius,
                                    center = centerOffset,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isProcessingCrop,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Batal")
                    }

                    Button(
                        onClick = {
                            val bmp = sourceBitmap ?: return@Button
                            isProcessingCrop = true
                            coroutineScope.launch {
                                withContext(Dispatchers.Default) {
                                    try {
                                        val outputSize = 512
                                        val croppedBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(croppedBitmap)

                                        val matrix = Matrix()
                                        val baseScale = max(outputSize.toFloat() / bmp.width, outputSize.toFloat() / bmp.height)
                                        val finalScale = baseScale * scale
                                        matrix.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                                        matrix.postScale(finalScale, finalScale)
                                        matrix.postTranslate(outputSize / 2f + offsetX, outputSize / 2f + offsetY)

                                        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
                                        canvas.drawBitmap(bmp, matrix, paint)

                                        val outputStream = ByteArrayOutputStream()
                                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                                        val bytes = outputStream.toByteArray()

                                        withContext(Dispatchers.Main) {
                                            onCropSuccess(bytes)
                                        }
                                    } catch (_: Exception) {
                                        withContext(Dispatchers.Main) {
                                            onDismiss()
                                        }
                                    } finally {
                                        withContext(Dispatchers.Main) {
                                            isProcessingCrop = false
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && sourceBitmap != null && !isProcessingCrop
                    ) {
                        if (isProcessingCrop) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Pangkas & Gunakan")
                        }
                    }
                }
            }
        }
    }
}
