package com.chatrok.app.core.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Dashboard : Screen("dashboard")
    object Inbox : Screen("inbox")
    object NewChat : Screen("new_chat")
    object EditProfile : Screen("edit_profile")
    object Settings : Screen("settings")
    object CallHistory : Screen("call_history")

    object Chat : Screen("chat/{conversationId}/{userId}") {
        fun createRoute(conversationId: String, userId: String): String = "chat/$conversationId/$userId"
    }

    object UserProfile : Screen("user_profile/{userId}") {
        fun createRoute(userId: String): String = "user_profile/$userId"
    }

    object Security : Screen("security/{userId}") {
        fun createRoute(userId: String): String = "security/$userId"
    }

    object MediaPreview : Screen("media_preview/{title}/{type}") {
        fun createRoute(title: String, type: String): String = "media_preview/$title/$type"
    }
}
