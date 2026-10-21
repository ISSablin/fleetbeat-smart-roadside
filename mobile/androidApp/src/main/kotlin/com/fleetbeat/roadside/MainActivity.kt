package com.fleetbeat.roadside

import android.os.Bundle
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
        setContent { App(viewModel) }
    }
}
