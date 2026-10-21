package com.fleetbeat.roadside.assistant

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable data class Advice(val title: String, val body: String, val stub: Boolean,
                                val causes: List<String> = emptyList())
@Serializable private data class AdviceRequest(val entry: String, val locale: String, val user_text: String)
interface AdviceService { suspend fun ask(question: String): Advice }
class AdviceFailure(message: String) : Exception(message)

class AdviceApi(private val client: HttpClient, baseUrl: String, private val token: () -> String) : AdviceService {
    private val base = baseUrl.trimEnd('/')
    override suspend fun ask(question: String): Advice {
        require(question.isNotBlank() && question.length <= 600) { "Enter a question up to 600 characters." }
        val response = client.post("$base/api/v1/help-wizard/advice") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            setBody(AdviceRequest("freeform", "eng", question))
            timeout { requestTimeoutMillis = 75_000; socketTimeoutMillis = 75_000 }
        }
        when (response.status.value) {
            200 -> Unit
            401, 403 -> throw AuthFailure("Your session expired. Please sign in again.", true)
            429 -> throw AdviceFailure("The AI service is busy. Please try again later.")
            502, 503, 504 -> throw AdviceFailure("AI guidance is unavailable right now. Please try again later.")
            else -> throw AdviceFailure("Could not get AI guidance. Please try again.")
        }
        val advice = response.body<Advice>()
        if (advice.stub || advice.body.isBlank()) throw AdviceFailure("The AI service did not return generated guidance. Please try again later.")
        return advice.copy(title = advice.title.take(120), body = advice.body.take(12000), causes = advice.causes.take(3).map { it.take(500) })
    }
}
