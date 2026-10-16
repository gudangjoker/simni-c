package com.chatrok.app.feature.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatrok.app.R
import com.chatrok.app.core.designsystem.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Dashboard pelapis SIMNI-C.
 *
 * SECURITY INVARIANT:
 * - Screen ini tidak boleh menginisialisasi Firebase/Auth/Firestore/E2EE.
 * - Screen ini hanya menerima callback navigasi.
 * - Jembatan CHATROK adalah kartu visual "Scan Presensi".
 * - Session/bootstrap security tetap dimiliki layer Auth/Security repository.
 */
@Composable
fun DashboardScreen(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onEnterChatrok: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailableMessage = stringResource(R.string.dashboard_feature_unavailable)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        modifier = modifier.fillMaxSize()
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFEFFBF8),
                            Color(0xFFF7FCFA),
                            Color(0xFFF2FAF7)
                        )
                    )
                )
                .padding(scaffoldPadding)
        ) {
            DashboardTopBar(
                themeMode = themeMode,
                onToggleTheme = onToggleTheme,
                onSettingsClick = onSettingsClick
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(26.dp))

                Text(
                    text = stringResource(R.string.dashboard_title),
                    color = Color(0xFF1E2D42),
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.dashboard_school_year),
                    color = Color(0xFF68778C),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal
                )

                Spacer(Modifier.height(28.dp))

                Surface(
                    color = Color.White.copy(alpha = 0.93f),
                    shape = RoundedCornerShape(15.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD0D7DF)),
                    modifier = Modifier.height(66.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 22.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_synced),
                            color = Color(0xFF364457),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(36.dp))

                SummaryGrid()

                Spacer(Modifier.height(34.dp))

                Text(
                    text = stringResource(R.string.dashboard_quick_actions),
                    color = Color(0xFF213047),
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold
                )

                Spacer(Modifier.height(18.dp))

                QuickActionsGrid(
                    onScanPresensi = onEnterChatrok,
                    onPlaceholder = {
                        scope.launch { snackbarHostState.showSnackbar(unavailableMessage) }
                    }
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun DashboardTopBar(
    themeMode: ThemeMode,
    onToggleTheme: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(88.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Surface(
                    color = Color(0xFFE5F6F2),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("dashboard_menu_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = stringResource(R.string.dashboard_menu),
                            tint = Color(0xFF17847D),
                            modifier = Modifier.size(27.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dashboard_menu_dashboard)) },
                        onClick = { menuExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dashboard_menu_settings)) },
                        onClick = {
                            menuExpanded = false
                            onSettingsClick()
                        }
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.size(46.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.simni_c_logo),
                    contentDescription = stringResource(R.string.simni_c_logo_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(3.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = stringResource(R.string.dashboard_brand),
                color = Color(0xFF1E293B),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )

            Surface(
                color = Color(0xFFF1F4F8),
                shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8DEE8)),
                modifier = Modifier.size(56.dp)
            ) {
                IconButton(
                    onClick = onToggleTheme,
                    modifier = Modifier.testTag("dashboard_theme_button")
                ) {
                    Icon(
                        imageVector = if (themeMode == ThemeMode.DARK) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = stringResource(R.string.dashboard_toggle_theme),
                        tint = if (themeMode == ThemeMode.DARK) Color(0xFFF59E0B) else Color(0xFF506079),
                        modifier = Modifier.size(25.dp)
                    )
                }
            }

            Spacer(Modifier.width(18.dp))

            Surface(
                color = Color(0xFF16B98A),
                shape = CircleShape,
                modifier = Modifier.size(12.dp)
            ) {}
        }
    }
}

@Composable
private fun SummaryGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SummaryCard(
                title = stringResource(R.string.dashboard_total_students),
                value = "22",
                icon = Icons.Default.Groups,
                iconTint = Color(0xFF0B69E8),
                iconBg = Color(0xFFEAF2FF),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = stringResource(R.string.dashboard_present_today),
                value = "0",
                icon = Icons.Default.HowToReg,
                iconTint = Color(0xFF059669),
                iconBg = Color(0xFFE8FAF4),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SummaryCard(
                title = stringResource(R.string.dashboard_learning_goals),
                value = "13",
                icon = Icons.Default.Star,
                iconTint = Color(0xFFEA7A00),
                iconBg = Color(0xFFFFF8E7),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = stringResource(R.string.dashboard_teaching_journal),
                value = "7",
                icon = Icons.Default.MenuBook,
                iconTint = Color(0xFF9818E6),
                iconBg = Color(0xFFF7ECFF),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.White.copy(alpha = 0.98f),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp,
        modifier = modifier.height(176.dp)
    ) {
        Column(
            modifier = Modifier.padding(26.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = title,
                    color = Color(0xFF6B7A90),
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = iconBg,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(27.dp)
                        )
                    }
                }
            }

            Text(
                text = value,
                color = Color(0xFF0F172A),
                fontSize = 46.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    onScanPresensi: () -> Unit,
    onPlaceholder: () -> Unit
) {
    val actions = listOf(
        QuickAction(
            label = stringResource(R.string.dashboard_scan_attendance),
            icon = Icons.Default.QrCodeScanner,
            iconTint = Color.White,
            iconBg = Color.White.copy(alpha = 0.20f),
            emphasized = true,
            onClick = onScanPresensi,
            testTag = "scan_presensi_chatrok_bridge"
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_input_score),
            icon = Icons.Default.Star,
            iconTint = Color(0xFF06B98C),
            iconBg = Color(0xFFE8FBF5),
            onClick = onPlaceholder
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_fill_journal),
            icon = Icons.Default.MenuBook,
            iconTint = Color(0xFF9818E6),
            iconBg = Color(0xFFF7ECFF),
            onClick = onPlaceholder
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_gadm),
            icon = Icons.Default.AutoAwesome,
            iconTint = Color(0xFF147D76),
            iconBg = Color(0xFFE5F7F4),
            onClick = onPlaceholder
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_teacher_notes),
            icon = Icons.Default.Checklist,
            iconTint = Color(0xFFFFA500),
            iconBg = Color(0xFFFFF7E0),
            onClick = onPlaceholder
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_files),
            icon = Icons.Default.Folder,
            iconTint = Color(0xFF2786F1),
            iconBg = Color(0xFFEAF3FF),
            onClick = onPlaceholder
        ),
        QuickAction(
            label = stringResource(R.string.dashboard_add_student),
            icon = Icons.Default.PersonAdd,
            iconTint = Color(0xFFFF1E5A),
            iconBg = Color(0xFFFFEAF0),
            onClick = onPlaceholder
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        actions.chunked(2).forEach { rowActions ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowActions.forEach { action ->
                    QuickActionCard(action = action, modifier = Modifier.weight(1f))
                }
                if (rowActions.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val iconTint: Color,
    val iconBg: Color,
    val emphasized: Boolean = false,
    val onClick: () -> Unit,
    val testTag: String? = null
)

@Composable
private fun QuickActionCard(
    action: QuickAction,
    modifier: Modifier = Modifier
) {
    val bg = if (action.emphasized) Color(0xFF16857C) else Color.White
    val textColor = if (action.emphasized) Color.White else Color(0xFF344257)

    Surface(
        color = bg,
        shape = RoundedCornerShape(22.dp),
        shadowElevation = if (action.emphasized) 6.dp else 4.dp,
        modifier = modifier
            .height(160.dp)
            .then(if (action.testTag != null) Modifier.testTag(action.testTag) else Modifier)
            .clickable(onClick = action.onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                color = action.iconBg,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = action.iconTint,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = action.label,
                color = textColor,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}
