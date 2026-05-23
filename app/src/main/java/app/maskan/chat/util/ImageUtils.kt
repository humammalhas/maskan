package app.maskan.chat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

object ImageUtils {

    fun compressImage(context: Context, uri: Uri, maxSizeKb: Int = 500): Pair<ByteArray, String> {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot read image")
        val originalBytes = inputStream.use { it.readBytes() }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, options)
        val width = options.outWidth
        val height = options.outHeight

        var sampleSize = 1
        val maxDimension = 1024
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOptions)
            ?: throw Exception("Cannot decode image")

        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newW = (bitmap.width * scale).toInt()
            val newH = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        var quality = 85
        var compressed: ByteArray
        do {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            compressed = out.toByteArray()
            quality -= 10
        } while (compressed.size > maxSizeKb * 1024 && quality > 10)

        scaled.recycle()

        return compressed to "image/jpeg"
    }
}
