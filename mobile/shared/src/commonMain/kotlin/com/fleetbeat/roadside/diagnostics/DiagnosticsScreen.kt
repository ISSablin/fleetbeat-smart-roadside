package com.fleetbeat.roadside.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleetbeat.roadside.diagnostics.ble.BleConnectionStatus
import kotlin.math.roundToLong

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, onScan: () -> Unit,
                      onEnableBluetooth: () -> Unit, onPermissions: () -> Unit, onLocation: () -> Unit, reportError: String?, onExplain: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val connected = connection.status == BleConnectionStatus.Connected
    val status = when (connection.status) {
        BleConnectionStatus.Connected -> "Connected"
        BleConnectionStatus.Connecting -> "Connecting…"
        BleConnectionStatus.Scanning -> "Scanning for ITD/ELD adapters…"
        BleConnectionStatus.Disabled -> "Bluetooth is off"
        BleConnectionStatus.Unavailable -> "Bluetooth is unavailable on this device"
        BleConnectionStatus.Disconnecting -> "Disconnecting…"
        BleConnectionStatus.Error -> "Connection needs attention"
        else -> "No adapter connected"
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::closeScreen) { Text("Back") }
            Text("FleetBeat Smart Roadside Assistant", Modifier.weight(1f).padding(start = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Adapter & live data", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("J1939 truck broadcasts", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        connection.connectedDevice?.let { Text(it.name); Text(it.address, fontSize = 13.sp) }
                        state.adapterVersion?.let { Text("Adapter firmware: $it") }
                        state.adapterVoltage?.let { Text("Adapter voltage: ${number(it)} V") }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (connected) {
                            OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
                        } else if (connection.bluetoothAvailable) {
                            if (!connection.bluetoothEnabled) OutlinedButton(onClick = onEnableBluetooth) { Text("Enable Bluetooth") }
                            Button(onClick = if (connection.isScanning) viewModel::stopScan else onScan, enabled = !state.busy) {
                                Text(if (connection.isScanning) "Stop scan" else "Scan adapters")
                            }
                        }
                        connection.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (!connected) {
                items(connection.devices, key = { it.address }) { device ->
                    Card(onClick = { viewModel.connect(device.address) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(device.name, fontWeight = FontWeight.Medium)
                            Text(device.address, fontSize = 13.sp)
                            device.rssi?.let { Text("Signal: $it dBm", fontSize = 13.sp) }
                            Text("Tap to connect", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (!connection.isScanning && connection.devices.isEmpty()) item {
                    Text("Power the ITD/ELD adapter and keep it nearby, then scan.")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPermissions) { Text("App permissions") }
                        TextButton(onClick = onLocation) { Text("Location settings") }
                    }
                }
            }
            if (connected) {
                item {
                    Text("CAN speed", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(250, 500).forEach { speed ->
                            FilterChip(selected = state.speed == speed, onClick = { viewModel.speed(speed) },
                                enabled = !state.listening && !state.busy, label = { Text("$speed kbit/s") })
                        }
                    }
                    Button(onClick = if (state.listening) viewModel::stopListening else viewModel::startListening,
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.listening) "Stop listening" else "Start listening")
                    }
                    Text(if (state.listening) "Listening · ${state.frames} frames received" else "Listening is stopped",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.values.isNotEmpty() || state.faults.isNotEmpty()) item {
                    Text("Reports save automatically on this device.", color = MaterialTheme.colorScheme.primary)
                    reportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onExplain, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Ask AI about these results")
                    }
                    Text("Opens an editable question and disconnects the adapter.", fontSize = 13.sp)
                }
                diagnosticReadings(state.listening, state.values, state.faults, state.now)
            }
        }
    }
}

fun androidx.compose.foundation.lazy.LazyListScope.diagnosticReadings(
    listening: Boolean, values: List<LiveValue>, faults: List<FaultReport>, now: Long,
) {
                item { Text(if (listening) "Live values" else "Last received values", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (values.isEmpty()) item { Text("Waiting for truck broadcasts. Missing values are not shown as zero.") }
                items(values, key = { "${it.key}:${it.source}" }) { value ->
                    val stale = now - value.receivedAt > 5000
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value.label, fontWeight = FontWeight.Medium)
                            Text(if (stale) "Stale" else value.value?.let { "${number(it)} ${value.unit}" } ?: "Unavailable", fontSize = 24.sp)
                            Text("Source ${source(value.source)} · ${((now - value.receivedAt).coerceAtLeast(0) / 1000)}s ago",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text("Active fault reports", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (faults.isEmpty()) item { Text("No DM1 fault message received yet.") }
                items(faults, key = { it.source }) { report ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val stale = now - report.receivedAt > 5000
                            Text("Source ${source(report.source)}${if (stale) " · stale report" else ""}", fontWeight = FontWeight.Medium)
                            if (report.codes.isEmpty()) Text("No active faults reported by this ECU.")
                            report.codes.forEach { code ->
                                Text(if (code.legacy) "Legacy SPN encoding · FMI ${code.fmi}" else "SPN ${code.spn} · FMI ${code.fmi}")
                                code.occurrences?.let { Text("Occurrences: $it", fontSize = 13.sp) }
                            }
                        }
                    }
                }
}

private fun number(value: Double): String = ((value * 10).roundToLong() / 10.0).toString()
private fun source(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')
