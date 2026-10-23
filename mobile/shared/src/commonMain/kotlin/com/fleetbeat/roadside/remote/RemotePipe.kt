package com.fleetbeat.roadside.remote

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class BrokerState { Disconnected, Connecting, Connected, Error }
interface RemotePipe {
    val state: StateFlow<BrokerState>
    val incoming: SharedFlow<Pair<String, ByteArray>>
    suspend fun connect(login: String, token: String)
    suspend fun subscribe(topic: String)
    suspend fun publish(topic: String, bytes: ByteArray)
    suspend fun disconnect()
    fun close()
}
