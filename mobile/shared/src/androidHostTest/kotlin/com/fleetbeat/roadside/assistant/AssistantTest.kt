package com.fleetbeat.roadside.assistant

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.auth.*
import com.fleetbeat.roadside.diagnostics.ecu.*
import com.fleetbeat.roadside.remote.RemoteState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantTest {
    private class Service : AdviceService {
        val questions = mutableListOf<String>()
        var action: suspend () -> Advice = { Advice("Guidance", "Read-only checks", false) }
        override suspend fun ask(question: String): Advice { questions += question; return action() }
    }
    private fun fixture(block: suspend TestScope.(AssistantViewModel, Service, MutableList<String>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = Service(); val expired = mutableListOf<String>()
        val vm = AssistantViewModel(service, expired::add)
        val store = ViewModelStore(); store.put("assistant", vm)
        try { vm.bindUser(1); vm.open(); block(vm, service, expired) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun previewDoesNotSendAndEachExplicitTapSendsOnce() = fixture { vm, service, _ ->
        vm.open(" Explain a coolant sensor fault "); runCurrent()
        assertTrue(service.questions.isEmpty())
        vm.send(); vm.send(); runCurrent()
        assertEquals(listOf("Explain a coolant sensor fault"), service.questions)
        assertEquals("Read-only checks", vm.state.value.advice?.body)
        vm.question("A different question")
        assertNull(vm.state.value.advice)
    }
    @Test fun emptyAndLongQuestionsAreBounded() = fixture { vm, service, _ ->
        vm.question("  "); vm.send(); runCurrent(); assertTrue(service.questions.isEmpty())
        assertNotNull(vm.state.value.error)
        vm.question("x".repeat(700)); vm.send(); runCurrent()
        assertEquals(600, service.questions.single().length)
    }
    @Test fun fallbackIsNotPresentedAsGeneratedAdvice() = fixture { vm, service, _ ->
        service.action = { Advice("Unavailable", "Echo", true) }
        vm.question("Fault question"); vm.send(); runCurrent()
        assertNull(vm.state.value.advice); assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }
    @Test fun timeoutIsVisibleAndCanRetry() = fixture { vm, service, _ ->
        service.action = { delay(90_000); Advice("Late", "Late", false) }
        vm.question("Fault question"); vm.send(); runCurrent()
        advanceTimeBy(80_001); runCurrent()
        assertContains(vm.state.value.error!!, "timed out")
        service.action = { Advice("Retry", "Works", false) }
        vm.send(); runCurrent(); assertEquals("Works", vm.state.value.advice?.body)
    }
    @Test fun stoppedRequestCannotOverwriteNewAnswer() = fixture { vm, service, _ ->
        service.action = { withContext(NonCancellable) { delay(1000); Advice("Old", "Old", false) } }
        vm.question("Old question"); vm.send(); runCurrent()
        vm.stopWaiting()
        service.action = { Advice("New", "New answer", false) }
        vm.question("New question"); vm.send(); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertEquals("New answer", vm.state.value.advice?.body)
        assertFalse(vm.state.value.busy)
    }
    @Test fun accountChangeAndBackClearQuestionAndIgnoreLateReplies() = fixture { vm, service, _ ->
        service.action = { withContext(NonCancellable) { delay(100); Advice("Late", "Late", false) } }
        vm.question("Private question"); vm.send(); runCurrent()
        vm.bindUser(2); advanceTimeBy(101); runCurrent()
        assertFalse(vm.state.value.open); assertEquals("", vm.state.value.question); assertNull(vm.state.value.advice)
        vm.open("New question"); vm.closeScreen()
        assertFalse(vm.state.value.open); assertEquals("", vm.state.value.question)
    }
    @Test fun expiredAuthenticationReturnsToSignIn() = fixture { vm, service, expired ->
        service.action = { throw AuthFailure("Sign in again", true) }
        vm.question("Fault question"); vm.send(); runCurrent()
        assertEquals(listOf("Sign in again"), expired)
        assertNull(vm.state.value.advice)
    }
    @Test fun speechAppendsToTypedQuestionAndNeverSendsAutomatically() = fixture { vm, service, _ ->
        vm.question("Existing symptoms.")
        val token = vm.beginVoiceInput()!!
        vm.send(); runCurrent(); assertTrue(service.questions.isEmpty())
        vm.voiceResult(token, " Engine runs rough. ")
        assertEquals("Existing symptoms. Engine runs rough.", vm.state.value.question)
        assertFalse(vm.state.value.voiceActive)
        assertTrue(service.questions.isEmpty())
    }
    @Test fun cancelledOrFailedSpeechPreservesTypedText() = fixture { vm, _, _ ->
        vm.question("Keep this")
        vm.voiceResult(vm.beginVoiceInput()!!, cancelled = true)
        assertEquals("Keep this", vm.state.value.question)
        assertFalse(vm.state.value.voiceActive)
        vm.voiceResult(vm.beginVoiceInput()!!, failure = "Microphone unavailable")
        assertEquals("Keep this", vm.state.value.question)
        assertEquals("Microphone unavailable", vm.state.value.error)
        vm.voiceResult(vm.beginVoiceInput()!!, "  ")
        assertEquals("Keep this", vm.state.value.question)
        assertContains(vm.state.value.error!!, "No speech")
    }
    @Test fun lateSpeechCannotEnterAnotherAccountOrScreen() = fixture { vm, _, _ ->
        val old = vm.beginVoiceInput()!!
        vm.closeScreen(); vm.open("New screen")
        vm.voiceResult(old, "Old recording")
        assertEquals("New screen", vm.state.value.question)
        val another = vm.beginVoiceInput()!!
        vm.bindUser(2); vm.open("Other account")
        vm.voiceResult(another, "Private text")
        assertEquals("Other account", vm.state.value.question)
    }
    @Test fun speechRespectsLimitAndDoesNotOverwriteExistingText() = fixture { vm, _, _ ->
        vm.question("a".repeat(590))
        vm.voiceResult(vm.beginVoiceInput()!!, "b".repeat(100))
        assertEquals(600, vm.state.value.question.length)
        assertTrue(vm.state.value.question.startsWith("a".repeat(590)))
        assertContains(vm.state.value.notice!!, "600-character")
        assertNull(vm.beginVoiceInput())
        assertFalse(vm.state.value.voiceActive)
    }
    @Test fun diagnosticPreviewLabelsSimulatorAgeAndRawCodes() {
        val state = RemoteState(code = "DIAG", now = 9000,
            requestedValues = listOf(RequestedValue("Engine RPM", 650.0, "rpm", 1000)),
            faultsReadAt = 2000, ecuFaults = listOf(EcuFault("0C4900", 47, description = "Sensor fault")))
        val question = state.diagnosticQuestion()
        assertContains(question, "DIAG simulator")
        assertContains(question, "8s ago")
        assertContains(question, "0C4900")
        assertContains(question, "Sensor fault")
        assertTrue(question.length <= 600)
    }
    @Test fun httpContractSendsOnlyReviewedQuestionAndUsesUserToken() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/v1/help-wizard/advice", request.url.encodedPath)
            assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("entry", "locale", "user_text"), body.keys)
            assertEquals("freeform", body["entry"]?.jsonPrimitive?.content)
            assertEquals("eng", body["locale"]?.jsonPrimitive?.content)
            assertEquals("Explain sensor fault", body["user_text"]?.jsonPrimitive?.content)
            respond("""{"title":"Sensor fault","body":"Read-only checks","stub":false,"drive_ok":null}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { configureClient(this) }
        try { assertEquals("Read-only checks", AdviceApi(client, "https://api.example", { "user-token" }).ask("Explain sensor fault").body) }
        finally { client.close() }
    }
    @Test fun stubAndServerErrorsNeverBecomeAdvice() = runTest {
        for (status in listOf(200, 401, 503)) {
            val client = HttpClient(MockEngine {
                respond("""{"title":"Fallback","body":"Echo","stub":true}""", HttpStatusCode.fromValue(status),
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configureClient(this) }
            try {
                val api = AdviceApi(client, "https://api.example", { "token" })
                if (status == 401) assertFailsWith<AuthFailure> { api.ask("Fault question") }
                else assertFailsWith<AdviceFailure> { api.ask("Fault question") }
            } finally { client.close() }
        }
    }
}
