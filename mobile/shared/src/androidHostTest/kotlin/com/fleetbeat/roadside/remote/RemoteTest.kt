package com.fleetbeat.roadside.remote

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.auth.AuthFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTest {
    private class FakePipe : RemotePipe {
        val connection = MutableStateFlow(BrokerState.Disconnected)
        override val state = connection.asStateFlow()
        val messages = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 32)
        override val incoming = messages.asSharedFlow()
        val writes = mutableListOf<Pair<String, ByteArray>>()
        var acknowledge = true
        var wrongId = false
        var refuseSubscription = false
        var subscribed: String? = null
        override suspend fun connect(login: String, token: String) { connection.value = BrokerState.Connected }
        override suspend fun subscribe(topic: String) {
            check(!refuseSubscription) { "Subscription refused" }
            subscribed = topic
        }
        override suspend fun publish(topic: String, bytes: ByteArray) {
            writes += topic to bytes
            if (acknowledge) {
                val command = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                messages.emit(topic.replace("/down", "/up") to buildJsonObject {
                    command.forEach { (key, value) -> put(key, value) }
                    put("t", "status"); put("ok", true); put("voltage", 13.8); put("version", "test")
                    if (wrongId) put("id", "another-client")
                }.toString().encodeToByteArray())
            }
        }
        override suspend fun disconnect() { close() }
        override fun close() { connection.value = BrokerState.Disconnected }
    }
    private class Sessions : RemoteSessions {
        val codes = mutableListOf<String>()
        var failure: Exception? = null
        override suspend fun join(code: String): RemoteSession {
            failure?.let { throw it }
            codes += code
            return RemoteSession(code, "joined")
        }
    }
    private fun fixture(block: suspend TestScope.(RemoteViewModel, FakePipe, Sessions, MutableList<String>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pipe = FakePipe(); val sessions = Sessions(); val expired = mutableListOf<String>()
        val vm = RemoteViewModel(sessions, pipe, { "tech@example.com" to "token" }, { testScheduler.currentTime }, expired::add)
        val store = ViewModelStore(); store.put("remote", vm)
        try { vm.open(); vm.code("diag"); block(vm, pipe, sessions, expired) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun joinRequiresAdapterResponseAndUsesOnlySessionTopics() = fixture { vm, pipe, sessions, _ ->
        vm.join(); runCurrent()
        assertTrue(vm.state.value.linked)
        assertEquals(listOf("DIAG"), sessions.codes)
        assertEquals("adapter/DIAG/up", pipe.subscribed)
        assertTrue(pipe.writes.all { it.first == "adapter/DIAG/down" })
        assertEquals("test", vm.state.value.version)
        assertTrue(vm.state.value.rxBytes > 0)
        assertFalse(vm.state.value.listening)
    }
    @Test fun anotherClientStatusDoesNotCompleteOurRequest() = fixture { vm, pipe, _, _ ->
        pipe.wrongId = true
        vm.join(); runCurrent()
        assertFalse(vm.state.value.linked)
        advanceTimeBy(10_001); runCurrent()
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.linked)
        assertEquals(BrokerState.Disconnected, pipe.state.value)
        assertContains(vm.state.value.error!!, "did not respond")
    }
    @Test fun subscriptionFailureNeverLooksLinked() = fixture { vm, pipe, _, _ ->
        pipe.refuseSubscription = true
        vm.join(); runCurrent()
        assertFalse(vm.state.value.linked)
        assertEquals(BrokerState.Disconnected, pipe.state.value)
        assertTrue(pipe.writes.isEmpty())
    }
    @Test fun listenDecodesOnlyCorrectTopicAndDisconnectStopsStream() = fixture { vm, pipe, _, _ ->
        vm.join(); runCurrent(); vm.startListening(); runCurrent()
        assertTrue(vm.state.value.listening)
        val frame = byteArrayOf(1, 0x88.toByte(), 0, 4, 0xF0.toByte(), 0x18, 0, 0, 0, 0x50, 0x14, 0, 0, 0)
        pipe.messages.emit("adapter/OTHER/up" to frame); runCurrent()
        advanceTimeBy(501); runCurrent(); assertEquals(0, vm.state.value.frames)
        pipe.messages.emit("adapter/DIAG/up" to frame); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertEquals(1, vm.state.value.frames)
        assertEquals(650.0, vm.state.value.values.single().value)
        vm.disconnect(true); runCurrent()
        assertFalse(vm.state.value.open); assertFalse(vm.state.value.linked)
        assertTrue(vm.state.value.values.isEmpty())
        val stop = Json.parseToJsonElement(pipe.writes.last().second.decodeToString()).jsonObject
        assertEquals(false, stop["stream"]?.jsonPrimitive?.boolean)
        assertTrue(pipe.writes.all { it.second.first().toInt().toChar() == '{' })
    }
    @Test fun brokerLossAndAccountChangeReleaseSession() = fixture { vm, pipe, _, _ ->
        vm.join(); runCurrent(); vm.startListening(); runCurrent()
        pipe.connection.value = BrokerState.Error; runCurrent()
        assertFalse(vm.state.value.linked); assertFalse(vm.state.value.listening)
        assertNotNull(vm.state.value.error)
        vm.bindUser(42); runCurrent()
        assertFalse(vm.state.value.open); assertTrue(vm.state.value.values.isEmpty())
    }
    @Test fun reservedProcedureSessionNeverReachesApi() = fixture { vm, pipe, sessions, _ ->
        vm.code("REGEN"); vm.join(); runCurrent()
        assertTrue(sessions.codes.isEmpty()); assertTrue(pipe.writes.isEmpty())
        assertContains(vm.state.value.error!!, "diagnostic sessions only")
    }
    @Test fun expiredLoginRequestsSignIn() = fixture { vm, _, sessions, expired ->
        sessions.failure = AuthFailure("Sign in again", true)
        vm.join(); runCurrent()
        assertEquals(listOf("Sign in again"), expired)
        assertFalse(vm.state.value.linked)
    }
    @Test fun cancellingJoinIgnoresLateResponses() = fixture { vm, pipe, _, _ ->
        pipe.acknowledge = false
        vm.join(); runCurrent()
        val request = Json.parseToJsonElement(pipe.writes.single().second.decodeToString()).jsonObject
        vm.disconnect(true); runCurrent()
        pipe.messages.emit("adapter/DIAG/up" to buildJsonObject {
            put("t", "status"); put("id", request["id"]!!); put("ok", true)
        }.toString().encodeToByteArray()); runCurrent()
        assertFalse(vm.state.value.open); assertFalse(vm.state.value.linked); assertEquals(0, vm.state.value.rxBytes)
    }
}
