package app.maskan.chat.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random

/**
 * Where generated images live: an AES-256-GCM encrypted file in app-private storage, with only
 * the file name recorded in the database.
 *
 * Why not the gallery, and why not the database?
 *  - The shared gallery (Pictures/) is readable by every app holding the photo permission and on
 *    most phones auto-uploads to Google Photos. Writing there by default would quietly undo the
 *    thing this app exists for. The user can still put a copy there deliberately - see the
 *    Save-to-phone flow, which writes a plain PNG wherever they choose via the Storage Access
 *    Framework and needs no storage permission on any API level.
 *  - The database would work but a full-size picture is 1-2MB and base64 adds ~35%; a few dozen
 *    would bloat the encrypted SQLCipher file for no benefit.
 *  - App-private storage alone is protected only by the device's disk encryption, so the file is
 *    encrypted with a key that never leaves the Android Keystore. This is invisible to the user:
 *    images decrypt for display, for saving and for sharing, with no password anywhere.
 *
 * Layout on disk is [12-byte IV][ciphertext+tag], one file per image.
 *
 * Note for the UI: app-private files are deleted when the app is uninstalled. Anything the user
 * wants to keep permanently has to be saved out - that is Android, not a choice made here, and
 * the app says so once.
 */
class ImageStore(private val context: Context) {

    fun save(bytes: ByteArray): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "img_${System.currentTimeMillis()}_${Random.nextInt(100000)}.enc"
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val body = cipher.doFinal(bytes)
        File(dir, name).outputStream().use { out ->
            out.write(cipher.iv)
            out.write(body)
        }
        return name
    }

    /** Decrypted bytes, or null if the file is gone or unreadable - never throws at the UI. */
    fun read(fileName: String): ByteArray? = try {
        val file = File(File(context.filesDir, DIR), fileName)
        if (!file.exists()) {
            null
        } else {
            val all = file.readBytes()
            if (all.size <= IV_BYTES) {
                null
            } else {
                val iv = all.copyOfRange(0, IV_BYTES)
                val body = all.copyOfRange(IV_BYTES, all.size)
                Cipher.getInstance(TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                    doFinal(body)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Room's foreign key cascade removes the message rows when a conversation is deleted, but it
     * knows nothing about the filesystem - without this the files would linger forever.
     */
    fun delete(fileNames: Collection<String>) {
        val dir = File(context.filesDir, DIR)
        fileNames.forEach { name ->
            runCatching { File(dir, name).delete() }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "maskan_image_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val DIR = "generated_images"
    }
}