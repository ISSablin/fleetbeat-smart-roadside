package com.fleetbeat.roadside.assistant

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class FaultSeverity(val label: String) {
    LOW("Low"), MODERATE("Moderate"), HIGH("High"), CRITICAL("Critical")
}
@Serializable enum class DriverAction(val label: String) {
    STOP_IMMEDIATELY("Stop immediately when safe"), PROCEED_WITH_CAUTION("Proceed with caution"),
    REPORT_ON_ARRIVAL("Report on arrival"), SEEK_SERVICE("Seek service")
}
@Serializable data class DiagnosticLocation(val lat: Double, val lng: Double,
    val label: String, val emulated: Boolean = true)
fun interface DiagnosticLocationProvider { suspend fun current(): DiagnosticLocation }
class EmulatedGpsLocation : DiagnosticLocationProvider {
    override suspend fun current() = DiagnosticLocation(37.396944, -122.144424, "3420 Hillview Ave, Palo Alto, CA 94304")
}
@Serializable data class NearbyService(val id: String, val name: String, val category: String,
    val lat: Double, val lng: Double, val address: String? = null, val city: String? = null,
    val state: String? = null, val phone: String? = null, val website: String? = null,
    val distance_km: Double? = null)
@Serializable data class RoadsideAssessment(val severity: FaultSeverity, val action: DriverAction,
    val categories: List<String>, val location: DiagnosticLocation,
    val services: List<NearbyService> = emptyList(), val serviceNotice: String? = null)
@Serializable private data class StructuredGuidance(val guidance: String, val severity: FaultSeverity,
    val action: DriverAction, val categories: List<String>)

val serviceCategories = setOf("truck_repair", "towing_service", "tire_dealer_and_repair",
    "brake_service_and_repair", "auto_electrical_repair", "engine_repair_service",
    "transmission_repair", "roadside_assistance", "trailer_repair")

interface TruckServiceCatalog {
    suspend fun nearby(location: DiagnosticLocation, categories: List<String>): List<NearbyService>
}
class TruckServiceApi(private val client: HttpClient, baseUrl: String, private val token: () -> String) : TruckServiceCatalog {
    private val base = baseUrl.trimEnd('/')
    override suspend fun nearby(location: DiagnosticLocation, categories: List<String>): List<NearbyService> = coroutineScope {
        require(location.lat.isFinite() && location.lat in -90.0..90.0 && location.lng.isFinite() && location.lng in -180.0..180.0)
        require(categories.size in 1..3 && categories.all { it in serviceCategories })
        categories.distinct().map { category -> async {
            val response = client.get("$base/api/v1/map/feed") {
                bearerAuth(token())
                parameter("lat", location.lat); parameter("lng", location.lng)
                parameter("radius_m", 50000); parameter("category", category); parameter("limit", 5)
            }
            if (response.status.value in listOf(401, 403)) throw AuthFailure("Your session expired. Please sign in again.", true)
            if (response.status.value != 200) throw AdviceFailure("Nearby services are unavailable. Try again later.")
            response.body<List<NearbyService>>().filter { it.category == category &&
                it.distance_km?.let { d -> d.isFinite() && d in 0.0..50.0 } == true }
        } }.flatMap { it.await() }.distinctBy { it.id }.sortedBy { it.distance_km }.take(5)
    }
}

/** Uses the existing gateway's instruction context; business details come only from the catalog. */
class RoadsideAdviceService(private val advice: AdviceApi, private val catalog: TruckServiceCatalog,
    private val gps: DiagnosticLocationProvider = EmulatedGpsLocation()) : AdviceService {
    private val responseJson = Json { ignoreUnknownKeys = true }
    override suspend fun ask(question: String): Advice {
        val location = gps.current()
        val context = """For this diagnostic request put a JSON object inside help_finish.body, no markdown:
            {"guidance":"concise explanation and read-only checks","severity":"LOW|MODERATE|HIGH|CRITICAL",
            "action":"STOP_IMMEDIATELY|PROCEED_WITH_CAUTION|REPORT_ON_ARRIVAL|SEEK_SERVICE","categories":["truck_repair"]}.
            Choose one severity and action, and 1-3 relevant categories from ${serviceCategories.joinToString(",")}.
            Critical hazards require STOP_IMMEDIATELY (stop when safe); never interpret codes alone as permission to drive.
            Unknown severity: HIGH and SEEK_SERVICE, explaining uncertainty. Never invent businesses.
            Location: ${location.label}, ${location.lat},${location.lng}; emulated GPS=${location.emulated}.
            Keep guidance under 150 words. No vehicle commands or active procedures. Do not mention regeneration.
        """.trimIndent()
        var response: Advice? = null
        var decoded: StructuredGuidance? = null
        // The legacy gateway occasionally returns prose despite the format request.
        // Retry that format failure once, never invent missing severity/action.
        repeat(2) { attempt ->
            if (decoded == null) {
                response = advice.askWithContext(question, context + if (attempt == 1)
                    "\nRequired response format: help_finish.body must contain ONLY the JSON object above, with all four fields." else "")
                decoded = try {
                    responseJson.decodeFromString<StructuredGuidance>(response!!.body.trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
                } catch (_: kotlinx.serialization.SerializationException) { null }
                  catch (_: IllegalArgumentException) { null }
            }
        }
        val structured = decoded ?: throw AdviceFailure("AI did not return severity and a driver action. Please try again.")
        if (structured.guidance.isBlank() || structured.categories.size !in 1..3 ||
            structured.categories.any { it !in serviceCategories } ||
            (structured.severity == FaultSeverity.CRITICAL && structured.action != DriverAction.STOP_IMMEDIATELY))
            throw AdviceFailure("AI returned an incomplete assessment. Please try again.")
        var notice: String? = null
        val services = try { catalog.nearby(location, structured.categories) }
        catch (error: CancellationException) { throw error }
        catch (error: AuthFailure) { throw error }
        catch (_: Exception) { notice = "Nearby services are unavailable. Diagnostic guidance is still available."; emptyList() }
        return response!!.copy(body = structured.guidance, assessment = RoadsideAssessment(structured.severity,
            structured.action, structured.categories.distinct(), location, services, notice))
    }
}
