package com.fleetbeat.roadside.assistant

import com.fleetbeat.roadside.auth.configureClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class RoadsideAdviceTest {
    private val location = DiagnosticLocation(37.396944, -122.144424, "3420 Hillview Ave, Palo Alto, CA 94304")
    private val assessment = """{"guidance":"Inspect intake connections with engine stopped.","severity":"HIGH","action":"SEEK_SERVICE","categories":["engine_repair_service","truck_repair"]}"""
    private fun reply(body: String) = buildJsonObject {
        put("title", "Power loss"); put("body", body); put("stub", false)
    }.toString()

    @Test fun structuredRequestPreservesQuestionAndUsesCatalogInsteadOfInventedBusinesses() = runTest {
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
            calls += request.url.encodedPath
            if (request.url.encodedPath.endsWith("advice")) {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("P0299 power loss", body["user_text"]!!.jsonPrimitive.content)
                val instruction = body["qa"]!!.jsonArray.single().jsonPrimitive.content
                assertContains(instruction, "STOP_IMMEDIATELY")
                assertContains(instruction, "37.396944")
                respond(reply(assessment), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertEquals("50000", request.url.parameters["radius_m"])
                assertEquals("37.396944", request.url.parameters["lat"])
                val category = request.url.parameters["category"]!!
                val name = if (category == "truck_repair") "Closest real shop" else "Engine shop"
                val distance = if (category == "truck_repair") 1.0 else 2.0
                respond("""[{"id":"$category","name":"$name","category":"$category","lat":32.78,"lng":-96.79,"distance_km":$distance}]""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { configureClient(this) }
        try {
            val result = RoadsideAdviceService(AdviceApi(client, "https://api.example", { "user-token" }),
                TruckServiceApi(client, "https://catalog.example", { "user-token" })).ask("P0299 power loss")
            assertEquals(FaultSeverity.HIGH, result.assessment!!.severity)
            assertEquals(DriverAction.SEEK_SERVICE, result.assessment!!.action)
            assertEquals("Closest real shop", result.assessment!!.services.first().name)
            assertEquals(3, calls.size)
            assertTrue(result.assessment!!.location.emulated)
            assertEquals("Inspect intake connections with engine stopped.", result.body)
        } finally { client.close() }
    }

    @Test fun serviceFailureKeepsGuidanceAndReportsFailureSeparately() = runTest {
        val client = HttpClient(MockEngine { respond(reply(assessment), headers = headersOf(HttpHeaders.ContentType,"application/json")) }) { configureClient(this) }
        val catalog = object : TruckServiceCatalog {
            override suspend fun nearby(location: DiagnosticLocation, categories: List<String>): List<NearbyService> = error("unavailable")
        }
        try {
            val result = RoadsideAdviceService(AdviceApi(client,"https://api.example", { "token" }),catalog).ask("Power loss")
            assertTrue(result.body.isNotEmpty())
            assertTrue(result.assessment!!.services.isEmpty())
            assertNotNull(result.assessment!!.serviceNotice)
        } finally { client.close() }
    }

    @Test fun malformedUnknownOrContradictoryAssessmentNeverTriggersServiceSearch() = runTest {
        for (body in listOf("Plain text", assessment.replace("HIGH", "UNKNOWN"),
            assessment.replace("truck_repair", "invented_category"), assessment.replace("HIGH", "CRITICAL"))) {
            val client = HttpClient(MockEngine { respond(reply(body), headers = headersOf(HttpHeaders.ContentType,"application/json")) }) { configureClient(this) }
            val catalog = object : TruckServiceCatalog {
                override suspend fun nearby(location: DiagnosticLocation, categories: List<String>): List<NearbyService> = error("Must not call catalog")
            }
            try { assertFailsWith<AdviceFailure> { RoadsideAdviceService(AdviceApi(client,"https://api.example", { "token" }),catalog).ask("Fault") } }
            finally { client.close() }
        }
    }

    @Test fun assessmentAndServicesRoundTripInSavedReportsAndOldTurnsStillDecode() {
        val json = Json { encodeDefaults = true }
        val turn = com.fleetbeat.roadside.reports.ReportTurn("Question", "Title", "Body", assessment = RoadsideAssessment(
            FaultSeverity.HIGH, DriverAction.SEEK_SERVICE, listOf("truck_repair"), location,
            listOf(NearbyService("shop-1","Shop","truck_repair",32.78,-96.79,distance_km=1.0))))
        assertEquals(turn, json.decodeFromString<com.fleetbeat.roadside.reports.ReportTurn>(json.encodeToString(com.fleetbeat.roadside.reports.ReportTurn.serializer(), turn)))
        assertNull(json.decodeFromString<com.fleetbeat.roadside.reports.ReportTurn>("""{"question":"Q","title":"T","body":"B"}""").assessment)
    }
    @Test fun legacyProseResponseRetriesFormatOnceWithoutInventingAssessment() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond(reply(if (calls == 1) "Unstructured guidance" else assessment),
                headers = headersOf(HttpHeaders.ContentType,"application/json"))
        }) { configureClient(this) }
        val catalog = object : TruckServiceCatalog {
            override suspend fun nearby(location: DiagnosticLocation, categories: List<String>) = emptyList<NearbyService>()
        }
        try {
            val result = RoadsideAdviceService(AdviceApi(client,"https://api.example", { "token" }),catalog).ask("Power loss")
            assertEquals(2, calls)
            assertEquals(FaultSeverity.HIGH, result.assessment!!.severity)
        } finally { client.close() }
    }

}
