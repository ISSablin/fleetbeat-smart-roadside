package com.fleetbeat.roadside.diagnostics

data class LiveValue(val key: String, val label: String, val value: Double?, val unit: String,
                     val source: Int, val receivedAt: Long)
data class TroubleCode(val spn: Int?, val fmi: Int, val occurrences: Int?, val legacy: Boolean)
data class FaultReport(val source: Int, val codes: List<TroubleCode>, val receivedAt: Long)

/** Standard truck broadcast subset. Emulator-specific aftertreatment PGNs are not decoded. */
class J1939Data {
    private data class Transfer(val pgn: Int, val size: Int, val packets: Int,
                                var next: Int, var at: Long, val data: MutableList<Byte>)
    private val transfers = mutableMapOf<Int, Transfer>()
    val values = linkedMapOf<String, LiveValue>()
    val faults = linkedMapOf<Int, FaultReport>()
    var frames: Long = 0; private set

    fun reset() { transfers.clear(); values.clear(); faults.clear(); frames = 0 }
    fun accept(frame: CanFrame, now: Long) {
        if (!frame.extended) return
        frames++
        val pgn = pgn(frame.id)
        val source = (frame.id and 255u).toInt()
        val data = frame.data
        transfers.entries.removeAll { now - it.value.at > 1500 }
        if (pgn == 0xEC00 && ((frame.id shr 8) and 255u) == 255u) {
            transfers.remove(source)
            if (data.size != 8 || u8(data, 0) != 0x20) return
            val size = u16(data, 1)
            val count = u8(data, 3)
            val target = u8(data, 5) or (u8(data, 6) shl 8) or (u8(data, 7) shl 16)
            if (target == 0xFECA && size in 9..1785 && count == (size + 6) / 7 && transfers.size < 32) {
                transfers[source] = Transfer(target, size, count, 1, now, mutableListOf())
            }
            return
        }
        if (pgn == 0xEB00 && ((frame.id shr 8) and 255u) == 255u) {
            val transfer = transfers[source] ?: return
            if (data.size != 8 || u8(data, 0) != transfer.next) { transfers.remove(source); return }
            transfer.at = now
            transfer.data.addAll(data.drop(1).take(transfer.size - transfer.data.size))
            transfer.next++
            if (transfer.next > transfer.packets) {
                transfers.remove(source)
                decodeFaults(source, transfer.data.toByteArray(), now)
            }
            return
        }
        if (pgn == 0xFECA) { decodeFaults(source, data, now); return }

        fun record(key: String, label: String, value: Double?, unit: String) {
            values["$key:$source"] = LiveValue(key, label, value, unit, source, now)
        }
        fun scaled16(offset: Int, scale: Double): Double? {
            if (data.size < offset + 2) return null
            val raw = u16(data, offset)
            return if (raw >= 0xFB00) null else raw * scale
        }
        fun scaled32(offset: Int, scale: Double): Double? {
            if (data.size < offset + 4) return null
            var raw = 0L
            repeat(4) { raw = raw or ((u8(data, offset + it).toLong()) shl (it * 8)) }
            return if (raw >= 0xFB000000L) null else raw * scale
        }
        when (pgn) {
            0xF004 -> if (data.size >= 5) record("rpm", "Engine RPM", scaled16(3, 0.125), "rpm")
            0xFEF1 -> if (data.size >= 3) record("speed", "Vehicle speed", scaled16(1, 1.0 / 256), "km/h")
            0xFEEE -> if (data.isNotEmpty()) record("coolant", "Coolant temperature", if (u8(data, 0) >= 251) null else u8(data, 0) - 40.0, "°C")
            0xFEF7 -> if (data.size >= 6) record("voltage", "Vehicle voltage", scaled16(4, 0.05), "V")
            0xFEE5 -> if (data.size >= 4) record("hours", "Engine hours", scaled32(0, 0.05), "h")
            0xFEE0 -> if (data.size >= 8) record("distance", "Total distance", scaled32(4, 0.125), "km")
            0xFEC1 -> if (data.size >= 8) record("distance", "Total distance", scaled32(4, 0.005), "km")
        }
    }

    private fun decodeFaults(source: Int, data: ByteArray, now: Long) {
        if (data.size < 6) return
        val codes = mutableListOf<TroubleCode>()
        for (offset in 2..data.size - 4 step 4) {
            val bytes = data.copyOfRange(offset, offset + 4)
            if (bytes.all { it == 0.toByte() } || bytes.all { it == 255.toByte() }) continue
            val b2 = u8(bytes, 2)
            val b3 = u8(bytes, 3)
            val legacy = b3 and 128 != 0
            val spn = if (legacy) null else u8(bytes, 0) or (u8(bytes, 1) shl 8) or ((b2 and 224) shl 11)
            codes += TroubleCode(spn, b2 and 31, (b3 and 127).takeUnless { it == 127 }, legacy)
        }
        faults[source] = FaultReport(source, codes, now)
    }

    companion object {
        fun pgn(id: UInt): Int {
            val value = ((id shr 8) and 0x3FFFFu).toInt()
            return if (((id shr 16) and 255u) < 240u) value and 0x3FF00 else value
        }
        private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 255
        private fun u16(bytes: ByteArray, offset: Int) = u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)
    }
}
