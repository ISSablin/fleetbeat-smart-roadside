package com.fleetbeat.roadside.catalog

import com.fleetbeat.roadside.auth.AuthFailure
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable data class Choice(val id: String, val name: String)
@Serializable data class ChoiceList(val items: List<Choice> = emptyList())
@Serializable data class CatalogYear(val id: String, val year: String, val descr: String = "")
@Serializable data class YearList(val items: List<CatalogYear> = emptyList())
@Serializable data class ControlUnit(
    val id: String, val name: String = "", val unit: String = "", val ecu: String = "",
    val volume: String = "", val gearbox: String = "", val protocol_id: Int = 0,
    val ir_available: Boolean = false,
) {
    val title: String get() = name.ifBlank { "Control unit" }
    val description: String get() = listOf(unit, ecu, volume, gearbox).filter { it.isNotBlank() }.distinct().joinToString(" · ")
}
@Serializable data class ControlUnitList(val items: List<ControlUnit> = emptyList())

class CatalogFailure(message: String) : Exception(message)

interface VehicleCatalog {
    suspend fun brands(): List<CatalogItem>
    suspend fun models(brandId: String): List<CatalogItem>
    suspend fun years(modelId: String): List<CatalogItem>
    suspend fun controlUnits(modelId: String, yearId: String): List<CatalogItem>
}

data class CatalogItem(val id: String, val title: String, val subtitle: String = "", val controlUnit: ControlUnit? = null)

class CarApi(private val client: HttpClient, baseUrl: String, private val accessToken: () -> String) : VehicleCatalog {
    private val base = baseUrl.trimEnd('/') + "/api/v1/vehicles"

    private suspend inline fun <reified T> get(path: String, vararg params: Pair<String, String>): T {
        val response = client.get("$base/$path") {
            bearerAuth(accessToken())
            parameter("segment", "Trucks")
            parameter("lang", "ENG")
            params.forEach { (name, value) -> parameter(name, value) }
        }
        when (response.status.value) {
            200 -> return response.body()
            401 -> throw AuthFailure("Your session expired. Please sign in again.", true)
            403 -> throw AuthFailure("Your account cannot access this catalog. Please sign in again.", true)
            404 -> throw CatalogFailure("This vehicle is no longer available. Go back and choose another.")
            else -> throw CatalogFailure("The vehicle catalog is unavailable. Please try again.")
        }
    }

    override suspend fun brands() = get<ChoiceList>("brands").items.map { CatalogItem(it.id, it.name) }
    override suspend fun models(brandId: String) = get<ChoiceList>("models", "brand_id" to brandId).items.map { CatalogItem(it.id, it.name) }
    override suspend fun years(modelId: String) = get<YearList>("years", "model_id" to modelId).items.map {
        CatalogItem(it.id, it.year.ifBlank { it.id }, it.descr)
    }
    override suspend fun controlUnits(modelId: String, yearId: String) =
        get<ControlUnitList>("ecus", "model_id" to modelId, "year_id" to yearId).items.map {
            CatalogItem(it.id, it.title, it.description, it)
        }
}
