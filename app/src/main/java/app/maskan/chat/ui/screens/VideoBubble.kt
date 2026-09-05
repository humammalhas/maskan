package app.maskan.chat.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.util.LruCache
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maskan.chat.R
import java.nio.ByteBuffer

/**
 * Video playback for the chat bubble, on the platform's own MediaPlayer - no Media3, no
 * ExoPlayer: the APK stays small and F-Droid-clean, and a five-second clip needs nothing more.
 *
 * Both the thumbnail and the player read the clip straight out of memory through a
 * [MediaDataSource]. The stored file is encrypted, and this way the decrypted bytes never touch
 * the filesystem: nothing to sweep, nothing left behind if the process dies mid-playback.
 */

/** A read-only MediaDataSource over a byte array. */
private class BytesDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size, (bytes.size - position).toInt())
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize(): Long = bytes.size.toLong()
    override fun close() {}
}

/**
 * First frames, keyed by the stored file name. A LazyColumn recomposes a bubble every time it
 * scrolls back into view; extracting a frame each time would make a chat with several clips
 * stutter, so the frame is kept once per message.
 */
internal object VideoThumbnails {
    private val cache = object : LruCache<String, Bitmap>(24) {}

    fun get(key: String, bytes: ByteArray): Bitmap? {
        cache.get(key)?.let { return it }
        // Not Kotlin's use {}: MediaMetadataRetriever only became AutoCloseable in API 29.
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(BytesDataSource(bytes))
            // A frame a little way in rather than frame 0: the first frame of a generated clip
            // is often the blandest one.
            retriever.getFrameAtTime(400_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        if (frame != null) cache.put(key, frame)
        return frame
    }
}

/** The thumbnail with a play badge; tapping opens the player. */
@Composable
internal fun VideoThumbnail(
    cacheKey: String,
    bytes: ByteArray,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frame = remember(cacheKey) { VideoThumbnails.get(cacheKey, bytes) }
    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = stringResource(R.string.generated_video),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            // No frame (corrupt or exotic file): a plain dark card, still tappable, still
            // saveable - the bytes are what they are.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f)
                    .background(Color.Black)
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.play_video),
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * Full-screen looping playback. Tap anywhere to close. Audio plays when the clip has a track
 * (cloud clips do; local Wan clips are silent).
 */
@Composable
internal fun VideoPlayerDialog(bytes: ByteArray, onDismiss: () -> Unit) {
    // Sized once the player knows the clip; until then a 9:16 placeholder keeps layout stable.
    var aspect by remember { mutableFloatStateOf(9f / 16f) }
    val player = remember(bytes) {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            setDataSource(BytesDataSource(bytes))
            isLooping = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            try { player.stop() } catch (_: Exception) {}
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                player.setDisplay(holder)
                                player.setOnPreparedListener { mp ->
                                    if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                        aspect = mp.videoWidth.toFloat() / mp.videoHeight
                                    }
                                    mp.start()
                                }
                                try { player.prepareAsync() } catch (_: Exception) {}
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                try { player.setDisplay(null) } catch (_: Exception) {}
                            }
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .padding(0.dp)
            )
        }
    }
}

/**
 * An animated WebP or GIF, actually moving. BitmapFactory returns one frame by definition,
 * which is why the wan-webp route looked like a still until now. ImageDecoder (API 28+) hands
 * back an AnimatedImageDrawable that plays itself inside an ImageView; older devices keep the
 * still. Returns false if this format is not animated here, so the caller draws the bitmap.
 */
@Composable
internal fun AnimatedImage(bytes: ByteArray, mimeType: String, modifier: Modifier = Modifier): Boolean {
    val drawable: Drawable? = remember(bytes, mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@remember null
        if (mimeType != "image/webp" && mimeType != "image/gif") return@remember null
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        } catch (_: Exception) {
            null
        }
    }
    if (drawable !is AnimatedImageDrawable) return false
    val aspect = if (drawable.intrinsicHeight > 0) {
        drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
    } else 1f
    DisposableEffect(drawable) {
        drawable.start()
        onDispose { drawable.stop() }
    }
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setImageDrawable(drawable)
            }
        },
        update = { view -> if (view.drawable !== drawable) view.setImageDrawable(drawable) },
        modifier = modifier
            .widthIn(max = 280.dp)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
    )
    return true
}
