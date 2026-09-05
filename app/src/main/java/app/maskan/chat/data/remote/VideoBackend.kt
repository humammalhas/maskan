package app.maskan.chat.data.remote

/**
 * The four calls the video spine needs from any server. VideoJobClient speaks the local
 * proxy's (Sora-shaped) job API; VeoVideoClient speaks Google's operations API. The worker,
 * the repository and the bubble see only this - that is what makes one spine serve both.
 */
interface VideoBackend {
    /** Create the job; returns the id the app must remember. */
    fun submit(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        seconds: Int,
        size: String,
        enhance: Boolean,
        imageDataUri: String? = null
    ): String

    fun status(baseUrl: String, apiKey: String, jobId: String): VideoJobClient.JobStatus

    fun download(baseUrl: String, apiKey: String, jobId: String): ByteArray

    fun cancel(baseUrl: String, apiKey: String, jobId: String)
}

/** A backend that can price a clip before making it. Only Venice offers this today. */
interface VideoQuoter {
    /** USD for one clip, or null when the server would not say. Blocking. */
    fun quote(baseUrl: String, apiKey: String, model: String, seconds: Int, size: String): Double?
}
