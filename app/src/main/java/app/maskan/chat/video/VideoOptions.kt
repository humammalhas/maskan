package app.maskan.chat.video

import androidx.annotation.StringRes
import app.maskan.chat.R

/**
 * What the local video server offers and what each choice costs, as measured on Humam's AI PC
 * (Wan 2.2 TI2V-5B, 24 fps) on 2026-09-04/05. Two size pairs, tall and wide being transposes of
 * each other at the same price; the sharper pair costs ~1.83x. Nothing below a 576 px short
 * side is offered: the model corrupts there and the server silently enlarges such a request,
 * so a "smaller, faster" chip would be a lie.
 */
object VideoOptions {

    class SizeOption(val id: String, @StringRes val labelRes: Int, val sharp: Boolean)

    val SIZES = listOf(
        SizeOption("576x1024", R.string.video_size_tall_fast, sharp = false),
        SizeOption("704x1280", R.string.video_size_tall_sharp, sharp = true),
        SizeOption("1024x576", R.string.video_size_wide_fast, sharp = false),
        SizeOption("1280x704", R.string.video_size_wide_sharp, sharp = true)
    )

    val LENGTHS = listOf(5, 10, 15)

    const val DEFAULT_SIZE = "576x1024"
    const val DEFAULT_SECONDS = 5

    /** Measured wall-clock minutes, prompt expansion included, rounded the way a person would. */
    fun minutesFor(size: String, seconds: Int): Int {
        val sharp = SIZES.firstOrNull { it.id == size }?.sharp ?: false
        return when (seconds) {
            5 -> if (sharp) 6 else 4
            10 -> if (sharp) 16 else 8
            else -> if (sharp) 29 else 15
        }
    }

    class ImageSizeOption(val id: String, @StringRes val labelRes: Int)

    val IMAGE_SIZES = listOf(
        ImageSizeOption("1024x1024", R.string.image_size_square),
        ImageSizeOption("1024x576", R.string.image_size_wide),
        ImageSizeOption("576x1024", R.string.image_size_tall)
    )

    const val DEFAULT_IMAGE_SIZE = "1024x1024"
}
