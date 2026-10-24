package app.maskan.chat.data.repository

import app.maskan.chat.data.local.DocumentEntity
import app.maskan.chat.data.local.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a fork copies (2.7, plan item 13). */
class ChatForkTest {

    private fun row(id: Long, role: String, content: String = "m$id", ts: Long = id * 10, path: String? = null, job: String? = null) =
        MessageEntity(id = id, conversationId = 1, role = role, content = content, timestamp = ts, imagePath = path, videoJobId = job)

    private fun doc(id: Long, attached: Long?, pages: String? = null) =
        DocumentEntity(id = id, conversationId = 1, attachedMessageId = attached, name = "d$id", kind = "pdf", text = "", pageImageIds = pages)

    private fun ready(result: ChatFork.Result) = (result as ChatFork.Result.Ready).plan

    private val chat = listOf(
        row(1, "system", ts = 5),
        row(2, "user"), row(3, "assistant", path = "img_a.enc"),
        row(4, "user"), row(5, "assistant"),
        row(6, "user"), row(7, "assistant")
    )

    @Test
    fun copiesUpToAndIncludingTheChosenMessageInOrder() {
        val plan = ready(ChatFork.plan(chat.shuffled(), emptyList(), 5, "deepseek", "deepseek-flash"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.messages.map { it.id })
        assertNull(plan.trailingSwitch)
    }

    @Test
    fun forkingFromAQuestionStopsAtTheQuestion() {
        val plan = ready(ChatFork.plan(chat, emptyList(), 4, "deepseek", "m"))
        assertEquals("user", plan.messages.last().role)
        assertEquals(4L, plan.messages.last().id)
    }

    @Test
    fun aRowSharingAMillisecondIsOrderedById() {
        val rows = listOf(row(10, "user", ts = 100), row(11, "assistant", ts = 100), row(12, "user", ts = 100))
        assertEquals(listOf(10L, 11L), ready(ChatFork.plan(rows.reversed(), emptyList(), 11, "p", "m")).messages.map { it.id })
    }

    @Test
    fun theSystemRowIsCopiedWhereverItSits() {
        // A style chosen late rewrites the one system row; the fork takes the style as it is now.
        val rows = chat.filter { it.role != "system" } + row(1, "system", ts = 999)
        assertEquals(1L, ready(ChatFork.plan(rows, emptyList(), 3, "p", "m")).messages.first().id)
    }

    @Test
    fun aSystemRowOrAnUnknownIdIsNotAForkPoint() {
        assertTrue(ChatFork.plan(chat, emptyList(), 1, "p", "m") is ChatFork.Result.NotFound)
        assertTrue(ChatFork.plan(chat, emptyList(), 99, "p", "m") is ChatFork.Result.NotFound)
    }

    @Test
    fun aVideoStillRenderingInTheRangeRefusesTheFork() {
        val rows = chat + row(8, "assistant", job = "job-1") + row(9, "user")
        assertTrue(ChatFork.plan(rows, emptyList(), 9, "p", "m") is ChatFork.Result.VideoRendering)
        // After the fork point it is not copied, so it does not matter.
        assertTrue(ChatFork.plan(rows, emptyList(), 7, "p", "m") is ChatFork.Result.Ready)
        // A finished clip has a file and no job: copied like any picture.
        val done = chat + row(8, "assistant", path = "vid.enc")
        assertTrue(ChatFork.plan(done, emptyList(), 8, "p", "m") is ChatFork.Result.Ready)
    }

    @Test
    fun documentsGoWithTheirMessageOrWithTheChat() {
        val docs = listOf(doc(1, null), doc(2, 3), doc(3, 7))
        val plan = ready(ChatFork.plan(chat, docs, 5, "p", "m"))
        assertEquals(listOf(1L, 2L), plan.documents.map { it.id })
    }

    @Test
    fun aCopiedDocumentPointsAtTheCopiedRows() {
        val idMap = mapOf(2L to 102L, 3L to 103L, 4L to 104L)
        val scan = ChatFork.remapDocument(doc(5, 2, pages = "2,3,4"), 50, idMap)
        assertEquals(0L, scan.id)
        assertEquals(50L, scan.conversationId)
        assertEquals(102L, scan.attachedMessageId)
        assertEquals("102,103,104", scan.pageImageIds)
        // A page that was not copied is dropped, never left pointing into the source.
        assertEquals("102", ChatFork.remapDocument(doc(5, 2, pages = "2,9"), 50, idMap).pageImageIds)
        // A text document has no pages and keeps none.
        assertNull(ChatFork.remapDocument(doc(6, null), 50, idMap).pageImageIds)
        assertNull(ChatFork.remapDocument(doc(6, null), 50, idMap).attachedMessageId)
    }

    @Test
    fun forkingFromBeforeASwitchSaysTheForkRunsOnTheNewModel() {
        val switch = ModelSwitch.encode(ModelSwitch.Move("deepseek", "deepseek-flash", "groq", "qwen/qwen3.8-27b"))
        val rows = chat + row(8, ChatRepository.ROLE_NOTICE, switch) + row(9, "user") + row(10, "assistant")
        val before = ready(ChatFork.plan(rows, emptyList(), 5, "groq", "qwen/qwen3.8-27b"))
        assertEquals(ModelSwitch.Move("deepseek", "deepseek-flash", "groq", "qwen/qwen3.8-27b"), before.trailingSwitch)
        // After the switch the fork is already on the model the source has: nothing to say.
        assertNull(ready(ChatFork.plan(rows, emptyList(), 10, "groq", "qwen/qwen3.8-27b")).trailingSwitch)
        // The notice itself is copied like any row when it is in the range.
        assertTrue(ready(ChatFork.plan(rows, emptyList(), 9, "groq", "qwen/qwen3.8-27b")).messages.any { it.id == 8L })
    }

    @Test
    fun anEditedReplyStaysMarkedInTheFork() {
        // The fork's transcript must not claim the model said what the user wrote (plan item 9).
        val rows = chat.map { if (it.id == 3L) it.copy(content = "fixed", editedAt = 1234L) else it }
        val copied = ready(ChatFork.plan(rows, emptyList(), 5, "p", "m")).messages.first { it.id == 3L }
        assertEquals(1234L, copied.editedAt)
        assertEquals("fixed", copied.content)
    }

    @Test
    fun theModelInForceIsTheLastSwitchBeforeTheForkPoint() {
        val a = ModelSwitch.encode(ModelSwitch.Move("deepseek", "x", "groq", "y"))
        val b = ModelSwitch.encode(ModelSwitch.Move("groq", "y", "openai", "z"))
        val rows = chat + row(8, ChatRepository.ROLE_NOTICE, a) + row(9, "user") + row(10, ChatRepository.ROLE_NOTICE, b) + row(11, "user")
        assertEquals(ModelSwitch.Move("groq", "y", "openai", "z"), ready(ChatFork.plan(rows, emptyList(), 9, "openai", "z")).trailingSwitch)
    }
}
