package com.fleetbeat.roadside.remote

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class RemoteSession(val session_code: String, val status: String, val expires_at: String? = null)
@Serializable private data class JoinRequest(val session_code: String)
interface RemoteSessions { suspend fun join(code: String): RemoteSession }

fun normalizedDiagnosticCode(raw: String): String {
    val code = raw.trim().uppercase()
    require(Regex("[A-Z0-9]{4,16}").matches(code)) { "Enter a session code with 4–16 letters or digits." }
    require(code != "REGEN") { "This app supports diagnostic sessions only." }
    return code
}

class RemoteApi(private val client: HttpClient, baseUrl: String, private val token: () -> String) : RemoteSessions {
    private val base = baseUrl.trimEnd('/')
    override suspend fun join(code: String): RemoteSession {
        val normalized = normalizedDiagnosticCode(code)
        val response = client.post("$base/api/v1/remote/sessions/join") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(JoinRequest(normalized))
        }
        return when (response.status.value) {
            200 -> response.body<RemoteSession>().also {
                check(it.session_code == normalized) { "The server returned a different session code." }
            }
            401, 403 -> throw AuthFailure("Your session expired. Please sign in again.", true)
            404 -> error("Session not found or expired. Check the code with the person at the vehicle.")
            else -> error("The remote session service is unavailable. Please try again.")
        }
    }
}
