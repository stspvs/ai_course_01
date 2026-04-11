package com.example.ai_develop.di

import app.cash.sqldelight.db.SqlDriver
import com.example.ai_develop.data.SqlDelightChatRepository
import com.example.ai_develop.data.database.LocalChatRepository
import com.example.ai_develop.database.AgentDatabase
import com.example.ai_develop.database.DriverFactory
import com.example.ai_develop.database.stageAdapter
import com.example.ai_develop.domain.AgentTool
import com.example.ai_develop.domain.CalculatorTool
import com.example.ai_develop.domain.WeatherTool
import com.example.ai_develop.mcp.NewsMcpAgentTool
import com.example.aidevelop.database.AgentMessageEntity
import com.example.aidevelop.database.AgentStateEntity
import com.example.aidevelop.database.InvariantEntity
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttpConfig
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual val platformModule = module {
    single { NewsMcpAgentTool() }
    single(named("agentTools")) {
        listOf<AgentTool>(WeatherTool(), CalculatorTool(), get<NewsMcpAgentTool>())
    }

    // SqlDelight Driver Factory and Driver
    single { DriverFactory() }
    single<SqlDriver> { get<DriverFactory>().createDriver() }

    single<AgentDatabase> {
        AgentDatabase(
            driver = get(),
            AgentMessageEntityAdapter = AgentMessageEntity.Adapter(
                stageAdapter = stageAdapter
            ),
            AgentStateEntityAdapter = AgentStateEntity.Adapter(
                currentStageAdapter = stageAdapter
            ),
            InvariantEntityAdapter = InvariantEntity.Adapter(
                stageAdapter = stageAdapter
            )
        )
    }

    single<LocalChatRepository> { get<SqlDelightChatRepository>() }
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
