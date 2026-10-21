package com.fleetbeat.roadside.guided

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class SymptomChoice(val id: String, val label: String)
@Serializable data class SymptomFollowup(val question: String, val choices: List<SymptomChoice>)
@Serializable data class SymptomSituation(val id: String, val title: String, val hint: String = "",
    val sort: Int = 0, val entry: String = "tree", val followup: SymptomFollowup? = null)
@Serializable data class SymptomTree(val version: Int, val locale: String, val situations: List<SymptomSituation>)
class GuidedFailure(message: String) : Exception(message)
interface GuidedService { suspend fun situations(): List<SymptomSituation> }

// Explicit product scope: symptom diagnosis. No regeneration, lockout or dispatch workflows.
private val diagnosticSituations = setOf("ai_help", "wont_start", "flat_tire", "check_engine", "brakes", "noise",
    "overheat", "warning_other", "transmission", "fluid_leaks", "climate_hvac", "smoke_exhaust", "leak_smell")
fun SymptomTree.diagnosticChoices(): List<SymptomSituation> {
    fun String.valid(max: Int) = isNotBlank() && length <= max
    if (locale != "eng" || version < 1 || situations.size > 100) throw GuidedFailure("Unsupported symptom list. Try again later.")
    val supported = situations.filter { it.id in diagnosticSituations }
    if (supported.isEmpty() || supported.map { it.id }.distinct().size != supported.size || supported.any { s ->
        !s.title.valid(120) || s.hint.length > 300 || s.entry !in setOf("tree", "freeform") ||
        (s.entry == "tree" && (s.followup == null || !s.followup.question.valid(180) ||
            s.followup.choices.size !in 1..12 || s.followup.choices.map { it.id }.distinct().size != s.followup.choices.size ||
            s.followup.choices.any { !it.id.valid(80) || !it.label.valid(180) }))
    }) throw GuidedFailure("Invalid symptom list. Try again later.")
    return supported.sortedBy { it.sort }
}
class GuidedApi(private val client: HttpClient, baseUrl: String, private val token: () -> String) : GuidedService {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun situations(): List<SymptomSituation> {
        val response = client.get("$base/api/v1/help-wizard/tree") { bearerAuth(token()); parameter("locale", "eng") }
        when (response.status.value) {
            200 -> Unit
            401, 403 -> throw AuthFailure("Your session expired. Please sign in again.", true)
            else -> throw GuidedFailure("Could not load symptoms. Check your connection and try again.")
        }
        val text = response.bodyAsText()
        if (text.length > 262144) throw GuidedFailure("The symptom list is too large.")
        return json.decodeFromString<SymptomTree>(text).diagnosticChoices()
    }
}
