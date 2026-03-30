package com.example.mychat.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class HealthDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "pdf" or "image"
    val recordType: String? = null,
    val recordDate: String? = null,
    val userLabel: String? = null,
    val tags: List<String> = emptyList(),
    val internalPath: String,
    val timestamp: Long = System.currentTimeMillis(),
    val summary: String? = null,
    val fullText: String? = null
)

@Singleton
class DocumentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthDocumentDao: HealthDocumentDao
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    suspend fun saveDocument(uri: Uri, originalName: String): HealthDocument? {
        val extension = originalName.substringAfterLast(".", "bin")
        val id = UUID.randomUUID().toString()
        val internalName = "doc_$id.$extension"
        val file = File(context.filesDir, internalName)

        return try {
            val encryptedFile = EncryptedFile.Builder(
                context,
                file,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()

            context.contentResolver.openInputStream(uri)?.use { input ->
                encryptedFile.openFileOutput().use { output ->
                    input.copyTo(output)
                }
            }
            
            val docEntity = HealthDocumentEntity(
                id = id,
                name = originalName,
                type = if (extension.lowercase() == "pdf") "pdf" else "image",
                internalPath = internalName,
                timestamp = System.currentTimeMillis(),
                summary = "Processing indexing...",
                userLabel = originalName // Default to original name
            )
            
            healthDocumentDao.insertDocument(docEntity)
            
            HealthDocument(
                id = docEntity.id, 
                name = docEntity.name, 
                type = docEntity.type, 
                recordType = docEntity.recordType, 
                recordDate = docEntity.recordDate, 
                userLabel = docEntity.userLabel,
                internalPath = docEntity.internalPath, 
                timestamp = docEntity.timestamp, 
                summary = docEntity.summary
            )
        } catch (e: Exception) {
            android.util.Log.e("DocumentManager", "Failed to save encrypted document", e)
            null
        }
    }

    fun getAllDocuments() = healthDocumentDao.getAllDocuments().map { entities ->
        entities.map { entity ->
            HealthDocument(
                id = entity.id,
                name = entity.name,
                type = entity.type,
                recordType = entity.recordType,
                recordDate = entity.recordDate,
                userLabel = entity.userLabel,
                tags = entity.tags?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                internalPath = entity.internalPath,
                timestamp = entity.timestamp,
                summary = entity.summary,
                fullText = entity.fullText
            )
        }
    }

    suspend fun updateMetadata(id: String, summary: String, recordType: String?, recordDate: String?, tags: List<String>? = null) {
        healthDocumentDao.updateMetadata(id, summary, recordType, recordDate)
        if (tags != null) {
            healthDocumentDao.updateTags(id, tags.joinToString(","))
        }
    }

    suspend fun updateUserLabel(id: String, label: String) {
        healthDocumentDao.updateUserLabel(id, label)
    }

    suspend fun updateFullText(id: String, text: String) {
        healthDocumentDao.updateFullText(id, text)
    }

    fun getTempFile(document: HealthDocument): File {
        val tempFile = File(context.cacheDir, "temp_${document.id}.${if(document.type == "pdf") "pdf" else "img"}")
        val inputStream = getDocumentDecryptStream(document)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        return tempFile
    }

    fun getDocumentDecryptStream(document: HealthDocument): java.io.InputStream {
        val file = File(context.filesDir, document.internalPath)
        val encryptedFile = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        
        return encryptedFile.openFileInput()
    }

    fun listDocuments(): List<File> {
        return context.filesDir.listFiles { _, name -> name.startsWith("doc_") }?.toList() ?: emptyList()
    }

    suspend fun deleteDocument(document: HealthDocument) {
        try {
            val file = File(context.filesDir, document.internalPath)
            if (file.exists()) {
                file.delete()
            }
            healthDocumentDao.deleteDocument(document.id)
        } catch (e: Exception) {
            android.util.Log.e("DocumentManager", "Failed to delete document: ${document.name}", e)
        }
    }

    suspend fun updateDocumentSummary(id: String, summary: String) {
        healthDocumentDao.updateMetadata(id, summary, null, null)
    }

    suspend fun readDocumentText(filename: String): String {
        return try {
            val entities = healthDocumentDao.getAllDocumentsList()
            val entity = entities.find { it.name == filename } ?: return "File not found in vault."
            
            if (!entity.fullText.isNullOrBlank()) {
                return entity.fullText
            }

            val doc = HealthDocument(
                id = entity.id, 
                name = entity.name, 
                type = entity.type, 
                internalPath = entity.internalPath, 
                timestamp = entity.timestamp, 
                summary = entity.summary,
                fullText = entity.fullText
            )
            
            val inputStream = getDocumentDecryptStream(doc)
            val content = inputStream.bufferedReader().use { it.readText() }
            
            if (entity.type == "pdf") {
                "PDF Content not indexed yet for '${entity.name}'."
            } else {
                content
            }
        } catch (e: Exception) {
            android.util.Log.e("DocumentManager", "Error reading file: $filename", e)
            "Error reading encrypted file: ${e.message}"
        }
    }

    fun getMemorySnapshot(): Map<String, String> {
        val memoryDir = File(context.filesDir, "memory")
        if (!memoryDir.exists()) memoryDir.mkdirs()
        
        return memoryDir.listFiles { _, name -> name.endsWith(".md") }?.associate { file ->
            file.name to file.readText()
        } ?: emptyMap()
    }

    fun saveMemoryFile(filename: String, content: String) {
        val memoryDir = File(context.filesDir, "memory")
        if (!memoryDir.exists()) memoryDir.mkdirs()
        
        val file = File(memoryDir, filename)
        file.writeText(content)
    }
}
