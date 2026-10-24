package app.maskan.chat.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import app.maskan.chat.MaskanApplication
import app.maskan.chat.data.backup.ArchiveCrypto
import app.maskan.chat.data.backup.ArchiveException
import app.maskan.chat.data.backup.ArchiveRefusal
import app.maskan.chat.data.backup.BackupFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The backup harness: known-answer vectors for the key derivation, then a real archive written,
 * read back and compared row by row against the live database.
 *
 * DEBUG BUILDS ONLY - this file lives in src/debug and is not compiled into a release.
 *
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.BackupProbeReceiver \
 *     --es what vectors
 *   adb shell am broadcast -n app.maskan.chat.debug/app.maskan.chat.debug.BackupProbeReceiver \
 *     --es what roundtrip
 *
 * `what` also takes `all` (the default). A password with Arabic in it cannot be passed with
 * `--es password` from a Windows host - that lesson is two sessions old - so the round trip uses
 * a fixed Arabic password compiled in here.
 */
class BackupProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.getStringExtra("what") ?: "all"
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (what == "all" || what == "vectors") vectors()
                if (what == "seed") seed(context, intent.getIntExtra("chats", 40))
                if (what == "unseed") unseed(context)
                if (what == "olderschema") {
                    olderSchema(context, intent.getStringExtra("password") ?: ARABIC_PASSWORD)
                }
                if (what == "all" || what == "roundtrip") roundTrip(context)
            } catch (e: Throwable) {
                Log.e(TAG, "probe failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    // -- 1. The key derivation, against published answers --------------------

    private fun vectors() {
        Log.i(TAG, "== PBKDF2 known-answer vectors ==")
        var failures = 0

        // RFC 6070. HMAC-SHA1, and the format never uses it - it is here because these are the
        // published answers for the LOOP, and the loop is the part that is ours.
        failures += check(
            "rfc6070 c=1",
            "HmacSHA1", "password", "salt", 1, 20,
            "0c60c80f961f0e71f3a9b524af6012062fe037a6"
        )
        failures += check(
            "rfc6070 c=2",
            "HmacSHA1", "password", "salt", 2, 20,
            "ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957"
        )
        failures += check(
            "rfc6070 c=4096",
            "HmacSHA1", "password", "salt", 4096, 20,
            "4b007901b765489abead49d926f721d065a429c1"
        )
        failures += check(
            "rfc6070 long salt, dkLen 25",
            "HmacSHA1", "passwordPASSWORDpassword",
            "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 25,
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038"
        )

        // HMAC-SHA256 - the one the format actually uses. Widely published vectors.
        failures += check(
            "sha256 c=1",
            "HmacSHA256", "password", "salt", 1, 32,
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        )
        failures += check(
            "sha256 c=4096",
            "HmacSHA256", "password", "salt", 4096, 32,
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"
        )
        failures += check(
            "sha256 long salt, dkLen 40",
            "HmacSHA256", "passwordPASSWORDpassword",
            "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 40,
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1" +
                "c635518c7dac47e9"
        )

        // The fixture that exists because of Arabic: the password below is 14 characters and 26
        // bytes of UTF-8. A provider that kept only the low eight bits of each char would hash
        // 14 bytes instead and produce something else entirely - and would keep doing it
        // consistently, which is why this has to be pinned rather than trusted.
        val arabicSalt = ByteArray(16) { it.toByte() }
        val arabicPassword = ARABIC_PASSWORD.toByteArray(Charsets.UTF_8)
        Log.i(TAG, "arabic password: " + ARABIC_PASSWORD.length + " chars, " +
            arabicPassword.size + " utf-8 bytes = " + ArchiveCrypto.hex(arabicPassword))
        failures += checkBytes(
            "maskan arabic fixture c=1000 dkLen=64",
            ArchiveCrypto.pbkdf2("HmacSHA256", arabicPassword, arabicSalt, 1000, 64),
            "ad633acee6705a52115615f7b69f416c6e6d6a8efc7067d997b7fbd68cd3a9be" +
                "1d81bce5b6969c5adca473d4a3aad3e03d22f9fd3c6d0d4e6bbaa977efef8238"
        )

        // The rest of the chain: the two HMAC labels that turn that block into the key the
        // archive is actually encrypted with, and into the password check. A typo in a label
        // passes every RFC vector above and makes every file ever written unreadable.
        val derived = ArchiveCrypto.derive(ARABIC_PASSWORD, arabicSalt, 1000)
        failures += checkBytes(
            "maskan derived key c=1000",
            derived.encryptionKey,
            "0c2d54db9335e090a96ebed27ad32431c185305225dc28f23599eab28f5abb4f"
        )
        failures += checkBytes(
            "maskan password check c=1000",
            derived.checkValue(),
            "ee65cd2a4307922b31f12250615fed6f"
        )
        derived.clear()

        // What the platform's own factory does with the same password, recorded rather than
        // argued about. Measured 2026-09-22: it agrees with UTF-8 on this phone.
        runCatching {
            val spec = javax.crypto.spec.PBEKeySpec(
                ARABIC_PASSWORD.toCharArray(), arabicSalt, 1000, 64 * 8
            )
            val viaFactory = javax.crypto.SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            Log.i(TAG, "platform SecretKeyFactory on the same input: " +
                ArchiveCrypto.hex(viaFactory).take(32) + "...")
        }.onFailure { Log.i(TAG, "platform SecretKeyFactory: " + it.javaClass.simpleName) }

        val started = System.currentTimeMillis()
        ArchiveCrypto.derive("a real password", arabicSalt, BackupFormat.DEFAULT_ITERATIONS)
        val ms = System.currentTimeMillis() - started
        Log.i(TAG, "derive at " + BackupFormat.DEFAULT_ITERATIONS + " iterations: " + ms + " ms")

        Log.i(TAG, if (failures == 0) "== vectors: ALL PASS ==" else "== vectors: " + failures + " FAILED ==")
    }

    private fun check(
        name: String,
        algorithm: String,
        password: String,
        salt: String,
        iterations: Int,
        length: Int,
        expected: String
    ): Int = checkBytes(
        name,
        ArchiveCrypto.pbkdf2(
            algorithm,
            password.toByteArray(Charsets.UTF_8),
            salt.toByteArray(Charsets.UTF_8),
            iterations,
            length
        ),
        expected
    )

    private fun checkBytes(name: String, actual: ByteArray, expected: String): Int {
        val got = ArchiveCrypto.hex(actual)
        return if (got == expected) {
            Log.i(TAG, "PASS " + name)
            0
        } else {
            Log.e(TAG, "FAIL " + name + "\n  expected " + expected + "\n  got      " + got)
            1
        }
    }

    // -- 2. Write an archive, read it back, compare ------------------------

    private suspend fun roundTrip(context: Context) {
        val app = context.applicationContext as MaskanApplication
        Log.i(TAG, "== round trip ==")

        val liveDigests = digests { table ->
            app.appDatabase.openHelper.readableDatabase.query("SELECT * FROM " + table + " ORDER BY id")
        }
        val inventory = app.backupWriter.inventory()
        Log.i(TAG, "live: " + inventory.counts + ", estimate " + inventory.estimatedBytes + " bytes")

        val file = File(context.cacheDir, "probe-backup.mkb")
        file.delete()
        val uri = Uri.fromFile(file)
        var lastPercent = -1
        val started = System.currentTimeMillis()
        val header = app.backupWriter.write(uri, ARABIC_PASSWORD) { progress ->
            val percent = (progress * 100).toInt()
            if (percent / 10 != lastPercent / 10) {
                lastPercent = percent
                Log.d(TAG, "  writing " + percent + "%")
            }
        }
        val writeMs = System.currentTimeMillis() - started
        Log.i(TAG, "wrote " + file.length() + " bytes in " + writeMs + " ms; header counts " + header.counts)

        // The plaintext header, read the way the restore screen will read it: no password.
        val headerOnly = app.backupReader.readHeader(uri)
        Log.i(TAG, "header without a password: made " + headerOnly.header.createdAt +
            " by " + headerOnly.header.app.versionName +
            ", schema " + headerOnly.header.schema +
            ", keys " + headerOnly.header.keysIncluded)

        val out = File(context.cacheDir, "probe-extract").apply { deleteRecursively(); mkdirs() }
        val readStarted = System.currentTimeMillis()
        val extracted = app.backupReader.extractTo(uri, ARABIC_PASSWORD, out)
        Log.i(TAG, "read back in " + (System.currentTimeMillis() - readStarted) + " ms")

        var failures = 0
        failures += expect("header counts == manifest counts",
            header.counts == extracted.manifest.counts, "" + header.counts + " vs " + extracted.manifest.counts)
        failures += expect("header counts == live counts",
            header.counts == inventory.counts, "" + header.counts + " vs " + inventory.counts)
        failures += expect("manifest schema == live schema",
            extracted.manifest.schema == app.appDatabase.openHelper.readableDatabase.version,
            "" + extracted.manifest.schema)

        val restored = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            extracted.database.absolutePath,
            extracted.manifest.dbKey,
            null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
            null,
            null
        )
        try {
            val restoredVersion = restored.rawQuery("PRAGMA user_version", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            failures += expect("restored user_version survives the export",
                restoredVersion == extracted.manifest.schema, "" + restoredVersion)

            val restoredDigests = digests { table ->
                restored.rawQuery("SELECT * FROM " + table + " ORDER BY id", null)
            }
            for ((table, digest) in liveDigests) {
                val other = restoredDigests[table]
                failures += expect(
                    "table " + table + " identical row for row",
                    digest == other,
                    digest + " vs " + other
                )
            }
            val restoredCounts = TABLES.associateWith { table ->
                restored.rawQuery("SELECT COUNT(*) FROM " + table, null)
                    .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            }
            failures += expect("payload rows == header counts",
                restoredCounts["conversations"] == header.counts.chats &&
                    restoredCounts["messages"] == header.counts.messages &&
                    restoredCounts["folders"] == header.counts.folders &&
                    restoredCounts["documents"] == header.counts.documents,
                restoredCounts.toString())
        } finally {
            restored.close()
        }

        val keysJson = extracted.keys?.readText() ?: ""
        failures += expect("the api keys travelled",
            keysJson.contains("_api_key") || header.counts.apiKeys == 0,
            "keys.json is " + (extracted.keys?.length() ?: -1) + " bytes")
        failures += expect("the device database key did NOT travel",
            !keysJson.contains(BackupFormat.DB_PREFS_KEY) &&
                (extracted.settings?.readText()?.contains(BackupFormat.DB_PREFS_KEY) != true),
            "checked keys.json and settings.json")
        Log.i(TAG, "skipped by the deny list: " + extracted.manifest.skippedPrefs)
        Log.i(TAG, "carried: " + extracted.manifest.carriedPrefs)

        // -- the refusals -------------------------------------------------
        failures += refuses("wrong password", ArchiveRefusal.WRONG_PASSWORD) {
            app.backupReader.extractTo(uri, "not the password", out)
        }

        val cut = File(context.cacheDir, "probe-truncated.mkb")

        // 64 bytes is the outer zip's trailer, which ZipInputStream never reads. The payload is
        // whole, so this one must be ACCEPTED - and the reader now proves it by hashing the
        // database against the manifest rather than taking its length on trust.
        file.copyTo(cut, overwrite = true)
        RandomAccessFile(cut, "rw").use { it.setLength(cut.length() - 64L) }
        failures += try {
            val stillGood = app.backupReader.extractTo(Uri.fromFile(cut), ARABIC_PASSWORD, out)
            expect(
                "zip trailer cut off, payload intact -> still restorable",
                stillGood.manifest.dbSha256 == extracted.manifest.dbSha256,
                stillGood.manifest.dbSha256
            )
        } catch (e: Throwable) {
            Log.e(TAG, "FAIL zip trailer cut off -> " + e.javaClass.simpleName + " " + e.message)
            1
        }

        for (bytesOff in listOf(2048L, 65536L, file.length() / 2)) {
            file.copyTo(cut, overwrite = true)
            RandomAccessFile(cut, "rw").use { it.setLength(cut.length() - bytesOff) }
            failures += refuses("truncated by " + bytesOff + " bytes", ArchiveRefusal.DAMAGED) {
                app.backupReader.extractTo(Uri.fromFile(cut), ARABIC_PASSWORD, out)
            }
        }

        val junk = File(context.cacheDir, "probe-junk.mkb")
        junk.writeBytes(ByteArray(40000) { (it * 7).toByte() })
        failures += refuses("not a Maskan file", ArchiveRefusal.NOT_A_BACKUP) {
            app.backupReader.readHeader(Uri.fromFile(junk))
        }

        val flipped = File(context.cacheDir, "probe-flipped.mkb")
        file.copyTo(flipped, overwrite = true)
        RandomAccessFile(flipped, "rw").use { raf ->
            val at = raf.length() - 4096
            raf.seek(at)
            val b = raf.read()
            raf.seek(at)
            raf.write(b xor 0xFF)
        }
        failures += refuses("one flipped byte inside the payload", ArchiveRefusal.DAMAGED) {
            app.backupReader.extractTo(Uri.fromFile(flipped), ARABIC_PASSWORD, out)
        }

        out.deleteRecursively()
        cut.delete()
        junk.delete()
        flipped.delete()
        Log.i(TAG, "probe archive left at " + file.absolutePath + " (" + file.length() + " bytes)")
        Log.i(TAG, if (failures == 0) "== round trip: ALL PASS ==" else "== round trip: " + failures + " FAILED ==")
    }

    // -- 2b. An archive from an OLDER Maskan ------------------------------

    /**
     * Writes `probe-schema8.mkb`: this phone's data as a 2.6-session-3 database would have held
     * it - no `documents` table, `user_version` 8. Restoring it must run MIGRATION_8_9 and keep
     * every row. Pull it with `run-as`, push it to Downloads, pick it in the app.
     */
    private suspend fun olderSchema(context: Context, password: String) {
        val app = context.applicationContext as MaskanApplication
        Log.i(TAG, "== schema-8 archive (password of " + password.length + " chars) ==")
        val base = File(context.cacheDir, "probe-base.mkb").apply { delete() }
        app.backupWriter.write(Uri.fromFile(base), ARABIC_PASSWORD) {}
        val out = File(context.cacheDir, "probe-s8-extract").apply { deleteRecursively(); mkdirs() }
        val extracted = app.backupReader.extractTo(Uri.fromFile(base), ARABIC_PASSWORD, out)

        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            extracted.database.absolutePath, extracted.manifest.dbKey, null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null
        )
        val counts = try {
            db.execSQL("DROP TABLE IF EXISTS documents")
            // Every column a migration after 8 adds goes too, or MIGRATION_9_10 meets a column it
            // is about to create and the restore fails with "duplicate column" - which this probe
            // did from schema 10 on (2.7 session 7). A new migration adds its column here.
            for (column in COLUMNS_ADDED_AFTER_8) db.execSQL("ALTER TABLE messages DROP COLUMN " + column)
            db.execSQL("PRAGMA user_version = 8")
            val version = db.rawQuery("PRAGMA user_version", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            Log.i(TAG, "snapshot rewritten: user_version " + version + ", documents dropped")
            extracted.manifest.counts.copy(documents = 0)
        } finally {
            db.close()
        }

        val target = File(context.cacheDir, "probe-schema8.mkb").apply { delete() }
        val header = app.backupWriter.writeFromSnapshot(
            Uri.fromFile(target), password, extracted.database,
            extracted.manifest.dbKey, 8, counts
        ) {}
        // Read it back the way restore will, so a bad probe file is found here and not there.
        val check = app.backupReader.readHeader(Uri.fromFile(target))
        Log.i(TAG, "schema-8 archive at " + target.absolutePath + " (" + target.length() +
            " bytes): header schema " + check.header.schema + ", counts " + header.counts)
        migratesToCurrent(extracted.database, extracted.manifest.dbKey, counts)
        out.deleteRecursively()
        base.delete()
    }

    /**
     * The restore path without the restore: the app's own migrations, 8 up to the current schema,
     * run on the schema-8 snapshot in the cache - never on the live database. Every row must still
     * be there, and every column a later migration adds must exist afterwards.
     */
    private fun migratesToCurrent(file: File, key: String, counts: app.maskan.chat.data.backup.BackupCounts) {
        val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
            file.absolutePath, key, null,
            net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null, null
        )
        try {
            val migrations = listOf(
                app.maskan.chat.data.local.AppDatabase.MIGRATION_8_9,
                app.maskan.chat.data.local.AppDatabase.MIGRATION_9_10,
                app.maskan.chat.data.local.AppDatabase.MIGRATION_10_11
            )
            for (m in migrations) m.migrate(db)
            val messages = db.rawQuery("SELECT COUNT(*) FROM messages", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            val chats = db.rawQuery("SELECT COUNT(*) FROM conversations", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            val columns = db.rawQuery("PRAGMA table_info(messages)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            val ok = messages == counts.messages && chats == counts.chats &&
                columns.containsAll(COLUMNS_ADDED_AFTER_8)
            val detail = "8 -> " + migrations.last().endVersion + ": chats " + chats + "/" + counts.chats +
                ", messages " + messages + "/" + counts.messages + ", columns " + columns
            if (ok) Log.i(TAG, "PASS schema-8 archive migrates to current: " + detail)
            else Log.e(TAG, "FAIL schema-8 archive migrates to current: " + detail)
        } catch (e: Throwable) {
            Log.e(TAG, "FAIL schema-8 archive migrates to current: " + e.javaClass.simpleName + " " + e.message)
        } finally {
            db.close()
        }
    }

    // -- 3. Volume, so the numbers mean something ------------------------

    private fun seed(context: Context, chats: Int) {
        val app = context.applicationContext as MaskanApplication
        val db = app.appDatabase.openHelper.writableDatabase
        Log.i(TAG, "seeding " + chats + " chats; before: " + counts(context))
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (c in 0 until chats) {
                db.execSQL(
                    "INSERT INTO conversations (title, createdAt, systemPromptId, dialectId, " +
                        "folderId, providerId, modelId) VALUES (?, ?, NULL, NULL, NULL, ?, NULL)",
                    arrayOf<Any>(SEED_PREFIX + " " + c + " \u0645\u062d\u0627\u062f\u062b\u0629",
                        now - c * 60000L, "gemini")
                )
                val id = db.query("SELECT last_insert_rowid()")
                    .use { if (it.moveToFirst()) it.getLong(0) else -1L }
                for (m in 0 until 125) {
                    db.execSQL(
                        "INSERT INTO messages (conversationId, role, content, timestamp) " +
                            "VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(
                            id,
                            if (m % 2 == 0) "user" else "assistant",
                            SEED_PREFIX + " " + c + "/" + m + " " + SEED_TEXT,
                            now - (c * 125L + m) * 1000L
                        )
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "seeded; after: " + counts(context))
    }

    private fun unseed(context: Context) {
        val app = context.applicationContext as MaskanApplication
        val db = app.appDatabase.openHelper.writableDatabase
        Log.i(TAG, "unseeding; before: " + counts(context))
        // Messages go with their conversation by ON DELETE CASCADE, but any row that ever got
        // orphaned is removed by its own marker too - deleting by prefix on both tables cannot
        // touch anything a person typed.
        db.execSQL("DELETE FROM messages WHERE content LIKE ?", arrayOf(SEED_PREFIX + "%"))
        db.execSQL("DELETE FROM conversations WHERE title LIKE ?", arrayOf(SEED_PREFIX + "%"))
        Log.i(TAG, "unseeded; after: " + counts(context))
    }

    private fun counts(context: Context): String {
        val app = context.applicationContext as MaskanApplication
        val db = app.appDatabase.openHelper.readableDatabase
        return TABLES.joinToString(", ") { table ->
            table + "=" + db.query("SELECT COUNT(*) FROM " + table)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        }
    }

    private fun expect(name: String, condition: Boolean, detail: String): Int =
        if (condition) {
            Log.i(TAG, "PASS " + name)
            0
        } else {
            Log.e(TAG, "FAIL " + name + " : " + detail)
            1
        }

    private suspend fun refuses(
        name: String,
        vararg accepted: ArchiveRefusal,
        block: suspend () -> Unit
    ): Int = try {
        block()
        Log.e(TAG, "FAIL " + name + " : it was accepted")
        1
    } catch (e: ArchiveException) {
        if (e.refusal in accepted) {
            Log.i(TAG, "PASS " + name + " -> " + e.refusal + " (" + e.message + ")")
            0
        } else {
            Log.e(TAG, "FAIL " + name + " -> " + e.refusal + " (" + e.message + ")")
            1
        }
    } catch (e: Throwable) {
        Log.e(TAG, "FAIL " + name + " -> " + e.javaClass.simpleName + " " + e.message)
        1
    }

    private fun digests(cursorFor: (String) -> Cursor): Map<String, String> =
        TABLES.associateWith { table -> cursorFor(table).use { digestOf(it) } }

    /** Every column of every row, in id order, through SHA-256. Two databases that agree here
     *  hold the same data; a count alone would not have noticed a truncated message. */
    private fun digestOf(cursor: Cursor): String {
        val digest = MessageDigest.getInstance("SHA-256")
        while (cursor.moveToNext()) {
            for (column in 0 until cursor.columnCount) {
                digest.update(cursor.getColumnName(column).toByteArray(Charsets.UTF_8))
                when (cursor.getType(column)) {
                    Cursor.FIELD_TYPE_NULL -> digest.update(0)
                    Cursor.FIELD_TYPE_INTEGER -> digest.update(cursor.getLong(column).toString().toByteArray())
                    Cursor.FIELD_TYPE_FLOAT -> digest.update(cursor.getDouble(column).toString().toByteArray())
                    Cursor.FIELD_TYPE_STRING -> digest.update(cursor.getString(column).toByteArray(Charsets.UTF_8))
                    Cursor.FIELD_TYPE_BLOB -> digest.update(cursor.getBlob(column))
                }
            }
        }
        return ArchiveCrypto.hex(digest.digest()).take(16)
    }

    companion object {
        private const val TAG = "MaskanBackupProbe"
        private val TABLES = listOf("conversations", "messages", "folders", "documents")

        /** `messages` columns MIGRATION_9_10 and later add, newest last. */
        private val COLUMNS_ADDED_AFTER_8 = listOf("sourcesJson", "editedAt")

        /** Nobody types this. It is what `unseed` deletes by, and the only thing it deletes. */
        private const val SEED_PREFIX = "MKB-SEED"
        private const val SEED_TEXT =
            "\u0647\u0630\u0627 \u0646\u0635 \u0639\u0631\u0628\u064a " +
                "\u0644\u0644\u0627\u062e\u062a\u0628\u0627\u0631 - Arabic test text, " +
                "long enough to be worth compressing, repeated across thousands of rows."


        /** 14 characters, 26 bytes of UTF-8. The whole reason the KDF is written by hand. */
        private const val ARABIC_PASSWORD = "كلمة السر " +
            "٢٠٢٦"
    }
}
