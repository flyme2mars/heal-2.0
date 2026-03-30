package com.example.mychat.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// --- 1. Health Documents (Vault) ---
@Entity(tableName = "health_documents")
data class HealthDocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // "pdf", "image", "text"
    val recordType: String? = null, // e.g., "Blood Test", "MRI"
    val recordDate: String? = null, // ISO date string
    val userLabel: String? = null,
    val tags: String? = null,
    val internalPath: String,
    val timestamp: Long,
    val summary: String? = null,
    val fullText: String? = null // Extracted text for indexing
)

// --- 2. Chat Sessions (Threads) ---
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val summary: String? = null, // AI-generated session summary
    val metadata: String? = null // JSON for extra context (vitals, etc.)
)

// --- 3. Chat Messages (Context) ---
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "tool"
    val content: String,
    val reasoning: String? = null,
    val timestamp: Long,
    val metadata: String? = null, // JSON for UI metadata
    val toolCallId: String? = null, // For tool results
    val toolCallsJson: String? = null // For assistant messages with tool calls
)

@Dao
interface HealthDocumentDao {
    @Query("SELECT * FROM health_documents ORDER BY timestamp DESC")
    fun getAllDocuments(): Flow<List<HealthDocumentEntity>>

    @Query("SELECT * FROM health_documents ORDER BY timestamp DESC")
    suspend fun getAllDocumentsList(): List<HealthDocumentEntity>

    @Query("SELECT * FROM health_documents WHERE id = :id")
    suspend fun getDocumentById(id: String): HealthDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: HealthDocumentEntity)

    @Query("UPDATE health_documents SET summary = :summary, recordType = :recordType, recordDate = :recordDate WHERE id = :id")
    suspend fun updateMetadata(id: String, summary: String, recordType: String?, recordDate: String?)

    @Query("UPDATE health_documents SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: String, tags: String)

    @Query("UPDATE health_documents SET userLabel = :label WHERE id = :id")
    suspend fun updateUserLabel(id: String, label: String)

    @Query("UPDATE health_documents SET fullText = :text WHERE id = :id")
    suspend fun updateFullText(id: String, text: String)

    @Query("DELETE FROM health_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Dao
interface ChatDao {
    // Sessions
    @Query("SELECT * FROM chat_sessions ORDER BY lastUpdatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("UPDATE chat_sessions SET title = :title, lastUpdatedAt = :timestamp WHERE id = :id")
    suspend fun updateSessionTitle(id: String, title: String, timestamp: Long)

    // Messages
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionList(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun clearSessionHistory(sessionId: String)
}

@Database(entities = [HealthDocumentEntity::class, ChatSessionEntity::class, ChatMessageEntity::class], version = 6)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDocumentDao(): HealthDocumentDao
    abstract fun chatDao(): ChatDao
}
