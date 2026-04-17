package com.example.ai_develop.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class RagSyntheticSourcePathTest {

    @Test
    fun prefix_isStable() {
        assertTrue(RagSyntheticSourcePathPrefix.startsWith("kmp-rag:"))
    }

    @Test
    fun ragSyntheticSourcePath_differsFromBareDocumentId() {
        val id = Uuid.random().toString()
        val path = ragSyntheticSourcePath(id)
        assertNotEquals(id, path)
        assertEquals(RagSyntheticSourcePathPrefix + id, path)
    }
}
