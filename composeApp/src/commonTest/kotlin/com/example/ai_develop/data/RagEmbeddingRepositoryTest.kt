@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.example.ai_develop.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.stageAdapter
import com.example.aidevelop.database.RagChunkEntity
import com.example.aidevelop.database.RagDocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class RagEmbeddingRepositoryTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: AgentDatabase
    private lateinit var repository: RagEmbeddingRepository

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AgentDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
        database = AgentDatabase(
            driver = driver,
            AgentMessageEntityAdapter = com.example.aidevelop.database.AgentMessageEntity.Adapter(
                stageAdapter = stageAdapter
            ),
            AgentStateEntityAdapter = com.example.aidevelop.database.AgentStateEntity.Adapter(
                currentStageAdapter = stageAdapter
            ),
            InvariantEntityAdapter = com.example.aidevelop.database.InvariantEntity.Adapter(
                stageAdapter = stageAdapter
            )
        )
        repository = RagEmbeddingRepository(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun doc(
        id: String,
        sourceFileName: String = "a.txt",
        sourcePath: String = "c:/tmp/a.txt",
        fullText: String = "hello",
    ) = RagDocumentEntity(
        id = id,
        title = "T",
        sourceFileName = sourceFileName,
        sourcePath = sourcePath,
        ollamaModel = "m",
        chunkSize = 10L,
        overlap = 2L,
        chunkStrategy = "FIXED_WINDOW",
        createdAt = 1L,
        fullText = fullText,
    )

    private fun chunk(
        id: String,
        documentId: String,
        index: Int,
        text: String = "x",
        blob: ByteArray = byteArrayOf(0, 0, 0, 0),
    ) = RagChunkEntity(
        id = id,
        documentId = documentId,
        chunkIndex = index.toLong(),
        startOffset = 0L,
        endOffset = text.length.toLong(),
        text = text,
        embeddingBlob = blob,
    )

    @Test
    fun insertDocumentWithChunks_persistsAndObserve_listsWithChunkCount() = runTest {
        val docId = Uuid.random().toString()
        val d = doc(docId)
        val c1 = chunk(Uuid.random().toString(), docId, 0, "aa")
        val c2 = chunk(Uuid.random().toString(), docId, 1, "bb")

        val replaced = repository.insertDocumentWithChunks(d, listOf(c1, c2))
        assertEquals(false, replaced)

        val list = repository.observeAllDocuments().first()
        assertEquals(1, list.size)
        assertEquals(docId, list[0].id)
        assertEquals(2, list[0].chunkCount)

        val loaded = repository.getDocument(docId)
        assertNotNull(loaded)
        assertEquals("hello", loaded.fullText)

        val chunks = repository.getChunks(docId)
        assertEquals(2, chunks.size)
        assertEquals(0L, chunks[0].chunkIndex)
        assertEquals(1L, chunks[1].chunkIndex)
        assertEquals("aa", chunks[0].text)
    }

    @Test
    fun insertDocumentWithChunks_sameSource_replaces_returnsReplacedTrue() = runTest {
        val id1 = Uuid.random().toString()
        val id2 = Uuid.random().toString()
        val path = "D:\\Data\\file.txt"
        val norm = normalizeRagSourcePath(path)

        repository.insertDocumentWithChunks(
            doc(id1, sourceFileName = "file.txt", sourcePath = path, fullText = "v1"),
            listOf(chunk(Uuid.random().toString(), id1, 0, "a")),
        )
        val replaced = repository.insertDocumentWithChunks(
            doc(id2, sourceFileName = "file.txt", sourcePath = path, fullText = "v2"),
            listOf(chunk(Uuid.random().toString(), id2, 0, "b")),
        )
        assertEquals(true, replaced)

        val list = repository.observeAllDocuments().first()
        assertEquals(1, list.size)
        assertEquals(id2, list[0].id)
        assertEquals(norm, list[0].sourcePath)
        assertEquals("v2", repository.getDocument(id2)!!.fullText)
        assertNull(repository.getDocument(id1))
    }

    @Test
    fun insertDocumentWithChunks_replacesLegacyRowWhereSourcePathEqualsDocumentId() = runTest {
        val legacyId = Uuid.random().toString()
        val legacyDoc = doc(legacyId, sourceFileName = "old.txt", sourcePath = legacyId, fullText = "old")
        repository.insertDocumentWithChunks(
            legacyDoc,
            listOf(chunk(Uuid.random().toString(), legacyId, 0)),
        )

        val newId = Uuid.random().toString()
        val newPath = "c:/x/old.txt"
        repository.insertDocumentWithChunks(
            doc(newId, sourceFileName = "old.txt", sourcePath = newPath, fullText = "new"),
            listOf(chunk(Uuid.random().toString(), newId, 0, "n")),
        )

        val list = repository.observeAllDocuments().first()
        assertEquals(1, list.size)
        assertEquals(newId, list[0].id)
        assertNull(repository.getDocument(legacyId))
    }

    @Test
    fun deleteDocument_removesDocumentAndChunks() = runTest {
        val docId = Uuid.random().toString()
        val chId = Uuid.random().toString()
        repository.insertDocumentWithChunks(
            doc(docId),
            listOf(chunk(chId, docId, 0)),
        )

        repository.deleteDocument(docId)

        assertNull(repository.getDocument(docId))
        assertTrue(repository.getChunks(docId).isEmpty())
        assertTrue(repository.observeAllDocuments().first().isEmpty())
    }

    @Test
    fun ragStoredChunk_equalsByIdAndBlob() {
        val b1 = byteArrayOf(1, 0, 0, 0)
        val b2 = byteArrayOf(2, 0, 0, 0)
        val a = RagStoredChunk("id1", 0L, 0L, 1L, "t", b1)
        val b = RagStoredChunk("id1", 0L, 0L, 1L, "t", b1)
        val c = RagStoredChunk("id1", 0L, 0L, 1L, "t", b2)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun loadAllChunksForRetrieval_returnsIndexedChunksWithEmbeddings() = runTest {
        val docId = Uuid.random().toString()
        val vec = floatArrayOf(0.5f, -0.25f, 1f)
        val blob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(vec)
        repository.insertDocumentWithChunks(
            doc(
                docId,
                sourceFileName = "retrieval.txt",
                sourcePath = "c:/retrieval.txt",
                fullText = "full",
            ),
            listOf(
                chunk(Uuid.random().toString(), docId, 0, text = "chunk text", blob = blob),
            ),
        )

        val loaded = repository.loadAllChunksForRetrieval()
        assertEquals(1, loaded.size)
        val ch = loaded.single()
        assertEquals("chunk text", ch.text)
        assertEquals("T", ch.documentTitle)
        assertEquals("retrieval.txt", ch.sourceFileName)
        assertEquals("m", ch.ollamaModel)
        assertEquals(vec.size, ch.embedding.size)
        for (i in vec.indices) {
            assertEquals(vec[i], ch.embedding[i], absoluteTolerance = 1e-6f)
        }
    }

    @Test
    fun getChunks_roundTripEmbeddingBlob() = runTest {
        val docId = Uuid.random().toString()
        val vec = floatArrayOf(1f, -2f, 0.5f)
        val blob = EmbeddingFloatCodec.floatArrayToLittleEndianBytes(vec)
        repository.insertDocumentWithChunks(
            doc(docId),
            listOf(chunk(Uuid.random().toString(), docId, 0, blob = blob)),
        )

        val stored = repository.getChunks(docId).single()
        val back = EmbeddingFloatCodec.littleEndianBytesToFloatArray(stored.embeddingBlob)
        assertEquals(vec.size, back.size)
        for (i in vec.indices) {
            assertEquals(vec[i], back[i], absoluteTolerance = 1e-6f)
        }
    }
}
