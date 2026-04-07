import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
    alias(libs.plugins.sqldelight)
    id("com.github.gmazzo.buildconfig") version "6.0.9"
}

kotlin {
    jvmToolchain(11)

    androidTarget()
    
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
            }
        }

        val roomMain by creating {
            dependsOn(commonMain)
            dependencies {
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

        val roomTest by creating {
            dependsOn(commonTest)
            dependsOn(roomMain)
        }
        
        val androidMain by getting {
            dependsOn(roomMain)
            dependencies {
                implementation(libs.androidx.appcompat)
                implementation(libs.androidx.activity.compose)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.koin.android)
                implementation(libs.sqldelight.android.driver)
            }
        }

        val androidUnitTest by getting {
            dependsOn(androidMain)
            dependsOn(roomTest)
        }
        
        val desktopMain by getting {
            dependsOn(roomMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val desktopTest by getting {
            dependsOn(desktopMain)
            dependsOn(roomTest)
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
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core.v1102)
    // Добавляем зависимость для Room Runtime в основной состав, чтобы Migration был доступен
    commonMainApi(libs.androidx.room.runtime)
}

android {
    namespace = "com.example.ai_develop"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.ai_develop"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
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
    
    // Флаг дебага
    buildConfigField("boolean", "IS_DEBUG", "true")

    buildConfigField("String", "DEEPSEEK_KEY", "\"${getSecret("DEEPSEEK_KEY")}\"")
    buildConfigField("String", "YANDEX_KEY", "\"${getSecret("YANDEX_KEY")}\"")
    buildConfigField("String", "YANDEX_FOLDER_ID", "\"${getSecret("YANDEX_FOLDER_ID")}\"")
    buildConfigField("String", "OPENROUTER_KEY", "\"${getSecret("OPENROUTER_KEY")}\"")
}

dependencies {
    debugImplementation(compose.uiTooling)
}
