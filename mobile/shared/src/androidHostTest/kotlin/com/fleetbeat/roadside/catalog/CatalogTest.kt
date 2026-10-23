package com.fleetbeat.roadside.catalog

import com.fleetbeat.roadside.auth.AuthFailure
import com.fleetbeat.roadside.auth.configureClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private open class FakeCatalog : VehicleCatalog {
        override suspend fun brands() = listOf(CatalogItem("v", "Volvo"), CatalogItem("s", "Scania"))
        override suspend fun models(brandId: String) = listOf(CatalogItem("fh", "FH"))
        override suspend fun years(modelId: String) = listOf(CatalogItem("2019", "2019", "D13"))
        override suspend fun controlUnits(modelId: String, yearId: String) = listOf(
            CatalogItem("engine", "Engine", controlUnit = ControlUnit("engine", "Engine", protocol_id = 12)))
    }

    @Test fun navigateSearchBackAndKeepSelectedUnit() = runTest(dispatcher) {
        val vm = CatalogViewModel(FakeCatalog(), {})
        vm.bindUser(1)
        vm.open(); runCurrent()
        vm.search("VOL")
        assertEquals(listOf("Volvo"), vm.state.value.filtered.map { it.title })
        vm.select(vm.state.value.filtered.single()); runCurrent()
        assertEquals(CatalogStage.MODELS, vm.state.value.stage)
        vm.back()
        assertEquals("VOL", vm.state.value.query)
        assertEquals(2, vm.state.value.items.size)
        vm.select(vm.state.value.filtered.single()); runCurrent()
        vm.select(vm.state.value.items.single()); runCurrent()
        vm.select(vm.state.value.items.single()); runCurrent()
        vm.select(vm.state.value.items.single())
        assertEquals(CatalogStage.SUMMARY, vm.state.value.stage)
        assertEquals("Volvo · FH · 2019", vm.state.value.vehicleName)
        assertEquals(12, vm.state.value.selectedUnit?.controlUnit?.protocol_id)
        vm.bindUser(1)
        assertEquals(CatalogStage.SUMMARY, vm.state.value.stage)
        vm.bindUser(null)
        assertFalse(vm.state.value.open)
        assertNull(vm.state.value.selectedUnit)
    }

    @Test fun staleRequestDoesNotReplacePreviousScreen() = runTest(dispatcher) {
        val vm = CatalogViewModel(object : FakeCatalog() {
            override suspend fun models(brandId: String): List<CatalogItem> = withContext(NonCancellable) {
                delay(100)
                listOf(CatalogItem("late", "Late response"))
            }
        }, {})
        vm.open(); runCurrent()
        vm.select(vm.state.value.items.first()); runCurrent()
        assertTrue(vm.state.value.loading)
        vm.back()
        advanceUntilIdle()
        assertEquals(CatalogStage.BRANDS, vm.state.value.stage)
        assertEquals("Volvo", vm.state.value.items.first().title)
        assertFalse(vm.state.value.loading)
    }

    @Test fun failedRequestCanRetryAndEmptyResultRemainsEmpty() = runTest(dispatcher) {
        var attempts = 0
        val vm = CatalogViewModel(object : FakeCatalog() {
            override suspend fun brands(): List<CatalogItem> {
                if (attempts++ == 0) throw CatalogFailure("Unavailable")
                return emptyList()
            }
        }, {})
        vm.open(); runCurrent()
        assertEquals("Unavailable", vm.state.value.error)
        vm.retry(); runCurrent()
        assertNull(vm.state.value.error)
        assertEquals(emptyList(), vm.state.value.filtered)
        assertFalse(vm.state.value.loading)
    }

    @Test fun expiredSessionReturnsToAuthentication() = runTest(dispatcher) {
        var message: String? = null
        val vm = CatalogViewModel(object : FakeCatalog() {
            override suspend fun brands(): List<CatalogItem> = throw AuthFailure("Expired", true)
        }, { message = it })
        vm.open(); runCurrent()
        assertEquals("Expired", message)
        assertFalse(vm.state.value.open)
    }

    @Test fun clientSendsTokenAndEncodedParameters() = runTest(dispatcher) {
        val engine = MockEngine { request ->
            assertEquals("Bearer secret-test-token", request.headers[HttpHeaders.Authorization])
            assertEquals("Trucks", request.url.parameters["segment"])
            assertEquals("ENG", request.url.parameters["lang"])
            assertEquals("A / B +", request.url.parameters["brand_id"])
            assertEquals("/api/v1/vehicles/models", request.url.encodedPath)
            respond("""{"items":[{"id":"m","name":"Model","brand_id":"ignored"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { configureClient(this) }
        try {
            val api = CarApi(client, "https://catalog.example/", { "secret-test-token" })
            assertEquals("Model", api.models("A / B +").single().title)
        } finally { client.close() }
    }

    @Test fun clientDistinguishesUnauthenticatedFromCatalogFailure() = runTest(dispatcher) {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { respond("{}", status) }) { configureClient(this) }
            try {
                val api = CarApi(client, "https://catalog.example", { "token" })
                if (status == HttpStatusCode.Unauthorized) {
                    assertTrue(assertFailsWith<AuthFailure> { api.brands() }.invalidSession)
                } else assertFailsWith<CatalogFailure> { api.brands() }
            } finally { client.close() }
        }
    }
}
