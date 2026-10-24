package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.local.AppDatabase
import app.maskan.chat.data.local.ConversationDao
import app.maskan.chat.data.local.ConversationEntity
import app.maskan.chat.data.local.MessageEntity
import app.maskan.chat.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Does Room's invalidation reach a Flow in this app, under SQLCipher? Measures it, once.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 * Subscribes to the conversations Flow (and one test chat's messages Flow), makes one write of
 * each kind the app makes, and logs whether the Flow re-emitted with the change and how long it
 * took. A raw execSQL outside any Room transaction is the control: Room is not told about it, so
 * it SHOULD be missed - which proves the probe can see a miss. Also logs whether there is one
 * AppDatabase, and whether Room's TEMP triggers exist on the writer connection.
 *
 *   adb shell am start -n app.maskan.chat.debug/app.maskan.chat.debug.ProbeActivity --es probe invalidation
 *
 * Lines are tagged MaskanInval. The test chat is titled with PREFIX and deleted at the end;
 * nothing else in the database is touched.
 */
class InvalidationProbeReceiver : BroadcastReceiver() {

    private class Seen<T>(var count: Int = 0, var last: T? = null)

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MaskanApplication ?: return
        val database = app.appDatabase
        val repository = app.chatRepository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            var testId = 0L
            try {
                instances(database, repository)
                sweepLeftovers(database)
                // The list's first-lines query re-runs on every streamed token: what does one cost?
                val t0 = SystemClock.elapsedRealtime()
                val firstLines = database.messageDao().getFirstUserMessages().first().size
                Log.i(TAG, "first-lines query " + (SystemClock.elapsedRealtime() - t0) + " ms, rows=" + firstLines)

                val conversations = Seen<List<ConversationEntity>>()
                val started = SystemClock.elapsedRealtime()
                scope.launch {
                    database.conversationDao().getAllConversations().collect {
                        synchronized(conversations) { conversations.count++; conversations.last = it }
                    }
                }
                awaitChange("initial conversations", conversations) { true }
                Log.i(TAG, "first emission " + (SystemClock.elapsedRealtime() - started) + " ms")

                delay(500)
                triggers(database, "after subscribing")

                testId = repository.createConversation(title = PREFIX + "created")
                awaitChange("create (repository)", conversations) { list -> list.any { it.id == testId } }

                database.conversationDao().updateConversationTitle(testId, PREFIX + "renamed")
                awaitChange("rename (DAO)", conversations) { list ->
                    list.any { it.id == testId && it.title == PREFIX + "renamed" }
                }

                val messages = Seen<List<MessageEntity>>()
                scope.launch {
                    database.messageDao().getMessagesForConversation(testId).collect {
                        synchronized(messages) { messages.count++; messages.last = it }
                    }
                }
                awaitChange("initial messages", messages) { true }
                database.messageDao().insertMessage(
                    MessageEntity(conversationId = testId, role = "user", content = PREFIX + "m1")
                )
                awaitChange("insert message (DAO)", messages) { list -> list.any { it.content == PREFIX + "m1" } }

                // The control: Room never hears of this write.
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE conversations SET title = ? WHERE id = ?",
                    arrayOf<Any>(PREFIX + "raw", testId)
                )
                awaitChange("raw execSQL (control, expect MISSED)", conversations) { list ->
                    list.any { it.id == testId && it.title == PREFIX + "raw" }
                }

                repository.deleteConversation(testId)
                awaitChange("delete (repository)", conversations) { list -> list.none { it.id == testId } }
                awaitChange("delete cascade (messages)", messages) { list -> list.isEmpty() }
                // Did the cascade happen at all, or did the Flow just not hear of it?
                Log.i(TAG, "rows left for the deleted chat: " + database.messageDao().countForConversation(testId))
                testId = 0L

                triggers(database, "at the end")
                Log.i(TAG, "DONE")
            } catch (e: Throwable) {
                Log.e(TAG, "FAILED", e)
            } finally {
                if (testId != 0L) runCatching { repository.deleteConversation(testId) }
                    .onSuccess { Log.i(TAG, "cleanup deleted the test chat") }
                scope.cancel()
            }
        }
    }

    /** One AppDatabase? The application's, the singleton's and the repository's DAO's owner. */
    private fun instances(database: AppDatabase, repository: ChatRepository) {
        val singleton = runCatching {
            AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }.getOrNull()
        val repoDao = ChatRepository::class.java.getDeclaredField("conversationDao")
            .apply { isAccessible = true }.get(repository) as ConversationDao
        Log.i(
            TAG,
            "instances app=" + System.identityHashCode(database) +
                " singleton=" + System.identityHashCode(singleton) +
                " daoSame=" + (repoDao === database.conversationDao())
        )
    }

    /** Message rows an earlier run left behind (a cascade that did not happen), by their prefix. */
    private fun sweepLeftovers(database: AppDatabase) {
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            val left = db.query("SELECT COUNT(*) FROM messages WHERE content LIKE '" + PREFIX + "%'")
                .use { it.moveToFirst(); it.getInt(0) }
            if (left > 0) db.execSQL("DELETE FROM messages WHERE content LIKE '" + PREFIX + "%'")
            Log.i(TAG, "leftover probe messages from an earlier run: " + left + (if (left > 0) " (deleted)" else ""))
            // Read only: every message whose chat no longer exists, the user's included.
            val orphans = db.query(
                "SELECT COUNT(*) FROM messages WHERE conversationId NOT IN (SELECT id FROM conversations)"
            ).use { it.moveToFirst(); it.getInt(0) }
            Log.i(TAG, "orphan messages in the whole database: " + orphans)
        }
    }

    /** Room's TEMP objects, read inside a transaction so the query runs on the writer connection. */
    private fun triggers(database: AppDatabase, label: String) {
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            val names = ArrayList<String>()
            db.query("SELECT type || ':' || name FROM temp.sqlite_master").use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            val journal = db.query("PRAGMA journal_mode").use { it.moveToFirst(); it.getString(0) }
            val foreignKeys = db.query("PRAGMA foreign_keys").use { it.moveToFirst(); it.getInt(0) }
            Log.i(TAG, "temp " + label + " journal=" + journal + " foreign_keys=" + foreignKeys +
                " n=" + names.size + " " + names)
        }
    }

    /** Wait up to TIMEOUT_MS for an emission after this call whose value satisfies [reflects]. */
    private suspend fun <T> awaitChange(label: String, seen: Seen<T>, reflects: (T) -> Boolean) {
        val before = synchronized(seen) { seen.count }
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < TIMEOUT_MS) {
            val (count, last) = synchronized(seen) { seen.count to seen.last }
            if (count > before && last != null && reflects(last)) {
                Log.i(TAG, label + ": re-emitted after " + (SystemClock.elapsedRealtime() - start) +
                    " ms, emissions +" + (count - before))
                return
            }
            delay(10)
        }
        val count = synchronized(seen) { seen.count }
        Log.i(TAG, label + ": MISSED in " + TIMEOUT_MS + " ms, emissions +" + (count - before))
    }

    private companion object {
        const val TAG = "MaskanInval"
        const val PREFIX = "zz-invalprobe-"
        const val TIMEOUT_MS = 3000L
    }
}
