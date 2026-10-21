import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
val apiUrl = providers.gradleProperty("apiBaseUrl").orElse("https://api3.truck-inspect.com")
val carApiUrl = providers.gradleProperty("carApiBaseUrl").orElse("https://cardb3.truck-inspect.com")
android {
    namespace = "com.fleetbeat.roadside"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.fleetbeat.roadside"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", "\"${apiUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "CAR_API_BASE_URL", "\"${carApiUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
    implementation(libs.ktor.core)
    implementation(libs.ktor.okhttp)
    implementation(libs.lifecycle.viewmodel)
}
