package com.example.ai_develop.database

import app.cash.sqldelight.ColumnAdapter
import com.example.ai_develop.domain.AgentStage

val stageAdapter = object : ColumnAdapter<AgentStage, String> {
    override fun decode(databaseValue: String): AgentStage = AgentStage.valueOf(databaseValue)
    override fun encode(value: AgentStage): String = value.name
}

val booleanAdapter = object : ColumnAdapter<Boolean, Long> {
    override fun decode(databaseValue: Long): Boolean = databaseValue == 1L
    override fun encode(value: Boolean): Long = if (value) 1L else 0L
}
