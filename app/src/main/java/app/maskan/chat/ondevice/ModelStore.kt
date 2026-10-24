package app.maskan.chat.ondevice

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Where a downloaded model lives, what it costs, and how to get rid of it.
 *
 * Everything about the file on disk is here rather than spread between the worker, the engine
 * and the screen, because three of the four questions this answers are ones the user is owed a
 * true number for: how much space this takes, how much comes back when they delete it, and
 * whether their phone can run it at all.
 *
 * `filesDir/models/` and not the cache directory: the system evicts a cache under pressure, and
 * a 1.6 GB download that the OS may silently throw away is not a download anyone should be
 * asked to make.
 */
class ModelStore(private val context: Context) {

    fun dir(): File = File(context.filesDir, MODELS_DIR).apply { mkdirs() }

    fun fileFor(model: OnDeviceModel): File = File(dir(), model.fileName)

    /**
     * The partial download. Renamed onto [fileFor] only after the sha256 matches, so a file
     * under the real name is always a file that has been checked.
     */
    fun partFor(model: OnDeviceModel): File = File(dir(), model.fileName + ".part")

    /**
     * Installed means present AND the right length.
     *
     * The length test costs nothing and catches the case that actually happens: a `.part`
     * renamed by hand, or a file truncated by a full disk. It is not a substitute for the
     * checksum - that is verified before the rename - it is the cheap guard on every launch.
     */
    fun isInstalled(model: OnDeviceModel): Boolean {
        val f = fileFor(model)
        return f.isFile && f.length() == model.bytes
    }

    fun partialBytes(model: OnDeviceModel): Long =
        partFor(model).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Delete the model and report how many bytes came back.
     *
     * The number is measured from the file before it is unlinked, not taken from the catalogue,
     * because the promise on the button is about this phone's disk and the catalogue is about
     * the published artifact. A half-finished `.part` counts too - it is the user's space.
     */
    fun delete(model: OnDeviceModel): Long {
        var freed = 0L
        for (f in listOf(fileFor(model), partFor(model))) {
            if (f.isFile) {
                val len = f.length()
                if (f.delete()) freed += len
            }
        }
        return freed
    }

    /** Total RAM the device reports. See OnDeviceModel.minRamGb for why total and not free. */
    fun deviceRamBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun hasEnoughRam(model: OnDeviceModel): Boolean = RamGate.fits(deviceRamBytes(), model.minRamGb)

    /** Free space where the model goes, which is not necessarily where the pictures go. */
    fun freeSpaceBytes(): Long = dir().usableSpace

    /**
     * Room for the download, counting what a resumed `.part` has already paid for.
     *
     * No doubling: the `.part` IS the file and is renamed in place, so the peak on disk is the
     * model's own size and not twice it.
     */
    fun hasRoomFor(model: OnDeviceModel): Boolean =
        freeSpaceBytes() + partialBytes(model) >= model.bytes + SPACE_HEADROOM

    /**
     * The sha256 of a file, in 1 MB bites, cancellable.
     *
     * This is what makes downloading a gigabyte and handing it to a native runtime a reasonable
     * thing to do. It takes about ten seconds on 1.6 GB, which is the price of knowing that the
     * bytes that arrived are the bytes that were measured - and it is checked against a hash
     * pinned in the APK, never one fetched from the same place as the file.
     */
    fun sha256(file: File, onProgress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                done += read
                onProgress(done)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODELS_DIR = "models"

        /**
         * Slack demanded on top of the model's own size before a download starts.
         *
         * A phone filled to the last megabyte by our download is a phone that cannot take a
         * photo afterwards.
         */
        private const val SPACE_HEADROOM = 200L * 1024 * 1024
    }
}
