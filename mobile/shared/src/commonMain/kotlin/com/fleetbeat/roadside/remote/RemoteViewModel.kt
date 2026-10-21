package com.fleetbeat.roadside.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.auth.AuthFailure
import com.fleetbeat.roadside.diagnostics.*
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
    val values: List<LiveValue> = emptyList(), val faults: List<FaultReport> = emptyList(),
)

/** Technician side only. The vehicle-side phone/simulator owns the physical adapter. */
class RemoteViewModel(private val sessions: RemoteSessions, private val pipe: RemotePipe,
    private val credentials: () -> Pair<String, String>, private val clock: () -> Long,
    private val expired: (String) -> Unit, private val closeClient: () -> Unit = {}) : ViewModel() {
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

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pipe.incoming.collect { (topic, bytes) ->
                if (topic != activeCode?.let { "adapter/$it/up" }) return@collect
                mutable.update { it.copy(rxBytes = it.rxBytes + bytes.size) }
                wire.feed(bytes).forEach { packet ->
                    when (packet) {
                        is AdapterPacket.Can -> if (mutable.value.listening) data.accept(packet.frame, clock())
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
                    operation?.cancel()
                    activeCode = null
                    pipe.close()
                    mutable.update { it.copy(linked = false, listening = false, busy = false,
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
    fun disconnect(closeScreen: Boolean = false) {
        generation++
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
    override fun onCleared() { pipe.close(); closeClient(); super.onCleared() }
}
