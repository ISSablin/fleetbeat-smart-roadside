package com.fleetbeat.roadside

import android.os.Bundle
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
        setContent {
            val catalogState by catalogViewModel.state.collectAsState()
            val authState by viewModel.state.collectAsState()
            BackHandler(enabled = authState.profile != null && catalogState.open) { catalogViewModel.back() }
            App(viewModel, catalogViewModel)
        }
    }
}
