package com.example.ai_develop.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.example.ai_develop.database.AgentDatabase
import com.example.aidevelop.database.RagChunkEntity
import com.example.aidevelop.database.RagDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class RagDocumentSummary(
    val id: String,
    val title: String,
    val sourceFileName: String,
    val ollamaModel: String,
    val chunkSize: Long,
    val overlap: Long,
    val createdAt: Long,
    val chunkCount: Int,
)

data class RagStoredChunk(
    val id: String,
    val chunkIndex: Long,
    val startOffset: Long,
    val endOffset: Long,
    val text: String,
    val embeddingBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RagStoredChunk
        if (id != other.id) return false
        if (!embeddingBlob.contentEquals(other.embeddingBlob)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}

class RagEmbeddingRepository(
    private val db: AgentDatabase,
) {

    private val queries = db.agentDatabaseQueries

    fun observeAllDocuments(): Flow<List<RagDocumentSummary>> {
        return queries.getAllRagDocuments()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    val id = row.id
                    val count = queries.getChunksForRagDocument(id).executeAsList().size
                    RagDocumentSummary(
                        id = id,
                        title = row.title,
                        sourceFileName = row.sourceFileName,
                        ollamaModel = row.ollamaModel,
                        chunkSize = row.chunkSize,
                        overlap = row.overlap,
                        createdAt = row.createdAt,
                        chunkCount = count,
                    )
                }
            }
    }

    suspend fun getDocument(id: String): RagDocumentEntity? = withContext(Dispatchers.Default) {
        queries.getRagDocument(id).executeAsOneOrNull()
    }

    suspend fun getChunks(documentId: String): List<RagStoredChunk> = withContext(Dispatchers.Default) {
        queries.getChunksForRagDocument(documentId).executeAsList().map { it.toStoredChunk() }
    }

    suspend fun insertDocumentWithChunks(
        document: RagDocumentEntity,
        chunks: List<RagChunkEntity>,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            queries.insertRagDocument(
                id = document.id,
                title = document.title,
                sourceFileName = document.sourceFileName,
                ollamaModel = document.ollamaModel,
                chunkSize = document.chunkSize,
                overlap = document.overlap,
                createdAt = document.createdAt,
                fullText = document.fullText,
            )
            chunks.forEach { chunk ->
                queries.insertRagChunk(
                    id = chunk.id,
                    documentId = chunk.documentId,
                    chunkIndex = chunk.chunkIndex,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    text = chunk.text,
                    embeddingBlob = chunk.embeddingBlob,
                )
            }
        }
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.Default) {
        queries.deleteRagDocument(id)
    }
}

private fun RagChunkEntity.toStoredChunk() = RagStoredChunk(
    id = id,
    chunkIndex = chunkIndex,
    startOffset = startOffset,
    endOffset = endOffset,
    text = text,
    embeddingBlob = embeddingBlob,
)
