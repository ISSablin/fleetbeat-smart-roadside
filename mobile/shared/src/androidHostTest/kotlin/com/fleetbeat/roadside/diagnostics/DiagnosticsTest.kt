package com.fleetbeat.roadside.diagnostics

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.diagnostics.ble.*
import com.fleetbeat.roadside.reports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class DecoderTest {
    private fun wire(id: UInt, bytes: ByteArray, extended: Boolean = true): ByteArray {
        val result = ByteArray(14)
        result[0] = 1; result[1] = (bytes.size or if (extended) 128 else 0).toByte()
        repeat(4) { result[it + 2] = (id shr (it * 8)).toByte() }
        bytes.copyInto(result, 6)
        return result
    }

    @Test fun binaryAndEscapedJsonCanBeFragmentedAtEveryByte() {
        val decoder = AdapterWire()
        val service = """{"t":"status","msg":"quote \" and } brace","ok":true}""".encodeToByteArray()
        val bytes = service + wire(0x18F00400u, byteArrayOf(1, 2, 3, 4, 5))
        val result = bytes.flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(2, result.size)
        assertIs<AdapterPacket.Service>(result[0])
        assertEquals(0x18F00400u, assertIs<AdapterPacket.Can>(result[1]).frame.id)
    }

    @Test fun malformedAndOversizeFramesDoNotCrashOrPoisonNextFrame() {
        val decoder = AdapterWire()
        val bad = wire(0x18F00400u, byteArrayOf(1))
        bad[1] = 137.toByte() // invalid DLC 9
        assertTrue(decoder.feed(bad).isEmpty())
        assertTrue(decoder.feed(("{" + "a".repeat(5000)).encodeToByteArray()).isEmpty())
        assertEquals(1, decoder.feed(wire(0x18F00400u, byteArrayOf(1))).size)
    }

    @Test fun unavailableValuesAndDifferentSourcesStayDistinct() {
        val data = J1939Data()
        data.accept(CanFrame(0x18F00400u, byteArrayOf(0, 0, 0, 0x50, 0x14)), 10)
        assertEquals(650.0, data.values["rpm:0"]?.value)
        data.accept(CanFrame(0x18F00403u, byteArrayOf(0, 0, 0, -1, -1)), 20)
        assertEquals(2, data.values.size)
        assertNull(data.values["rpm:3"]?.value)
        data.accept(CanFrame(0x18F00400u, byteArrayOf(0, 0, 0, -1, -1)), 30)
        assertNull(data.values["rpm:0"]?.value)
        assertEquals(0x1EF00, J1939Data.pgn(0x19EF80F9u))
    }

    @Test fun dm1UsesHighSpnBitsLowFmiBitsAndClearsPerEcu() {
        val data = J1939Data()
        val fault = byteArrayOf(4, -1, 0x21, 0x43, 0xB2.toByte(), 5, -1, -1)
        data.accept(CanFrame(0x18FECA00u, fault), 10)
        data.accept(CanFrame(0x18FECA03u, fault), 11)
        assertEquals(0x54321, data.faults[0]?.codes?.single()?.spn)
        assertEquals(18, data.faults[0]?.codes?.single()?.fmi)
        assertEquals(5, data.faults[0]?.codes?.single()?.occurrences)
        data.accept(CanFrame(0x18FECA00u, byteArrayOf(0, -1, 0, 0, 0, 0, -1, -1)), 12)
        assertTrue(data.faults[0]!!.codes.isEmpty())
        assertEquals(1, data.faults[3]!!.codes.size)
    }

    @Test fun bamTransfersAreSourceAwareAndRejectBrokenSequences() {
        val data = J1939Data()
        val cm = byteArrayOf(0x20, 10, 0, 2, -1, 0xCA.toByte(), 0xFE.toByte(), 0)
        val payload = byteArrayOf(4, -1, 0x21, 0x43, 0xB2.toByte(), 5, 0x10, 0, 3, 1)
        data.accept(CanFrame(0x18ECFF00u, cm), 0)
        data.accept(CanFrame(0x18ECFF03u, cm), 0)
        data.accept(CanFrame(0x18EBFF00u, byteArrayOf(1) + payload.copyOfRange(0, 7)), 10)
        data.accept(CanFrame(0x18EBFF03u, byteArrayOf(2) + ByteArray(7)), 11)
        data.accept(CanFrame(0x18EBFF00u, byteArrayOf(2) + payload.copyOfRange(7, 10) + ByteArray(4) { -1 }), 20)
        assertEquals(2, data.faults[0]!!.codes.size)
        assertFalse(data.faults.containsKey(3))
        data.accept(CanFrame(0x18ECFF03u, cm), 30)
        data.accept(CanFrame(0x18EBFF03u, byteArrayOf(1) + payload.copyOfRange(0, 7)), 2000)
        assertFalse(data.faults.containsKey(3))
    }

    @Test fun legacyDtcIsNotMisrepresentedAsModernSpn() {
        val data = J1939Data()
        data.accept(CanFrame(0x18FECA00u, byteArrayOf(4, -1, 1, 2, 3, 0x80.toByte(), -1, -1)), 0)
        val code = data.faults[0]!!.codes.single()
        assertTrue(code.legacy)
        assertNull(code.spn)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdapterSessionTest {
    private class FakeBle : BleRepository {
        val mutable = MutableStateFlow(BleUiState())
        override val uiState = mutable.asStateFlow()
        override val terminalLines = MutableStateFlow<List<BleTerminalLine>>(emptyList())
        val input = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
        override val incomingBytes = input.asSharedFlow()
        override val writePayloadSize = MutableStateFlow(509)
        val writes = mutableListOf<ByteArray>()
        var acknowledge = true
        var connectImmediately = true
        var closed = false
        override suspend fun refreshSystemState() { }
        override suspend fun startScan() { mutable.update { it.copy(isScanning = true, status = BleConnectionStatus.Scanning) } }
        override suspend fun stopScan() { mutable.update { it.copy(isScanning = false) } }
        override suspend fun connect(address: String) {
            mutable.value = BleUiState(status = if (connectImmediately) BleConnectionStatus.Connected else BleConnectionStatus.Connecting, connectedDevice = BleDevice(address, "ITD_ELD_TEST"))
        }
        override suspend fun disconnect() { mutable.value = BleUiState() }
        override suspend fun sendText(text: String, appendNewline: Boolean) { error("Not used") }
        override suspend fun sendHex(hex: String, appendNewline: Boolean) { error("Not used") }
        override fun clearTerminal() { }
        override fun close() { closed = true }
        override suspend fun sendBytes(bytes: ByteArray) {
            writes += bytes
            val fields = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (fields["t"]?.jsonPrimitive?.content == "set" && acknowledge) {
                input.emit(buildJsonObject {
                    fields.forEach { (key, value) -> put(key, value) }
                    put("t", "status"); put("ok", true)
                }.toString().encodeToByteArray())
            }
        }
    }

    @Test fun configurationAckIsRequiredAndNeverSendsVehicleCommands() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val ble = FakeBle()
        val vm = DiagnosticsViewModel(ble) { testScheduler.currentTime }
        val store = ViewModelStore()
        store.put("live", vm)
        try {
            vm.open(); vm.connect("test"); runCurrent()
            advanceTimeBy(600); runCurrent()
            vm.startListening(); runCurrent()
            assertTrue(vm.state.value.listening)
            val config = ble.writes.map { Json.parseToJsonElement(it.decodeToString()).jsonObject }.first { it["t"]?.jsonPrimitive?.content == "set" }
            assertEquals("listen", config["mode"]?.jsonPrimitive?.content)
            assertTrue(config["filter"]!!.jsonArray.isEmpty())
            assertTrue(ble.writes.all { it.first() == '{'.code.toByte() })
            vm.stopListening(); runCurrent()
            assertFalse(vm.state.value.listening)
            vm.closeScreen(); advanceTimeBy(250); runCurrent()
            assertFalse(vm.state.value.open)
            assertNull(vm.connection.value.connectedDevice)
        } finally { store.clear(); Dispatchers.resetMain() }
        assertTrue(ble.closed)
    }

    @Test fun receivedFramesAreSavedBeforeDisconnectAndNewConnectionGetsNewSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ble = FakeBle()
        val vm = DiagnosticsViewModel(ble) { testScheduler.currentTime }
        val rows = mutableMapOf<String, DiagnosticReport>()
        val persistence = object : ReportStore {
            override suspend fun load(owner: String) = StoredReports(rows.values.toList())
            override suspend fun save(owner: String, report: DiagnosticReport) { rows[report.id] = report }
            override suspend fun upsert(owner: String, report: DiagnosticReport) { rows[report.id] = report }
            override suspend fun delete(owner: String, id: String) { rows.remove(id) }
        }
        val reports = ReportsViewModel(persistence, "test-api", { 10000 }, { testScheduler.currentTime }, { "UTC" }, { java.util.UUID.randomUUID().toString() })
        val store = ViewModelStore().apply { put("live", vm); put("reports", reports) }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { reports.autoLocal(it) } }
        fun frame(id: UInt, payload: ByteArray) = ByteArray(14).also { bytes ->
            bytes[0] = 1; bytes[1] = (128 or payload.size).toByte()
            repeat(4) { bytes[it + 2] = (id shr (it * 8)).toByte() }; payload.copyInto(bytes, 6)
        }
        try {
            reports.bindUser(1); vm.bindUser(1); vm.open(); vm.connect("test"); runCurrent()
            advanceTimeBy(600); runCurrent(); val firstSession = vm.state.value.session
            assertTrue(firstSession > 0); assertEquals("ITD_ELD_TEST", vm.state.value.adapterName)
            vm.startListening(); runCurrent()
            ble.input.emit(frame(0x18F00400u, byteArrayOf(0, 0, 0, 0x50, 0x14)))
            ble.input.emit(frame(0x18FECA03u, byteArrayOf(4, -1, 0x21, 0x43, 0xB2.toByte(), 5, -1, -1)))
            runCurrent(); advanceTimeBy(600); runCurrent()
            assertEquals(650.0, rows.values.single().values.single().value)
            assertEquals(3, rows.values.single().faults.single().sourceAddress)
            val captured = rows.values.single()
            vm.closeScreen(); advanceTimeBy(300); runCurrent()
            assertTrue(vm.state.value.values.isEmpty()); assertEquals(captured, rows.values.single())
            vm.open(); vm.connect("test"); runCurrent(); advanceTimeBy(600); runCurrent()
            assertTrue(vm.state.value.session > firstSession)
            assertTrue(vm.state.value.values.isEmpty()); assertTrue(vm.state.value.faults.isEmpty())
            vm.bindUser(2); runCurrent()
            assertEquals(0, vm.state.value.session); assertFalse(vm.state.value.open)
        } finally { collector.cancel(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun missingAckCannotClaimListening() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ble = FakeBle().apply { acknowledge = false }
        val vm = DiagnosticsViewModel(ble) { testScheduler.currentTime }
        val store = ViewModelStore().apply { put("live", vm) }
        try {
            vm.open(); vm.connect("test"); runCurrent()
            advanceTimeBy(600); runCurrent()
            vm.startListening(); advanceTimeBy(7000); runCurrent()
            assertFalse(vm.state.value.listening)
            assertFalse(vm.state.value.busy)
            assertNotNull(vm.state.value.message)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun connectionTimeoutReleasesThePendingConnection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ble = FakeBle().apply { connectImmediately = false }
        val vm = DiagnosticsViewModel(ble) { testScheduler.currentTime }
        val store = ViewModelStore().apply { put("live", vm) }
        try {
            vm.open(); vm.connect("test"); runCurrent()
            advanceTimeBy(21_000); runCurrent()
            assertFalse(vm.state.value.busy)
            assertNull(vm.connection.value.connectedDevice)
            assertNotNull(vm.state.value.message)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

}
