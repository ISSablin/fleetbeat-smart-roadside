package com.fleetbeat.roadside.diagnostics.ecu

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class EcuInit(val kind: String = "", val bitrate: Int? = null, val tx_id: Int? = null,
                                val rx_id: Int? = null, val mask: Int? = null)
@Serializable data class EcuProtocol(val inits: List<EcuInit> = emptyList())
@Serializable data class ProtocolEnvelope(val protocol_id: Int, val role: String = "", val ir: EcuProtocol? = null)
@Serializable data class FaultDescription(val raw: String, val show: String = "", val description: String = "")
@Serializable private data class DescriptionList(val protocol_id: Int, val items: List<FaultDescription> = emptyList())
@Serializable private data class DecodeRequest(val protocol_id: Int, val raw_codes: List<String>)

interface DiagnosticCatalog {
    suspend fun benchProfile(): BenchProfile
    suspend fun descriptions(codes: List<String>): List<FaultDescription>
}

data class BenchProfile(val tx: UInt, val rx: UInt, val speed: Int) {
    companion object {
        fun from(envelope: ProtocolEnvelope): BenchProfile {
            require(envelope.protocol_id == 5935 && envelope.role == "ecu_session") { "Unsupported diagnostic protocol." }
            val init = envelope.ir?.inits?.firstOrNull {
                it.kind == "can" && it.bitrate == 500000 && it.mask == 29 &&
                    it.tx_id == 0x18DA00F9 && it.rx_id == 0x18DAF900
            } ?: error("The catalog does not provide the supported DIAG ECU address and speed.")
            return BenchProfile(init.tx_id!!.toUInt(), init.rx_id!!.toUInt(), init.bitrate!! / 1000)
        }
    }
}

class DiagnosticCatalogApi(private val client: HttpClient, baseUrl: String,
                           private val token: () -> String) : DiagnosticCatalog {
    private val base = baseUrl.trimEnd('/') + "/api/v1/protocols"
    private fun checkStatus(status: Int) {
        if (status in listOf(401, 403)) throw AuthFailure("Your session expired. Please sign in again.", true)
        check(status == 200) { "The diagnostic catalog is unavailable or this protocol is unsupported." }
    }
    override suspend fun benchProfile(): BenchProfile {
        val response = client.get("$base/5935/session") { bearerAuth(token()); parameter("lang", "ENG") }
        checkStatus(response.status.value)
        return BenchProfile.from(response.body())
    }
    override suspend fun descriptions(codes: List<String>): List<FaultDescription> {
        if (codes.isEmpty()) return emptyList()
        val response = client.post("$base/dtc/decode") {
            bearerAuth(token()); parameter("lang", "ENG"); contentType(ContentType.Application.Json)
            setBody(DecodeRequest(protocol_id = 5935, raw_codes = codes))
        }
        checkStatus(response.status.value)
        val body = response.body<DescriptionList>()
        check(body.protocol_id == 5935) { "Unexpected diagnostic catalog response." }
        return body.items
    }
}

data class RequestedValue(val label: String, val value: Double?, val unit: String, val at: Long)
data class EcuFault(val raw: String, val status: Int, val label: String = "", val description: String = "")

object BenchDecode {
    fun rpm(payload: ByteArray): Double? {
        require(payload.size == 5 && payload.take(3) == EcuRead.EngineRpm.positivePrefix.toList()) { "Malformed engine RPM response." }
        val raw = (u8(payload[3]) shl 8) or u8(payload[4])
        return if (raw >= 0xFFFE) null else raw * 0.25
    }
    fun coolant(payload: ByteArray): Double? {
        require(payload.size == 4 && payload.take(3) == EcuRead.Coolant.positivePrefix.toList()) { "Malformed coolant response." }
        return u8(payload[3]).takeIf { it < 254 }?.let { it - 40.0 }
    }
    /** DIAG's documented 59 02 + 4-byte records omit the standard status-availability byte. */
    fun faults(payload: ByteArray): List<EcuFault> {
        require(payload.size >= 2 && payload[0] == 0x59.toByte() && payload[1] == 2.toByte() &&
            (payload.size - 2) % 4 == 0) { "Malformed DIAG fault-code response." }
        return (2 until payload.size step 4).mapNotNull { at ->
            val raw = payload.copyOfRange(at, at + 3).hex()
            if (raw == "000000" || raw == "FFFFFF") null else EcuFault(raw, u8(payload[at + 3]))
        }
    }
    private fun u8(value: Byte) = value.toInt() and 255
}

fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).uppercase().padStart(2, '0') }
fun diagnosticSettings(id: String, profile: BenchProfile) = buildJsonObject {
    put("v", 1); put("t", "set"); put("id", id)
    put("speed", profile.speed); put("format", 29); put("mode", "active"); put("stream", true)
    putJsonArray("filter") { add(profile.rx.toLong()) }
    putJsonArray("pgn_filter") { }
}.toString().encodeToByteArray()
