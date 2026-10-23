package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.remote.Message
import app.maskan.chat.data.remote.MessageContent
import app.maskan.chat.data.remote.providers.ProviderRegistry
import app.maskan.chat.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Runs the real request assembly over every conversation on the phone and logs a fingerprint of
 * each result. Sends nothing.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * It exists to prove a refactor of ChatRepository's assembly changed nothing: run it before,
 * run it after, and the two logs must be identical line for line, in English and in Arabic
 * (the dialect voice depends on the app's language). Real chats cover the shapes the unit-test
 * fixtures cannot reach without a database - folders, memory, documents, notices, photos.
 *
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.AssemblyProbeReceiver
 *
 * Lines are tagged MaskanAsm: `conv=<id> provider=<id> model=<id> msgs=<n> sha=<hex>`, then
 * `DONE count=<n>`. buildMessageList is private, so it is reached by reflection - acceptable in a
 * debug probe, and the reason this is not a unit test.
 */
class AssemblyProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication ?: return
        val repository = app.chatRepository
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dao = conversationDao(repository)
                val conversations = dao.getAllConversations().first().sortedBy { it.id }
                val build = ChatRepository::class.java.getDeclaredMethod(
                    "buildMessageList",
                    ConversationEntity::class.java,
                    String::class.java,
                    Continuation::class.java
                ).apply { isAccessible = true }

                for (conversation in conversations) {
                    val model = conversation.modelId
                        ?: ProviderRegistry.getProvider(conversation.providerId)?.defaultModel
                        ?: ""
                    @Suppress("UNCHECKED_CAST")
                    val messages = suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                        build.invoke(repository, conversation, model, cont)
                    } as List<Message>
                    Log.i(
                        TAG,
                        "conv=" + conversation.id + " provider=" + conversation.providerId +
                            " model=" + model + " msgs=" + messages.size + " sha=" + fingerprint(messages)
                    )
                }
                Log.i(TAG, "DONE count=" + conversations.size)
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
            }
        }
    }

    private fun conversationDao(repository: ChatRepository): app.maskan.chat.data.local.ConversationDao =
        ChatRepository::class.java.getDeclaredField("conversationDao")
            .apply { isAccessible = true }
            .get(repository) as app.maskan.chat.data.local.ConversationDao

    /** Role, text and any photo, in order - everything a provider turns into the request. */
    private fun fingerprint(messages: List<Message>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (message in messages) {
            digest.update(message.role.toByteArray())
            digest.update(0)
            when (val content = message.content) {
                is MessageContent.Text -> digest.update(content.text.toByteArray())
                is MessageContent.WithImage -> {
                    digest.update(content.text.toByteArray())
                    digest.update(1)
                    digest.update(content.mimeType.toByteArray())
                    digest.update(content.imageBase64.toByteArray())
                }
            }
            digest.update(2)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    private companion object {
        const val TAG = "MaskanAsm"
    }
}
