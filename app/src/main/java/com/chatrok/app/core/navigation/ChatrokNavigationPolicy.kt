package com.chatrok.app.core.navigation

import com.chatrok.app.core.model.SessionBootstrapState

/**
 * Kebijakan Navigasi Terpusat untuk Pengetatan Gated Session CHATROK (B6-10).
 *
 * ATURAN KEAMANAN:
 * 1. Hanya SessionBootstrapState.Ready(user) yang diizinkan membuka Dashboard dan Inbox.
 * 2. Unauthenticated, Loading, dan Error TIDAK BOLEH membuka Dashboard atau Inbox.
 * 3. Login/Register Result.success tidak cukup sendirian; wajib memeriksa sessionBootstrapState.
 * 4. Scan Presensi pada Dashboard bertindak murni sebagai jembatan navigasi ke Inbox hanya jika session Ready.
 * 5. Ketukan berulang (repeated tap) dicegah agar tidak menduplikasi rute atau bootstrap.
 * 6. Jika session drop menjadi Unauthenticated atau Error saat berada di protected route,
 *    seluruh rute terlindungi langsung dievつい (purged) ke rute Login.
 */
object ChatrokNavigationPolicy {

    val PROTECTED_ROUTES = setOf(
        Screen.Dashboard.route,
        Screen.Inbox.route,
        Screen.NewChat.route,
        Screen.EditProfile.route,
        Screen.Settings.route,
        Screen.CallHistory.route,
        Screen.Chat.route,
        Screen.UserProfile.route,
        Screen.Security.route,
        Screen.MediaPreview.route
    )

    fun isProtectedRoute(route: String?): Boolean {
        if (route == null) return false
        return PROTECTED_ROUTES.any { protectedRoute ->
            if (protectedRoute.contains("{")) {
                val prefix = protectedRoute.substringBefore("{")
                route.startsWith(prefix)
            } else {
                route == protectedRoute
            }
        }
    }

    fun resolveSplashDestination(sessionState: SessionBootstrapState): Screen? {
        return when (sessionState) {
            is SessionBootstrapState.Loading -> null // Tetap di Splash/loading
            is SessionBootstrapState.Ready -> Screen.Dashboard // Hanya Ready yang membuka Dashboard
            is SessionBootstrapState.Unauthenticated -> Screen.Login
            is SessionBootstrapState.Error -> Screen.Login // Error dilarang ke Dashboard, alihkan ke safe auth path
        }
    }

    fun canNavigateToDashboardOnAuthSuccess(
        uiSuccess: Boolean,
        sessionState: SessionBootstrapState
    ): Boolean {
        return uiSuccess && sessionState is SessionBootstrapState.Ready
    }

    fun canEnterChatrokFromDashboard(
        currentRoute: String?,
        sessionState: SessionBootstrapState
    ): Boolean {
        // Hanya boleh navigasi jika saat ini persis berada di Dashboard dan session Ready
        val isOnDashboard = currentRoute == Screen.Dashboard.route
        val isSessionReady = sessionState is SessionBootstrapState.Ready
        return isOnDashboard && isSessionReady
    }

    fun shouldEvictProtectedRoutes(
        currentRoute: String?,
        sessionState: SessionBootstrapState
    ): Boolean {
        val isProtected = isProtectedRoute(currentRoute)
        val isUnsafeSession = sessionState is SessionBootstrapState.Unauthenticated ||
                sessionState is SessionBootstrapState.Error
        return isProtected && isUnsafeSession
    }
}
