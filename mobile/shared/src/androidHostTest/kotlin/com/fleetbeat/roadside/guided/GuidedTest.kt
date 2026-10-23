package com.fleetbeat.roadside.guided

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*

private val situation = SymptomSituation("wont_start", "Won't start", sort = 2,
    followup = SymptomFollowup("What happens?", listOf(SymptomChoice("click", "Clicking"), SymptomChoice("dead", "No response"))))
private val freeform = SymptomSituation("ai_help", "Other problem", sort = 1, entry = "freeform")

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedTest {
    private class Service : GuidedService {
        var calls = 0
        var action: suspend () -> List<SymptomSituation> = { listOf(freeform, situation) }
        override suspend fun situations(): List<SymptomSituation> { calls++; return action() }
    }
    private fun fixture(block: suspend TestScope.(GuidedViewModel, Service, MutableList<String>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = Service(); val expired = mutableListOf<String>()
        val vm = GuidedViewModel(service, expired::add)
        val store = ViewModelStore(); store.put("guided", vm)
        try { vm.bindUser(1); block(vm, service, expired) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun selectionRequiresChoiceThenPreparesEditableQuestion() = fixture { vm, service, _ ->
        vm.open(); runCurrent()
        vm.select("wont_start"); assertNull(vm.preview())
        vm.choose("unknown"); assertNull(vm.preview())
        vm.choose("click")
        val text = vm.preview()!!
        assertContains(text, "Won't start"); assertContains(text, "What happens?"); assertContains(text, "Clicking")
        assertTrue(text.length <= 600); assertEquals(1, service.calls)
        vm.back(); assertNull(vm.state.value.selected); assertTrue(vm.state.value.open)
        vm.back(); assertFalse(vm.state.value.open)
    }
    @Test fun freeformOpensEmptyQuestionAndReusesListWithinAccount() = fixture { vm, service, _ ->
        vm.open(); runCurrent(); vm.select("ai_help"); assertEquals("", vm.preview())
        vm.closeScreen(); assertNull(vm.preview())
        vm.open(); runCurrent(); assertEquals(1, service.calls)
        vm.bindUser(2); vm.open(); runCurrent(); assertEquals(2, service.calls)
        assertNull(vm.state.value.selected)
    }
    @Test fun lateResponseCannotReopenOrRevealAnotherAccountSelection() = fixture { vm, service, _ ->
        service.action = { withContext(NonCancellable) { delay(1000); listOf(situation) } }
        vm.open(); runCurrent(); vm.bindUser(2); advanceTimeBy(1001); runCurrent()
        assertFalse(vm.state.value.open); assertTrue(vm.state.value.situations.isEmpty())
    }
    @Test fun loadingTimeoutAndRetry() = fixture { vm, service, _ ->
        service.action = { delay(30_000); listOf(situation) }
        vm.open(); runCurrent(); advanceTimeBy(20_001); runCurrent()
        assertContains(vm.state.value.error!!, "timed out"); assertFalse(vm.state.value.loading)
        service.action = { listOf(situation) }; vm.reload(); runCurrent()
        assertEquals(listOf(situation), vm.state.value.situations); assertNull(vm.state.value.error)
    }
    @Test fun expiredSessionIsReported() = fixture { vm, service, expired ->
        service.action = { throw AuthFailure("Sign in again", true) }
        vm.open(); runCurrent(); assertEquals(listOf("Sign in again"), expired)
    }
    @Test fun filterSortAndRejectMalformedSupportedData() {
        val tree = SymptomTree(4, "eng", listOf(situation, SymptomSituation("regen", "Regeneration"), freeform))
        assertEquals(listOf("ai_help", "wont_start"), tree.diagnosticChoices().map { it.id })
        assertFailsWith<GuidedFailure> { tree.copy(locale = "rus").diagnosticChoices() }
        assertFailsWith<GuidedFailure> { tree.copy(situations = listOf(situation.copy(followup = null))).diagnosticChoices() }
        assertFailsWith<GuidedFailure> { tree.copy(situations = listOf(situation, situation)).diagnosticChoices() }
        assertFailsWith<GuidedFailure> { tree.copy(situations = listOf(situation.copy(title = "x".repeat(121)))).diagnosticChoices() }
        assertFailsWith<GuidedFailure> { tree.copy(situations = emptyList()).diagnosticChoices() }
    }
    @Test fun apiUsesAuthenticatedEnglishTreeWithoutAdviceRequest() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++; assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v1/help-wizard/tree", request.url.encodedPath)
            assertEquals("eng", request.url.parameters["locale"])
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respond(Json.encodeToString(SymptomTree(4, "eng", listOf(situation))), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try { assertEquals(listOf(situation), GuidedApi(client, "https://api.example/", { "test-token" }).situations()); assertEquals(1, calls) }
        finally { client.close() }
    }
    @Test fun apiRejectsUnauthorizedAndUnavailable() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { respond("unavailable", status) })
            try {
                if (status.value in listOf(401, 403)) assertFailsWith<AuthFailure> { GuidedApi(client, "https://api.example", { "token" }).situations() }
                else assertFailsWith<GuidedFailure> { GuidedApi(client, "https://api.example", { "token" }).situations() }
            } finally { client.close() }
        }
    }
}
