package com.example.ai_develop.data

import kotlin.test.Test
import kotlin.test.assertEquals

class RagSourcePathTest {

    @Test
    fun normalizesSlashesAndCase() {
        assertEquals("c:/users/x/doc.txt", normalizeRagSourcePath("C:\\Users\\x\\DOC.txt"))
    }

    @Test
    fun blankBecomesEmpty() {
        assertEquals("", normalizeRagSourcePath("  "))
    }

    @Test
    fun emptyString() {
        assertEquals("", normalizeRagSourcePath(""))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("c:/a/b.txt", normalizeRagSourcePath("  C:/A/B.TXT  "))
    }

    @Test
    fun unixPathUnchangedExceptCase() {
        assertEquals("/home/user/file.md", normalizeRagSourcePath("/HOME/user/FILE.md"))
    }
}
