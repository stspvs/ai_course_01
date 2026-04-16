package com.example.ai_develop.data

/** Системный промпт для [KtorChatRepository.rewriteQueryForRag] (покрыт unit-тестами). */
internal val ragQueryRewriteSystemPrompt: String =
    "You rewrite user queries for retrieval against a knowledge base. " +
        "Output exactly one concise search line. " +
        "The output language MUST match the user's query language: " +
        "do not translate, do not switch languages, keep Russian if the user wrote Russian, English if English, etc. " +
        "No quotes, no explanation, no preamble."
