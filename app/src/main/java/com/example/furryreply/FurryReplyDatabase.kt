package com.example.furryreply

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "clients")
data class Client(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val platform: String,
    val username: String,
    val createdAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Client::class,
        parentColumns = ["id"],
        childColumns = ["clientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clientId"), Index(value = ["clientId", "timestamp"]), Index(value = ["clientId", "memoryProcessed"])]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val senderType: String,
    val content: String,
    val timestamp: Long,
    val memoryProcessed: Boolean = false
)

@Entity(
    tableName = "client_memories",
    foreignKeys = [ForeignKey(
        entity = Client::class,
        parentColumns = ["id"],
        childColumns = ["clientId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("clientId"), Index(value = ["clientId", "key"], unique = true)]
)
data class ClientMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val key: String,
    val value: String,
    val importance: Int,
    val category: String = "OTHER",
    val isActive: Boolean = true,
    val confidence: Float = 1.0f,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "conversation_summaries",
    foreignKeys = [ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["clientId"], unique = true)]
)
data class ConversationSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val content: String,
    val lastSummarizedMessageId: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "reply_feedback",
    foreignKeys = [ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("clientId"), Index(value = ["clientId", "timestamp"])]
)
data class ReplyFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: Long,
    val incomingMessage: String,
    val generatedSuggestion: String,
    val finalSentMessage: String,
    val selectedSuggestionIndex: Int,
    val conversationState: String = "CASUAL_CHAT",
    val replyIntent: String = "AUTO",
    val replyModifier: String = "NONE",
    val usedWhatIMean: Boolean = false,
    val selectedReplyLength: Int = 0,
    val selectedReplyEmojiCount: Int = 0,
    val timestamp: Long
)

@Entity(tableName = "style_profile")
data class StyleProfile(
    @PrimaryKey val id: Int = 1,
    val learnedReplyCount: Int,
    val summary: String,
    val updatedAt: Long
)

@Entity(
    tableName = "app_client_usage",
    primaryKeys = ["hostPackageName", "clientId"],
    foreignKeys = [ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("clientId"), Index(value = ["hostPackageName", "lastUsedAt"])]
)
data class AppClientUsage(
    val hostPackageName: String,
    val clientId: Long,
    val lastUsedAt: Long
)

