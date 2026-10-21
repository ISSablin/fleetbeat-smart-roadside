package com.fleetbeat.roadside.guided

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

@Composable
fun GuidedScreen(viewModel: GuidedViewModel, onReview: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::back) { Text("Back") }
            Text("FleetBeat Smart Roadside", Modifier.weight(1f).padding(start = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Guided diagnostics", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading symptoms…") }
            else if (state.selected == null) {
                item { Text("What problem are you seeing?"); Text("Choose a symptom, answer a follow-up, then review your question before asking AI.", fontSize = 14.sp) }
                if (state.error != null) item { OutlinedButton(onClick = viewModel::reload) { Text("Try again") } }
                items(state.situations, key = { it.id }) { situation ->
                    Card(onClick = { viewModel.select(situation.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (situation.entry == "freeform") "Describe another problem" else situation.title, fontWeight = FontWeight.Bold)
                            if (situation.hint.isNotBlank()) Text(situation.hint, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                val selected = state.selected!!
                item { Text(selected.title, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                if (selected.entry == "freeform") item { Text("Describe the symptoms in Diagnostic assistant using text or voice.") }
                else {
                    item { Text(selected.followup!!.question, fontSize = 18.sp) }
                    items(selected.followup!!.choices, key = { it.id }) { choice ->
                        OutlinedCard(onClick = { viewModel.choose(choice.id) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.choice?.id == choice.id, onClick = { viewModel.choose(choice.id) })
                                Text(choice.label, Modifier.weight(1f).padding(start = 8.dp))
                            }
                        }
                    }
                }
                item {
                    Text("Selection does not call AI or send vehicle commands. You can add observations or voice input on the next screen.", fontSize = 14.sp)
                    Button(onClick = { viewModel.preview()?.let(onReview) }, enabled = selected.entry == "freeform" || state.choice != null,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Review question") }
                }
            }
        }
    }
}
