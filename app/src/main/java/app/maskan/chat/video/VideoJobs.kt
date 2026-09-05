package app.maskan.chat.video

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.maskan.chat.MainActivity
import app.maskan.chat.R
import app.maskan.chat.data.local.ConversationDao
import app.maskan.chat.data.local.MessageDao
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What the chat shows while a video renders. Lives in WorkManager's progress data, NOT in the
 * database: a poll lands every five seconds and a SQLCipher write per poll would be pure
 * churn. The database holds only what must survive - the job id - and, at the end, the file.
 */
data class VideoProgress(
    /** waiting | queued | expanding | rendering | done */
    val phase: String,
    val progress: Int,
    val promptExpanded: String?,
    val etaSeconds: Int?
) {
    fun toData(): Data = workDataOf(
        KEY_PHASE to phase,
        KEY_PROGRESS to progress,
        KEY_PROMPT_EXPANDED to promptExpanded,
        KEY_ETA to (etaSeconds ?: -1)
    )

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_PROGRESS = "progress"
        const val KEY_PROMPT_EXPANDED = "prompt_expanded"
        const val KEY_ETA = "eta"

        /** Before the first poll answers WorkManager reports empty progress; that is "waiting". */
        val WAITING = VideoProgress("waiting", 0, null, null)

        fun fromData(data: Data): VideoProgress? {
            val phase = data.getString(KEY_PHASE) ?: return null
            return VideoProgress(
                phase = phase,
                progress = data.getInt(KEY_PROGRESS, 0),
                promptExpanded = data.getString(KEY_PROMPT_EXPANDED),
                etaSeconds = data.getInt(KEY_ETA, -1).takeIf { it >= 0 }
            )
        }
    }
}

/**
 * Enqueues, resumes and cancels the WorkManager job that waits for a video, and owns the
 * notification it shows while doing so. Manual DI: constructed once by MaskanApplication.
 *
 * One unique work item per message row, keyed by the row id, KEEP policy - so resuming on app
 * start never doubles up a job that is already being polled.
 */
class VideoJobs(private val context: Context) {

    fun enqueue(messageId: Long, conversationId: Long, providerId: String) {
        val request = OneTimeWorkRequestBuilder<VideoRenderWorker>()
            .setInputData(
                workDataOf(
                    VideoRenderWorker.KEY_MESSAGE_ID to messageId,
                    VideoRenderWorker.KEY_PROVIDER_ID to providerId
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            // Result.retry() is how the worker hands a long render back to WorkManager when it
            // could not hold a foreground service; ten seconds is the shortest backoff allowed.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG_VIDEO)
            .addTag(tagForMessage(messageId))
            .addTag(tagForConversation(conversationId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(messageId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(messageId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(messageId))
        NotificationManagerCompat.from(context).cancel(notificationId(messageId))
    }

    /**
     * On app start: every row that still holds a job id but no file gets its worker back. The
     * server keeps finished clips for days, so a job started before bed is collected in the
     * morning even if the phone rebooted in between.
     */
    suspend fun resumePending(messageDao: MessageDao, conversationDao: ConversationDao) {
        for (row in messageDao.getPendingVideoMessages()) {
            val providerId = conversationDao.getConversationById(row.conversationId)?.providerId ?: continue
            enqueue(row.id, row.conversationId, providerId)
        }
    }

    // ── Notifications ───────────────────────────────────────────────────

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.video_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    /** The quiet progress notification that lets the foreground service hold the render. */
    fun foregroundInfo(workId: UUID, messageId: Long, progress: VideoProgress): ForegroundInfo {
        val notification = progressNotification(workId, messageId, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(messageId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId(messageId), notification)
        }
    }

    fun updateProgress(workId: UUID, messageId: Long, progress: VideoProgress) {
        notify(notificationId(messageId), progressNotification(workId, messageId, progress))
    }

    fun showFinished(messageId: Long, success: Boolean, detail: String?) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_video)
            .setContentTitle(
                context.getString(if (success) R.string.video_ready else R.string.video_failed)
            )
            .apply { if (!detail.isNullOrBlank()) setContentText(detail) }
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(notificationId(messageId), notification)
    }

    private fun progressNotification(workId: UUID, messageId: Long, progress: VideoProgress): android.app.Notification {
        ensureChannel()
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val text = when (progress.phase) {
            "expanding" -> context.getString(R.string.video_writing_scene)
            "rendering", "done" -> progress.etaSeconds?.let { eta ->
                context.getString(R.string.video_eta_minutes, maxOf(1, (eta + 59) / 60))
            } ?: context.getString(R.string.video_making)
            else -> context.getString(R.string.video_waiting_server)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_video)
            .setContentTitle(context.getString(R.string.video_notification_title))
            .setContentText(text)
            .setProgress(100, progress.progress.coerceIn(0, 100), progress.phase != "rendering")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.video_cancel), cancelIntent)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // Without POST_NOTIFICATIONS (Android 13+) the foreground service still runs; only the
        // notification is withheld. Never let that surface as a crash.
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val CHANNEL_ID = "video_renders"
        const val TAG_VIDEO = "video"
        private const val TAG_MESSAGE_PREFIX = "video-msg:"
        private const val TAG_CONVERSATION_PREFIX = "video-conv:"

        fun uniqueName(messageId: Long) = "video-render-$messageId"
        fun tagForMessage(messageId: Long) = "$TAG_MESSAGE_PREFIX$messageId"
        fun tagForConversation(conversationId: Long) = "$TAG_CONVERSATION_PREFIX$conversationId"
        fun messageIdFromTags(tags: Set<String>): Long? =
            tags.firstOrNull { it.startsWith(TAG_MESSAGE_PREFIX) }
                ?.removePrefix(TAG_MESSAGE_PREFIX)?.toLongOrNull()

        /** Stable per message so a progress update replaces, never stacks. */
        fun notificationId(messageId: Long): Int = 41000 + (messageId % 100000).toInt()
    }
}
