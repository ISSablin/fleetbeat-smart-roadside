package com.fleetbeat.roadside.assistant

import com.fleetbeat.roadside.reports.*
import kotlin.random.Random

/** Replace the reader implementation when local/remote ECU routing is introduced. */
interface FaultCodeReader { suspend fun read(previousCodes: Set<String>): FaultCodeSnapshot }
data class FaultCodeSnapshot(val vehicleModel: String, val vin: String?, val faults: List<ReportCode>,
    val origin: String, val simulated: Boolean) {
    fun questionBlock(): String = buildString {
        appendLine("ECU fault codes")
        appendLine("Truck: $vehicleModel")
        appendLine("VIN: ${vin ?: "not configured"}")
        appendLine("Explain codes and suggest read-only diagnostic checks.")
        faults.forEach { appendLine("${it.code}: ${it.description}") }
    }.trimEnd()
    fun report(id: String, at: Long, label: String) = DiagnosticReport(id = id,
        title = "$vehicleModel diagnostic report", capturedAt = at, capturedLabel = label,
        simulated = simulated, origin = origin, vehicleModel = vehicleModel, vin = vin,
        profile = vehicleModel, values = emptyList(),
        faults = listOf(ReportFaults(if (simulated) "Simulated ECU fault codes" else "ECU fault codes", 0, false, faults.toList())))
}

/** Codes/descriptions follow the supplied Volvo D13C reference; each case is one related fault scenario.
 * No live measurements, ECU status bytes or occurrence counts are invented.
 */
class VolvoDemoFaultReader(private val vin: String? = "TESTVIN1233456566", private val random: Random = Random.Default) : FaultCodeReader {
    private var lastCodes = emptySet<String>()
    override suspend fun read(previousCodes: Set<String>): FaultCodeSnapshot {
        val candidates = cases.filter { it.toSet() != previousCodes && it.toSet() != lastCodes }
        val selected = candidates[random.nextInt(candidates.size)]
        lastCodes = selected.toSet()
        return FaultCodeSnapshot("Volvo D13C", vin, selected.map { code -> ReportCode(code, description = descriptions.getValue(code)) },
            "Generated Volvo D13C snapshot", true)
    }
    companion object {
        // Pick a whole case, never independent random codes from unrelated systems.
        internal val cases = listOf(
            listOf("P0299", "P00AF"), listOf("P0299", "P0045"), listOf("P0299", "U010C"),
            listOf("P0299", "P00AF", "P003A"),
            listOf("P0087", "P008A"), listOf("P0087", "P016F"), listOf("P0087", "P228E", "P016F"),
            listOf("P0106", "P0069"), listOf("P0299", "P0106", "P0069"),
            listOf("P2463", "P244B"), listOf("P2463", "P0471"), listOf("P2463", "P244B", "P24A4"),
            listOf("P204F", "P20E8"), listOf("P204F", "P20E8", "P2BA8"),
            listOf("P207F", "P2BA9"), listOf("P207F", "P2BA9", "P20EE"), listOf("P203F", "P2BA7"))
        private val descriptions = mapOf(
            "P0299" to "Turbo underboost", "P00AF" to "Boost control performance", "P0045" to "Boost control circuit open",
            "U010C" to "Lost turbo control module communication", "P003A" to "Boost position learning limit exceeded",
            "P0087" to "Fuel rail pressure too low", "P008A" to "Low-pressure fuel supply too low",
            "P016F" to "Fuel pressure control at low limit", "P228E" to "Fuel regulator learning limit: low pressure",
            "P0106" to "Intake pressure sensor range/performance", "P0069" to "Intake/barometric pressure correlation",
            "P2463" to "DPF soot accumulation", "P244B" to "DPF differential pressure too high",
            "P0471" to "Exhaust pressure sensor range/performance", "P24A4" to "DPF soot accumulation too high",
            "P204F" to "SCR dosing system fault", "P20E8" to "Reductant pressure too low",
            "P2BA8" to "NOx exceedance: dosing interrupted", "P207F" to "Reductant quality",
            "P2BA9" to "NOx exceedance: reductant quality", "P20EE" to "SCR catalyst efficiency below threshold",
            "P203F" to "Reductant level low", "P2BA7" to "NOx exceedance: reductant tank empty")
    }
}
