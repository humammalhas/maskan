package app.maskan.chat.video

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import app.maskan.chat.MaskanApplication
import app.maskan.chat.R
import app.maskan.chat.data.local.MessageDao
import app.maskan.chat.data.remote.VideoJobClient
import app.maskan.chat.data.remote.providers.ProviderRegistry
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Waits for one video job and lands the clip in its message row.
 *
 * This is the part of the feature that has to survive a locked phone. The row was created and
 * the job submitted before this worker exists (ChatRepository); from here on everything the
 * worker needs is either in its input data (row id, provider id) or in the database (the job
 * id), so a process death, a reboot or a WorkManager reschedule loses nothing - it simply asks
 * the server where the job got to.
 *
 * Failure policy, in order of what was learned the hard way:
 *  - A poll that times out is NOT a failure. Under GPU load the link to a home server can
 *    stall for half a minute; the last known progress stays on screen and the next poll is
 *    tried. Only the server saying "failed", or forgetting the job, fails the message.
 *  - The worker asks for a foreground service (quiet notification with a Cancel action) so the
 *    OS lets it keep polling for the whole render. If the OS refuses - the app is deep in the
 *    background on Android 12+ - the worker polls for as long as WorkManager allows a plain
 *    worker and then hands the job back with retry(), keeping the job id. The clip is never
 *    lost: the server keeps it for days and the app resumes on next launch.
 *  - Cancel from the notification stops this worker with STOP_REASON_CANCELLED_BY_APP, and only
 *    then is the server told to cancel and the row dropped. Any other stop (constraint lost,
 *    time limit, system pressure) leaves the job alone so it can be resumed.
 */
class VideoRenderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MaskanApplication
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        val providerId = inputData.getString(KEY_PROVIDER_ID)
        if (messageId < 0 || providerId == null) return Result.failure()

        val dao = app.messageDao
        val row = dao.getMessageById(messageId) ?: return Result.success() // row deleted: nothing to do
        val jobId = row.videoJobId ?: return Result.success()
        if (row.imagePath != null) return Result.success()                  // already collected

        val provider = ProviderRegistry.getProvider(providerId)
        val baseUrl = app.keyRepository.getBaseUrl(providerId)?.takeIf { it.isNotBlank() }
            ?: provider?.defaultBaseUrl?.takeIf { it.isNotBlank() }
            ?: run {
                markFailed(dao, messageId, applicationContext.getString(R.string.video_no_server))
                return Result.failure()
            }
        val apiKey = app.keyRepository.getApiKey(providerId) ?: ""
        val client = app.videoBackendFor(providerId)
        val jobs = app.videoJobs

        var last = VideoProgress.WAITING
        val foreground = try {
            setForeground(jobs.foregroundInfo(id, messageId, last))
            true
        } catch (_: Exception) {
            false
        }
        val startedAt = SystemClock.elapsedRealtime()

        try {
            while (true) {
                if (!foreground && SystemClock.elapsedRealtime() - startedAt > BACKGROUND_BUDGET_MS) {
                    // Out of plain-worker time with the render still going: come back later.
                    return Result.retry()
                }

                val status = try {
                    client.status(baseUrl, apiKey, jobId)
                } catch (e: VideoJobClient.JobGone) {
                    markFailed(dao, messageId, applicationContext.getString(R.string.video_job_lost))
                    jobs.showFinished(messageId, success = false, detail = null)
                    return Result.failure()
                } catch (e: VideoJobClient.ServerError) {
                    if (e.isTransient) {
                        delay(POLL_MS)
                        continue
                    }
                    markFailed(dao, messageId, e.providerMessage ?: e.message ?: "HTTP ${e.code}")
                    jobs.showFinished(messageId, success = false, detail = e.providerMessage)
                    return Result.failure()
                } catch (e: IOException) {
                    // Wire trouble - a stalled link, a server mid-restart. Keep the last known
                    // state on screen and simply ask again.
                    delay(POLL_MS)
                    continue
                }

                last = VideoProgress(
                    phase = status.phase ?: if (status.status == "queued") "queued" else "rendering",
                    progress = status.progress,
                    promptExpanded = status.promptExpanded,
                    etaSeconds = status.etaSeconds?.toInt()
                )
                setProgress(last.toData())
                if (foreground) jobs.updateProgress(id, messageId, last)

                when (status.status) {
                    "completed" -> {
                        val bytes = try {
                            client.download(baseUrl, apiKey, jobId)
                        } catch (e: VideoJobClient.JobGone) {
                            markFailed(dao, messageId, applicationContext.getString(R.string.video_job_lost))
                            jobs.showFinished(messageId, success = false, detail = null)
                            return Result.failure()
                        } catch (e: VideoJobClient.ServerError) {
                            // 409 = not ready after all; anything transient = ask again.
                            delay(POLL_MS)
                            continue
                        } catch (e: IOException) {
                            delay(POLL_MS)
                            continue
                        }
                        val fileName = app.imageStore.save(bytes)
                        dao.updateVideoDone(messageId, fileName, VIDEO_MIME)
                        jobs.showFinished(messageId, success = true, detail = null)
                        return Result.success()
                    }
                    "failed" -> {
                        val reason = status.error
                            ?: applicationContext.getString(R.string.video_failed)
                        markFailed(dao, messageId, reason)
                        jobs.showFinished(messageId, success = false, detail = status.error)
                        return Result.failure()
                    }
                    "cancelled" -> {
                        // Cancelled on the server side (or by an earlier app instance): the row
                        // has nothing to show.
                        dao.deleteMessageById(messageId)
                        return Result.success()
                    }
                    else -> delay(POLL_MS)
                }
            }
        } catch (e: CancellationException) {
            val cancelledByApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
            if (cancelledByApp) {
                withContext(NonCancellable) {
                    runCatching { client.cancel(baseUrl, apiKey, jobId) }
                    runCatching { dao.deleteMessageById(messageId) }
                }
            }
            throw e
        }
    }

    private suspend fun markFailed(dao: MessageDao, messageId: Long, reason: String) {
        dao.markVideoFailed(messageId, reason)
    }

    companion object {
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_PROVIDER_ID = "provider_id"
        const val VIDEO_MIME = "video/mp4"
        private const val POLL_MS = 5_000L
        // WorkManager stops a non-foreground worker at ten minutes; hand back before that.
        private const val BACKGROUND_BUDGET_MS = 9 * 60 * 1000L
    }
}
