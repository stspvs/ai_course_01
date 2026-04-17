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
    val sourcePath: String,
    val ollamaModel: String,
    val chunkSize: Long,
    val overlap: Long,
    val chunkStrategy: String,
    val createdAt: Long,
    val chunkCount: Int,
)

/**
 * Чанк с эмбеддингом и метаданными документа для RAG-поиска.
 */
data class RagIndexedChunk(
    val chunkId: String,
    val documentId: String,
    val chunkIndex: Long,
    val text: String,
    val embedding: FloatArray,
    val ollamaModel: String,
    val documentTitle: String,
    val sourceFileName: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as RagIndexedChunk
        if (chunkId != other.chunkId) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

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
                        sourcePath = row.sourcePath,
                        ollamaModel = row.ollamaModel,
                        chunkSize = row.chunkSize,
                        overlap = row.overlap,
                        chunkStrategy = row.chunkStrategy,
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

    suspend fun loadAllChunksForRetrieval(): List<RagIndexedChunk> = withContext(Dispatchers.Default) {
        queries.getAllRagChunksWithDocuments().executeAsList().map { row ->
            RagIndexedChunk(
                chunkId = row.chunkId,
                documentId = row.documentId,
                chunkIndex = row.chunkIndex,
                text = row.chunkText,
                embedding = EmbeddingFloatCodec.littleEndianBytesToFloatArray(row.embeddingBlob),
                ollamaModel = row.ollamaModel.trim(),
                documentTitle = row.documentTitle,
                sourceFileName = row.sourceFileName,
            )
        }
    }

    /**
     * Сохраняет документ и чанки. Перед вставкой удаляются все строки для того же источника:
     * то же [RagDocumentEntity.sourceFileName], и путь совпадает с нормализованным [RagDocumentEntity.sourcePath].
     * Если вставляется путь к файлу (есть `/` или `\`), дополнительно удаляются старые строки с тем же именем файла,
     * где `sourcePath = id` (миграция со старых версий).
     *
     * @return true, если до вставки уже были строки для этого источника
     */
    suspend fun insertDocumentWithChunks(
        document: RagDocumentEntity,
        chunks: List<RagChunkEntity>,
    ): Boolean = withContext(Dispatchers.Default) {
        val pathKey = normalizeRagSourcePath(document.sourcePath)
        val looksLikeRealPathOnDisk = pathKey.contains('/') || pathKey.contains('\\')
        var replaced = false
        db.transaction {
            replaced = queries.selectRagDocumentIdsForSameFileSource(
                sourceFileName = document.sourceFileName,
                sourcePath = pathKey,
            ).executeAsList().isNotEmpty()
            queries.deleteRagDocumentsForSameFileSource(
                sourceFileName = document.sourceFileName,
                sourcePath = pathKey,
            )
            if (looksLikeRealPathOnDisk) {
                queries.deleteRagDocumentsLegacySourcePathEqualsIdForFileName(
                    sourceFileName = document.sourceFileName,
                )
            }
            queries.insertRagDocument(
                id = document.id,
                title = document.title,
                sourceFileName = document.sourceFileName,
                sourcePath = pathKey,
                ollamaModel = document.ollamaModel,
                chunkSize = document.chunkSize,
                overlap = document.overlap,
                chunkStrategy = document.chunkStrategy,
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
        replaced
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
