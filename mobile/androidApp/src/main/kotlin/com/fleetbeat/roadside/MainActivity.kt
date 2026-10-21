package com.fleetbeat.roadside

import com.fleetbeat.roadside.remote.*
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.Manifest
import android.content.Intent
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
                    SystemClock::elapsedRealtime, viewModel::sessionExpired) { client.close() } as T
            }
        }
        val remoteViewModel = ViewModelProvider(this, remoteFactory)[RemoteViewModel::class.java]
        setContent {
            val remoteState by remoteViewModel.state.collectAsState()
            val catalogState by catalogViewModel.state.collectAsState()
            val authState by viewModel.state.collectAsState()
            val diagnosticsState by diagnosticsViewModel.state.collectAsState()
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
            BackHandler(enabled = authState.profile != null && (catalogState.open || diagnosticsState.open || remoteState.open)) {
                if (remoteState.open) remoteViewModel.disconnect(true)
                else if (diagnosticsState.open) diagnosticsViewModel.closeScreen() else catalogViewModel.back()
            }
            App(viewModel, catalogViewModel, diagnosticsViewModel, remoteViewModel, ::requestScan, ::requestEnable,
                { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        }
    }
}
