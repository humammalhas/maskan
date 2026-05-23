package app.maskan.chat.navigation

/**
 * Type-safe navigation routes for the app.
 * Using string-based routes with arguments for MVP simplicity.
 */
object Routes {
    const val CONVERSATION_LIST = "conversation_list"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val WELCOME = "welcome"

    fun chatRoute(conversationId: Long) = "chat/$conversationId"
}
