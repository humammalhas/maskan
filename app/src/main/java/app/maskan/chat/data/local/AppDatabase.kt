package app.maskan.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FolderEntity::class,
        DocumentEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN providerId TEXT NOT NULL DEFAULT 'deepseek'")
                db.execSQL("ALTER TABLE conversations ADD COLUMN modelId TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageBase64 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN imageMimeType TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT DEFAULT NULL")
            }
        }

        // The one migration v2.5 allows itself: a video job id must survive process death, and
        // the message row is the only place that is both durable and already tied to the bubble.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN videoJobId TEXT DEFAULT NULL")
            }
        }

        /**
         * 2.6: a folder becomes a project. Two nullable TEXT columns, no data rewrite, so an
         * upgrade over a database full of real chats is two ALTERs and nothing else.
         *
         * Hand-written rather than a destructive fallback on purpose - the fallback would drop
         * every conversation on the phone, and this database is the only copy there is.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN instructions TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE folders ADD COLUMN memory TEXT DEFAULT NULL")
            }
        }

        /**
         * 2.6: a conversation can hold documents. One new table, no existing column touched, so
         * an upgrade over a phone full of real chats adds a table and changes nothing else.
         *
         * The text of a file lives here rather than in a message row because a message row is
         * re-sent on every following request, and a 30-page contract pasted into the history
         * would be paid for again on every question about it.
         *
         * Hand-written for the same reason as 7->8: the destructive fallback would drop every
         * conversation on the phone, and this database is the only copy there is.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS documents (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "conversationId INTEGER NOT NULL, " +
                        "attachedMessageId INTEGER, " +
                        "name TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "pages INTEGER NOT NULL DEFAULT 0, " +
                        "tokens INTEGER NOT NULL DEFAULT 0, " +
                        "text TEXT NOT NULL, " +
                        "notes TEXT, " +
                        "notesDone INTEGER NOT NULL DEFAULT 0, " +
                        "chunkCount INTEGER NOT NULL DEFAULT 0, " +
                        "chunkTokens INTEGER NOT NULL DEFAULT 0, " +
                        "pageImageIds TEXT, " +
                        "warning TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(conversationId) REFERENCES conversations(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_conversationId " +
                        "ON documents (conversationId)"
                )
            }
        }

        /**
         * 2.7: the pages a provider's web search read, kept with the answer. One nullable column,
         * additive only - every existing row reads null, which means "did not search". A backup
         * made at schema 9 restores through this same migration (BACKUP_FORMAT.md section 5).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sourcesJson TEXT")
            }
        }

        /**
         * 2.7: a reply the user rewrote is marked as rewritten. One nullable column, additive only -
         * every existing row reads null, which means "the model's own words". A backup made at
         * schema 10 or older restores through this same migration (BACKUP_FORMAT.md section 5).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER")
            }
        }

        /**
         * 2.7: an audio file on a user message and its transcript (plan item 3). Six nullable
         * columns, additive only - every existing row reads null, which means "no audio". A backup
         * made at schema 11 or older restores through this same migration (BACKUP_FORMAT.md section 5).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN audioPath TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN audioMimeType TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN audioName TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN audioDurationMs INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN transcript TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN transcribedBy TEXT")
            }
        }

        /**
         * 2.7: which model heard a voice note itself (plan item 3, "Listen and answer"). One nullable
         * column, additive only - every existing row reads null, "read as a transcript". A backup
         * made at schema 12 or older restores through this same migration.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN heardBy TEXT")
            }
        }

        fun getInstance(context: Context, passphrase: ByteArray): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val factory = SupportOpenHelperFactory(passphrase)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "privacyai_database"
                )
                    .openHelperFactory(factory)
                    .addMigrations(
                        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                        MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
