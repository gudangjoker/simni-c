package com.chatrok.app.core.common

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeUtils {

    val APP_ZONE_ID: ZoneId = ZoneId.of("Asia/Jakarta")
    val INDONESIA_LOCALE: Locale = Locale("id", "ID")

    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", INDONESIA_LOCALE)
    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM", INDONESIA_LOCALE)
    private val FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", INDONESIA_LOCALE)

    fun toZonedDateTime(timestamp: Long): ZonedDateTime {
        return Instant.ofEpochMilli(timestamp).atZone(APP_ZONE_ID)
    }

    fun formatTime(timestamp: Long): String {
        return toZonedDateTime(timestamp).format(TIME_FORMATTER)
    }

    fun formatConversationDate(timestamp: Long): String {
        val messageZdt = toZonedDateTime(timestamp)
        val nowZdt = ZonedDateTime.now(APP_ZONE_ID)

        val messageDate = messageZdt.toLocalDate()
        val today = nowZdt.toLocalDate()

        return when {
            messageDate.isEqual(today) -> messageZdt.format(TIME_FORMATTER)
            messageDate.isEqual(today.minusDays(1)) -> "Kemarin"
            messageDate.year == today.year -> messageZdt.format(DATE_FORMATTER)
            else -> messageZdt.format(DateTimeFormatter.ofPattern("d MMM yyyy", INDONESIA_LOCALE))
        }
    }

    fun formatDateSeparator(timestamp: Long): String {
        val messageZdt = toZonedDateTime(timestamp)
        val nowZdt = ZonedDateTime.now(APP_ZONE_ID)

        val messageDate = messageZdt.toLocalDate()
        val today = nowZdt.toLocalDate()

        return when {
            messageDate.isEqual(today) -> "Hari ini"
            messageDate.isEqual(today.minusDays(1)) -> "Kemarin"
            else -> messageZdt.format(FULL_DATE_FORMATTER)
        }
    }

    fun formatDuration(seconds: Int): String {
        val d = Duration.ofSeconds(seconds.toLong())
        val mins = d.toMinutes()
        val secs = d.minusMinutes(mins).seconds
        return String.format(INDONESIA_LOCALE, "%02d:%02d", mins, secs)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(INDONESIA_LOCALE, "%.1f MB", bytes.toDouble() / (1024 * 1024))
        }
    }
}
