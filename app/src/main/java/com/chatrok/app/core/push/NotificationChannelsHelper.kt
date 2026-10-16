package com.chatrok.app.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Helper untuk menginisialisasi Android Notification Channels resmi pada Application.onCreate().
 *
 * Saluran:
 * 1. chatrok-message-activity: untuk pesan chat (normal priority, PRIVATE visibility di lockscreen).
 * 2. chatrok-calls: untuk panggilan masuk (high priority, PUBLIC visibility, sound & vibrate).
 */
object NotificationChannelsHelper {

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

            // 1. Channel Aktivitas Pesan (Message Activity)
            val messageChannel = NotificationChannel(
                PushConstants.CHANNEL_MESSAGE_ACTIVITY,
                "Aktivitas Pesan",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi aktivitas pesan chat SIMNI-C"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                setShowBadge(true)
            }

            // 2. Channel Panggilan Masuk (Incoming Calls)
            val callsChannel = NotificationChannel(
                PushConstants.CHANNEL_CALLS,
                "Panggilan",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi panggilan suara dan video"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                enableVibration(true)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(messageChannel, callsChannel))
        }
    }
}
