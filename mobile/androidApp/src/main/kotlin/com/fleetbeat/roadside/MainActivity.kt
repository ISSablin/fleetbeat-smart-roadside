package com.fleetbeat.roadside

import com.fleetbeat.roadside.remote.*
import com.fleetbeat.roadside.assistant.*
import com.fleetbeat.roadside.reports.*
import java.io.File
import java.util.UUID
import java.util.Date
import java.util.TimeZone
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fleetbeat.roadside.diagnostics.ecu.DiagnosticCatalogApi
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.speech.RecognizerIntent
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.provider.Settings
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fleetbeat.roadside.diagnostics.DiagnosticsViewModel
import com.fleetbeat.roadside.diagnostics.ble.AndroidBleRepository
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fleetbeat.roadside.catalog.CarApi
import com.fleetbeat.roadside.catalog.CatalogViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.fleetbeat.roadside.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0xFF101824.toInt()),
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val client = HttpClient(OkHttp) { configureClient(this) }
                val repository = AuthRepository(AuthApi(client, BuildConfig.API_BASE_URL),
                    AndroidSessionStore(applicationContext), System::currentTimeMillis)
                return AuthViewModel(repository) { client.close() } as T
            }
        }
        val viewModel = ViewModelProvider(this, factory)[AuthViewModel::class.java]
        val catalogFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val client = HttpClient(OkHttp) { configureClient(this) }
                return CatalogViewModel(CarApi(client, BuildConfig.CAR_API_BASE_URL, viewModel::accessToken),
                    viewModel::sessionExpired) { client.close() } as T
            }
        }
        val catalogViewModel = ViewModelProvider(this, catalogFactory)[CatalogViewModel::class.java]
        val diagnosticsFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiagnosticsViewModel(AndroidBleRepository(applicationContext), SystemClock::elapsedRealtime) as T
        }
        val diagnosticsViewModel = ViewModelProvider(this, diagnosticsFactory)[DiagnosticsViewModel::class.java]
        val remoteFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val client = HttpClient(OkHttp) { configureClient(this) }
                return RemoteViewModel(RemoteApi(client, BuildConfig.API_BASE_URL, viewModel::accessToken),
                    AndroidRemotePipe(BuildConfig.MQTT_HOST, BuildConfig.MQTT_PORT),
                    { (viewModel.state.value.profile?.login ?: "") to viewModel.accessToken() },
                    SystemClock::elapsedRealtime, viewModel::sessionExpired,
                    DiagnosticCatalogApi(client, BuildConfig.CAR_API_BASE_URL, viewModel::accessToken)) { client.close() } as T
            }
        }
        val remoteViewModel = ViewModelProvider(this, remoteFactory)[RemoteViewModel::class.java]
        val assistantFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val client = HttpClient(OkHttp) { configureClient(this) }
                return AssistantViewModel(AdviceApi(client, BuildConfig.API_BASE_URL, viewModel::accessToken),
                    viewModel::sessionExpired) { client.close() } as T
            }
        }
        val assistantViewModel = ViewModelProvider(this, assistantFactory)[AssistantViewModel::class.java]
        val reportsFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReportsViewModel(
                AndroidReportStore(File(filesDir, "diagnostic-reports")), BuildConfig.API_BASE_URL,
                System::currentTimeMillis, SystemClock::elapsedRealtime,
                { at -> SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at)) },
                { UUID.randomUUID().toString() }) as T
        }
        val reportsViewModel = ViewModelProvider(this, reportsFactory)[ReportsViewModel::class.java]
        setContent {
            val reportsState by reportsViewModel.state.collectAsState()
            val assistantState by assistantViewModel.state.collectAsState()
            val remoteState by remoteViewModel.state.collectAsState()
            val catalogState by catalogViewModel.state.collectAsState()
            val authState by viewModel.state.collectAsState()
            val diagnosticsState by diagnosticsViewModel.state.collectAsState()
            var reportExportToken by rememberSaveable { mutableStateOf<Int?>(null) }
            val exportReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                val token = reportExportToken
                reportExportToken = null
                if (token != null) {
                    if (uri == null) reportsViewModel.cancelExport(token)
                    else reportsViewModel.writeExport(token) { text ->
                        withContext(Dispatchers.IO) {
                            val output = contentResolver.openOutputStream(uri, "wt") ?: error("Could not open export location.")
                            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                        }
                    }
                }
            }
            fun requestReportExport() {
                val ticket = reportsViewModel.beginExport() ?: return
                reportExportToken = ticket.token
                try { exportReport.launch(ticket.filename) }
                catch (_: ActivityNotFoundException) { reportExportToken = null; reportsViewModel.cancelExport(ticket.token, failed = true) }
                catch (_: SecurityException) { reportExportToken = null; reportsViewModel.cancelExport(ticket.token, failed = true) }
            }
            var speechRequest by rememberSaveable { mutableStateOf<Int?>(null) }
            val speechInput = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val token = speechRequest
                speechRequest = null
                if (token != null) {
                    val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
                    when (result.resultCode) {
                        RESULT_OK -> assistantViewModel.voiceResult(token, transcript)
                        RESULT_CANCELED -> assistantViewModel.voiceResult(token, cancelled = true)
                        RecognizerIntent.RESULT_NO_MATCH -> assistantViewModel.voiceResult(token)
                        RecognizerIntent.RESULT_AUDIO_ERROR -> assistantViewModel.voiceResult(token,
                            failure = "The speech service could not use the microphone. Check its microphone permission and try again.")
                        else -> assistantViewModel.voiceResult(token,
                            failure = "Speech recognition is unavailable right now. Check your connection or type your question.")
                    }
                }
            }
            fun requestVoiceInput() {
                val token = assistantViewModel.beginVoiceInput() ?: return
                speechRequest = token
                try {
                    speechInput.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the symptoms or fault codes")
                    })
                } catch (_: ActivityNotFoundException) {
                    speechRequest = null
                    assistantViewModel.voiceResult(token, failure = "No speech recognition service is installed. Enable a speech service or type your question.")
                } catch (_: SecurityException) {
                    speechRequest = null
                    assistantViewModel.voiceResult(token, failure = "Speech recognition is blocked. Check the speech service permissions or type your question.")
                }
            }
            var enableAfterPermission by remember { mutableStateOf(false) }
            val permissions = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                    if (enableAfterPermission) enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    else diagnosticsViewModel.scan()
                } else diagnosticsViewModel.permissionDenied()
            }
            fun requestScan() {
                enableAfterPermission = false
                if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) diagnosticsViewModel.scan()
                else requestPermissions.launch(permissions)
            }
            fun requestEnable() {
                enableAfterPermission = true
                if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                else requestPermissions.launch(permissions)
            }
            BackHandler(enabled = authState.profile != null && (catalogState.open || diagnosticsState.open || remoteState.open || assistantState.open || reportsState.open)) {
                if (reportsState.open) reportsViewModel.back()
                else if (assistantState.open) assistantViewModel.closeScreen()
                else if (remoteState.open) remoteViewModel.disconnect(true)
                else if (diagnosticsState.open) diagnosticsViewModel.closeScreen() else catalogViewModel.back()
            }
            App(viewModel, catalogViewModel, diagnosticsViewModel, remoteViewModel, assistantViewModel, reportsViewModel, ::requestReportExport, ::requestVoiceInput, ::requestScan, ::requestEnable,
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        }
    }
}
