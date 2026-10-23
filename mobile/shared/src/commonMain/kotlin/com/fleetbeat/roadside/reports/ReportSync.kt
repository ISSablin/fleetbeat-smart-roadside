package com.fleetbeat.roadside.reports

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable

@Serializable data class ReportUploadState(val status: String = "pending", val remoteReportId: String? = null,
    val uploadedFingerprint: String? = null, val message: String? = null) {
    val label get() = when (status) {
        "synced" -> "Uploaded to FleetBeat"
        "uploading" -> "Uploading to FleetBeat…"
        "uncertain" -> "Upload awaiting server confirmation"
        "blocked" -> message ?: "Upload needs attention"
        else -> message ?: "Upload pending · saved on this device"
    }
}
@Serializable private data class SyncReply(val status: String, val remoteReportId: String? = null, val message: String? = null)
interface ReportUploadService { suspend fun upload(report: DiagnosticReport): ReportUploadState }
interface ReportUploadStateStore {
    suspend fun load(owner: String): Map<String, ReportUploadState>
    suspend fun write(owner: String, states: Map<String, ReportUploadState>)
}
class ReportUploadApi(private val client: HttpClient, baseUrl: String, private val token: () -> String) : ReportUploadService {
    private val base = baseUrl.trimEnd('/')
    override suspend fun upload(report: DiagnosticReport): ReportUploadState {
        val response = client.post("$base/api/v1/assistant-reports/sync") {
            bearerAuth(token()); contentType(ContentType.Application.Json)
            // Json defaults are explicitly encoded by the Android sync client.
            setBody(report)
            timeout { requestTimeoutMillis = 45_000; socketTimeoutMillis = 45_000 }
        }
        return when (response.status.value) {
            200 -> {
                val reply = response.body<SyncReply>()
                if (reply.status == "synced" && reply.remoteReportId.isNullOrBlank())
                    ReportUploadState(message = "Upload awaiting server confirmation")
                else if (reply.status !in setOf("synced", "pending", "blocked", "uncertain"))
                    ReportUploadState(message = "Upload awaiting server confirmation")
                else ReportUploadState(reply.status, reply.remoteReportId, message = reply.message)
            }
            404, 503 -> ReportUploadState(message = "Upload waiting for backend · saved on this device")
            401, 403 -> ReportUploadState(status = "blocked", message = "Sign in again to upload reports")
            413 -> ReportUploadState(status = "blocked", message = "Report is too large to upload")
            422 -> ReportUploadState(status = "blocked", message = "Report upload format is not accepted yet")
            else -> ReportUploadState(message = "Upload pending · saved on this device")
        }
    }
}

/** Local report files are the persistent queue. Only upload receipts live separately. */
class ReportSync(private val reports: ReportStore, private val states: ReportUploadStateStore,
    private val service: ReportUploadService, private val fingerprint: (DiagnosticReport) -> String,
    private val retryMillis: Long = 60_000, private val debounceMillis: Long = 2000) {
    private var worker: Job? = null
    private var requests: Channel<Unit>? = null
    fun bind(scope: CoroutineScope, owner: String?, changed: (Map<String, ReportUploadState>) -> Unit) {
        worker?.cancel(); requests?.close(); requests = null
        if (owner == null) return
        val channel = Channel<Unit>(Channel.CONFLATED)
        requests = channel
        worker = scope.launch {
            var saved = try { states.load(owner).toMutableMap() } catch (_: Exception) { mutableMapOf() }
            changed(saved.toMap())
            while (isActive) {
                withTimeoutOrNull(retryMillis) { channel.receive() }
                delay(debounceMillis)
                try {
                    val snapshot = reports.load(owner).reports
                    saved.keys.retainAll(snapshot.map { it.id }.toSet())
                    for (report in snapshot) {
                        ensureActive()
                        val revision = fingerprint(report)
                        if (saved[report.id]?.uploadedFingerprint == revision) continue
                        if (report.vehicleModel.isNullOrBlank() || report.vin.isNullOrBlank()) {
                            saved[report.id] = ReportUploadState(status = "blocked", message = "Add vehicle model and VIN to upload")
                            changed(saved.toMap()); continue
                        }
                        changed(saved.toMap() + (report.id to ReportUploadState(status = "uploading")))
                        val result = try { service.upload(report) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { ReportUploadState(message = "Upload pending · check connection") }
                        saved[report.id] = result.copy(uploadedFingerprint = if (result.status == "synced") revision else null)
                        states.write(owner, saved)
                        changed(saved.toMap())
                    }
                    states.write(owner, saved)
                    changed(saved.toMap())
                } catch (error: CancellationException) { throw error }
                  catch (_: Exception) { /* Local report remains the queue; retry on the next trigger. */ }
            }
        }
        channel.trySend(Unit)
    }
    fun schedule() { requests?.trySend(Unit) }
    fun close() { worker?.cancel(); requests?.close() }
}
