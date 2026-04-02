package com.example.ai_develop.data

import com.example.ai_develop.domain.ChatFacts
import com.example.ai_develop.domain.ChatMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PromptBuilder {
    fun buildFactsExtractionPrompt(
        currentFacts: ChatFacts,
        newMessages: List<ChatMessage>,
        json: Json
    ): String {
        return """
            Analyze the dialogue and update the list of key facts. 
            Keep track of: goals, constraints, preferences, decisions, user names/info.
            
            Current facts: 
            ${if (currentFacts.facts.isEmpty()) "No facts yet." else json.encodeToString(currentFacts.facts)}
            
            New messages for analysis:
            ${newMessages.joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Instructions:
            1. Review current facts and new messages.
            2. If a new fact is discovered, add it to the list.
            3. If a current fact is updated or corrected, modify it in the list.
            4. Do NOT delete any current facts unless they are explicitly contradicted or became obsolete.
            5. Return the FINAL COMPLETE list of all facts (old and new).
            6. Output MUST be a valid JSON array of strings.
            
            Example output: ["User name is Ivan", "Goal is to learn Kotlin", "Primary language is Russian"]
        """.trimIndent()
    }

    fun buildSummarizationPrompt(
        previousSummary: String?,
        messages: List<ChatMessage>,
        instruction: String
    ): String {
        return """
            $instruction
            
            ${previousSummary?.let { "Previous summary: $it\n" } ?: ""}
            
            Messages to summarize:
            ${messages.joinToString("\n") { "${it.role}: ${it.content}" }}
            
            Return ONLY the new summary text.
        """.trimIndent()
    }
}
