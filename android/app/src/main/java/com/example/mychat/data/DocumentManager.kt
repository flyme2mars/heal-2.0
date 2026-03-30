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
                summary = "Processing indexing..."
            )
            
            healthDocumentDao.insertDocument(docEntity)
            
            HealthDocument(
                id = docEntity.id, 
                name = docEntity.name, 
                type = docEntity.type, 
                recordType = docEntity.recordType, 
                recordDate = docEntity.recordDate, 
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
                internalPath = entity.internalPath,
                timestamp = entity.timestamp,
                summary = entity.summary,
                fullText = entity.fullText
            )
        }
    }

    suspend fun updateMetadata(id: String, summary: String, recordType: String?, recordDate: String?) {
        healthDocumentDao.updateMetadata(id, summary, recordType, recordDate)
    }

    suspend fun updateFullText(id: String, text: String) {
        healthDocumentDao.updateFullText(id, text)
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
            val docs = healthDocumentDao.getAllDocumentsList()
            val doc = docs.find { it.name == filename } ?: return "File not found in vault."
            
            val inputStream = getDocumentDecryptStream(
                HealthDocument(
                    id = doc.id, 
                    name = doc.name, 
                    type = doc.type, 
                    internalPath = doc.internalPath, 
                    timestamp = doc.timestamp, 
                    summary = doc.summary
                )
            )
            
            val content = inputStream.bufferedReader().use { it.readText() }
            
            if (doc.type == "pdf") {
                // PDF extraction would normally happen here. 
                // For now, we return the raw text or a note about the format.
                "This is a PDF document titled '${doc.name}'. Content extraction in progress..."
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
