package com.chatrok.app

import com.chatrok.app.core.common.DateTimeUtils
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DateTimeUtilsTest {

    @Test
    fun `zona waktu aplikasi adalah Asia Jakarta`() {
        assertEquals(ZoneId.of("Asia/Jakarta"), DateTimeUtils.APP_ZONE_ID)
    }

    @Test
    fun `format jam menggunakan 24 jam`() {
        val instant = LocalDateTime.of(2026, 9, 18, 14, 35)
            .atZone(ZoneId.of("Asia/Jakarta"))
            .toInstant()
            .toEpochMilli()
        assertEquals("14:35", DateTimeUtils.formatTime(instant))
    }

    @Test
    fun `format pemisah tanggal menggunakan bahasa Indonesia`() {
        val instant = LocalDateTime.now(ZoneId.of("Asia/Jakarta"))
            .withHour(10)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .atZone(ZoneId.of("Asia/Jakarta"))
            .toInstant()
            .toEpochMilli()
        assertTrue(DateTimeUtils.formatDateSeparator(instant).isNotBlank())
    }
}
