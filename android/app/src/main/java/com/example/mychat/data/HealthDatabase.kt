package com.example.mychat.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

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

@Database(entities = [HealthDocumentEntity::class], version = 4)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDocumentDao(): HealthDocumentDao
}
