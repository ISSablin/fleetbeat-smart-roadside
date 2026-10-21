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
