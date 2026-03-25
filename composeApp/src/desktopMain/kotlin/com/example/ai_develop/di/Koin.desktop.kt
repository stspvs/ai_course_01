package com.example.ai_develop.di

import com.example.ai_develop.data.database.getDatabaseBuilder
import org.koin.dsl.module

actual val platformModule = module {
    single {
        getDatabaseBuilder().build()
    }
}
