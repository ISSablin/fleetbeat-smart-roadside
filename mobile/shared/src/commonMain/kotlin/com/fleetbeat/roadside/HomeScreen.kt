package com.fleetbeat.roadside

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(login: String, selectedTab: Int, onTab: (Int) -> Unit, loading: Boolean, error: String?,
    onGuided: () -> Unit, onAi: () -> Unit, onVehicle: () -> Unit, onAdapter: () -> Unit,
    onRemote: () -> Unit, onReports: () -> Unit, onRefresh: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).widthIn(max = 460.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("FleetBeat Smart Roadside", Modifier.padding(top = 24.dp, bottom = 20.dp),
                fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (selectedTab == 0) {
                    Button(onClick = onAi, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("AI diagnostic")
                    }
                    OutlinedButton(onClick = onGuided, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Guided diagnostics")
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Signed in", color = MaterialTheme.colorScheme.primary)
                            Text(login, fontSize = 20.sp)
                            Text("Account active")
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onVehicle, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Select vehicle") }
                    OutlinedButton(onClick = onAdapter, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Adapter & live data") }
                    OutlinedButton(onClick = onRemote, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Remote diagnostics") }
                    OutlinedButton(onClick = onReports, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Saved reports") }
                    OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Refresh profile") }
                    OutlinedButton(onClick = onLogout, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign out") }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
            windowInsets = WindowInsets(0)) {
            val colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                indicatorColor = Color.Transparent)
            NavigationBarItem(selected = selectedTab == 0, onClick = { onTab(0) }, colors = colors,
                icon = { Icon(HomeTabIcon, contentDescription = null, modifier = Modifier.size(26.dp)) },
                label = { Text("Main menu", fontSize = 11.sp, fontWeight = FontWeight.Medium) })
            NavigationBarItem(selected = selectedTab == 1, onClick = { onTab(1) }, colors = colors,
                icon = { Icon(ConfigTabIcon, contentDescription = null, modifier = Modifier.size(26.dp)) },
                label = { Text("Config", fontSize = 11.sp, fontWeight = FontWeight.Medium) })
        }
    }
}

// Local vector assets keep the navigation independent of an additional icon library.
private val HomeTabIcon = ImageVector.Builder("Home", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(12f, 3f); lineTo(2f, 12f); lineTo(5f, 12f); lineTo(5f, 21f)
        lineTo(10f, 21f); lineTo(10f, 15f); lineTo(14f, 15f); lineTo(14f, 21f)
        lineTo(19f, 21f); lineTo(19f, 12f); lineTo(22f, 12f); close()
    }
}.build()

private val ConfigTabIcon = ImageVector.Builder("Config", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(3f, 17f); verticalLineTo(19f); horizontalLineTo(9f); verticalLineTo(17f); close()
        moveTo(3f, 5f); verticalLineTo(7f); horizontalLineTo(13f); verticalLineTo(5f); close()
        moveTo(13f, 21f); verticalLineTo(19f); horizontalLineTo(21f); verticalLineTo(17f)
        horizontalLineTo(13f); verticalLineTo(15f); horizontalLineTo(11f); verticalLineTo(21f); close()
        moveTo(7f, 9f); verticalLineTo(11f); horizontalLineTo(3f); verticalLineTo(13f)
        horizontalLineTo(7f); verticalLineTo(15f); horizontalLineTo(9f); verticalLineTo(9f); close()
        moveTo(21f, 13f); verticalLineTo(11f); horizontalLineTo(11f); verticalLineTo(13f); close()
        moveTo(15f, 9f); horizontalLineTo(17f); verticalLineTo(7f); horizontalLineTo(21f)
        verticalLineTo(5f); horizontalLineTo(17f); verticalLineTo(3f); horizontalLineTo(15f); close()
    }
}.build()
