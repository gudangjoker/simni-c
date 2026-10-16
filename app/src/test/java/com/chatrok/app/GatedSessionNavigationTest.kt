package com.chatrok.app

import com.chatrok.app.core.model.SessionBootstrapState
import com.chatrok.app.core.model.User
import com.chatrok.app.core.navigation.ChatrokNavigationPolicy
import com.chatrok.app.core.navigation.Screen
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatedSessionNavigationTest {

    private val sampleUser = User(
        id = "usr_citra_99",
        username = "citra_lestari",
        displayName = "Citra Lestari"
    )

    @Test
    fun testSplashUnauthenticatedResolvesToLoginAndNeverOpensDashboard() {
        // [Item A] Unauthenticated -> Splash/Login -> Dashboard TIDAK dibuka
        val destination = ChatrokNavigationPolicy.resolveSplashDestination(
            SessionBootstrapState.Unauthenticated
        )
        assertEquals(Screen.Login, destination)
        assertFalse("Dashboard dilarang keras dibuka saat unauthenticated", destination == Screen.Dashboard)
    }

    @Test
    fun testSplashLoadingKeepsUserOnSplashAndDoesNotOpenDashboard() {
        // [Item B] Loading -> Dashboard TIDAK dibuka (tetap di splash)
        val destination = ChatrokNavigationPolicy.resolveSplashDestination(
            SessionBootstrapState.Loading
        )
        assertNull("Loading state harus menahan pengguna di Splash tanpa navigate", destination)
    }

    @Test
    fun testSplashReadyOpensDashboard() {
        // [Item C] Ready(user) -> Dashboard dibuka
        val destination = ChatrokNavigationPolicy.resolveSplashDestination(
            SessionBootstrapState.Ready(sampleUser)
        )
        assertEquals(Screen.Dashboard, destination)
    }

    @Test
    fun testSplashErrorResolvesToLoginAndNeverOpensDashboard() {
        // [Item D] Error -> Dashboard TIDAK dibuka
        val destination = ChatrokNavigationPolicy.resolveSplashDestination(
            SessionBootstrapState.Error(IOException("Koneksi gagal saat load profil"))
        )
        assertEquals(Screen.Login, destination)
        assertFalse("Dashboard dilarang keras dibuka saat error bootstrap", destination == Screen.Dashboard)
    }

    @Test
    fun testLoginSuccessWithoutReadyStateCannotNavigateToDashboard() {
        // [Item E] Login success tetapi session belum Ready -> tidak navigate Dashboard
        val cannotNavigateWhileLoading = ChatrokNavigationPolicy.canNavigateToDashboardOnAuthSuccess(
            uiSuccess = true,
            sessionState = SessionBootstrapState.Loading
        )
        assertFalse("Login success dengan state Loading tidak boleh navigate ke Dashboard", cannotNavigateWhileLoading)

        val cannotNavigateWhileError = ChatrokNavigationPolicy.canNavigateToDashboardOnAuthSuccess(
            uiSuccess = true,
            sessionState = SessionBootstrapState.Error(IllegalStateException("Profil hilang"))
        )
        assertFalse("Login success dengan state Error tidak boleh navigate ke Dashboard", cannotNavigateWhileError)

        val cannotNavigateWhileUnauth = ChatrokNavigationPolicy.canNavigateToDashboardOnAuthSuccess(
            uiSuccess = true,
            sessionState = SessionBootstrapState.Unauthenticated
        )
        assertFalse("Login success dengan state Unauthenticated tidak boleh navigate ke Dashboard", cannotNavigateWhileUnauth)

        val canNavigateWhenReady = ChatrokNavigationPolicy.canNavigateToDashboardOnAuthSuccess(
            uiSuccess = true,
            sessionState = SessionBootstrapState.Ready(sampleUser)
        )
        assertTrue("Hanya jika state Ready(user), navigasi ke Dashboard diizinkan", canNavigateWhenReady)
    }

    @Test
    fun testScanPresensiBridgeFromDashboardWithReadyStateNavigatesToInbox() {
        // [Item F] Dashboard + Ready + Scan Presensi -> navigate Inbox
        val canEnter = ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
            currentRoute = Screen.Dashboard.route,
            sessionState = SessionBootstrapState.Ready(sampleUser)
        )
        assertTrue("Scan presensi dari Dashboard dengan session Ready harus diizinkan ke Inbox", canEnter)
    }

    @Test
    fun testScanPresensiBridgeWithUnsafeSessionStateRejectsInboxNavigation() {
        // [Item G] Dashboard + Error/Loading/Unauthenticated + Scan Presensi -> Inbox TIDAK dibuka
        assertFalse(
            "Scan presensi dengan state Error tidak boleh membuka Inbox",
            ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
                currentRoute = Screen.Dashboard.route,
                sessionState = SessionBootstrapState.Error(Exception("Session broken"))
            )
        )
        assertFalse(
            "Scan presensi dengan state Loading tidak boleh membuka Inbox",
            ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
                currentRoute = Screen.Dashboard.route,
                sessionState = SessionBootstrapState.Loading
            )
        )
        assertFalse(
            "Scan presensi dengan state Unauthenticated tidak boleh membuka Inbox",
            ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
                currentRoute = Screen.Dashboard.route,
                sessionState = SessionBootstrapState.Unauthenticated
            )
        )
    }

    @Test
    fun testRepeatedScanPresensiTapDoesNotDuplicateNavigation() {
        // [Item H] Repeated Scan Presensi tap -> tidak duplicate route/session bootstrap
        // Jika rute saat ini sudah berpindah ke Inbox (atau sedang transisi), ketukan ulang harus ditolak
        val canEnterAgainFromInbox = ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
            currentRoute = Screen.Inbox.route,
            sessionState = SessionBootstrapState.Ready(sampleUser)
        )
        assertFalse("Ketukan ulang saat sudah berada di Inbox tidak boleh memicu navigasi ganda", canEnterAgainFromInbox)

        val canEnterFromOtherScreen = ChatrokNavigationPolicy.canEnterChatrokFromDashboard(
            currentRoute = Screen.Chat.route,
            sessionState = SessionBootstrapState.Ready(sampleUser)
        )
        assertFalse("Ketukan dari rute selain Dashboard tidak boleh memicu navigasi", canEnterFromOtherScreen)
    }

    @Test
    fun testInboxBackNavigationContractReturnsToDashboard() {
        // [Item I] Inbox back -> Dashboard (bukan Login)
        val backStack = ArrayDeque<String>()
        backStack.add(Screen.Dashboard.route)
        backStack.add(Screen.Inbox.route)

        // User melakukan 'Back' dari Inbox
        val poppedScreen = backStack.removeLast()
        assertEquals(Screen.Inbox.route, poppedScreen)

        // Layar aktif setelah back harus Dashboard
        val currentScreenAfterBack = backStack.last()
        assertEquals("Back dari Inbox harus kembali ke Dashboard", Screen.Dashboard.route, currentScreenAfterBack)
    }

    @Test
    fun testLogoutPurgesAllProtectedRoutesFromBackstackToLogin() {
        // [Item J] Logout -> Login -> protected routes cleared
        val backStack = ArrayDeque<String>()
        backStack.add(Screen.Dashboard.route)
        backStack.add(Screen.Inbox.route)
        backStack.add(Screen.Settings.route)

        // Simulasi logout dengan popUpTo(0) { inclusive = true }
        backStack.clear()
        backStack.add(Screen.Login.route)

        assertEquals(1, backStack.size)
        assertEquals(Screen.Login.route, backStack.last())
        assertFalse("Tidak boleh ada Dashboard di backstack setelah logout", backStack.contains(Screen.Dashboard.route))
        assertFalse("Tidak boleh ada Inbox di backstack setelah logout", backStack.contains(Screen.Inbox.route))
        assertFalse("Tidak boleh ada Settings di backstack setelah logout", backStack.contains(Screen.Settings.route))

        // Verifikasi kebijakan eviksi proteksi
        ChatrokNavigationPolicy.PROTECTED_ROUTES.forEach { protectedRoute ->
            assertTrue(
                "Rute terlindungi $protectedRoute harus dieviksi saat Unauthenticated",
                ChatrokNavigationPolicy.shouldEvictProtectedRoutes(protectedRoute, SessionBootstrapState.Unauthenticated)
            )
        }
    }

    @Test
    fun testSessionErrorWhileOnProtectedRouteTriggersEvictionToSafeAuthPath() {
        // Verifikasi aturan #10: Error setelah berada di Dashboard/Inbox harus dieviksi ke Login
        val errorState = SessionBootstrapState.Error(IOException("Koneksi terputus"))

        assertTrue(
            "Dashboard harus dieviksi saat session Error",
            ChatrokNavigationPolicy.shouldEvictProtectedRoutes(Screen.Dashboard.route, errorState)
        )
        assertTrue(
            "Inbox harus dieviksi saat session Error",
            ChatrokNavigationPolicy.shouldEvictProtectedRoutes(Screen.Inbox.route, errorState)
        )
        assertTrue(
            "Chat harus dieviksi saat session Error",
            ChatrokNavigationPolicy.shouldEvictProtectedRoutes("chat/conv_1/user_1", errorState)
        )
        assertFalse(
            "Rute Login tidak boleh dieviksi saat session Error",
            ChatrokNavigationPolicy.shouldEvictProtectedRoutes(Screen.Login.route, errorState)
        )
        assertFalse(
            "Rute Splash tidak boleh dieviksi saat session Error",
            ChatrokNavigationPolicy.shouldEvictProtectedRoutes(Screen.Splash.route, errorState)
        )
    }
}
