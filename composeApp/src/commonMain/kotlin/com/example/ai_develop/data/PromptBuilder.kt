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
            Analyze the dialogue and update the key-value facts. 
            Keep track of: goals, constraints, preferences, decisions, user names/info.
            
            Current facts: 
            ${if (currentFacts.facts.isEmpty()) "No facts yet." else json.encodeToString(currentFacts.facts)}
            
            New messages for analysis:
            ${newMessages.joinToString("\n") { "${it.source.role}: ${it.message}" }}
            
            Instructions:
            1. Review current facts and new messages.
            2. If a new fact is discovered, add it.
            3. If a current fact is updated or corrected, modify it.
            4. Do NOT delete any current facts unless they are explicitly contradicted or became obsolete.
            5. Return the FINAL COMPLETE set of all facts (old and new).
            6. Output MUST be a valid JSON object where keys and values are strings.
            
            Example output: {"user_name": "Ivan", "goal": "learn kotlin", "language": "Russian"}
        """.trimIndent()
    }
}
