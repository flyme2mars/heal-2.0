package com.example.mychat.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class HealthDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "pdf" or "image"
    val internalPath: String,
    val timestamp: Long = System.currentTimeMillis()
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
            
            val doc = HealthDocument(
                id = id,
                name = originalName,
                type = if (extension.lowercase() == "pdf") "pdf" else "image",
                internalPath = internalName
            )
            
            healthDocumentDao.insertDocument(
                HealthDocumentEntity(
                    id = doc.id,
                    name = doc.name,
                    type = doc.type,
                    internalPath = doc.internalPath,
                    timestamp = doc.timestamp
                )
            )
            
            doc
        } catch (e: Exception) {
            android.util.Log.e("DocumentManager", "Failed to save encrypted document", e)
            null
        }
    }

    fun getAllDocuments() = healthDocumentDao.getAllDocuments()

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
}
