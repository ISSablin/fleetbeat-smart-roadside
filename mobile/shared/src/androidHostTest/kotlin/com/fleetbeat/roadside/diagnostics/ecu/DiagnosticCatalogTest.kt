package com.fleetbeat.roadside.diagnostics.ecu

import com.fleetbeat.roadside.auth.configureClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DiagnosticCatalogTest {
    @Test fun descriptionRequestIncludesRequiredProtocolIdAndPreservesRawCodes() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("ENG", request.url.parameters["lang"])
            if (request.url.encodedPath.endsWith("/session")) {
                respond("""{"protocol_id":5935,"role":"ecu_session","ir":{"inits":[{"kind":"can","bitrate":500000,"tx_id":416940281,"rx_id":417003776,"mask":29}]}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertEquals(HttpMethod.Post, request.method)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(5935, body["protocol_id"]?.jsonPrimitive?.int)
                assertEquals(listOf("0C4900"), body["raw_codes"]?.jsonArray?.map { it.jsonPrimitive.content })
                respond("""{"protocol_id":5935,"items":[{"raw":"0C4900","show":"3145","title":"3145","description":"Sensor fault","status":null}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val client = HttpClient(engine) { configureClient(this) }
        try {
            val catalog = DiagnosticCatalogApi(client, "https://catalog.example", { "test-token" })
            assertEquals(0x18DA00F9u, catalog.benchProfile().tx)
            assertEquals("Sensor fault", catalog.descriptions(listOf("0C4900")).single().description)
        } finally { client.close() }
    }
}
