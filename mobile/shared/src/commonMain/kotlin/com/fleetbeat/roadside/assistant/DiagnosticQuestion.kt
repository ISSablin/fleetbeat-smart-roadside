package com.fleetbeat.roadside.assistant

import com.fleetbeat.roadside.remote.RemoteState

/** An editable preview only; no request is sent by preparing it. */
fun RemoteState.diagnosticQuestion(): String = buildString {
    appendLine("Explain these results and suggest read-only diagnostic checks.")
    appendLine(if (code == "DIAG") "DIAG simulator, protocol 5935. Bench codes, not SAE P-codes." else "Diagnostic snapshots; vehicle details not supplied.")
    requestedValues.forEach { value ->
        appendLine("${value.label}: ${value.value?.let { "$it ${value.unit}" } ?: "unavailable"}, read ${(now - value.at).coerceAtLeast(0) / 1000}s ago.")
    }
    faultsReadAt?.let { at ->
        appendLine("Fault snapshot ${(now - at).coerceAtLeast(0) / 1000}s old: ${ecuFaults.size} records.")
        ecuFaults.take(5).forEach { fault ->
            appendLine("${fault.raw} status ${fault.status.toString(16).uppercase()}: ${fault.description.take(32)}")
        }
    }
}.trim().take(600)

/** Complete lines fit the request limit. The attached local report always keeps the full snapshot. */
fun com.fleetbeat.roadside.reports.DiagnosticReport.broadcastQuestion(): String {
    val lines = mutableListOf("Explain these results and suggest read-only diagnostic checks.",
        "Local Bluetooth J1939 snapshot. Vehicle identity not verified.",
        "${values.size} values; ${faults.size} ECU fault reports; $codeCount fault records. Ages at capture.")
    if (faults.isEmpty()) lines += "No DM1 received; this is not a no-fault result."
    faults.forEach { report ->
        val source = report.sourceAddress?.let { com.fleetbeat.roadside.reports.sourceHex(it) } ?: "unknown"
        if (report.codes.isEmpty()) lines += "ECU $source: no active faults in DM1, ${report.ageSeconds}s old."
        report.codes.forEach { code ->
            lines += "ECU $source: ${code.code}${code.occurrences?.let { ", count $it" } ?: ""}, ${report.ageSeconds}s old."
        }
    }
    values.forEach { value ->
        val source = value.sourceAddress?.let { com.fleetbeat.roadside.reports.sourceHex(it) } ?: "unknown"
        lines += "${value.label}: ${value.value?.let { "$it ${value.unit}" } ?: "unavailable"}, ECU $source, ${value.ageSeconds}s old."
    }
    val selected = mutableListOf<String>()
    var omitted = false
    for (line in lines) {
        // Reserve space for an explicit partial-preview label.
        if ((selected + line).joinToString("\n").length <= 545) selected += line else omitted = true
    }
    if (omitted) selected += "Partial preview; full snapshot retained in local report."
    return selected.joinToString("\n")
}
