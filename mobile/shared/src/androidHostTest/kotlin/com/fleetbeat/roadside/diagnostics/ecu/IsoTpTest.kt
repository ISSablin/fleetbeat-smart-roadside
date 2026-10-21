package com.fleetbeat.roadside.diagnostics.ecu

import com.fleetbeat.roadside.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IsoTpTest {
    private val rx = 0x18DAF900u
    private val tx = 0x18DA00F9u
    private fun frame(vararg data: Int) = CanFrame(rx, data.map { it.toByte() }.toByteArray())
    @Test fun singleFrameIgnoresOtherEcuAndWireRoundTrips() {
        val decoder = IsoTpReceiver(rx) { 0 }
        assertNull(decoder.accept(CanFrame(123u, byteArrayOf(1, 2))))
        val response = frame(5, 0x62, 0xF4, 0x0C, 0x0A, 0x28, 0, 0)
        val packet = AdapterWire().feed(canWire(response)).single() as AdapterPacket.Can
        assertEquals(rx, packet.frame.id)
        assertContentEquals(response.data, packet.frame.data)
        assertContentEquals(byteArrayOf(0x62, 0xF4.toByte(), 0x0C, 0x0A, 0x28),
            (decoder.accept(response) as IsoTpEvent.Payload).bytes)
    }
    @Test fun multiFrameAssemblesAndSequenceWraps() {
        val decoder = IsoTpReceiver(rx) { 0 }
        val payload = ByteArray(130) { it.toByte() }
        assertIs<IsoTpEvent.FlowControl>(decoder.accept(CanFrame(rx, byteArrayOf(0x10, 130.toByte()) + payload.take(6))))
        var sequence = 1; var last: IsoTpEvent? = null
        payload.drop(6).chunked(7).forEach { chunk ->
            last = decoder.accept(CanFrame(rx, byteArrayOf((0x20 or sequence).toByte()) + chunk))
            sequence = (sequence + 1) and 15
        }
        assertContentEquals(payload, (last as IsoTpEvent.Payload).bytes)
    }
    @Test fun wrongSequenceShortFrameAndOversizeAreRejected() {
        fun receiver() = IsoTpReceiver(rx) { 0 }
        val decoder = receiver()
        decoder.accept(frame(0x10, 10, 0x59, 2, 1, 2, 3, 4))
        assertFailsWith<IllegalArgumentException> { decoder.accept(frame(0x22, 1, 2, 3, 4, 0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { receiver().accept(frame(7, 1)) }
        assertFailsWith<IllegalArgumentException> { receiver().accept(frame(0x14, 1, 0, 0, 0, 0, 0, 0)) }
        assertFailsWith<IllegalArgumentException> { receiver().accept(frame(0x10, 0, 0, 0, 0, 0, 0, 0)) }
    }
    @Test fun interFrameTimeoutRejectsPartialResponseAndCanReset() {
        var time = 0L
        val decoder = IsoTpReceiver(rx) { time }
        decoder.accept(frame(0x10, 10, 0x59, 2, 1, 2, 3, 4))
        time = 1501
        assertFailsWith<IllegalStateException> { decoder.accept(frame(0x21, 1, 2, 3, 4, 0, 0, 0)) }
        decoder.reset()
        assertIs<IsoTpEvent.Payload>(decoder.accept(frame(1, 0x62)))
    }
    @Test fun benchDtcLayoutIsExplicitAndMalformedStandardLayoutIsRejected() {
        val payload = byteArrayOf(0x59, 2, 0x0C, 0x49, 0, 0x2F, 0, 0x97.toByte(), 0, 15)
        val codes = BenchDecode.faults(payload)
        assertEquals(listOf("0C4900", "009700"), codes.map { it.raw })
        assertEquals(listOf(47, 15), codes.map { it.status })
        assertFailsWith<IllegalArgumentException> { BenchDecode.faults(byteArrayOf(0x59, 2, -1) + payload.drop(2)) }
        assertTrue(BenchDecode.faults(byteArrayOf(0x59, 2)).isEmpty())
        assertNull(BenchDecode.rpm(byteArrayOf(0x62, 0xF4.toByte(), 0x0C, -1, -1)))
        assertFailsWith<IllegalArgumentException> { BenchDecode.coolant(byteArrayOf(0x62, 0, 0x67, 70)) }
    }
    @Test fun unsupportedProtocolMetadataDoesNotProduceProfile() {
        assertFailsWith<IllegalArgumentException> { BenchProfile.from(ProtocolEnvelope(123, "ecu_session")) }
        assertFailsWith<IllegalStateException> { BenchProfile.from(ProtocolEnvelope(5935, "ecu_session", EcuProtocol(listOf(EcuInit("can", 250000, tx.toInt(), rx.toInt(), 29))))) }
    }
    @Test fun exchangeIgnoresWrongDidAndAllowsBoundedResponsePending() = runTest {
        val sent = mutableListOf<CanFrame>()
        val exchange = EcuExchange(tx, rx, { testScheduler.currentTime }, sent::add)
        val result = async { exchange.read(EcuRead.EngineRpm) }; runCurrent()
        exchange.receive(frame(4, 0x62, 0xF4, 0x67, 80, 0, 0, 0))
        exchange.receive(frame(3, 0x7F, 0x22, 0x78, 0, 0, 0, 0))
        assertFalse(result.isCompleted)
        exchange.receive(frame(5, 0x62, 0xF4, 0x0C, 0x0A, 0x28, 0, 0))
        assertEquals(650.0, BenchDecode.rpm(result.await()))
        assertEquals(1, sent.size)
    }
    @Test fun exchangeNegativeResponseAndNoResponseFail() = runTest {
        val exchange = EcuExchange(tx, rx, { testScheduler.currentTime }) { }
        supervisorScope {
            val rejected = async { exchange.read(EcuRead.EngineRpm) }; runCurrent()
            exchange.receive(frame(3, 0x7F, 0x22, 0x31, 0, 0, 0, 0))
            assertContains(assertFailsWith<IllegalStateException> { rejected.await() }.message!!, "NRC 31")
            val timeout = async { exchange.read(EcuRead.EngineRpm) }; runCurrent()
            advanceTimeBy(10_001); runCurrent()
            assertFailsWith<TimeoutCancellationException> { timeout.await() }
        }
    }
}
