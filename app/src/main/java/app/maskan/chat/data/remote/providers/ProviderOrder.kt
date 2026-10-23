package app.maskan.chat.data.remote.providers

/**
 * The user's own arrangement of the provider list: an order, and the providers they hid
 * (2.7, plan item 1 as Humam re-cut it on 2026-10-24 - hide and reorder instead of add/edit/delete;
 * adding an endpoint is what Custom already does).
 *
 * Hiding is tidying, not switching off: a hidden provider leaves the Settings list and the chat's
 * Change list, and its key, its models and every chat already on it keep working.
 *
 * Pure, so the tests reach it without Android. Ids only - the registry stays the one source of
 * which providers exist.
 */
object ProviderOrder {

    data class Arranged(val visible: List<String>, val hidden: List<String>)

    /**
     * [all] in registry order, arranged by the [saved] order and [hidden] set. A provider the saved
     * order does not know - one added in a later version - joins at the end, so an update never
     * reshuffles what the user arranged; an id the registry no longer has is dropped.
     */
    fun arrange(all: List<String>, saved: List<String>, hidden: Set<String>): Arranged {
        val known = all.toSet()
        val savedKnown = saved.filter { it in known }.distinct()
        val ordered = savedKnown + all.filter { it !in savedKnown }
        return Arranged(ordered.filter { it !in hidden }, ordered.filter { it in hidden })
    }

    /**
     * The full order after moving [id] one place up ([delta] = -1) or down (+1) among the VISIBLE
     * providers. Hidden ones have no place on screen, so they are kept after the visible ones; one
     * shown again comes back at the bottom of the list, where the user can see it arrive.
     */
    fun move(arranged: Arranged, id: String, delta: Int): List<String> {
        val visible = arranged.visible.toMutableList()
        val from = visible.indexOf(id)
        val to = from + delta
        if (from >= 0 && to in visible.indices) {
            visible[from] = visible[to]
            visible[to] = id
        }
        return visible + arranged.hidden
    }
}
