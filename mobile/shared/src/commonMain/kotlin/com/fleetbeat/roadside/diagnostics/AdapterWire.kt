package com.fleetbeat.roadside.diagnostics

import kotlinx.serialization.json.*

data class CanFrame(val id: UInt, val data: ByteArray, val extended: Boolean = true)
sealed interface AdapterPacket {
    data class Can(val frame: CanFrame) : AdapterPacket
    data class Service(val fields: JsonObject) : AdapterPacket
}

/** C+1 adapter wire: 14-byte CAN records mixed with JSON service messages. */
class AdapterWire {
    private var pending = byteArrayOf()
    fun reset() { pending = byteArrayOf() }

    fun feed(bytes: ByteArray): List<AdapterPacket> {
        if (pending.size + bytes.size > 8192) reset()
        pending += bytes.take(8192).toByteArray()
        val output = mutableListOf<AdapterPacket>()
        var offset = 0
        while (offset < pending.size) {
            val first = pending[offset].toInt() and 255
            if (first == 1) {
                if (pending.size - offset < 14) break
                val flags = pending[offset + 1].toInt() and 255
                val count = flags and 15
                val extended = flags and 128 != 0
                var id = 0u
                repeat(4) { id = id or ((pending[offset + 2 + it].toUInt() and 255u) shl (it * 8)) }
                if (count <= 8 && flags and 64 == 0 && id <= if (extended) 0x1FFFFFFFu else 0x7FFu) {
                    output += AdapterPacket.Can(CanFrame(id, pending.copyOfRange(offset + 6, offset + 6 + count), extended))
                }
                offset += 14
            } else if (first == '{'.code) {
                var depth = 0
                var quoted = false
                var escaped = false
                var end = offset
                while (end < pending.size) {
                    val ch = pending[end].toInt().toChar()
                    if (quoted) {
                        if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                    } else when (ch) {
                        '"' -> quoted = true
                        '{' -> depth++
                        '}' -> depth--
                    }
                    end++
                    if (depth == 0) break
                }
                if (depth != 0) {
                    if (pending.size - offset > 4096) offset = pending.size
                    break
                }
                val fields = runCatching { Json.parseToJsonElement(pending.copyOfRange(offset, end).decodeToString()) as? JsonObject }.getOrNull()
                if (fields != null) output += AdapterPacket.Service(fields)
                offset = end
            } else offset++
        }
        pending = pending.copyOfRange(offset, pending.size)
        return output
    }
}

object AdapterCommands {
    private fun service(type: String, id: String): JsonObjectBuilder.() -> Unit = {
        put("v", 1); put("t", type); put("id", id)
    }
    fun enter(id: String) = buildJsonObject(service("enter_adapter", id)).toString().encodeToByteArray()
    fun status(id: String) = buildJsonObject(service("get", id)).toString().encodeToByteArray()
    fun exit(id: String) = buildJsonObject(service("exit_session", id)).toString().encodeToByteArray()
    fun listen(id: String, speed: Int, stream: Boolean) = buildJsonObject {
        service("set", id).invoke(this)
        put("speed", speed); put("format", 29); put("mode", "listen"); put("stream", stream)
        putJsonArray("filter") { }
        putJsonArray("pgn_filter") {
            if (stream) listOf(61444, 65265, 65262, 65271, 65253, 65248, 65217, 65226, 60416, 60160).forEach { add(it) }
        }
    }.toString().encodeToByteArray()
}
