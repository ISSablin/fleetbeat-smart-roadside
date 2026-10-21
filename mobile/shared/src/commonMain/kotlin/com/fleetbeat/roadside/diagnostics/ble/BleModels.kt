package com.fleetbeat.roadside.diagnostics.ble

enum class BleConnectionStatus {
    Unavailable,
    Disabled,
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Disconnecting,
    Error,
}

data class BleDevice(
    val address: String,
    val name: String,
    val rssi: Int? = null,
    val signalStrength: Int = 0,
    val isConnected: Boolean = false,
    val isRemembered: Boolean = false,
)

data class BleUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val status: BleConnectionStatus = BleConnectionStatus.Disconnected,
    val devices: List<BleDevice> = emptyList(),
    val connectedDevice: BleDevice? = null,
    val isScanning: Boolean = false,
    val message: String? = null,
)

enum class BleTerminalDirection {
    TX,
    RX,
    STATUS,
    ERROR,
    HEAD,
}

data class BleTerminalLine(
    val text: String,
    val direction: BleTerminalDirection,
)
