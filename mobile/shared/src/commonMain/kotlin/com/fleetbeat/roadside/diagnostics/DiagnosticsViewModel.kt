package com.fleetbeat.roadside.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.diagnostics.ble.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class DiagnosticsState(
    val session: Int = 0, val dataSpeed: Int? = null, val adapterName: String? = null,
    val open: Boolean = false, val speed: Int = 500, val listening: Boolean = false,
    val busy: Boolean = false, val message: String? = null, val adapterVersion: String? = null,
    val adapterVoltage: Double? = null, val values: List<LiveValue> = emptyList(),
    val faults: List<FaultReport> = emptyList(), val frames: Long = 0, val now: Long = 0,
)

class DiagnosticsViewModel(private val ble: BleRepository, private val clock: () -> Long) : ViewModel() {
    val connection = ble.uiState
    private val mutable = MutableStateFlow(DiagnosticsState())
    val state = mutable.asStateFlow()
    private val wire = AdapterWire()
    private val data = J1939Data()
    private var operation: Job? = null
    private var scanTimer: Job? = null
    private var bootstrap: Job? = null
    private var ack: CompletableDeferred<Unit>? = null
    private var ackId: String? = null
    private var sequence = 0
    private var connectionSequence = 0
    private var ownerId: Int? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ble.incomingBytes.collect { payload ->
                wire.feed(payload).forEach { packet ->
                    when (packet) {
                        is AdapterPacket.Can -> if (mutable.value.listening) data.accept(packet.frame, clock())
                        is AdapterPacket.Service -> service(packet.fields)
                    }
                }
            }
        }
        viewModelScope.launch {
            var previous = BleConnectionStatus.Disconnected
            connection.collect { current ->
                if (current.status == BleConnectionStatus.Connected && previous != current.status && mutable.value.open) {
                    data.reset(); wire.reset()
                    mutable.update { it.copy(session = ++connectionSequence, dataSpeed = null, adapterName = current.connectedDevice?.name,
                        values = emptyList(), faults = emptyList(), frames = 0) }
                    bootstrap?.cancel()
                    bootstrap = viewModelScope.launch {
                        delay(300)
                        safely {
                            ble.sendBytes(AdapterCommands.enter("fb${++sequence}"))
                            delay(250)
                            ble.sendBytes(AdapterCommands.status("status${++sequence}"))
                        }
                    }
                } else if (current.status != BleConnectionStatus.Connected) {
                    ack?.completeExceptionally(IllegalStateException("Adapter disconnected"))
                    mutable.update { it.copy(listening = false) }
                    data.reset(); wire.reset()
                    if (previous == BleConnectionStatus.Connected) {
                        bootstrap?.cancel()
                        operation?.cancel()
                        mutable.update { it.copy(busy = false, values = emptyList(), faults = emptyList(), frames = 0,
                            adapterVoltage = null, adapterVersion = null) }
                    }
                }
                previous = current.status
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (mutable.value.open) mutable.update { it.copy(now = clock(), frames = data.frames,
                    values = data.values.values.sortedWith(compareBy<LiveValue> { v -> v.label }.thenBy { v -> v.source }),
                    faults = data.faults.values.sortedBy { report -> report.source }) }
            }
        }
    }

    fun bindUser(userId: Int?) {
        if (ownerId != userId) {
            if (mutable.value.open || connection.value.connectedDevice != null) closeScreen()
            mutable.value = DiagnosticsState()
            ownerId = userId
        }
    }
    fun open() { mutable.update { it.copy(open = true) }; viewModelScope.launch { safely { ble.refreshSystemState() } } }
    fun permissionDenied() { mutable.update { it.copy(message = "Allow Bluetooth permissions, then scan again. You can also change them in App permissions.") } }
    fun speed(value: Int) { if (!mutable.value.busy && !mutable.value.listening && value in listOf(250, 500)) mutable.update { it.copy(speed = value) } }

    fun scan() {
        if (mutable.value.busy || connection.value.status == BleConnectionStatus.Connected) return
        scanTimer?.cancel()
        scanTimer = viewModelScope.launch { safely { ble.startScan(); delay(20_000); ble.stopScan() } }
    }
    fun stopScan() { scanTimer?.cancel(); viewModelScope.launch { safely { ble.stopScan() } } }
    fun connect(address: String) {
        if (mutable.value.busy) return
        stopScan()
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null, adapterVoltage = null, adapterVersion = null) }
            safely {
                ble.connect(address)
                val connected = withTimeout(20_000) { connection.first { it.status in listOf(BleConnectionStatus.Connected, BleConnectionStatus.Error) } }
                if (connected.status != BleConnectionStatus.Connected) error(connected.message ?: "Could not connect to adapter")
            }
            if (connection.value.status != BleConnectionStatus.Connected) {
                safely { ble.disconnect() }
            }
            mutable.update { it.copy(busy = false) }
        }
    }

    fun startListening() {
        if (mutable.value.busy || mutable.value.listening || connection.value.status != BleConnectionStatus.Connected) return
        operation = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            safely {
                bootstrap?.join()
                data.reset(); wire.reset()
                ack = CompletableDeferred()
                ackId = "listen${++sequence}"
                ble.sendBytes(AdapterCommands.listen(ackId!!, mutable.value.speed, true))
                withTimeout(6_000) { ack!!.await() }
                mutable.update { it.copy(listening = true, dataSpeed = it.speed, values = emptyList(), faults = emptyList(), frames = 0) }
            }
            ack = null; ackId = null
            mutable.update { it.copy(busy = false) }
        }
    }

    fun stopListening() {
        operation?.cancel(); ack?.cancel(); ack = null; ackId = null
        mutable.update { it.copy(listening = false, busy = false) }
        viewModelScope.launch { safely { if (connection.value.status == BleConnectionStatus.Connected)
            ble.sendBytes(AdapterCommands.listen("stop${++sequence}", mutable.value.speed, false)) } }
    }

    fun disconnect() {
        operation?.cancel(); bootstrap?.cancel(); scanTimer?.cancel(); ack?.cancel()
        mutable.update { it.copy(listening = false, busy = true) }
        viewModelScope.launch {
            safely {
                if (connection.value.status == BleConnectionStatus.Connected) {
                    ble.sendBytes(AdapterCommands.exit("exit${++sequence}")); delay(200)
                }
            }
            safely { ble.stopScan(); ble.disconnect() }
            data.reset(); wire.reset()
            mutable.update { it.copy(busy = false, values = emptyList(), faults = emptyList(), frames = 0) }
        }
    }
    fun closeScreen() { mutable.update { it.copy(open = false) }; disconnect() }

    private fun service(fields: JsonObject) {
        fun string(name: String) = (fields[name] as? JsonPrimitive)?.contentOrNull
        when (string("t")) {
            "status", "hello", "hb" -> {
                mutable.update { it.copy(adapterVersion = string("version") ?: it.adapterVersion,
                    adapterVoltage = (fields["voltage"] as? JsonPrimitive)?.doubleOrNull ?: it.adapterVoltage) }
                if (ack != null && (string("id") == ackId || string("id") == null) &&
                    string("mode") == "listen" && (fields["stream"] as? JsonPrimitive)?.booleanOrNull == true &&
                    (fields["format"] as? JsonPrimitive)?.intOrNull == 29 &&
                    (fields["speed"] as? JsonPrimitive)?.intOrNull == mutable.value.speed &&
                    (fields["ok"] as? JsonPrimitive)?.booleanOrNull != false) ack?.complete(Unit)
            }
            "err" -> {
                val message = string("msg")?.takeIf { it.isNotBlank() } ?: string("code") ?: "Adapter error"
                if (ack != null && string("id") == ackId) ack?.completeExceptionally(IllegalStateException(message))
                else if (string("code") != "unknown_type") mutable.update { it.copy(message = message) }
            }
        }
    }

    private suspend fun safely(action: suspend () -> Unit) {
        try { action() }
        catch (error: TimeoutCancellationException) { mutable.update { it.copy(message = "Adapter did not respond in time. Reconnect and try again.") } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Adapter operation failed") } }
    }

    override fun onCleared() { ble.close(); super.onCleared() }
}
