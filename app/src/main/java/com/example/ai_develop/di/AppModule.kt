package com.example.ai_develop.di

import com.example.ai_develop.BuildConfig
import com.example.ai_develop.data.DeepSeekClientAPI
import com.example.ai_develop.data.DeepSeekRepository
import com.example.ai_develop.data.YandexClientAPI
import com.example.ai_develop.data.OpenRouterClientAPI
import com.example.ai_develop.domain.ChatRepository
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

    @Provides
    @Singleton
    fun provideYandexClientAPI(): YandexClientAPI {
        return YandexClientAPI(
            apiKey = BuildConfig.YANDEX_KEY,
            folderId = BuildConfig.YANDEX_FOLDER_ID
        )
    }

    @Provides
    @Singleton
    fun provideOpenRouterClientAPI(): OpenRouterClientAPI {
        return OpenRouterClientAPI(BuildConfig.OPENROUTER_KEY)
    }

    @Provides
    @Singleton
    fun provideChatRepository(repository: DeepSeekRepository): ChatRepository {
        return repository
    }
}
