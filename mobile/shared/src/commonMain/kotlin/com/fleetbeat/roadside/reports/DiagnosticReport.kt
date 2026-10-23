package com.fleetbeat.roadside.reports

import com.fleetbeat.roadside.assistant.RoadsideAssessment
import com.fleetbeat.roadside.remote.RemoteState
import com.fleetbeat.roadside.diagnostics.DiagnosticsState
import kotlinx.serialization.Serializable

@Serializable data class ReportValue(val label: String, val value: Double?, val unit: String,
    val ageSeconds: Long, val staleAtCapture: Boolean, val origin: String, val sourceAddress: Int? = null)
@Serializable data class ReportCode(val code: String, val status: Int? = null,
    val label: String = "", val description: String = "", val occurrences: Int? = null)
@Serializable data class ReportFaults(val origin: String, val ageSeconds: Long, val staleAtCapture: Boolean,
    val codes: List<ReportCode>, val sourceAddress: Int? = null)
@Serializable data class ReportTurn(val question: String, val title: String, val body: String, val causes: List<String> = emptyList(), val assessment: RoadsideAssessment? = null)
@Serializable data class DiagnosticReport(
    val schemaVersion: Int = 1, val id: String,
    val companyId: String = "e2fc8a58-4981-40d7-bbaa-93566b200711", val title: String, val capturedAt: Long,
    val capturedLabel: String, val simulated: Boolean, val origin: String,
    val vehicleModel: String? = null, val vin: String? = null,
    val adapterName: String? = null, val busSpeedKbit: Int? = null,
    val profile: String? = null, val adapterVersion: String? = null, val adapterVoltage: Double? = null,
    val turns: List<ReportTurn> = emptyList(), val additionalInput: String = "", val notes: String = "",
    val ecuCapturedLabel: String? = null, val broadcastFrames: Long = 0, val values: List<ReportValue>, val faults: List<ReportFaults>,
) {
    val codeCount get() = faults.sumOf { it.codes.size }
    val hasEvidence get() = values.isNotEmpty() || faults.isNotEmpty() || turns.isNotEmpty()
}

fun remoteReport(state: RemoteState, id: String, capturedAt: Long, capturedLabel: String, monotonicNow: Long): DiagnosticReport {
    fun age(at: Long) = (monotonicNow - at).coerceAtLeast(0)
    val values = state.requestedValues.map {
        ReportValue(it.label, it.value, it.unit, age(it.at) / 1000, age(it.at) > 5000, "Requested ECU")
    } + state.values.map {
        ReportValue(it.label, it.value, it.unit, age(it.receivedAt) / 1000, age(it.receivedAt) > 5000,
            "J1939 broadcast", it.source)
    }
    val requestedFaults = state.faultsReadAt?.let { at -> listOf(ReportFaults("Requested ECU fault records",
        age(at) / 1000, age(at) > 5000, state.ecuFaults.map { ReportCode(it.raw, it.status, it.label, it.description) })) }.orEmpty()
    val broadcasts = state.faults.map { report -> ReportFaults("J1939 DM1", age(report.receivedAt) / 1000,
        age(report.receivedAt) > 5000, report.codes.map { code ->
            ReportCode(if (code.legacy) "Legacy SPN encoding · FMI ${code.fmi}" else "SPN ${code.spn} · FMI ${code.fmi}",
                occurrences = code.occurrences)
        }, report.source) }
    return DiagnosticReport(id = id, title = if (state.code == "DIAG") "DIAG simulator report" else "Remote diagnostic report",
        capturedAt = capturedAt, capturedLabel = capturedLabel, simulated = state.code == "DIAG",
        origin = if (state.code == "DIAG") "DIAG simulator" else "Remote adapter", profile = state.ecuProfile,
        adapterVersion = state.version, adapterVoltage = state.voltage, broadcastFrames = state.frames,
        values = values, faults = requestedFaults + broadcasts)
}