@Entity(
    tableName = "client_conversation_states",
    foreignKeys = [ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["clientId"], unique = true)]
)
data class ClientConversationStateEntity(
    @PrimaryKey val clientId: Long,
    val automaticMode: Boolean,
    val manualState: String,
    val detectedState: String,
    val confidence: Float?,
    val updatedAt: Long
) {
    fun toModel() = ClientConversationState(
        clientId = clientId,
        automaticMode = automaticMode,
        manualState = manualState,
        detectedState = detectedState,
        confidence = confidence,
        updatedAt = updatedAt,
    )

    companion object {
        fun fromModel(model: ClientConversationState) = ClientConversationStateEntity(
            clientId = model.clientId,
            automaticMode = model.automaticMode,
            manualState = model.manualState,
            detectedState = model.detectedState,
            confidence = model.confidence,
            updatedAt = model.updatedAt,
        )
    }
}

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients ORDER BY displayName COLLATE NOCASE, id")
    fun getAll(): List<Client>

    @Query("SELECT * FROM clients WHERE id = :clientId")
    fun getById(clientId: Long): Client?

    @Insert
    fun insert(client: Client): Long

    @Query("DELETE FROM clients WHERE id = :clientId")
    fun deleteById(clientId: Long)

    @Query("DELETE FROM clients")
    fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("DELETE FROM messages")
    fun deleteAll()

    @Query("SELECT * FROM messages")
    fun getAll(): List<Message>

    @Insert
    fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE clientId = :clientId ORDER BY timestamp ASC, id ASC")
    fun getAllForClient(clientId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE clientId = :clientId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun getRecentForClient(clientId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM messages WHERE clientId = :clientId AND memoryProcessed = 0 ORDER BY id ASC LIMIT :limit")
    fun getUnprocessedForClient(clientId: Long, limit: Int): List<Message>

    @Query("UPDATE messages SET memoryProcessed = 1 WHERE id IN (:messageIds)")
    fun markMemoryProcessed(messageIds: List<Long>)

    @Query("SELECT COUNT(*) FROM messages WHERE clientId = :clientId")
    fun countForClient(clientId: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE clientId = :clientId AND id < :beforeId AND memoryProcessed = 0")
    fun countUnprocessedBefore(clientId: Long, beforeId: Long): Int

    @Query("SELECT * FROM messages WHERE clientId = :clientId AND id > :afterId AND id < :beforeId ORDER BY id ASC")
    fun getBetween(clientId: Long, afterId: Long, beforeId: Long): List<Message>

    @Query("DELETE FROM messages WHERE clientId = :clientId AND id < :beforeId")
    fun deleteBefore(clientId: Long, beforeId: Long)
}

@Dao
interface ClientMemoryDao {
    @Query("DELETE FROM client_memories")
    fun deleteAll()

    @Query("SELECT * FROM client_memories")
    fun getAll(): List<ClientMemory>

    @Query("SELECT * FROM client_memories WHERE clientId = :clientId ORDER BY importance DESC, updatedAt DESC, id DESC")
    fun getAllForClient(clientId: Long): List<ClientMemory>

    @Query("SELECT * FROM client_memories WHERE clientId = :clientId AND isActive = 1 ORDER BY importance DESC, updatedAt DESC, id DESC")
    fun getActiveForClient(clientId: Long): List<ClientMemory>

    @Query("SELECT * FROM client_memories WHERE clientId = :clientId AND key = :key LIMIT 1")
    fun getByKey(clientId: Long, key: String): ClientMemory?

    @Query("DELETE FROM client_memories WHERE clientId = :clientId AND key = :key")
    fun deleteByKey(clientId: Long, key: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(memory: ClientMemory): Long

    @Update
    fun update(memory: ClientMemory)

    @Query("DELETE FROM client_memories WHERE id = :memoryId")
    fun deleteById(memoryId: Long)
}

@Dao
interface ConversationSummaryDao {
    @Query("SELECT * FROM conversation_summaries")
    fun getAll(): List<ConversationSummary>

    @Query("SELECT * FROM conversation_summaries WHERE clientId = :clientId")
    fun getForClient(clientId: Long): ConversationSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(summary: ConversationSummary): Long
}

@Dao
interface ReplyFeedbackDao {
    @Query("SELECT * FROM reply_feedback")
    fun getAll(): List<ReplyFeedback>

    @Insert fun insert(feedback: ReplyFeedback): Long
    @Update fun update(feedback: ReplyFeedback)
    @Query("SELECT * FROM reply_feedback ORDER BY timestamp DESC, id DESC LIMIT :limit") fun getRecent(limit: Int): List<ReplyFeedback>
    @Query("SELECT * FROM reply_feedback WHERE id = :feedbackId") fun getById(feedbackId: Long): ReplyFeedback?
    @Query("SELECT * FROM reply_feedback WHERE timestamp >= :since") fun getAllAfter(since: Long): List<ReplyFeedback>
    @Query("DELETE FROM reply_feedback") fun deleteAll()
}

@Dao
interface StyleProfileDao {
    @Query("SELECT * FROM style_profile")
    fun getAll(): List<StyleProfile>

    @Query("SELECT * FROM style_profile WHERE id = 1") fun get(): StyleProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insert(profile: StyleProfile)
    @Query("DELETE FROM style_profile") fun deleteAll()
}

@Dao
interface AppClientUsageDao {
    @Query("SELECT * FROM app_client_usage")
    fun getAll(): List<AppClientUsage>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun upsert(usage: AppClientUsage)
    @Query("SELECT clientId FROM app_client_usage WHERE hostPackageName = :hostPackageName ORDER BY lastUsedAt DESC LIMIT 1") fun getLastClientId(hostPackageName: String): Long?
    @Query("SELECT clients.* FROM clients INNER JOIN app_client_usage ON clients.id = app_client_usage.clientId WHERE app_client_usage.hostPackageName = :hostPackageName ORDER BY app_client_usage.lastUsedAt DESC LIMIT :limit") fun getRecentClients(hostPackageName: String, limit: Int): List<Client>
}

@Dao
interface ClientConversationStateDao {
    @Query("SELECT * FROM client_conversation_states")
    fun getAll(): List<ClientConversationStateEntity>

    @Query("SELECT * FROM client_conversation_states WHERE clientId = :clientId")
    fun getForClient(clientId: Long): ClientConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: ClientConversationStateEntity)
}

@Entity(
    tableName = "commission_profiles",
    foreignKeys = [ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("clientId", unique = true)]
)
data class CommissionProfile(
    @PrimaryKey val clientId: Long,
    val status: String,
    val commissionType: String?,
    val quotedPrice: String?,
    val currency: String?,
    val clientBudget: String?,
    val subjectDescription: String?,
    val referenceNotes: String?,
    val deadline: String?,
    val paymentStatus: String,
    val revisionStatus: String,
    val createdAt: Long,
    val updatedAt: Long,
    val notes: String?
)

@Dao
interface CommissionProfileDao {
    @Query("SELECT * FROM commission_profiles")
    fun getAll(): List<CommissionProfile>

    @Query("SELECT * FROM commission_profiles WHERE clientId = :clientId")
    fun getForClient(clientId: Long): CommissionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: CommissionProfile)

    @Query("DELETE FROM commission_profiles WHERE clientId = :clientId")
    fun deleteForClient(clientId: Long)
}

@Database(
    entities = [
        Client::class, Message::class, ClientMemory::class, ConversationSummary::class,
        ReplyFeedback::class, StyleProfile::class, AppClientUsage::class, ClientConversationStateEntity::class,
        CommissionProfile::class
    ],
    version = 8,
    exportSchema = false,
)
abstract class FurryReplyDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun messageDao(): MessageDao
    abstract fun clientMemoryDao(): ClientMemoryDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun replyFeedbackDao(): ReplyFeedbackDao
    abstract fun styleProfileDao(): StyleProfileDao
    abstract fun appClientUsageDao(): AppClientUsageDao
    abstract fun clientConversationStateDao(): ClientConversationStateDao
    abstract fun commissionProfileDao(): CommissionProfileDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN memoryProcessed INTEGER NOT NULL DEFAULT 0")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_messages_clientId_memoryProcessed ON messages(clientId, memoryProcessed)")
                database.execSQL("CREATE TABLE IF NOT EXISTS conversation_summaries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clientId INTEGER NOT NULL, content TEXT NOT NULL, lastSummarizedMessageId INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(clientId) REFERENCES clients(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversation_summaries_clientId ON conversation_summaries(clientId)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS reply_feedback (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, clientId INTEGER NOT NULL, incomingMessage TEXT NOT NULL, generatedSuggestion TEXT NOT NULL, finalSentMessage TEXT NOT NULL, selectedSuggestionIndex INTEGER NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(clientId) REFERENCES clients(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_reply_feedback_clientId ON reply_feedback(clientId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_reply_feedback_clientId_timestamp ON reply_feedback(clientId, timestamp)")
                database.execSQL("CREATE TABLE IF NOT EXISTS style_profile (id INTEGER NOT NULL PRIMARY KEY, learnedReplyCount INTEGER NOT NULL, summary TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS app_client_usage (hostPackageName TEXT NOT NULL, clientId INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL, PRIMARY KEY(hostPackageName, clientId), FOREIGN KEY(clientId) REFERENCES clients(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_app_client_usage_clientId ON app_client_usage(clientId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_app_client_usage_hostPackageName_lastUsedAt ON app_client_usage(hostPackageName, lastUsedAt)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS client_conversation_states (
                        clientId INTEGER NOT NULL PRIMARY KEY,
                        automaticMode INTEGER NOT NULL,
                        manualState TEXT NOT NULL,
                        detectedState TEXT NOT NULL,
                        confidence REAL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(clientId) REFERENCES clients(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_client_conversation_states_clientId ON client_conversation_states(clientId)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN conversationState TEXT NOT NULL DEFAULT 'CASUAL_CHAT'")
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN replyIntent TEXT NOT NULL DEFAULT 'AUTO'")
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN replyModifier TEXT NOT NULL DEFAULT 'NONE'")
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN usedWhatIMean INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN selectedReplyLength INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE reply_feedback ADD COLUMN selectedReplyEmojiCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE client_memories ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER'")
                database.execSQL("ALTER TABLE client_memories ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE client_memories ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                database.execSQL("ALTER TABLE client_memories ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE client_memories SET createdAt = updatedAt")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS commission_profiles (
                        clientId INTEGER NOT NULL PRIMARY KEY,
                        status TEXT NOT NULL,
                        commissionType TEXT,
                        quotedPrice TEXT,
                        currency TEXT,
                        clientBudget TEXT,
                        subjectDescription TEXT,
                        referenceNotes TEXT,
                        deadline TEXT,
                        paymentStatus TEXT NOT NULL,
                        revisionStatus TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        notes TEXT,
                        FOREIGN KEY(clientId) REFERENCES clients(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_commission_profiles_clientId ON commission_profiles(clientId)")
            }
        }

        @Volatile private var instance: FurryReplyDatabase? = null

        fun get(context: Context): FurryReplyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FurryReplyDatabase::class.java,
                "furryreply.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
        }
    }
}
