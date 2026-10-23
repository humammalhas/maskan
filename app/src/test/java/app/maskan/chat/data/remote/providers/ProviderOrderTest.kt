package app.maskan.chat.data.remote.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's arrangement of the provider list (2.7, plan item 1 as re-cut: hide and reorder). */
class ProviderOrderTest {

    private val all = listOf("ondevice", "deepseek", "openai", "groq", "custom")

    @Test
    fun nothingSavedIsTheRegistryOrder() {
        assertEquals(ProviderOrder.Arranged(all, emptyList()), ProviderOrder.arrange(all, emptyList(), emptySet()))
    }

    @Test
    fun aSavedOrderWinsAndANewProviderJoinsAtTheEnd() {
        // "custom" is unknown to the saved order (added in a later version); "gone" no longer exists.
        val arranged = ProviderOrder.arrange(all, listOf("groq", "gone", "deepseek", "ondevice", "openai"), emptySet())
        assertEquals(listOf("groq", "deepseek", "ondevice", "openai", "custom"), arranged.visible)
    }

    @Test
    fun hiddenProvidersLeaveTheVisibleList() {
        val arranged = ProviderOrder.arrange(all, emptyList(), setOf("openai", "ondevice"))
        assertEquals(listOf("deepseek", "groq", "custom"), arranged.visible)
        assertEquals(listOf("ondevice", "openai"), arranged.hidden)
    }

    @Test
    fun movingSkipsHiddenProvidersAndStopsAtTheEnds() {
        val arranged = ProviderOrder.arrange(all, emptyList(), setOf("openai"))
        // groq moves up past the hidden openai, landing above deepseek.
        assertEquals(listOf("ondevice", "groq", "deepseek", "custom", "openai"), ProviderOrder.move(arranged, "groq", -1))
        assertEquals(listOf("ondevice", "deepseek", "groq", "custom", "openai"), ProviderOrder.move(arranged, "ondevice", -1))
        assertEquals(listOf("ondevice", "deepseek", "groq", "custom", "openai"), ProviderOrder.move(arranged, "custom", 1))
    }
}
