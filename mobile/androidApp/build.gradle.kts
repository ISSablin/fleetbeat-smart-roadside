import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
val apiUrl = providers.gradleProperty("apiBaseUrl").orElse("https://api3.truck-inspect.com")
val truckServiceApiUrl = providers.gradleProperty("truckServiceApiBaseUrl").orElse("https://mapdb3.truck-inspect.com")
val carApiUrl = providers.gradleProperty("carApiBaseUrl").orElse("https://cardb3.truck-inspect.com")
val mqttHost = providers.gradleProperty("mqttHost").orElse("cardb3.truck-inspect.com")
val mqttPort = providers.gradleProperty("mqttPort").orElse("8822")
android {
    namespace = "com.fleetbeat.roadside"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.fleetbeat.roadside"
        minSdk = 24
        targetSdk = 36
        buildConfigField("String", "MQTT_HOST", "\"${mqttHost.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("int", "MQTT_PORT", mqttPort.get().toInt().also { require(it in 1..65535) }.toString())
        buildConfigField("String", "TRUCK_SERVICE_API_BASE_URL", "\"${truckServiceApiUrl.get()}\"")
        versionCode = 2
        versionName = "0.0.1"
        buildConfigField("String", "API_BASE_URL", "\"${apiUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "CAR_API_BASE_URL", "\"${carApiUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging.resources {
        excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        merges += setOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/DEPENDENCIES")
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
