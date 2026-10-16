package com.chatrok.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.chatrok.app.core.model.Attachment
import com.chatrok.app.core.model.AttachmentType
import com.chatrok.app.core.model.CallType
import com.chatrok.app.core.navigation.Screen
import com.chatrok.app.feature.auth.AuthViewModel
import com.chatrok.app.feature.auth.LoginScreen
import com.chatrok.app.feature.auth.RegisterScreen
import com.chatrok.app.feature.auth.SplashScreen
import com.chatrok.app.feature.call.CallScreen
import com.chatrok.app.feature.call.CallViewModel
import com.chatrok.app.feature.chat.ChatScreen
import com.chatrok.app.feature.chat.ChatViewModel
import com.chatrok.app.feature.inbox.CallHistoryScreen
import com.chatrok.app.feature.inbox.InboxScreen
import com.chatrok.app.feature.inbox.InboxViewModel
import com.chatrok.app.feature.inbox.NewChatScreen
import com.chatrok.app.feature.media.MediaPreviewScreen
import com.chatrok.app.core.designsystem.theme.ThemeMode
import com.chatrok.app.feature.dashboard.DashboardScreen
import com.chatrok.app.feature.profile.EditProfileScreen
import com.chatrok.app.feature.profile.ProfileViewModel
import com.chatrok.app.feature.profile.SecurityScreen
import com.chatrok.app.feature.profile.UserProfileScreen
import com.chatrok.app.feature.settings.SettingsScreen
import com.chatrok.app.feature.settings.ThemeViewModel

@Composable
fun ChatrokNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    initialConversationId: String? = null
) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val inboxViewModel: InboxViewModel = hiltViewModel()
    val callViewModel: CallViewModel = hiltViewModel()
    val profileViewModel: ProfileViewModel = hiltViewModel()
    val themeViewModel: ThemeViewModel = hiltViewModel()

    val currentCall by callViewModel.currentCall.collectAsStateWithLifecycle()
    val sessionState by authViewModel.sessionBootstrapState.collectAsStateWithLifecycle()
    val currentThemeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(initialConversationId) {
        if (!initialConversationId.isNullOrBlank()) {
            navController.navigate(Screen.Chat.createRoute(initialConversationId, ""))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Splash.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    sessionState = sessionState,
                    onNavigateNext = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    viewModel = authViewModel,
                    onLoginSuccess = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = {
                        navController.navigate(Screen.Register.route)
                    }
                )
            }

            composable(Screen.Register.route) {
                RegisterScreen(
                    viewModel = authViewModel,
                    onRegisterSuccess = {
                        navController.navigate(Screen.Dashboard.route) {
                            popUpTo(Screen.Register.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    themeMode = currentThemeMode,
                    onToggleTheme = {
                        val nextMode = if (currentThemeMode == ThemeMode.DARK) {
                            ThemeMode.LIGHT
                        } else {
                            ThemeMode.DARK
                        }
                        themeViewModel.setThemeMode(nextMode)
                    },
                    onEnterChatrok = {
                        navController.navigate(Screen.Inbox.route)
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(Screen.Inbox.route) {
                InboxScreen(
                    viewModel = inboxViewModel,
                    onConversationClick = { convId, otherUserId ->
                        navController.navigate(Screen.Chat.createRoute(convId, otherUserId))
                    },
                    onNewChatClick = {
                        navController.navigate(Screen.NewChat.route)
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onCallHistoryClick = {
                        navController.navigate(Screen.CallHistory.route)
                    }
                )
            }

            composable(Screen.NewChat.route) {
                NewChatScreen(
                    viewModel = inboxViewModel,
                    onUserSelected = { convId, userId ->
                        navController.navigate(Screen.Chat.createRoute(convId, userId)) {
                            popUpTo(Screen.NewChat.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.Chat.route,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.StringType },
                    navArgument("userId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val chatViewModel: ChatViewModel = hiltViewModel(backStackEntry)

                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onViewProfile = { userId ->
                        navController.navigate(Screen.UserProfile.createRoute(userId))
                    },
                    onViewSecurity = { userId ->
                        navController.navigate(Screen.Security.createRoute(userId))
                    },
                    onMediaClick = { title, type ->
                        navController.navigate(Screen.MediaPreview.createRoute(title, type))
                    },
                    onLaunchCamera = {
                        val cameraAttachment = Attachment(
                            id = "cam_" + System.currentTimeMillis(),
                            type = AttachmentType.IMAGE,
                            title = "Foto_Kamera.jpg",
                            sizeBytes = 1890000,
                            mimeType = "image/jpeg"
                        )
                        chatViewModel.sendAttachment(cameraAttachment, "Foto kamera")
                    }
                )
            }

            composable(
                route = Screen.UserProfile.route,
                arguments = listOf(
                    navArgument("userId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                UserProfileScreen(
                    userId = userId,
                    viewModel = profileViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onStartChat = { targetUserId ->
                        inboxViewModel.createOrOpenConversation(targetUserId) { convId ->
                            navController.navigate(Screen.Chat.createRoute(convId, targetUserId))
                        }
                    },
                    onStartCall = { targetUserId, callType ->
                        callViewModel.simulateIncomingCall(targetUserId, callType)
                    },
                    onViewSecurity = { targetUserId ->
                        navController.navigate(Screen.Security.createRoute(targetUserId))
                    }
                )
            }

            composable(Screen.EditProfile.route) {
                EditProfileScreen(
                    viewModel = profileViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Security.route,
                arguments = listOf(
                    navArgument("userId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                SecurityScreen(
                    userId = userId,
                    viewModel = profileViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    authViewModel = authViewModel,
                    themeViewModel = themeViewModel,
                    onEditProfileClick = {
                        navController.navigate(Screen.EditProfile.route)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.CallHistory.route) {
                CallHistoryScreen(
                    viewModel = inboxViewModel,
                    onStartCall = { userId, type ->
                        callViewModel.simulateIncomingCall(userId, type)
                    },
                    onSimulateIncomingCall = { userId, type ->
                        callViewModel.simulateIncomingCall(userId, type)
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.MediaPreview.route,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType },
                    navArgument("type") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val title = backStackEntry.arguments?.getString("title") ?: "Pratinjau Media"
                val type = backStackEntry.arguments?.getString("type") ?: "IMAGE"
                MediaPreviewScreen(
                    title = title,
                    type = type,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // Active Call Overlay (Voice & Video Call Screens)
        AnimatedVisibility(
            visible = currentCall != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            CallScreen(viewModel = callViewModel)
        }
    }
}

