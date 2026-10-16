package com.chatrok.app.data.notification

import android.content.Context
import com.chatrok.app.domain.repository.NotificationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementasi persisten untuk [NotificationRepository] menggunakan SharedPreferences.
 * Menjamin status preferensi notifikasi tersimpan secara lokal dan persisten lintas restart aplikasi.
 */
@Singleton
class PersistentNotificationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : NotificationRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _notificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
    )
    override val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    companion object {
        const val PREFS_NAME = "chatrok_notification_prefs"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    }
}
