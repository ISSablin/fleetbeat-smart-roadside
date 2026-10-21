package com.fleetbeat.roadside.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.auth.AuthFailure
import com.fleetbeat.roadside.diagnostics.*
import com.fleetbeat.roadside.diagnostics.ecu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.random.Random

data class RemoteState(
    val open: Boolean = false, val code: String = "", val linked: Boolean = false,
    val busy: Boolean = false, val listening: Boolean = false, val speed: Int = 500,
    val status: String = "Enter a diagnostic session code", val error: String? = null,
    val version: String? = null, val voltage: Double? = null,
    val rxBytes: Long = 0, val txBytes: Long = 0, val frames: Long = 0,
    val lastResponse: Long? = null, val now: Long = 0,
    val ecuBusy: Boolean = false, val ecuStatus: String? = null, val ecuProfile: String? = null,
    val requestedValues: List<RequestedValue> = emptyList(), val ecuFaults: List<EcuFault> = emptyList(),
    val faultsReadAt: Long? = null, val catalogWarning: String? = null,
    val values: List<LiveValue> = emptyList(), val faults: List<FaultReport> = emptyList(),
)

/** Technician side only. The vehicle-side phone/simulator owns the physical adapter. */
class RemoteViewModel(private val sessions: RemoteSessions, private val pipe: RemotePipe,
    private val credentials: () -> Pair<String, String>, private val clock: () -> Long,
    private val expired: (String) -> Unit, private val diagnosticCatalog: DiagnosticCatalog? = null, private val closeClient: () -> Unit = {}) : ViewModel() {
    private val mutable = MutableStateFlow(RemoteState())
    val state = mutable.asStateFlow()
    private val wire = AdapterWire()
    private val data = J1939Data()
    private val prefix = "fb${Random.nextInt(1_000_000)}"
    private var sequence = 0
    private var operation: Job? = null
    private var cleanup: Job? = null
    private var pending: CompletableDeferred<JsonObject>? = null
    private var pendingId: String? = null
    private var owner: Int? = null
    private var activeCode: String? = null
    private var streamRequested = false
    private var generation = 0
    private var ecu: EcuExchange? = null

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipe.incoming.collect { (topic, bytes) ->
                if (topic != activeCode?.let { "adapter/$it/up" }) return@collect
                mutable.update { it.copy(rxBytes = it.rxBytes + bytes.size) }
                wire.feed(bytes).forEach { packet ->
                    when (packet) {
                        is AdapterPacket.Can -> {
                            ecu?.receive(packet.frame)
                            if (mutable.value.listening) data.accept(packet.frame, clock())
                        }
                        is AdapterPacket.Service -> {
                            val fields = packet.fields
                            val type = (fields["t"] as? JsonPrimitive)?.contentOrNull
                            if (type in listOf("status", "hb", "hello")) mutable.update { it.copy(
                                lastResponse = clock(), version = (fields["version"] as? JsonPrimitive)?.contentOrNull ?: it.version,
                                voltage = (fields["voltage"] as? JsonPrimitive)?.doubleOrNull ?: it.voltage) }
                            if ((fields["id"] as? JsonPrimitive)?.contentOrNull == pendingId && pending != null) {
                                if (type == "err" || (fields["ok"] as? JsonPrimitive)?.booleanOrNull == false)
                                    pending?.completeExceptionally(IllegalStateException("The remote adapter rejected this command."))
                                else if (type == "status") pending?.complete(fields)
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            pipe.state.collect { broker ->
                if (broker == BrokerState.Error && activeCode != null) {
                    pending?.completeExceptionally(IllegalStateException("The broker connection was lost. Reconnect the session."))
                    operation?.cancel(); ecu?.cancel(); ecu = null
                    activeCode = null
                    pipe.close()
                    mutable.update { it.copy(linked = false, listening = false, busy = false, ecuBusy = false,
                        status = "Disconnected", error = "The broker connection was lost. Reconnect the session.") }
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (mutable.value.open) mutable.update { it.copy(now = clock(), frames = data.frames,
                    values = data.values.values.sortedWith(compareBy<LiveValue> { it.label }.thenBy { it.source }),
                    faults = data.faults.values.sortedBy { it.source }) }
            }
        }
    }
    fun bindUser(id: Int?) { if (owner != id) { disconnect(true); owner = id } }
    fun open() { mutable.update { it.copy(open = true) } }
    fun code(value: String) { if (!mutable.value.busy && !mutable.value.linked)
        mutable.update { it.copy(code = value.uppercase().filter { c -> c in 'A'..'Z' || c in '0'..'9' }.take(16), error = null) } }
    fun speed(value: Int) { if (!mutable.value.busy && !mutable.value.listening && value in listOf(250,500))
        mutable.update { it.copy(speed = value) } }
    fun join() {
        if (mutable.value.busy || mutable.value.linked) return
        launchOperation {
            cleanup?.join()
            val code = normalizedDiagnosticCode(mutable.value.code)
            val (login, token) = credentials()
            check(login.isNotBlank() && token.isNotBlank()) { "Sign in again before joining a session." }
            mutable.update { it.copy(status = "Looking up session…", frames = 0, values = emptyList(), faults = emptyList(),
                rxBytes = 0, txBytes = 0, lastResponse = null, version = null, voltage = null) }
            data.reset(); wire.reset()
            sessions.join(code)
            activeCode = code
            mutable.update { it.copy(status = "Connecting to broker…") }
            pipe.connect(login, token)
            pipe.subscribe("adapter/$code/up")
            mutable.update { it.copy(status = "Waiting for remote adapter…") }
            request { AdapterCommands.status(it) }
            mutable.update { it.copy(linked = true, status = "Remote adapter connected") }
        }
    }
    fun refreshStatus() {
        if (!mutable.value.linked || mutable.value.busy) return
        launchOperation { request { AdapterCommands.status(it) } }
    }
    fun startListening() {
        if (!mutable.value.linked || mutable.value.busy || mutable.value.listening) return
        launchOperation {
            data.reset(); wire.reset()
            val speed = mutable.value.speed
            streamRequested = true
            val response = request { AdapterCommands.listen(it, speed, true) }
            check((response["stream"] as? JsonPrimitive)?.booleanOrNull == true &&
                (response["mode"] as? JsonPrimitive)?.contentOrNull == "listen" &&
                (response["format"] as? JsonPrimitive)?.intOrNull == 29 &&
                (response["speed"] as? JsonPrimitive)?.intOrNull == speed) { "The adapter did not confirm the listening settings." }
            mutable.update { it.copy(listening = true, status = "Receiving remote J1939 broadcasts") }
        }
    }
    fun stopListening() {
        if (!mutable.value.linked || mutable.value.busy) return
        launchOperation {
            request { AdapterCommands.listen(it, mutable.value.speed, false) }
            streamRequested = false
            mutable.update { it.copy(listening = false, status = "Remote listening stopped") }
        }
    }
    fun readEcuValues() = diagnosticRead("Reading ECU values…") { exchange, _ ->
        val rpm = BenchDecode.rpm(exchange.read(EcuRead.EngineRpm))
        val rpmAt = clock()
        val coolant = BenchDecode.coolant(exchange.read(EcuRead.Coolant))
        mutable.update { it.copy(requestedValues = listOf(RequestedValue("Engine RPM", rpm, "rpm", rpmAt),
            RequestedValue("Coolant temperature", coolant, "°C", clock())), ecuStatus = "ECU values read") }
    }
    fun readEcuFaults() = diagnosticRead("Reading ECU fault codes…") { exchange, catalog ->
        val codes = BenchDecode.faults(exchange.read(EcuRead.Faults))
        mutable.update { it.copy(ecuFaults = codes, faultsReadAt = clock(), ecuStatus = "${codes.size} ECU fault codes read", catalogWarning = null) }
        // Raw ECU records remain usable if the separate description service fails.
        try {
            val descriptions = catalog.descriptions(codes.map { it.raw }).associateBy { it.raw.uppercase() }
            mutable.update { state -> state.copy(ecuFaults = codes.map { code ->
                val detail = descriptions[code.raw]
                if (detail?.description.isNullOrBlank()) code else code.copy(label = detail.show, description = detail.description)
            }) }
        } catch (error: CancellationException) { throw error }
          catch (error: AuthFailure) { throw error }
          catch (_: Exception) { mutable.update { it.copy(catalogWarning = "Descriptions are unavailable. Raw ECU codes and status are shown.") } }
    }
    private fun diagnosticRead(status: String, action: suspend (EcuExchange, DiagnosticCatalog) -> Unit) {
        if (!mutable.value.linked || mutable.value.busy || mutable.value.listening || activeCode != "DIAG") return
        launchOperation {
            val catalog = diagnosticCatalog ?: error("Requested diagnostics are unavailable in this build.")
            mutable.update { it.copy(ecuBusy = true, ecuStatus = status, catalogWarning = null) }
            val profile = catalog.benchProfile()
            mutable.update { it.copy(ecuProfile = "DIAG engine · protocol 5935 · 500 kbit/s", speed = profile.speed) }
            streamRequested = true
            val ack = request { diagnosticSettings(it, profile) }
            check((ack["mode"] as? JsonPrimitive)?.contentOrNull == "active" &&
                (ack["speed"] as? JsonPrimitive)?.intOrNull == profile.speed &&
                (ack["format"] as? JsonPrimitive)?.intOrNull == 29 &&
                (ack["stream"] as? JsonPrimitive)?.booleanOrNull == true &&
                (ack["filter"] as? JsonArray)?.map { it.jsonPrimitive.long } == listOf(profile.rx.toLong())) {
                "The adapter did not confirm the ECU diagnostic settings."
            }
            val exchange = EcuExchange(profile.tx, profile.rx, clock) { frame ->
                val code = activeCode ?: error("Remote session disconnected.")
                val bytes = canWire(frame)
                pipe.publish("adapter/$code/down", bytes)
                mutable.update { it.copy(txBytes = it.txBytes + bytes.size) }
            }
            ecu = exchange
            try { action(exchange, catalog) }
            finally { exchange.cancel(); if (ecu === exchange) ecu = null }
            request { AdapterCommands.listen(it, profile.speed, false) }
            streamRequested = false
            mutable.update { it.copy(ecuBusy = false, status = "Remote adapter connected") }
        }
    }
    fun disconnect(closeScreen: Boolean = false) {
        generation++
        ecu?.cancel(); ecu = null
        operation?.cancel(); pending?.cancel(); pending = null; pendingId = null
        val code = activeCode
        activeCode = null
        val speed = mutable.value.speed
        val wasListening = mutable.value.listening || streamRequested
        streamRequested = false
        mutable.value = RemoteState(open = mutable.value.open && !closeScreen, code = if (closeScreen) "" else mutable.value.code, speed = speed)
        data.reset(); wire.reset()
        val previousCleanup = cleanup
        cleanup = viewModelScope.launch {
            previousCleanup?.join()
            try {
                if (code != null && wasListening && pipe.state.value == BrokerState.Connected)
                    withTimeout(2000) { pipe.publish("adapter/$code/down", AdapterCommands.listen("$prefix-stop${++sequence}", speed, false)) }
            } catch (error: CancellationException) { if (error !is TimeoutCancellationException) throw error }
              catch (_: Exception) { /* Disconnect must still release the transport. */ }
            finally { pipe.close() }
        }
    }
    private suspend fun request(command: (String) -> ByteArray): JsonObject {
        val code = activeCode ?: error("Join a remote session first.")
        val id = "$prefix-${++sequence}"
        val response = CompletableDeferred<JsonObject>()
        pending = response; pendingId = id
        return try {
            val bytes = command(id)
            pipe.publish("adapter/$code/down", bytes)
            mutable.update { it.copy(txBytes = it.txBytes + bytes.size) }
            withTimeout(10_000) { response.await() }
        } finally { if (pending === response) { pending = null; pendingId = null } }
    }
    private fun launchOperation(action: suspend () -> Unit) {
        val epoch = generation
        mutable.update { it.copy(busy = true, error = null) }
        operation = viewModelScope.launch {
            try { action() }
            catch (error: TimeoutCancellationException) {
                if (generation == epoch) fail("The remote adapter did not respond. Check the vehicle-side connection and retry.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation != epoch) return@launch
                if (error is AuthFailure && error.invalidSession) expired(error.message ?: "Please sign in again.")
                fail(if (error is AuthFailure) error.message ?: "Please sign in again." else
                    if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "Remote operation failed."
                    else "Could not connect to the remote session. Check your connection and retry.")
            } finally {
                if (generation == epoch) mutable.update { it.copy(busy = false) }
            }
        }
    }
    private fun fail(message: String) {
        operation = null
        disconnect()
        mutable.update { it.copy(status = "Disconnected", error = message) }
    }
    override fun onCleared() { ecu?.cancel(); pipe.close(); closeClient(); super.onCleared() }
}
