package com.fleetbeat.roadside.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class Credentials(val login: String, val password: String)
@Serializable data class Token(val access_token: String, val expires_in: Long)
@Serializable data class Profile(val id: Int, val login: String, val is_active: Boolean)

class AuthFailure(message: String, val invalidSession: Boolean = false) : Exception(message)

fun configureClient(config: io.ktor.client.HttpClientConfig<*>, encodeDefaults: Boolean = false) {
    config.expectSuccess = false
    config.install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; this.encodeDefaults = encodeDefaults }) }
    config.install(HttpTimeout) {
        connectTimeoutMillis = 15_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
}

class AuthApi(private val client: HttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/') + "/api/v1/auth"

    suspend fun login(email: String, password: String): Token {
        val response = client.post("$base/login") {
            contentType(ContentType.Application.Json)
            setBody(Credentials(email.trim(), password))
        }
        when (response.status.value) {
            200 -> return response.body()
            401 -> throw AuthFailure("Email or password is incorrect.")
            403 -> throw AuthFailure("This account is inactive.")
            422 -> throw AuthFailure("Enter a valid email and password.")
            else -> throw AuthFailure("Unable to sign in right now. Please try again.")
        }
    }

    suspend fun profile(token: String): Profile {
        val response = client.get("$base/me") { bearerAuth(token) }
        when (response.status.value) {
            200 -> return response.body()
            401 -> throw AuthFailure("Your session expired. Please sign in again.", true)
            403 -> throw AuthFailure("This account is inactive.", true)
            else -> throw AuthFailure("Unable to load your profile. Please try again.")
        }
    }
}