fun localReport(state: DiagnosticsState, id: String, capturedAt: Long, capturedLabel: String, monotonicNow: Long): DiagnosticReport {
    // Broadcast decoding and snapshot mapping are identical for local and remote transports.
    val snapshot = remoteReport(RemoteState(values = state.values, faults = state.faults, frames = state.frames),
        id, capturedAt, capturedLabel, monotonicNow)
    return snapshot.copy(title = "Local Bluetooth diagnostic report", origin = "Local Bluetooth adapter",
        adapterName = state.adapterName, busSpeedKbit = state.dataSpeed ?: state.speed,
        adapterVersion = state.adapterVersion, adapterVoltage = state.adapterVoltage)
}

fun DiagnosticReport.asText(): String = buildString {
    appendLine("FleetBeat Smart Roadside Assistant")
    appendLine(title)
    appendLine("Captured: $capturedLabel")
    appendLine("Source: $origin")
    if (simulated) appendLine("SIMULATED DATA — this report does not describe a real vehicle.")
    vehicleModel?.let { appendLine("Truck: $it") }
    if (vehicleModel != null) appendLine("VIN: ${vin ?: "not configured"}")
    profile?.let { appendLine("Profile: $it") }
    adapterName?.let { appendLine("Adapter name: $it") }
    busSpeedKbit?.let { appendLine("CAN speed: $it kbit/s") }
    adapterVersion?.let { appendLine("Adapter: $it") }
    adapterVoltage?.let { appendLine("Adapter voltage: $it V") }
    ecuCapturedLabel?.let { appendLine("ECU snapshot captured: $it") }
    appendLine("Broadcast frames: $broadcastFrames")
    appendLine("Snapshot ages below are relative to capture time, not live readings.")
    appendLine()
    appendLine("VALUES (${values.size})")
    if (values.isEmpty()) appendLine("No measurements were included.")
    values.forEach { value ->
        appendLine("${value.label}: ${value.value?.let { "$it ${value.unit}" } ?: "Unavailable"}")
        appendLine("  ${value.origin}${value.sourceAddress?.let { " · source " + sourceHex(it) } ?: ""}; " +
            "${value.ageSeconds}s old at capture${if (value.staleAtCapture) "; stale at capture" else ""}")
    }
    appendLine()
    appendLine("FAULT RECORDS ($codeCount)")
    if (faults.isEmpty()) appendLine("No ECU fault response was included. This does not mean the vehicle has no faults.")
    faults.forEach { section ->
        appendLine("${section.origin}${section.sourceAddress?.let { " · source " + sourceHex(it) } ?: ""}")
        appendLine("  ${section.ageSeconds}s old at capture${if (section.staleAtCapture) "; stale at capture" else ""}")
        if (section.codes.isEmpty()) appendLine("  This ECU reported no matching faults.")
        section.codes.forEach { code ->
            appendLine("  ${code.code}${code.status?.let { " · status " + sourceHex(it) } ?: ""}")
            if (code.label.isNotBlank()) appendLine("    ${code.label}")
            if (code.description.isNotBlank()) appendLine("    ${code.description}")
            code.occurrences?.let { appendLine("    Occurrences: $it") }
        }
    }
    if (turns.isNotEmpty()) {
        appendLine(); appendLine("AI SESSION — generated guidance")
        turns.forEachIndexed { index, turn ->
            appendLine(); appendLine("Question ${index + 1}: ${turn.question}")
            appendLine(turn.title); appendLine(turn.body)
            turn.assessment?.let { assessment ->
                appendLine("Severity: ${assessment.severity.label}")
                appendLine("Recommended action: ${assessment.action.label}")
                appendLine("Location: ${assessment.location.label} (${assessment.location.lat}, ${assessment.location.lng}); emulated GPS: ${assessment.location.emulated}")
                assessment.services.forEach { appendLine("Nearby: ${it.name} · ${it.distance_km} km · ${it.category} · ${it.phone.orEmpty()} · ${it.address.orEmpty()}") }
                assessment.serviceNotice?.let { appendLine(it) }
            }
            turn.causes.forEach { appendLine("Possible cause: $it") }
        }
    }
    if (additionalInput.isNotBlank()) { appendLine(); appendLine("ADDITIONAL INPUT (not submitted to AI)"); appendLine(additionalInput) }
    if (notes.isNotBlank()) { appendLine(); appendLine("USER NOTES"); appendLine(notes) }
}
fun sourceHex(value: Int) = "0x" + value.toString(16).uppercase().padStart(2, '0')
