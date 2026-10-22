package com.fleetbeat.roadside.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AssistantScreen(viewModel: AssistantViewModel, onVoiceInput: () -> Unit, reportNotice: String?) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = viewModel::closeScreen) { Text("Back") }
            Text("FleetBeat Smart Roadside", Modifier.weight(1f).padding(start = 8.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 640.dp).fillMaxWidth()
            .align(Alignment.CenterHorizontally).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("AI diagnostic", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Explain symptoms or fault codes and ask for diagnostic checks.")
            OutlinedTextField(state.question, viewModel::question, Modifier.fillMaxWidth(), enabled = !state.busy && !state.voiceActive,
                label = { Text("Your question") }, minLines = 4, maxLines = 12,
                supportingText = { Text("${state.question.length}/600 characters") })
            OutlinedButton(onClick = onVoiceInput,
                enabled = !state.busy && !state.voiceActive && state.question.length < 600,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.voiceActive) "Listening…" else "Voice input") }
            Text("Your device's speech service converts speech to text and may use the internet. Review the text before sending.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("When you tap Get guidance, this question is sent to the backend and AI provider. Review any copied ECU results before sending.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Guidance only. The assistant cannot run vehicle commands.", fontSize = 14.sp)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onClick = viewModel::stopWaiting, modifier = Modifier.fillMaxWidth()) { Text("Stop waiting") }
            } else Button(onClick = viewModel::send, enabled = state.question.isNotBlank() && !state.voiceActive, modifier = Modifier.fillMaxWidth()) { Text("Get guidance") }
            if (state.turns.isNotEmpty()) {
                Text("Report saves automatically on this device.", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(state.notes, viewModel::notes, label = { Text("Report notes") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    supportingText = { Text("${state.notes.length}/4000 characters · saved automatically") })
                reportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("${state.turns.size} questions and answers retained in this session. Reports include unsent input. Each guidance request sends only the question shown above.", fontSize = 13.sp)
            }
            state.turns.forEachIndexed { index, turn ->
                val advice = Advice(turn.title, turn.body, false, turn.causes)
                Text("Question ${index + 1}: ${turn.question}", fontWeight = FontWeight.Bold)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(advice.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("AI-generated guidance", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                        Text(advice.body)
                        if (advice.causes.isNotEmpty()) {
                            Text("Possible causes", fontWeight = FontWeight.Bold)
                            advice.causes.forEach { Text("• $it") }
                        }
                    }
                }
            }
        }
    }
}
