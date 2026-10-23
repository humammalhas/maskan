package app.maskan.chat.util

/**
 * A send the app stopped before anything left the phone, for a reason the user can fix
 * (2.7, plan item 10). ErrorMapper turns each kind into one plain sentence that says what is
 * missing and where to fix it - never a provider's raw body, never silence.
 *
 * [subject] is what the sentence names: the provider id for [Kind.NO_KEY], the model id for
 * [Kind.PHOTO_UNREADABLE], nothing for [Kind.ONDEVICE_TOO_LONG].
 */
class SendRefused(val kind: Kind, val subject: String = "") : Exception("send refused: $kind $subject") {
    enum class Kind {
        /** The provider needs a key and has none. Any chat, switched or not. */
        NO_KEY,

        /** A chat moved to a model known not to see, with a photo or scanned page it would need. */
        PHOTO_UNREADABLE,

        /** A chat moved to on-device whose history does not fit the window. Never trimmed. */
        ONDEVICE_TOO_LONG
    }
}
