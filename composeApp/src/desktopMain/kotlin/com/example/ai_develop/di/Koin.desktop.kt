package com.example.ai_develop.di

import com.example.ai_develop.data.database.*
import com.example.ai_develop.domain.AgentRepository
import com.example.ai_develop.domain.MessageRepository
import com.example.ai_develop.domain.TaskRepository
import com.example.ai_develop.database.DriverFactory
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import org.koin.dsl.module
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import app.cash.sqldelight.db.SqlDriver

actual val platformModule = module {
    single<AppDatabase> {
        getDatabaseBuilder().build()
    }
    
    single<AgentRepository> { DatabaseAgentRepository(get()) }
    single<TaskRepository> { DatabaseTaskRepository(get()) }
    single<MessageRepository> { DatabaseMessageRepository(get()) }
    
    single<DatabaseChatRepository> { 
        DatabaseChatRepository(
            agentRepository = get(),
            taskRepository = get(),
            messageRepository = get()
        ) 
    }
    
    single<LocalChatRepository> { get<DatabaseChatRepository>() }
    
    // SqlDelight Driver Factory and Driver
    single { DriverFactory() }
    single<SqlDriver> { get<DriverFactory>().createDriver() }
}

class TrustAllX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

private fun isProxyAvailable(host: String, port: Int): Boolean {
    return try {
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(host, port), 100)
        socket.close()
        true
    } catch (e: Exception) {
        false
    }
}

actual fun HttpClientConfig<*>.configurePlatform() {
    engine {
        // Проверяем, запущен ли Fiddler (обычно порт 8888), прежде чем использовать его как прокси
        if (isProxyAvailable("127.0.0.1", 8888)) {
            proxy = ProxyBuilder.http("http://127.0.0.1:8888")
            
            // Если используем прокси (Fiddler), отключаем проверку SSL для OkHttp, 
            // так как Fiddler подменяет сертификаты для расшифровки трафика
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
}
