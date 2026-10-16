package com.chatrok.app.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

data class MediaPolicy(
    val type: String,
    val maxSourceBytes: Long,
    val maxUploadBytes: Long,
    val ttlMs: Long,
    val maxDimension: Int = 2560
)

object ChatMediaPolicy {
    val IMAGE = MediaPolicy("image", 25L * 1024 * 1024, 8L * 1024 * 1024, 5L * 24 * 60 * 60 * 1000, 2560)
    val AUDIO = MediaPolicy("audio", 8L * 1024 * 1024, 8L * 1024 * 1024, 5L * 24 * 60 * 60 * 1000)
    val FILE = MediaPolicy("file", 8L * 1024 * 1024, 8L * 1024 * 1024, 5L * 24 * 60 * 60 * 1000)
    val VIDEO = MediaPolicy("video", 50L * 1024 * 1024, 8L * 1024 * 1024, 3L * 24 * 60 * 60 * 1000)

    const val SMALL_IMAGE_BYPASS_BYTES = 300 * 1024L // 300 KB

    fun getPolicy(wireType: String): MediaPolicy = when (wireType.lowercase()) {
        "image" -> IMAGE
        "audio" -> AUDIO
        "video" -> VIDEO
        else -> FILE
    }

    fun sanitizeFilename(value: String?): String {
        val text = (value ?: "file")
            .replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.take(180).ifEmpty { "file" }
    }

    fun isScreenshotOrDocumentImage(filename: String, mime: String, width: Int, height: Int): Boolean {
        val lower = filename.lowercase()
        if (Regex("screenshot|screen_shot|screen-shot|snip|capture|tangkapan_layar|dokumen|struk|nota|receipt", RegexOption.IGNORE_CASE).containsMatchIn(lower)) {
            return true
        }
        if (mime.lowercase() == "image/png") return true
        if (width > 0 && height > 0) {
            val ratio = kotlin.math.max(width, height).toFloat() / kotlin.math.min(width, height).toFloat()
            if (kotlin.math.abs(ratio - 19.5f / 9f) < 0.03f ||
                kotlin.math.abs(ratio - 20f / 9f) < 0.03f ||
                kotlin.math.abs(ratio - 20.5f / 9f) < 0.03f ||
                kotlin.math.abs(ratio - 21f / 9f) < 0.03f
            ) {
                if (width >= 1080 || height >= 1080) return true
            }
        }
        return false
    }
}

data class PreparedMedia(
    val bytes: ByteArray,
    val mimeType: String,
    val filename: String,
    val originalSize: Long,
    val sizeBytes: Long,
    val wireType: String,
    val policy: MediaPolicy,
    val durationSeconds: Int? = null,
    val width: Int? = null,
    val height: Int? = null
)

object ChatMediaCompressor {

    /**
     * Mempersiapkan media file (kompresi adaptif untuk gambar, validasi ukuran Worker 8 MiB)
     */
    fun prepareMedia(
        rawBytes: ByteArray,
        filename: String,
        mimeType: String,
        wireType: String,
        durationSeconds: Int? = null
    ): PreparedMedia {
        val cleanName = ChatMediaPolicy.sanitizeFilename(filename)
        val policy = ChatMediaPolicy.getPolicy(wireType)

        require(rawBytes.size <= policy.maxSourceBytes) {
            "Ukuran media (${rawBytes.size / (1024 * 1024)} MiB) melebihi batas sumber (${policy.maxSourceBytes / (1024 * 1024)} MiB)."
        }

        var processedBytes = rawBytes
        var finalMime = mimeType
        var width: Int? = null
        var height: Int? = null

        if (wireType == "image") {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
                val w = opts.outWidth
                val h = opts.outHeight
                if (w > 0 && h > 0) {
                    width = w
                    height = h
                    val isSmall = rawBytes.size <= ChatMediaPolicy.SMALL_IMAGE_BYPASS_BYTES
                    val isScreenshot = ChatMediaPolicy.isScreenshotOrDocumentImage(cleanName, mimeType, w, h)
                    val quality = if (isScreenshot) 92 else 85

                    // Kompresi jika bukan small bypass atau dimensi melebihi batas 2560px
                    if (!isSmall || w > policy.maxDimension || h > policy.maxDimension) {
                        var sampleSize = 1
                        while (w / sampleSize > policy.maxDimension || h / sampleSize > policy.maxDimension) {
                            sampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                        if (bitmap != null) {
                            val baos = ByteArrayOutputStream()
                            val compressFormat = if (mimeType.contains("png") && isScreenshot) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            bitmap.compress(compressFormat, quality, baos)
                            val compressed = baos.toByteArray()
                            if (compressed.size < rawBytes.size || rawBytes.size > policy.maxUploadBytes) {
                                processedBytes = compressed
                                finalMime = if (compressFormat == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
                                width = bitmap.width
                                height = bitmap.height
                            }
                            bitmap.recycle()
                        }
                    }
                }
            } catch (_: Exception) {
                // Gunakan rawBytes jika decode bitmap gagal (e.g. format khusus)
            }
        }

        require(processedBytes.size <= policy.maxUploadBytes) {
            "Ciphertext/media melebihi batas Cloudflare Worker (8 MiB)."
        }

        return PreparedMedia(
            bytes = processedBytes,
            mimeType = finalMime,
            filename = cleanName,
            originalSize = rawBytes.size.toLong(),
            sizeBytes = processedBytes.size.toLong(),
            wireType = wireType,
            policy = policy,
            durationSeconds = durationSeconds,
            width = width,
            height = height
        )
    }
}
