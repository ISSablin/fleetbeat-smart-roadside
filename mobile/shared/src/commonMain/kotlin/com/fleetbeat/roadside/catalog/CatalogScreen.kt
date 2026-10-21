package com.fleetbeat.roadside.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CatalogScreen(viewModel: CatalogViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state by viewModel.state.collectAsState()
    val scroll = rememberLazyListState()
    LaunchedEffect(state.stage, state.brand?.id, state.model?.id, state.year?.id, state.query) {
        scroll.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding().padding(horizontal = 20.dp)) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::back) { Text("Back") }
                Text("FleetBeat Smart Roadside", Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(state.title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            if (state.vehicleName.isNotBlank()) Text(state.vehicleName,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            if (state.stage != CatalogStage.SUMMARY) OutlinedTextField(
                value = state.query, onValueChange = viewModel::search,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                label = { Text("Search") }, singleLine = true, enabled = !state.loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { viewModel.search("") }) { Text("Clear") } },
            )
        }
        if (state.loading) LinearProgressIndicator(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.error != null) item {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::retry, modifier = Modifier.padding(top = 12.dp)) { Text("Try again") }
            } else if (state.stage == CatalogStage.SUMMARY) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Vehicle selected", color = MaterialTheme.colorScheme.primary)
                            Text(state.vehicleName, fontSize = 20.sp)
                            state.year?.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            HorizontalDivider()
                            val unit = state.selectedUnit?.controlUnit
                            Text(unit?.title ?: "Control unit", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            unit?.unit?.takeIf { it.isNotBlank() }?.let { Text("Engine / unit: $it") }
                            unit?.ecu?.takeIf { it.isNotBlank() }?.let { Text("ECU: $it") }
                            unit?.volume?.takeIf { it.isNotBlank() }?.let { Text("Displacement: $it") }
                            unit?.gearbox?.takeIf { it.isNotBlank() }?.let { Text("Transmission: $it") }
                        }
                    }
                    OutlinedButton(onClick = viewModel::back, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Text("Change control unit")
                    }
                }
            } else if (!state.loading && state.filtered.isEmpty()) item {
                Text(if (state.query.isNotBlank()) "No matches. Try a different search."
                    else when (state.stage) {
                        CatalogStage.MODELS -> "No models found for this brand."
                        CatalogStage.YEARS -> "No years found for this model."
                        CatalogStage.CONTROL_UNITS -> "No control units found for this vehicle."
                        else -> "No truck brands available."
                    }, modifier = Modifier.padding(vertical = 20.dp))
            } else {
                item { Text("${state.filtered.size} ${if (state.filtered.size == 1) "option" else "options"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(state.filtered, key = { index, item -> "$index:${item.id}" }) { _, item ->
                    Card(onClick = { keyboard?.hide(); focus.clearFocus(); viewModel.select(item) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            if (item.subtitle.isNotBlank()) Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
