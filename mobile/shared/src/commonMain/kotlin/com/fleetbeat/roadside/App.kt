package com.fleetbeat.roadside

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleetbeat.roadside.remote.*
import com.fleetbeat.roadside.assistant.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.fleetbeat.roadside.reports.*
import com.fleetbeat.roadside.auth.AuthViewModel
import com.fleetbeat.roadside.catalog.CatalogViewModel
import com.fleetbeat.roadside.catalog.CatalogScreen
import com.fleetbeat.roadside.diagnostics.DiagnosticsViewModel
import com.fleetbeat.roadside.diagnostics.DiagnosticsScreen

@Composable
fun App(viewModel: AuthViewModel, catalogViewModel: CatalogViewModel, diagnosticsViewModel: DiagnosticsViewModel, remoteViewModel: RemoteViewModel, assistantViewModel: AssistantViewModel, reportsViewModel: ReportsViewModel,
        onExportReport: () -> Unit, onVoiceInput: () -> Unit, onScan: () -> Unit, onEnableBluetooth: () -> Unit, onPermissions: () -> Unit, onLocation: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val reportsState by reportsViewModel.state.collectAsState()
    val assistantState by assistantViewModel.state.collectAsState()
    val remoteState by remoteViewModel.state.collectAsState()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    val catalogState by catalogViewModel.state.collectAsState()
    LaunchedEffect(state.profile?.id) { catalogViewModel.bindUser(state.profile?.id); diagnosticsViewModel.bindUser(state.profile?.id); remoteViewModel.bindUser(state.profile?.id); assistantViewModel.bindUser(state.profile?.id); reportsViewModel.bindUser(state.profile?.id) }
    LaunchedEffect(state.profile?.id) {
        reportsViewModel.bindUser(state.profile?.id)
        if (state.profile != null) {
            launch { remoteViewModel.state.collect { reportsViewModel.autoRemote(it) } }
            launch { assistantViewModel.state.collect { reportsViewModel.autoAssistant(it) } }
        }
    }
    var passwordVisible by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFF7AA32), onPrimary = Color(0xFF151B25),
        background = Color(0xFF101824), surface = Color(0xFF1A2635),
        onBackground = Color(0xFFF2F5F8), onSurface = Color(0xFFF2F5F8),
    )) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.profile != null && reportsState.open) {
                ReportsScreen(reportsViewModel, onExportReport)
            } else if (state.profile != null && assistantState.open) {
                AssistantScreen(assistantViewModel, onVoiceInput, reportsState.error ?: reportsState.notice)
            } else if (state.profile != null && remoteState.open) {
                RemoteScreen(remoteViewModel, reportsState.error ?: reportsState.notice) {
                    val question = remoteState.diagnosticQuestion()
                    val context = reportsViewModel.captureContext(remoteState)
                    remoteViewModel.disconnect()
                    assistantViewModel.open(question, context)
                }
            } else if (state.profile != null && diagnosticsState.open) {
                DiagnosticsScreen(diagnosticsViewModel, onScan, onEnableBluetooth, onPermissions, onLocation)
            } else if (state.profile != null && catalogState.open) {
                CatalogScreen(catalogViewModel, diagnosticsViewModel::open)
            } else {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("FleetBeat Smart Roadside", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                    Text(if (state.profile == null) "Sign in to your technician account" else "Your account",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp)
                    if (state.profile == null) {
                        OutlinedTextField(state.email, viewModel::emailChanged, Modifier.fillMaxWidth(),
                            label = { Text("Email") }, singleLine = true, enabled = !state.loading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(state.password, viewModel::passwordChanged, Modifier.fillMaxWidth(),
                            label = { Text("Password") }, singleLine = true, enabled = !state.loading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(if (passwordVisible) "Hide" else "Show")
                            } })
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Signed in", color = MaterialTheme.colorScheme.primary)
                                Text(state.profile!!.login, fontSize = 20.sp)
                                Text("Account active")
                            }
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.profile == null) {
                        Button(onClick = viewModel::login, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign in") }
                    } else {
                        Button(onClick = catalogViewModel::open, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Select vehicle") }
                        OutlinedButton(onClick = diagnosticsViewModel::open, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Adapter & live data") }
                        OutlinedButton(onClick = { diagnosticsViewModel.closeScreen(); remoteViewModel.open() }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Remote diagnostics") }
                        OutlinedButton(onClick = { assistantViewModel.open() }, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Diagnostic assistant") }
                        OutlinedButton(onClick = reportsViewModel::open, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Saved reports") }
                        OutlinedButton(onClick = viewModel::refresh, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Refresh profile") }
                        OutlinedButton(onClick = viewModel::logout, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign out") }
                    }
                }
            }
            }
        }
        if (state.profile != null && reportsState.draft != null) SaveReportDialog(reportsViewModel)
    }
}
