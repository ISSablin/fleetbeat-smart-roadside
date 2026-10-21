package com.fleetbeat.roadside.diagnostics.ecu

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.diagnostics.*
import com.fleetbeat.roadside.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestedDiagnosticsTest {
    private class Catalog : DiagnosticCatalog {
        var unavailable = false
        override suspend fun benchProfile() = BenchProfile(0x18DA00F9u, 0x18DAF900u, 500)
        override suspend fun descriptions(codes: List<String>): List<FaultDescription> {
            check(!unavailable) { "No catalog" }
            return codes.map { FaultDescription(it, "Catalog label", "Catalog description") }
        }
    }
    private class Pipe : RemotePipe {
        val connection = MutableStateFlow(BrokerState.Disconnected)
        override val state = connection.asStateFlow()
        val messages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 32)
        override val incoming = messages.asSharedFlow()
        val writes = mutableListOf<ByteArray>()
        var answerEcu = true
        var badSequence = false
        override suspend fun connect(login: String, token: String) { connection.value = BrokerState.Connected }
        override suspend fun subscribe(topic: String) { }
        override suspend fun publish(topic: String, bytes: ByteArray) {
            writes += bytes
            val up = topic.replace("/down", "/up")
            if (bytes.first() == 1.toByte()) {
                val frame = (AdapterWire().feed(bytes).single() as AdapterPacket.Can).frame
                if (frame.data[0] == 0x30.toByte() || !answerEcu) return
                val sid = frame.data[1].toInt() and 255
                val response = if (sid == 0x19) byteArrayOf(0x59, 2, 0x0C, 0x49, 0, 0x2F, 0, 0x97.toByte(), 0, 15)
                    else if (frame.data[3] == 0x0C.toByte()) byteArrayOf(0x62, 0xF4.toByte(), 0x0C, 0x0A, 0x28)
                    else byteArrayOf(0x62, 0xF4.toByte(), 0x67, 118)
                if (response.size <= 7) messages.emit(up to canWire(CanFrame(0x18DAF900u,
                    (byteArrayOf(response.size.toByte()) + response).copyOf(8))))
                else {
                    messages.emit(up to canWire(CanFrame(0x18DAF900u, byteArrayOf(0x10, response.size.toByte()) + response.take(6))))
                    messages.emit(up to canWire(CanFrame(0x18DAF900u,
                        (byteArrayOf(if (badSequence) 0x22 else 0x21) + response.drop(6)).copyOf(8))))
                }
            } else {
                val fields = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                messages.emit(up to buildJsonObject {
                    fields.forEach { (key, value) -> put(key, value) }
                    put("t", "status"); put("ok", true)
                }.toString().encodeToByteArray())
            }
        }
        override suspend fun disconnect() { close() }
        override fun close() { connection.value = BrokerState.Disconnected }
    }
    private fun fixture(block: suspend TestScope.(RemoteViewModel, Pipe, Catalog) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pipe = Pipe(); val catalog = Catalog()
        val vm = RemoteViewModel(object : RemoteSessions {
            override suspend fun join(code: String) = RemoteSession(code, "joined")
        }, pipe, { "tech@example.com" to "token" }, { testScheduler.currentTime }, {}, catalog)
        val store = ViewModelStore(); store.put("remote", vm)
        try { vm.open(); vm.code("DIAG"); vm.join(); runCurrent(); block(vm, pipe, catalog) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun readValuesConfiguresPhysicalEcuAndStopsStream() = fixture { vm, pipe, _ ->
        vm.readEcuValues(); runCurrent()
        assertFalse(vm.state.value.busy); assertTrue(vm.state.value.linked)
        assertEquals(listOf(650.0, 78.0), vm.state.value.requestedValues.map { it.value })
        val configs = pipe.writes.filter { it.first() != 1.toByte() }.map { Json.parseToJsonElement(it.decodeToString()).jsonObject }
        assertTrue(configs.any { it["mode"]?.jsonPrimitive?.content == "active" && it["filter"]?.jsonArray?.single()?.jsonPrimitive?.long == 0x18DAF900L })
        assertEquals(false, configs.last()["stream"]?.jsonPrimitive?.boolean)
        val requests = pipe.writes.filter { it.first() == 1.toByte() }.map { (AdapterWire().feed(it).single() as AdapterPacket.Can).frame }
        assertTrue(requests.all { it.id == 0x18DA00F9u && it.data[1] == 0x22.toByte() })
    }
    @Test fun multiFrameFaultsReceiveFlowControlAndDescriptions() = fixture { vm, pipe, _ ->
        vm.readEcuFaults(); runCurrent()
        assertEquals(listOf("0C4900", "009700"), vm.state.value.ecuFaults.map { it.raw })
        assertTrue(vm.state.value.ecuFaults.all { it.description == "Catalog description" })
        assertNotNull(vm.state.value.faultsReadAt)
        val requests = pipe.writes.filter { it.first() == 1.toByte() }.map { (AdapterWire().feed(it).single() as AdapterPacket.Can).frame }
        assertTrue(requests.any { it.data[0] == 0x30.toByte() })
        assertTrue(requests.all { it.data[0] == 0x30.toByte() || it.data[1] == 0x19.toByte() })
    }
    @Test fun catalogFailureKeepsRawFaults() = fixture { vm, _, catalog ->
        catalog.unavailable = true
        vm.readEcuFaults(); runCurrent()
        assertEquals(2, vm.state.value.ecuFaults.size)
        assertNotNull(vm.state.value.catalogWarning); assertTrue(vm.state.value.linked)
    }
    @Test fun badSequenceDisconnectsWithoutReportingEmptyFaults() = fixture { vm, pipe, _ ->
        pipe.badSequence = true
        vm.readEcuFaults(); runCurrent()
        assertFalse(vm.state.value.linked)
        assertContains(vm.state.value.error!!, "sequence")
        assertNull(vm.state.value.faultsReadAt)
    }
    @Test fun disconnectCancelsReadAndAllowsFreshJoin() = fixture { vm, pipe, _ ->
        pipe.answerEcu = false
        vm.readEcuValues(); runCurrent(); assertTrue(vm.state.value.ecuBusy)
        vm.disconnect(); runCurrent()
        assertFalse(vm.state.value.busy); assertTrue(vm.state.value.requestedValues.isEmpty())
        pipe.answerEcu = true
        vm.join(); runCurrent(); vm.readEcuValues(); runCurrent()
        assertEquals(2, vm.state.value.requestedValues.size)
    }
    @Test fun broadcastListeningAndUnknownSessionDoNotSendRequests() = fixture { vm, pipe, _ ->
        vm.startListening(); runCurrent()
        vm.readEcuValues(); runCurrent()
        assertTrue(pipe.writes.none { it.first() == 1.toByte() })
        vm.disconnect(); runCurrent(); vm.code("OTHER"); vm.join(); runCurrent()
        vm.readEcuFaults(); runCurrent()
        assertTrue(pipe.writes.none { it.first() == 1.toByte() })
    }
}
