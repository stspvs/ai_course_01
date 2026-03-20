package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.DeepSeekClientAPI
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {

    @Provides
    @Singleton
    fun provideDeepSeekClientAPI(): DeepSeekClientAPI {
        return DeepSeekClientAPI(BuildConfig.DEEPSEEK_KEY)
    }
}
