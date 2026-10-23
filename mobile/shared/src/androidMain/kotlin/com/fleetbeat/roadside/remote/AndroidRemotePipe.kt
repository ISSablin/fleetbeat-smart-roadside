package com.fleetbeat.roadside.remote

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Compatible with the deployed MQTT 5 broker; no automatic reconnect/replay. */
class AndroidRemotePipe(private val host: String, private val port: Int) : RemotePipe {
    private val mutableState = MutableStateFlow(BrokerState.Disconnected)
    override val state = mutableState.asStateFlow()
    private val messages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 256)
    override val incoming = messages.asSharedFlow()
    private var client: Mqtt5AsyncClient? = null

    override suspend fun connect(login: String, token: String) {
        check(client == null) { "Disconnect the current remote session first." }
        mutableState.value = BrokerState.Connecting
        val id = "fleetbeat-master-${UUID.randomUUID()}"
        val built = MqttClient.builder().useMqttVersion5().identifier(id).serverHost(host).serverPort(port)
            .simpleAuth().username(login).password(token.encodeToByteArray()).applySimpleAuth()
            .addDisconnectedListener { context ->
                if (client?.config?.clientIdentifier == context.clientConfig.clientIdentifier)
                    mutableState.value = BrokerState.Error
            }.buildAsync()
        client = built
        try {
            waitFor(built.connect())
            check(client === built) { "Connection cancelled." }
            mutableState.value = BrokerState.Connected
        } catch (error: Exception) {
            if (client === built) { client = null; mutableState.value = BrokerState.Error }
            built.disconnect()
            throw error
        }
    }
    override suspend fun subscribe(topic: String) {
        val current = client ?: error("Broker is disconnected.")
        val result = waitFor(current.subscribeWith().topicFilter(topic).qos(MqttQos.AT_MOST_ONCE)
            .callback { message ->
                if (client === current && !messages.tryEmit(message.topic.toString() to message.payloadAsBytes.copyOf())) {
                    mutableState.value = BrokerState.Error
                    current.disconnect()
                }
            }.send())
        check(result.reasonCodes.all { !it.isError }) { "The broker refused this session subscription." }
    }
    override suspend fun publish(topic: String, bytes: ByteArray) {
        val current = client ?: error("Broker is disconnected.")
        val result = waitFor(current.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).retain(false).payload(bytes).send())
        check(!result.error.isPresent) { "The broker rejected the adapter command." }
    }
    override suspend fun disconnect() {
        val current = client
        client = null; mutableState.value = BrokerState.Disconnected
        if (current != null) waitFor(current.disconnect())
    }
    override fun close() {
        val current = client
        client = null; mutableState.value = BrokerState.Disconnected
        current?.disconnect()
    }
    private suspend fun <T> waitFor(future: CompletableFuture<T>): T = withTimeout(20_000) {
        suspendCancellableCoroutine { continuation ->
            future.whenComplete { result, error ->
                if (error != null) continuation.resumeWithException(error) else continuation.resume(result)
            }
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    }
}
