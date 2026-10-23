import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}
kotlin {
    android {
        namespace = "com.fleetbeat.roadside.shared"
        compileSdk = 37
        minSdk = 24
        withHostTest { }
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.ktor.core)
            implementation(libs.ktor.content)
            implementation(libs.ktor.json)
            implementation(libs.coroutines)
            implementation(libs.serialization)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("io.ktor:ktor-client-mock:3.1.3")
        }
        androidMain.dependencies {
            implementation(libs.ktor.okhttp)
            implementation("com.hivemq:hivemq-mqtt-client:1.3.5")
        }
    }
}
