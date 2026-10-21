package com.fleetbeat.roadside.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SaveReportDialog(viewModel: ReportsViewModel) {
    val state by viewModel.state.collectAsState()
    val report = state.draft ?: return
    AlertDialog(onDismissRequest = viewModel::dismissDraft, title = { Text("Save diagnostic report") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(report.capturedLabel)
            if (report.simulated) Text("Simulated DIAG data", color = MaterialTheme.colorScheme.primary)
            Text("${report.values.size} values · ${report.codeCount} fault records · ${report.turns.size} AI answers")
            OutlinedTextField(state.draftTitle, viewModel::draftTitle, label = { Text("Report title") },
                singleLine = true, enabled = !state.saving)
            OutlinedTextField(report.notes, viewModel::draftNotes, label = { Text("Additional notes") },
                enabled = !state.saving, maxLines = 4, supportingText = { Text("${report.notes.length}/4000 characters") })
            Text("Saved on this device for your account. Snapshot ages are preserved.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = viewModel::saveDraft, enabled = !state.saving && state.draftTitle.isNotBlank()) { Text("Save report") } },
       dismissButton = { TextButton(onClick = viewModel::dismissDraft, enabled = !state.saving) { Text("Cancel") } })
}

@Composable
fun ReportsScreen(viewModel: ReportsViewModel, onExport: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var deleteId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::back, enabled = !state.exporting) { Text("Back") }
            Text("FleetBeat Smart Roadside", Modifier.weight(1f).padding(start = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text(if (state.selected == null) "Saved reports" else "Diagnostic report", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { notice -> item { Text(notice, color = MaterialTheme.colorScheme.primary) } }
            if (state.loading || state.exporting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val report = state.selected
            if (report == null) {
                item { Text("Reports saved on this device for your signed-in account."); Text("${state.reports.size}/100 reports", fontSize = 13.sp) }
                if (!state.loading && state.reports.isEmpty()) item { Text("No saved reports yet. Reports appear automatically after ECU readings or AI guidance.") }
                if (state.error != null) item { OutlinedButton(onClick = viewModel::reload) { Text("Try again") } }
                itemsIndexed(state.reports, key = { _, row -> row.id }) { _, row ->
                    Card(onClick = { viewModel.select(row.id) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.title, fontWeight = FontWeight.Bold)
                            Text(row.capturedLabel, fontSize = 13.sp)
                            Text("${row.values.size} values · ${row.codeCount} fault records · ${row.turns.size} AI answers")
                            Text(if (row.simulated) "DIAG · simulated data" else row.origin, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                item {
                    Text(report.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(report.capturedLabel)
                    if (report.simulated) Text("SIMULATED DATA · not a real vehicle", color = MaterialTheme.colorScheme.primary)
                    Text(report.origin)
                    report.ecuCapturedLabel?.let { Text("ECU snapshot: $it") }
                    report.profile?.let { Text(it) }
                    report.adapterVersion?.let { Text("Adapter: $it", fontSize = 13.sp) }
                    report.adapterVoltage?.let { Text("Adapter voltage: $it V", fontSize = 13.sp) }
                    Text("${report.broadcastFrames} broadcast frames", fontSize = 13.sp)
                    Text("Snapshot ages are relative to capture time. These are saved results.", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onExport, enabled = !state.exporting && !state.loading) { Text("Export text") }
                        OutlinedButton(onClick = { deleteId = report.id }, enabled = !state.exporting && !state.loading) { Text("Delete report") }
                    }
                }
                itemsIndexed(report.turns, key = { index, _ -> "ai:$index" }) { index, turn ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Question ${index + 1}", fontWeight = FontWeight.Bold)
                            Text(turn.question)
                            Text(turn.title, fontWeight = FontWeight.Bold)
                            Text("AI-generated guidance", color = MaterialTheme.colorScheme.primary)
                            Text(turn.body)
                            turn.causes.forEach { Text("• $it") }
                        }
                    }
                }
                if (report.additionalInput.isNotBlank()) item { Text("Additional input (not submitted to AI)", fontWeight = FontWeight.Bold); Text(report.additionalInput) }
                if (report.notes.isNotBlank()) item { Text("User notes", fontWeight = FontWeight.Bold); Text(report.notes) }
                item { Text("Values · ${report.values.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (report.values.isEmpty()) item { Text("No measurements were included.") }
                itemsIndexed(report.values, key = { index, _ -> "value:$index" }) { _, value ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value.label, fontWeight = FontWeight.Medium)
                            Text(value.value?.let { "$it ${value.unit}" } ?: "Unavailable", fontSize = 24.sp)
                            Text("${value.origin}${value.sourceAddress?.let { " · " + sourceHex(it) } ?: ""}", fontSize = 13.sp)
                            Text("${value.ageSeconds}s old at capture${if (value.staleAtCapture) " · stale" else ""}", fontSize = 13.sp)
                        }
                    }
                }
                item { Text("Fault records · ${report.codeCount}", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                if (report.faults.isEmpty()) item { Text("No ECU fault response was included. This is not a no-fault result.") }
                report.faults.forEachIndexed { sectionIndex, section ->
                    item {
                        Text("${section.origin}${section.sourceAddress?.let { " · " + sourceHex(it) } ?: ""}", fontWeight = FontWeight.Bold)
                        Text("${section.ageSeconds}s old at capture${if (section.staleAtCapture) " · stale" else ""}", fontSize = 13.sp)
                        if (section.codes.isEmpty()) Text("This ECU reported no matching faults.")
                    }
                    itemsIndexed(section.codes, key = { index, _ -> "fault:$sectionIndex:$index" }) { _, code ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(code.code, fontWeight = FontWeight.Bold)
                                if (code.label.isNotBlank()) Text(code.label)
                                if (code.description.isNotBlank()) Text(code.description)
                                code.status?.let { Text("Status ${sourceHex(it)}", fontSize = 13.sp) }
                                code.occurrences?.let { Text("Occurrences: $it", fontSize = 13.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteId != null && state.selected?.id == deleteId) AlertDialog(
        onDismissRequest = { deleteId = null }, title = { Text("Delete this report?") },
        text = { Text("This removes the saved report from this device. Exported files remain separate.") },
        confirmButton = { TextButton(onClick = { deleteId = null; viewModel.deleteSelected() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Cancel") } })
}
