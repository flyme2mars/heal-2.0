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
    val type: String,
    val internalPath: String,
    val timestamp: Long,
    val summary: String? = null
)

@Dao
interface HealthDocumentDao {
    @Query("SELECT * FROM health_documents ORDER BY timestamp DESC")
    fun getAllDocuments(): Flow<List<HealthDocumentEntity>>

    @Query("SELECT * FROM health_documents WHERE id = :id")
    suspend fun getDocumentById(id: String): HealthDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: HealthDocumentEntity)

    @Query("UPDATE health_documents SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: String, summary: String)

    @Query("DELETE FROM health_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Database(entities = [HealthDocumentEntity::class], version = 2)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDocumentDao(): HealthDocumentDao
}
