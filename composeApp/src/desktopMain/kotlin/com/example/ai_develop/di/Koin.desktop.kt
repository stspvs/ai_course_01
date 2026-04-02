package com.example.ai_develop.di

import com.example.ai_develop.data.database.AppDatabase
import com.example.ai_develop.data.database.DatabaseChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.data.database.getDatabaseBuilder
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import org.koin.dsl.module
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

actual val platformModule = module {
    single<AppDatabase> {
        getDatabaseBuilder().build()
    }
    single { get<AppDatabase>().agentDao() }
    single<LocalChatRepository> { DatabaseChatRepository(get()) }
}

class TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

actual fun HttpClientConfig<*>.configurePlatform() {
    engine {
        // Настройка прокси для Fiddler
        proxy = ProxyBuilder.http("http://127.0.0.1:8888")

        // Отключаем проверку SSL для OkHttp
        if (this is OkHttpConfig) {
            config {
                val trustManager = TrustAllX509TrustManager()
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, arrayOf(trustManager), SecureRandom())
                
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
            }
        }
    }
}
