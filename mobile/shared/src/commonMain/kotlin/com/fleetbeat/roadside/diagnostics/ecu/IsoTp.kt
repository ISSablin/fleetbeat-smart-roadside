package com.fleetbeat.roadside.diagnostics.ecu

import com.fleetbeat.roadside.diagnostics.CanFrame
import kotlinx.coroutines.*

sealed interface IsoTpEvent {
    data class Payload(val bytes: ByteArray) : IsoTpEvent
    data object FlowControl : IsoTpEvent
}

/** Classical CAN, normal addressing, one physical ECU. CAN FD/extended addressing are not accepted. */
class IsoTpReceiver(private val receiveId: UInt, private val clock: () -> Long) {
    private var expectedSize = 0
    private var nextSequence = 1
    private var lastAt = 0L
    private val bytes = mutableListOf<Byte>()
    fun reset() { expectedSize = 0; nextSequence = 1; bytes.clear() }
    fun accept(frame: CanFrame): IsoTpEvent? {
        if (!frame.extended || frame.id != receiveId) return null
        val data = frame.data
        require(data.isNotEmpty() && data.size <= 8) { "Invalid ISO-TP CAN frame length." }
        if (expectedSize > 0 && clock() - lastAt > 1500) {
            reset(); error("The ECU multi-frame response timed out.")
        }
        val kind = u8(data[0]) shr 4
        return when (kind) {
            0 -> {
                check(expectedSize == 0) { "A new ECU response interrupted the transfer." }
                val size = u8(data[0]) and 15
                require(size in 1..7 && size + 1 <= data.size) { "Invalid ISO-TP single frame." }
                IsoTpEvent.Payload(data.copyOfRange(1, size + 1))
            }
            1 -> {
                check(expectedSize == 0) { "A new ECU transfer interrupted the response." }
                require(data.size == 8) { "Incomplete ISO-TP first frame." }
                val size = ((u8(data[0]) and 15) shl 8) or u8(data[1])
                require(size in 8..1024) { "ECU response exceeds the supported size." }
                expectedSize = size; nextSequence = 1; lastAt = clock()
                bytes.clear(); bytes.addAll(data.drop(2))
                IsoTpEvent.FlowControl
            }
            2 -> {
                if (expectedSize == 0) return null // Late or unrelated continuation.
                require(u8(data[0]) and 15 == nextSequence) { "ECU response frames arrived out of sequence." }
                val remaining = expectedSize - bytes.size
                require(data.size >= 1 + minOf(7, remaining)) { "Incomplete ISO-TP consecutive frame." }
                bytes.addAll(data.drop(1).take(remaining))
                nextSequence = (nextSequence + 1) and 15; lastAt = clock()
                if (bytes.size == expectedSize) {
                    val result = bytes.toByteArray(); reset(); IsoTpEvent.Payload(result)
                } else null
            }
            else -> error("Unexpected ISO-TP frame type.")
        }
    }
    private fun u8(value: Byte) = value.toInt() and 255
}

fun canWire(frame: CanFrame): ByteArray {
    require(frame.extended && frame.id <= 0x1FFFFFFFu && frame.data.size in 1..8)
    return ByteArray(14).also { bytes ->
        bytes[0] = 1; bytes[1] = (128 or frame.data.size).toByte()
        repeat(4) { bytes[2 + it] = (frame.id shr (8 * it)).toByte() }
        frame.data.copyInto(bytes, 6)
    }
}

/** Fixed read-only commands for the deployed DIAG bench, never arbitrary catalog commands. */
enum class EcuRead(val request: ByteArray, val positivePrefix: ByteArray) {
    EngineRpm(byteArrayOf(0x22, 0xF4.toByte(), 0x0C), byteArrayOf(0x62, 0xF4.toByte(), 0x0C)),
    Coolant(byteArrayOf(0x22, 0xF4.toByte(), 0x67), byteArrayOf(0x62, 0xF4.toByte(), 0x67)),
    Faults(byteArrayOf(0x19, 0x02, 0xAF.toByte()), byteArrayOf(0x59, 0x02)),
}

class EcuExchange(private val tx: UInt, private val rx: UInt, private val clock: () -> Long,
                  private val send: suspend (CanFrame) -> Unit) {
    private val receiver = IsoTpReceiver(rx, clock)
    private var completion: CompletableDeferred<ByteArray>? = null
    private var command: EcuRead? = null
    private var pendingCount = 0

    suspend fun read(read: EcuRead): ByteArray {
        check(completion == null) { "Another ECU request is in progress." }
        val result = CompletableDeferred<ByteArray>()
        receiver.reset(); command = read; completion = result; pendingCount = 0
        return try {
            val data = ByteArray(8)
            data[0] = read.request.size.toByte(); read.request.copyInto(data, 1)
            send(CanFrame(tx, data))
            withTimeout(10_000) { result.await() }
        } finally {
            if (completion === result) { completion = null; command = null; receiver.reset() }
        }
    }
    suspend fun receive(frame: CanFrame) {
        val waiting = completion ?: return
        val read = command ?: return
        if (frame.id != rx || !frame.extended || waiting.isCompleted) return
        try {
            // Do not assemble responses to another service or DID on the same ECU.
            if (frame.data.isNotEmpty()) {
                val kind = (frame.data[0].toInt() and 255) shr 4
                val offset = when (kind) { 0 -> 1; 1 -> 2; else -> null }
                if (offset != null) {
                    val first = frame.data.drop(offset).toByteArray()
                    val negative = first.size >= 2 && first[0] == 0x7F.toByte() && first[1] == read.request[0]
                    if (!negative && !first.startsWith(read.positivePrefix)) return
                }
            }
            when (val event = receiver.accept(frame)) {
                IsoTpEvent.FlowControl -> send(CanFrame(tx, byteArrayOf(0x30, 0, 0, 0, 0, 0, 0, 0)))
                is IsoTpEvent.Payload -> {
                    val payload = event.bytes
                    if (payload[0] == 0x7F.toByte()) {
                        require(payload.size == 3 && payload[1] == read.request[0]) { "Malformed ECU negative response." }
                        val nrc = payload[2].toInt() and 255
                        if (nrc == 0x78 && ++pendingCount <= 3) return
                        error("ECU rejected the read request (NRC ${nrc.toString(16).uppercase().padStart(2, '0')}).")
                    }
                    require(payload.startsWith(read.positivePrefix)) { "Unexpected ECU response." }
                    waiting.complete(payload)
                }
                null -> Unit
            }
        } catch (error: TimeoutCancellationException) { receiver.reset(); waiting.completeExceptionally(IllegalStateException("Sending ISO-TP flow control timed out.", error)) }
          catch (error: CancellationException) { throw error }
          catch (error: Exception) { receiver.reset(); waiting.completeExceptionally(error) }
    }
    fun cancel() { completion?.cancel(); completion = null; command = null; receiver.reset() }
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
