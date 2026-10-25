package app.maskan.chat.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Any audio file Android can decode, as a 16 kHz mono 16-bit WAV (2.7, plan item 3, "Listen and
 * answer"). OpenAI's chat audio takes only WAV or MP3 (measured, session 11: "Supported values are:
 * 'wav' and 'mp3'"), and a WhatsApp voice note is Ogg Opus. Android's own MediaCodec decodes it -
 * no library, nothing added to the APK. 16 kHz mono is what speech models are trained on and keeps
 * the upload at 32 KB a second.
 */
object AudioToWav {

    private const val RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /** Blocking; call it off the main thread. Throws when the file cannot be decoded. */
    fun convert(context: Context, bytes: ByteArray): ByteArray {
        // MediaExtractor reads from a file; the copy lives in the app's own cache for a moment.
        val temp = File.createTempFile("listen", ".audio", context.cacheDir)
        try {
            temp.writeBytes(bytes)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(temp.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw IllegalArgumentException("no audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                    val (pcm, rate, channels) = decode(extractor, codec, format)
                    return wav(resample(mono(pcm, channels), rate))
                } finally {
                    runCatching { codec.stop() }
                    codec.release()
                }
            } finally {
                extractor.release()
            }
        } finally {
            temp.delete()
        }
    }

    private data class Decoded(val pcm: ShortArray, val rate: Int, val channels: Int)

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, input: MediaFormat): Decoded {
        val out = ByteArrayOutputStream()
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    val chunk = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val raw = out.toByteArray()
        val shorts = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return Decoded(shorts, rate, channels)
    }

    private fun mono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        return ShortArray(pcm.size / channels) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += pcm[frame * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear interpolation to [RATE]. Speech loses nothing a model listens for. */
    internal fun resample(pcm: ShortArray, rate: Int): ShortArray {
        if (rate == RATE || pcm.isEmpty()) return pcm
        val outLength = (pcm.size.toLong() * RATE / rate).toInt()
        return ShortArray(outLength) { i ->
            val position = i.toDouble() * rate / RATE
            val left = position.toInt().coerceAtMost(pcm.size - 1)
            val right = (left + 1).coerceAtMost(pcm.size - 1)
            val fraction = position - left
            (pcm[left] + (pcm[right] - pcm[left]) * fraction).toInt().toShort()
        }
    }

    internal fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        pcm.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}
