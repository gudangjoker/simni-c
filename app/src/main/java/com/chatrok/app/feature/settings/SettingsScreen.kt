package com.chatrok.app.feature.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chatrok.app.BuildConfig
import com.chatrok.app.core.designsystem.component.ChatrokAvatar
import com.chatrok.app.core.designsystem.theme.ThemeMode
import com.chatrok.app.feature.auth.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    themeViewModel: ThemeViewModel,
    onEditProfileClick: () -> Unit,
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
    val currentThemeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()

    val prefs = remember { context.getSharedPreferences("simni_settings_prefs", Context.MODE_PRIVATE) }

    var notificationsSound by remember { mutableStateOf(prefs.getBoolean("notifications_sound", true)) }
    var notificationsVibrate by remember { mutableStateOf(prefs.getBoolean("notifications_vibrate", true)) }
    var readReceipts by remember { mutableStateOf(prefs.getBoolean("read_receipts", true)) }
    var typingIndicators by remember { mutableStateOf(prefs.getBoolean("typing_indicators", true)) }
    var screenLockEnabled by remember { mutableStateOf(prefs.getBoolean("screen_lock_enabled", false)) }
    var enterIsSend by remember { mutableStateOf(prefs.getBoolean("enter_is_send", false)) }

    var cacheSizeBytes by remember { mutableLongStateOf(0L) }
    var isClearingCache by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    // Hitung ukuran riil folder cache pada latar belakang
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSizeBytes = calculateCacheSize(context)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Profile Card
            val user = currentUser
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditProfileClick() }
                    .testTag("settings_profile_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChatrokAvatar(name = user?.displayName ?: "Pengguna", size = 60.dp, avatarUrl = user?.avatarUrl)
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.displayName ?: "Pengguna Terdaftar",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "@${user?.username ?: "user"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!user?.phoneNumber.isNullOrBlank()) {
                            Text(
                                text = user?.phoneNumber ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Profil",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Tampilan & Obrolan
            SectionHeader(title = "Tampilan & Obrolan")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    // Pilihan Tema
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showThemeDialog = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tema Aplikasi", style = MaterialTheme.typography.titleSmall)
                            val themeSubtitle = when (currentThemeMode) {
                                ThemeMode.SYSTEM -> "Mengikuti Sistem Ponsel"
                                ThemeMode.LIGHT -> "Terang"
                                ThemeMode.DARK -> "Gelap (True Black OLED)"
                            }
                            Text(themeSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                    // Tekan Enter untuk Mengirim
                    SettingSwitchRow(
                        title = "Tekan Enter untuk Mengirim",
                        subtitle = "Tombol Enter pada keyboard akan langsung mengirim pesan",
                        checked = enterIsSend,
                        onCheckedChange = {
                            enterIsSend = it
                            prefs.edit().putBoolean("enter_is_send", it).apply()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Notifications Section
            SectionHeader(title = "Notifikasi & Nada")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingSwitchRow(
                        title = "Suara Pesan",
                        subtitle = "Putar suara saat ada pesan baru masuk",
                        checked = notificationsSound,
                        onCheckedChange = {
                            notificationsSound = it
                            prefs.edit().putBoolean("notifications_sound", it).apply()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingSwitchRow(
                        title = "Getaran",
                        subtitle = "Getar untuk panggilan dan pesan",
                        checked = notificationsVibrate,
                        onCheckedChange = {
                            notificationsVibrate = it
                            prefs.edit().putBoolean("notifications_vibrate", it).apply()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Privacy & E2EE Section
            SectionHeader(title = "Privasi & Enkripsi")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SettingSwitchRow(
                        title = "Laporan Dibaca",
                        subtitle = "Izinkan kontak melihat tanda centang biru saat Anda membaca pesan",
                        checked = readReceipts,
                        onCheckedChange = {
                            readReceipts = it
                            prefs.edit().putBoolean("read_receipts", it).apply()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingSwitchRow(
                        title = "Indikator Mengetik",
                        subtitle = "Tampilkan status sedang mengetik saat menulis pesan",
                        checked = typingIndicators,
                        onCheckedChange = {
                            typingIndicators = it
                            prefs.edit().putBoolean("typing_indicators", it).apply()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    SettingSwitchRow(
                        title = "Kunci Aplikasi",
                        subtitle = "Wajibkan biometrik atau PIN untuk membuka SIMNI-C",
                        checked = screenLockEnabled,
                        onCheckedChange = {
                            screenLockEnabled = it
                            prefs.edit().putBoolean("screen_lock_enabled", it).apply()
                            Toast.makeText(
                                context,
                                if (it) "Kunci aplikasi diaktifkan (memerlukan PIN/Biometrik)" else "Kunci aplikasi dinonaktifkan",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Storage & Data
            SectionHeader(title = "Data & Penyimpanan")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    // Pembersihan Cache Riil
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isClearingCache) {
                                isClearingCache = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                                        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                                    } catch (_: Exception) {}
                                    cacheSizeBytes = calculateCacheSize(context)
                                    withContext(Dispatchers.Main) {
                                        isClearingCache = false
                                        Toast.makeText(context, "Cache media berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 8.dp)
                            .testTag("clear_cache_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bersihkan Cache Media", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "${formatCacheSize(cacheSizeBytes)} tersimpan di penyimpanan lokal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

                    // Pembersihan Database Riil
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        val dbList = context.databaseList()
                                        dbList?.forEach { name ->
                                            if (name.contains("chatrok") || name.contains("simni")) {
                                                context.deleteDatabase(name)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Database lokal dan cache percakapan berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .padding(vertical = 8.dp)
                            .testTag("clear_database_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bersihkan Database & Cache Percakapan", style = MaterialTheme.typography.titleSmall)
                            Text("Kosongkan cache lokal untuk sinkron ulang bersih", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // About SIMNI-C Section
            SectionHeader(title = "Tentang SIMNI-C")
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "SIMNI-C Private Messenger v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Aplikasi komunikasi pesan instan 1-ke-1 terenkripsi ujung-ke-ujung (E2EE) SIMNI-C.\nKunci kriptografi disimpan secara aman di perangkat lokal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Logout Action
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showLogoutDialog = true }
                    .testTag("settings_logout_button")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Keluar dari Akun",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Dialog Pemilihan Tema (3 Pilihan: Terang, Gelap True Black, Sistem)
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Pilih Tema Aplikasi", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    ThemeOptionRow(
                        title = "Mengikuti Sistem Ponsel",
                        selected = currentThemeMode == ThemeMode.SYSTEM,
                        onClick = {
                            themeViewModel.setThemeMode(ThemeMode.SYSTEM)
                            showThemeDialog = false
                        }
                    )
                    ThemeOptionRow(
                        title = "Terang",
                        selected = currentThemeMode == ThemeMode.LIGHT,
                        onClick = {
                            themeViewModel.setThemeMode(ThemeMode.LIGHT)
                            showThemeDialog = false
                        }
                    )
                    ThemeOptionRow(
                        title = "Gelap (True Black OLED)",
                        selected = currentThemeMode == ThemeMode.DARK,
                        onClick = {
                            themeViewModel.setThemeMode(ThemeMode.DARK)
                            showThemeDialog = false
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Tutup")
                }
            }
        )
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Konfirmasi Keluar") },
            text = { Text("Apakah Anda yakin ingin keluar dari akun SIMNI-C?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        authViewModel.logout()
                        onLoggedOut()
                    }
                ) {
                    Text("Keluar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun calculateCacheSize(context: Context): Long {
    var size = 0L
    try {
        context.cacheDir?.let { size += calculateFolderSize(it) }
        context.externalCacheDir?.let { size += calculateFolderSize(it) }
    } catch (_: Exception) {}
    return size
}

private fun calculateFolderSize(file: File): Long {
    var size = 0L
    try {
        file.listFiles()?.forEach { child ->
            size += if (child.isDirectory) calculateFolderSize(child) else child.length()
        }
    } catch (_: Exception) {}
    return size
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes <= 0L -> "0 KB"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }
}
