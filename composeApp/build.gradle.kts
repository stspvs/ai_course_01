import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
    alias(libs.plugins.sqldelight)
    id("com.github.gmazzo.buildconfig") version "6.0.9"
}

kotlin {
    jvmToolchain(17) 

    jvm("desktop")
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.logging)
                
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutine.extensions)

                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
                implementation(libs.koin.test)
            }
        }
        
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val desktopTest by getting {
            dependsOn(commonTest)
        }
    }
}

sqldelight {
    databases {
        create("AgentDatabase") {
            packageName.set("com.example.ai_develop.database")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.androidx.room.compiler)
}

compose.desktop {
    application {
        mainClass = "com.example.ai_develop.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb)
            packageVersion = "1.0.0"
        }
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun getSecret(name: String): String {
    return localProperties.getProperty(name) 
        ?: project.findProperty(name)?.toString() 
        ?: ""
}

buildConfig {
    packageName("com.example.ai_develop")
    
    buildConfigField("boolean", "IS_DEBUG", "true")

    buildConfigField("String", "DEEPSEEK_KEY", "\"${getSecret("DEEPSEEK_KEY")}\"")
    buildConfigField("String", "YANDEX_KEY", "\"${getSecret("YANDEX_KEY")}\"")
    buildConfigField("String", "YANDEX_FOLDER_ID", "\"${getSecret("YANDEX_FOLDER_ID")}\"")
    buildConfigField("String", "OPENROUTER_KEY", "\"${getSecret("OPENROUTER_KEY")}\"")
}
