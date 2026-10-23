package com.fleetbeat.roadside

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.fleetbeat.roadside.guided.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.fleetbeat.roadside.reports.*
import com.fleetbeat.roadside.auth.AuthViewModel
import com.fleetbeat.roadside.catalog.CatalogViewModel
import com.fleetbeat.roadside.catalog.CatalogScreen
import com.fleetbeat.roadside.diagnostics.DiagnosticsViewModel
import com.fleetbeat.roadside.diagnostics.DiagnosticsScreen

@Composable
fun App(viewModel: AuthViewModel, catalogViewModel: CatalogViewModel, diagnosticsViewModel: DiagnosticsViewModel, remoteViewModel: RemoteViewModel, assistantViewModel: AssistantViewModel, reportsViewModel: ReportsViewModel, guidedViewModel: GuidedViewModel,
        onExportReport: () -> Unit, onVoiceInput: () -> Unit, onScan: () -> Unit, onEnableBluetooth: () -> Unit, onPermissions: () -> Unit, onLocation: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val guidedState by guidedViewModel.state.collectAsState()
    val reportsState by reportsViewModel.state.collectAsState()
    val assistantState by assistantViewModel.state.collectAsState()
    val remoteState by remoteViewModel.state.collectAsState()
    val diagnosticsState by diagnosticsViewModel.state.collectAsState()
    val catalogState by catalogViewModel.state.collectAsState()
    LaunchedEffect(state.profile?.id) { catalogViewModel.bindUser(state.profile?.id); diagnosticsViewModel.bindUser(state.profile?.id); remoteViewModel.bindUser(state.profile?.id); assistantViewModel.bindUser(state.profile?.id); reportsViewModel.bindUser(state.profile?.id); guidedViewModel.bindUser(state.profile?.id) }
    LaunchedEffect(state.profile?.id) {
        reportsViewModel.bindUser(state.profile?.id)
        if (state.profile != null) {
            launch { diagnosticsViewModel.state.collect { reportsViewModel.autoLocal(it) } }
            launch { remoteViewModel.state.collect { reportsViewModel.autoRemote(it) } }
            launch { assistantViewModel.state.collect { reportsViewModel.autoAssistant(it) } }
        }
    }
    var homeTab by rememberSaveable(state.profile?.id) { mutableStateOf(0) }
    var passwordVisible by remember { mutableStateOf(false) }
    MaterialTheme(colorScheme = FleetBeatLightColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.profile != null && reportsState.open) {
                ReportsScreen(reportsViewModel, onExportReport)
            } else if (state.profile != null && guidedState.open) {
                GuidedScreen(guidedViewModel) { question -> guidedViewModel.closeScreen(); assistantViewModel.open(question) }
            } else if (state.profile != null && assistantState.open) {
                AssistantScreen(assistantViewModel, onVoiceInput, { assistantViewModel.readFaultCodes(reportsViewModel::captureFaultContext) }, reportsState.error ?: reportsState.notice)
            } else if (state.profile != null && remoteState.open) {
                RemoteScreen(remoteViewModel, reportsState.error ?: reportsState.notice) {
                    val question = remoteState.diagnosticQuestion()
                    val context = reportsViewModel.captureContext(remoteState)
                    remoteViewModel.disconnect()
                    assistantViewModel.open(question, context)
                }
            } else if (state.profile != null && diagnosticsState.open) {
                DiagnosticsScreen(diagnosticsViewModel, onScan, onEnableBluetooth, onPermissions, onLocation, reportsState.error) {
                    val context = reportsViewModel.captureLocalContext(diagnosticsState)
                    val question = context.broadcastQuestion()
                    reportsViewModel.autoLocal(diagnosticsState)
                    diagnosticsViewModel.closeScreen()
                    assistantViewModel.open(question, context)
                }
            } else if (state.profile != null && catalogState.open) {
                CatalogScreen(catalogViewModel, diagnosticsViewModel::open)
            } else if (state.profile != null) {
                HomeScreen(login = state.profile!!.login, selectedTab = homeTab, onTab = { homeTab = it },
                    loading = state.loading, error = state.error,
                    onGuided = guidedViewModel::open, onAi = { assistantViewModel.open() },
                    onVehicle = catalogViewModel::open, onAdapter = diagnosticsViewModel::open,
                    onRemote = { diagnosticsViewModel.closeScreen(); remoteViewModel.open() },
                    onReports = reportsViewModel::open, onRefresh = viewModel::refresh, onLogout = viewModel::logout)
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
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.profile == null) {
                        Button(onClick = viewModel::login, enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign in") }

                    }
                }
            }
            }
        }
        if (state.profile != null && reportsState.draft != null) SaveReportDialog(reportsViewModel)
    }
}
