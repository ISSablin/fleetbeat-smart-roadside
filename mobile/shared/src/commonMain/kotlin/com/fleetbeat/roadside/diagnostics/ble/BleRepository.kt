package com.fleetbeat.roadside.diagnostics.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BleRepository {
    val uiState: StateFlow<BleUiState>
    val terminalLines: StateFlow<List<BleTerminalLine>>

    /** Raw GATT notify payloads (adapter protocol / terminal). */
    val incomingBytes: SharedFlow<ByteArray>

    /** ATT write payload size (MTU − 3). JSON set needs ~100+. */
    val writePayloadSize: StateFlow<Int>

    suspend fun refreshSystemState()

    suspend fun startScan()

    suspend fun stopScan()

    suspend fun connect(address: String)

    suspend fun disconnect()

    suspend fun sendText(
        text: String,
        appendNewline: Boolean = false,
    )

    suspend fun sendHex(
        hex: String,
        appendNewline: Boolean = false,
    )

    /** Raw write for adapter binary/JSON frames. */
    suspend fun sendBytes(bytes: ByteArray)

    fun close()

    fun clearTerminal()
}
