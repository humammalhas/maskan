package app.maskan.chat.video

import androidx.annotation.StringRes
import app.maskan.chat.R

/**
 * What each video server offers and what each choice costs.
 *
 * Local (Custom URL, Wan 2.2 on Humam's AI PC, measured 2026-09-04/05): two size pairs, tall
 * and wide being transposes at the same price, the sharper pair ~1.83x; 5 / 10 / 15 s; the cost
 * is TIME. Nothing below a 576 px short side is offered - the model corrupts there and the
 * server silently enlarges such a request, so a "smaller, faster" chip would be a lie.
 *
 * Gemini (Veo, per the Gemini API docs read 2026-09-05): 16:9 or 9:16 at 720p, 4 / 6 / 8 s; the
 * cost is MONEY per second of video - lite $0.05, fast $0.10, standard $0.40 (third-party
 * pricing pages dated 2026-08-14; shown with "~" for that reason). The size string sent is the
 * aspect ratio itself.
 */
object VideoOptions {

    class SizeOption(val id: String, @StringRes val labelRes: Int, val sharp: Boolean)

    private val LOCAL_SIZES = listOf(
        SizeOption("576x1024", R.string.video_size_tall_fast, sharp = false),
        SizeOption("704x1280", R.string.video_size_tall_sharp, sharp = true),
        SizeOption("1024x576", R.string.video_size_wide_fast, sharp = false),
        SizeOption("1280x704", R.string.video_size_wide_sharp, sharp = true)
    )

    private val VEO_SIZES = listOf(
        SizeOption("9:16", R.string.image_size_tall, sharp = false),
        SizeOption("16:9", R.string.image_size_wide, sharp = false)
    )

    private val LOCAL_LENGTHS = listOf(5, 10, 15)
    private val VEO_LENGTHS = listOf(4, 6, 8)
    private val VENICE_LENGTHS = listOf(5, 10)

    const val DEFAULT_SIZE = "576x1024"
    const val DEFAULT_SECONDS = 5

    /** Providers whose video is billed per second rather than rendered on the user's own GPU. */
    fun isCloud(providerId: String): Boolean =
        providerId == "gemini" || providerId == "openrouter" || providerId == "venice"

    fun sizesFor(providerId: String): List<SizeOption> = if (isCloud(providerId)) VEO_SIZES else LOCAL_SIZES
    fun lengthsFor(providerId: String): List<Int> = when {
        providerId == "venice" -> VENICE_LENGTHS
        isCloud(providerId) -> VEO_LENGTHS
        else -> LOCAL_LENGTHS
    }
    fun defaultSize(providerId: String): String = sizesFor(providerId).first().id
    fun defaultSeconds(providerId: String): Int = lengthsFor(providerId).first()

    /** A remembered choice is only valid for the provider it was made on. */
    fun validSize(providerId: String, size: String?): String =
        size?.takeIf { s -> sizesFor(providerId).any { it.id == s } } ?: defaultSize(providerId)

    fun validSeconds(providerId: String, seconds: Int?): Int =
        seconds?.takeIf { it in lengthsFor(providerId) } ?: defaultSeconds(providerId)

    /** Local: measured wall-clock minutes, prompt expansion included. */
    fun minutesFor(size: String, seconds: Int): Int {
        val sharp = LOCAL_SIZES.firstOrNull { it.id == size }?.sharp ?: false
        return when (seconds) {
            5 -> if (sharp) 6 else 4
            10 -> if (sharp) 16 else 8
            else -> if (sharp) 29 else 15
        }
    }

    /**
     * Cloud: approximate dollars for one clip on this model, or null when the app has no
     * figure to stand behind (OpenRouter prices every model differently and the catalogue's
     * per-video-second SKUs are not read yet) - better no number than a wrong one.
     */
    fun dollarsFor(providerId: String, model: String, seconds: Int): Double? {
        if (providerId != "gemini") return null
        val lower = model.lowercase()
        val perSecond = when {
            "lite" in lower -> 0.05
            "fast" in lower -> 0.10
            else -> 0.40
        }
        return perSecond * seconds
    }

    class ImageSizeOption(val id: String, @StringRes val labelRes: Int)

    val IMAGE_SIZES = listOf(
        ImageSizeOption("1024x1024", R.string.image_size_square),
        ImageSizeOption("1024x576", R.string.image_size_wide),
        ImageSizeOption("576x1024", R.string.image_size_tall)
    )

    const val DEFAULT_IMAGE_SIZE = "1024x1024"
}
