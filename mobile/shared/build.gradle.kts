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
        androidMain.dependencies { implementation(libs.ktor.okhttp) }
    }
}
