package app.maskan.chat.util

import app.maskan.chat.util.ChatTitle.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming a chat. Every raw reply below was MEASURED on the Pixel in 2.7 session 6 - Qwen2.5 1.5B
 * through the app's own engine, DeepSeek as the control - except the one marked as reported.
 * Re-measure when the on-device model changes (plan item 14).
 */
class ChatTitleTest {

    private val hiEn = "hi"
    private val hiAr = "مرحبا"
    private val jordanAr = "ما عاصمة الأردن؟"
    private val coffeeAr = "كيف أحضّر قهوة عربية؟"
    private val hiTh = "สวัสดี"
    private val bangkokTh = "กรุงเทพฯ มีสถานที่ท่องเที่ยวอะไรบ้าง"
    private val tomYumTh = "ทำต้มยำกุ้งอย่างไร"
    private val pythonEn = "What's a good way to learn Python as a beginner?"
    private val eggEn = "How long should I boil an egg for a soft yolk?"

    @Test
    fun anArabicChatNamedInAnotherLanguageKeepsItsName() {
        for (raw in listOf(
            "Hello, how can I assist you today?", "What is the capital of Jordan?",
            "preparación de café árabe", "茶制作", "茶制作法", "Arab coffee preparation", "Hola, ¿cómo estás?"
        )) {
            assertNull(raw, ChatTitle.clean(raw, if ("Jordan" in raw) jordanAr else coffeeAr))
        }
    }

    @Test
    fun aThaiChatNamedInEnglishKeepsItsName() {
        assertNull(ChatTitle.clean("Royal Palace & Market", bangkokTh))
        assertNull(ChatTitle.clean("Japanese greetings", hiTh))
    }

    @Test
    fun theGreetingCopiedInIsNotATitle() {
        assertNull(ChatTitle.clean("Welcome! What can I assist you with?", hiEn))
        assertNull(ChatTitle.clean("مرحبا! كيف يمكنني مساعدتك اليوم؟", hiAr))
        assertNull(ChatTitle.clean("Hello? What's this?", hiTh))
        assertNull(ChatTitle.clean("สิ่งที่คุณต้องการทราบ?", hiTh))
    }

    @Test
    fun aQuestionEchoedBackIsAllowedWhenTheChatAskedOne() {
        assertEquals("ما هي عاصمة الأردن؟", ChatTitle.clean("ما هي عاصمة الأردن؟", jordanAr))
    }

    @Test
    fun utf8ReadAsLatin1IsNeverSaved() {
        assertNull(ChatTitle.clean("การชà¹įาารี", hiTh))
        assertNull(ChatTitle.clean("CafÃ© arabic preparation", coffeeAr))
        assertNull(ChatTitle.clean("CafÃ© arabic preparation", "How do I make Arabic coffee?"))
        assertNull(ChatTitle.clean("abc" + 0xFFFD.toChar() + "def", "abc"))
        // Real accented Latin is not mojibake.
        assertEquals("Café au lait à Paris", ChatTitle.clean("Café au lait à Paris", "Café au lait?"))
    }

    @Test
    fun underscoresBecomeSpaces() {
        assertEquals("عاصمة الأردن", ChatTitle.clean("عاصمة_الأردن", jordanAr))
    }

    @Test
    fun theReportedLeadInIsStripped() {
        // REPORTED (2.6.1), not reproduced in 126 measured runs.
        assertEquals("greeting inquiry", ChatTitle.clean("The title for this conversation is: greeting inquiry", hiEn))
        assertEquals("Boiling eggs", ChatTitle.clean("Title: Boiling eggs", eggEn))
        assertEquals("عاصمة الأردن", ChatTitle.clean("العنوان: عاصمة الأردن", jordanAr))
        assertEquals("วิธีทำต้มยำกุ้ง", ChatTitle.clean("ชื่อเรื่อง: วิธีทำต้มยำกุ้ง", tomYumTh))
        // A colon that is part of the title stays.
        assertEquals("Python: Basics and Projects", ChatTitle.clean("Python: Basics and Projects", pythonEn))
        assertEquals("Title IX: an overview", ChatTitle.clean("Title IX: an overview", "What is Title IX?"))
    }

