package com.fleetbeat.roadside.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AssessmentSummary(assessment: RoadsideAssessment) {
    Text("Severity: ${assessment.severity.label}", fontWeight = FontWeight.Bold,
        color = if (assessment.severity in listOf(FaultSeverity.HIGH, FaultSeverity.CRITICAL)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Text("Recommended action: ${assessment.action.label}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text("AI assessment; follow dashboard warnings and stop safely if conditions worsen.", fontSize = 13.sp)
}

@Composable
fun NearbyServices(assessment: RoadsideAssessment) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(assessment) { mutableStateOf<String?>(null) }
    fun open(uri: String) {
        try { uriHandler.openUri(uri); linkError = null }
        catch (_: Exception) { linkError = "No app available to open this link." }
    }
    HorizontalDivider()
    Text("Nearby services", fontWeight = FontWeight.Bold)
    Text("Within 50 km of ${assessment.location.label} · straight-line distance", fontSize = 13.sp)
    if (assessment.serviceNotice != null) Text(assessment.serviceNotice)
    else if (assessment.services.isEmpty()) Text("No matching services found within 50 km.")
    assessment.services.forEach { service ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(service.name, fontWeight = FontWeight.Bold)
            Text("${service.category.replace('_', ' ')} · ${service.distance_km} km")
            Text(listOfNotNull(service.address, service.city, service.state).joinToString(", "))
            service.phone?.let { Text("Phone: $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                service.phone?.filter { it.isDigit() || it == '+' }?.takeIf { it.isNotEmpty() }?.let { phone ->
                    TextButton(onClick = { open("tel:$phone") }) { Text("Call") }
                }
                TextButton(onClick = { open(service.mapSearchUrl()) }) { Text("Map") }
                service.website?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { website ->
                    TextButton(onClick = { open(website) }) { Text("Website") }
                }
            }
        }
    }
    linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Confirm availability and truck capability before travelling. A listing does not imply it is safe to drive there.", fontSize = 13.sp)
}
