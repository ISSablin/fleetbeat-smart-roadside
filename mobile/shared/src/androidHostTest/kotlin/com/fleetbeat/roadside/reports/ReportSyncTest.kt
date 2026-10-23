package com.fleetbeat.roadside.reports

import com.fleetbeat.roadside.auth.configureClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportSyncTest {
    private fun report() = DiagnosticReport(id="00000001-0000-0000-0000-000000000001",title="AI report",
        capturedAt=1,capturedLabel="UTC",simulated=false,origin="AI diagnostic",vehicleModel="Volvo D13C",vin="TESTVIN1233456566",
        turns=listOf(ReportTurn("Power loss","Checks","Inspect intake")),values=emptyList(),faults=emptyList())
    private class Reports : ReportStore {
        val rows = mutableMapOf<String,List<DiagnosticReport>>()
        override suspend fun load(owner:String) = StoredReports(rows[owner].orEmpty())
        override suspend fun save(owner:String,report:DiagnosticReport) { rows[owner]=rows[owner].orEmpty().filterNot { it.id==report.id }+report }
        override suspend fun delete(owner:String,id:String) { rows[owner]=rows[owner].orEmpty().filterNot { it.id==id } }
    }
    private class States : ReportUploadStateStore {
        val rows=mutableMapOf<String,Map<String,ReportUploadState>>()
        override suspend fun load(owner:String)=rows[owner].orEmpty()
        override suspend fun write(owner:String,states:Map<String,ReportUploadState>) { rows[owner]=states.toMap() }
    }
    @Test fun offlineQueueResumesAfterRestartAndUpdatedReportsUploadAgain() = runTest {
        val reports=Reports();val states=States();val calls=mutableListOf<DiagnosticReport>();var offline=true
        reports.save("owner",report())
        val service=object:ReportUploadService {
            override suspend fun upload(report:DiagnosticReport):ReportUploadState {
                calls+=report
                if(offline) error("offline")
                return ReportUploadState("synced","server-id")
            }
        }
        fun coordinator()=ReportSync(reports,states,service,::reportFingerprint)
        val first=coordinator();first.bind(backgroundScope,"owner") {};advanceTimeBy(2001);runCurrent()
        assertNull(states.rows["owner"]!![report().id]!!.uploadedFingerprint)
        first.close();offline=false
        val second=coordinator();second.bind(backgroundScope,"owner") {};advanceTimeBy(2001);runCurrent()
        assertEquals("synced",states.rows["owner"]!![report().id]!!.status)
        second.schedule();advanceTimeBy(2001);runCurrent();assertEquals(2,calls.size)
        reports.save("owner",report().copy(notes="new observations"));second.schedule();advanceTimeBy(2001);runCurrent()
        assertEquals("new observations",calls.last().notes);assertEquals(3,calls.size)
        second.bind(backgroundScope,"other") {};advanceTimeBy(2001);runCurrent();assertEquals(3,calls.size)
        second.close()
    }
    @Test fun mobileUsesUserJwtAndExplicitNullsWithoutFleetBeatKey() = runTest {
        val client=HttpClient(MockEngine { request ->
            assertEquals("/api/v1/assistant-reports/sync",request.url.encodedPath)
            assertEquals("Bearer user-token",request.headers[HttpHeaders.Authorization])
            val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(JsonNull,body["adapterVersion"]);assertEquals(JsonNull,body["adapterVoltage"])
            assertEquals(JsonNull,body["profile"]);assertEquals(JsonNull,body["ecuCapturedLabel"])
            assertTrue(body["values"]!!.jsonArray.isEmpty())
            assertTrue(body["faults"]!!.jsonArray.isEmpty())
            assertEquals("TESTVIN1233456566",body["vin"]!!.jsonPrimitive.content)
            respond("""{"status":"synced","remoteReportId":"server-id"}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }) { configureClient(this, encodeDefaults = true) }
        try { assertEquals("synced",ReportUploadApi(client,"https://api.example", {"user-token"}).upload(report()).status) }
        finally {client.close()}
    }
    @Test fun backendNotReadyDoesNotLoseLocalReport() = runTest {
        val client=HttpClient(MockEngine { respond("",HttpStatusCode.NotFound) }) {configureClient(this, encodeDefaults = true)}
        try {assertEquals("pending",ReportUploadApi(client,"https://api.example", {"token"}).upload(report()).status)}
        finally {client.close()}
    }
}