    @Test
    fun whatTheOldCleanupDidStillHappens() {
        assertEquals("Boiling eggs", ChatTitle.clean("\"Boiling eggs.\"", eggEn))
        assertEquals("قهوة عربية", ChatTitle.clean("«قهوة عربية»", coffeeAr))
        assertEquals("Python Learning Guide", ChatTitle.clean("\n\nPython Learning Guide\nSecond line", pythonEn))
        assertNull(ChatTitle.clean("", pythonEn))
        assertNull(ChatTitle.clean("   \n  ", pythonEn))
        // A sentence is still not a title, whatever the cleanup did to it.
        assertNull(ChatTitle.clean(
            "Learning Python is easiest when you start with the official tutorial and then build things",
            pythonEn
        ))
    }

    @Test
    fun goodMeasuredTitlesSurviveEveryGuard() {
        val good = mapOf(
            // Qwen, measured.
            "Python Learning Guide" to pythonEn,
            "Learning Python: Basics, Projects, Practice" to pythonEn,
            "Boiling time for soft yolk" to eggEn,
            "Conversation starter" to hiEn,
            "عاصمة الأردن" to jordanAr,
            "طريقة تحضير قهوة عربية" to coffeeAr,
            "السلام عليكم" to hiAr,
            "การทำอาหารไทย" to tomYumTh,
            // DeepSeek, measured - every one of its 27 replies was a good title.
            "Simple Friendly Greeting Exchange" to hiEn,
            "Soft-Boiled Egg Timing" to eggEn,
            "ترحيب وبداية محادثة" to hiAr,
            "تحية وعرض مساعدة" to hiAr,
            "عاصمة الأردن هي عمّان" to jordanAr,
            "طريقة تحضير القهوة العربية" to coffeeAr,
            "บทสนทนาทักทายง่ายๆ" to hiTh,
            "สถานที่ท่องเที่ยวในกรุงเทพฯ" to bangkokTh,
            "วิธีทำต้มยำกุ้ง" to tomYumTh
        )
        for ((raw, question) in good) {
            assertEquals(raw, raw, ChatTitle.clean(raw, question))
        }
    }

    @Test
    fun aQuestionWithNoLettersDoesNotBlockATitle() {
        assertNotNull(ChatTitle.clean("Simple addition", "2+2"))
    }

    @Test
    fun theInstructionNamesArabicAndThaiOnly() {
        assertTrue("written in Arabic." in ChatTitle.instruction(jordanAr))
        assertTrue("written in Thai." in ChatTitle.instruction(tomYumTh))
        val en = ChatTitle.instruction(pythonEn)
        assertTrue("written in THE SAME LANGUAGE as the exchange." in en)
        assertFalse("Arabic" in en)
    }

    @Test
    fun scriptIsTheMajorityOfTheLetters() {
        assertEquals(Script.ARABIC, ChatTitle.scriptOf("كيف أتعلم Python؟"))
        assertEquals(Script.LATIN, ChatTitle.scriptOf("How do I say مرحبا in English?"))
        assertEquals(Script.THAI, ChatTitle.scriptOf(bangkokTh))
        assertEquals(Script.CJK, ChatTitle.scriptOf("茶制作法"))
        assertEquals(Script.OTHER, ChatTitle.scriptOf("2+2 = ?"))
    }

    @Test
    fun theFallbackNameIgnoresTheWhitespaceAroundTheMessage() {
        // Measured on the Pixel: a first message ending in a newline kept it in the title, and
        // generateTitle, comparing against the trimmed question, never named the chat.
        val typed = "Name three long rivers in Africa\n"
        assertEquals("Name three long rivers in Africa", ChatTitle.fallback(typed))
        assertEquals(ChatTitle.fallback(typed.trim()), ChatTitle.fallback(typed))
        assertEquals(jordanAr, ChatTitle.fallback("\n  $jordanAr \n"))
    }

    @Test
    fun aLongMessageIsCutAtFiftyCharactersAfterTrimming() {
        assertEquals("a".repeat(50) + "...", ChatTitle.fallback("  " + "a".repeat(80)))
    }
}
