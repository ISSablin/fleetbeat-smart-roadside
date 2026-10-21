package com.fleetbeat.roadside.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleetbeat.roadside.diagnostics.diagnosticReadings

@Composable
fun RemoteScreen(viewModel: RemoteViewModel, onExplain: () -> Unit) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewModel.disconnect(true) }) { Text("Back") }
            Text("FleetBeat Smart Roadside", Modifier.weight(1f).padding(start = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Text("Remote diagnostics", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("Technician · connect to an adapter at another location", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!state.linked) {
                            OutlinedTextField(state.code, viewModel::code, Modifier.fillMaxWidth(), singleLine = true,
                                enabled = !state.busy, label = { Text("Session code") })
                            Text("Use the code from the vehicle-side app. DIAG connects to the shared diagnostic simulator.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { viewModel.code("DIAG") }, enabled = !state.busy) { Text("Use DIAG simulator") }
                            }
                            Button(onClick = viewModel::join, enabled = !state.busy && state.code.length >= 4,
                                modifier = Modifier.fillMaxWidth()) { Text("Join session") }
                        } else {
                            Text("Session ${state.code}${if (state.code == "DIAG") " · simulated data" else ""}", fontWeight = FontWeight.Bold)
                            state.version?.let { Text("Remote adapter: $it") }
                            state.voltage?.let { Text("Adapter voltage: $it V") }
                            Text("Received ${state.rxBytes} bytes · sent ${state.txBytes} bytes", fontSize = 13.sp)
                            state.lastResponse?.let { at ->
                                val seconds = (state.now - at).coerceAtLeast(0) / 1000
                                Text("Last adapter response: "+seconds+"s ago", color = if (seconds > 15)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(onClick = viewModel::refreshStatus, enabled = !state.busy) { Text("Check adapter") }
                        }
                        Text(state.status, color = MaterialTheme.colorScheme.primary)
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.linked || state.busy) OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                    }
                }
            }
            if (state.linked) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Requested ECU diagnostics", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (state.code == "DIAG") {
                                Text("DIAG engine · simulated ECU. Read current values or stored fault records on demand.")
                                state.ecuProfile?.let { Text(it, fontSize = 13.sp) }
                                if (state.listening) Text("Stop broadcast listening to read the ECU.")
                                Button(onClick = viewModel::readEcuValues, enabled = !state.busy && !state.listening,
                                    modifier = Modifier.fillMaxWidth()) { Text("Read ECU values") }
                                OutlinedButton(onClick = viewModel::readEcuFaults, enabled = !state.busy && !state.listening,
                                    modifier = Modifier.fillMaxWidth()) { Text("Read ECU fault codes") }
                                if (state.requestedValues.isNotEmpty() || state.faultsReadAt != null) {
                                    OutlinedButton(onClick = onExplain, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                        Text("Ask AI about these results")
                                    }
                                    Text("Opens an editable question and disconnects this session.", fontSize = 13.sp)
                                }
                                state.ecuStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                                if (state.ecuBusy) Text("Reading… Use Disconnect or Back to cancel.")
                                state.catalogWarning?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } else Text("Requested reads currently support the DIAG engine profile only. Other sessions support broadcast listening.")
                        }
                    }
                }
                items(state.requestedValues, key = { "requested:" + it.label }) { value ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val seconds = (state.now - value.at).coerceAtLeast(0) / 1000
                            Text("ECU · ${value.label}", fontWeight = FontWeight.Medium)
                            Text(value.value?.let { "$it ${value.unit}" } ?: "Unavailable", fontSize = 24.sp)
                            Text("Read ${seconds}s ago${if (seconds > 5) " · stale snapshot" else ""}", fontSize = 13.sp)
                        }
                    }
                }
                state.faultsReadAt?.let { at ->
                    item {
                        Text("ECU fault records · ${state.ecuFaults.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Read ${(state.now - at).coerceAtLeast(0) / 1000}s ago · snapshot")
                        if (state.ecuFaults.isEmpty()) Text("The ECU returned no matching fault records.")
                    }
                }
                itemsIndexed(state.ecuFaults, key = { index, code -> "ecu:$index:${code.raw}" }) { _, code ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Code ${code.raw}", fontWeight = FontWeight.Bold)
                            if (code.label.isNotBlank()) Text(code.label)
                            if (code.description.isNotBlank()) Text(code.description)
                            Text("Status 0x${code.status.toString(16).uppercase().padStart(2, '0')}", fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Text("Broadcast listening", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Remote CAN speed", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(250, 500).forEach { speed ->
                            FilterChip(selected = state.speed == speed, onClick = { viewModel.speed(speed) },
                                enabled = !state.busy && !state.listening, label = { Text("$speed kbit/s") })
                        }
                    }
                    Button(onClick = if (state.listening) viewModel::stopListening else viewModel::startListening,
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.listening) "Stop listening" else "Start listening")
                    }
                    Text("${state.frames} broadcast frames received")
                }
                diagnosticReadings(state.listening, state.values, state.faults, state.now)
            }
        }
    }
}
