package com.example.ai_develop

import android.app.Application
import com.example.ai_develop.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class AIChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidLogger()
            androidContext(this@AIChatApplication)
        }
    }
}
