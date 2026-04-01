package com.example.ai_develop.di

import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.data.database.WasmChatRepository
import org.koin.dsl.module

actual val platformModule = module {
    single<LocalChatRepository> { WasmChatRepository(get()) }
}
