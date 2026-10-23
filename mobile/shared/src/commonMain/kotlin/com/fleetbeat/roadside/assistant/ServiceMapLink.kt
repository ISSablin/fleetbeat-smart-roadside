package com.fleetbeat.roadside.assistant

import io.ktor.http.URLBuilder

/** Search for the selected business, rather than resolving a coordinate to an unrelated POI. */
fun NearbyService.mapSearchUrl(): String {
    val addressParts = listOfNotNull(address, city, state).map { it.trim() }.filter { it.isNotEmpty() }
    val query = if (addressParts.isNotEmpty())
        (listOf(name.trim()) + addressParts + "USA").filter { it.isNotEmpty() }.joinToString(", ")
    else "${name.trim()} near $lat,$lng"
    return URLBuilder("https://www.google.com/maps/search/").apply {
        parameters.append("api", "1")
        parameters.append("query", query)
    }.buildString()
}
