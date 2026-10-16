package com.chatrok.app.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chatrok.app.MainActivity
import com.chatrok.app.R
import com.chatrok.app.core.push.PushConstants
import com.chatrok.app.core.push.PushRouteAction
import com.chatrok.app.domain.repository.PushTokenRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Service Android Native resmi untuk menangani push notification Firebase Cloud Messaging (FCM).
 *
 * Menegakkan aturan invariant privasi SIMNI-C:
 * - Notifikasi pesan WAJIB disamarkan (strict cloaking):
 *   Judul: "SIMNI ADMINISTRASI KELAS"
 *   Isi: "Database telah siap, segera lakukan backup"
 * - Tanpa bocoran plaintext atau nama pengirim di lockscreen (VISIBILITY_PRIVATE).
 * - Dengan Milestone 7 ditangguhkan, panggilan ditangani secara aman tanpa memicu crash mesin panggilan.
 */
@AndroidEntryPoint
class ChatrokFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushTokenRepository: PushTokenRepository

    @Inject
    lateinit var auth: FirebaseAuth

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "Token FCM baru diterima, menyinkronkan ke repositori")
        serviceScope.launch {
            try {
                if (auth.currentUser != null) {
                    pushTokenRepository.registerCurrentToken(token)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gagal memperbarui token FCM di Firestore", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d(TAG, "Pesan FCM tanpa data diterima, diabaikan")
            return
        }

        try {
            val routeAction = PushConstants.parseNotificationRouting(data)
            showNotification(applicationContext, routeAction)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memproses pesan FCM", e)
        }
    }

    companion object {
        private const val TAG = "ChatrokFCM"
        private val notificationIdGenerator = AtomicInteger(1000)

        fun showNotification(context: Context, routeAction: PushRouteAction) {
            val prefs = context.getSharedPreferences(
                com.chatrok.app.data.notification.PersistentNotificationRepository.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val notificationsEnabled = prefs.getBoolean(
                com.chatrok.app.data.notification.PersistentNotificationRepository.KEY_NOTIFICATIONS_ENABLED,
                true
            )
            if (!notificationsEnabled) {
                Log.d(TAG, "Notifikasi dinonaktifkan oleh pengguna melalui preferensi aplikasi")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "Izin POST_NOTIFICATIONS belum diberikan, notifikasi dibatalkan")
                    return
                }
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                when (routeAction) {
                    is PushRouteAction.OpenConversation -> {
                        putExtra(PushConstants.EXTRA_ROUTE, "chat")
                        putExtra(PushConstants.EXTRA_CONVERSATION_ID, routeAction.conversationId)
                    }
                    is PushRouteAction.OpenInbox -> {
                        putExtra(PushConstants.EXTRA_ROUTE, "inbox")
                    }
                    is PushRouteAction.IncomingCall -> {
                        // Milestone 7 ditangguhkan: route secara aman ke inbox/dashboard
                        putExtra(PushConstants.EXTRA_ROUTE, "inbox")
                        putExtra(PushConstants.EXTRA_CALLER_UID, routeAction.callerUid)
                        putExtra(PushConstants.EXTRA_CALL_TYPE, routeAction.callType)
                    }
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationIdGenerator.incrementAndGet(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Strict Cloaked Notification untuk privasi mutlak
            val notification = NotificationCompat.Builder(context, PushConstants.CHANNEL_MESSAGE_ACTIVITY)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(PushConstants.CLOAKED_TITLE)
                .setContentText(PushConstants.CLOAKED_BODY)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            val notificationId = when (routeAction) {
                is PushRouteAction.OpenConversation -> routeAction.conversationId.hashCode()
                else -> notificationIdGenerator.incrementAndGet()
            }

            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}
