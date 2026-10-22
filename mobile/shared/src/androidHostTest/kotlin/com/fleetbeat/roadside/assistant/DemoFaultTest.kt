package com.fleetbeat.roadside.assistant

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.reports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.random.Random
import kotlin.test.*

private fun capture(snapshot: FaultCodeSnapshot) = snapshot.report("00000001-0000-0000-0000-000000000001", 1000, "UTC")

@OptIn(ExperimentalCoroutinesApi::class)
class DemoFaultTest {
    private fun fixture(reader: FaultCodeReader = VolvoDemoFaultReader(random = Random(7)),
        block: suspend TestScope.(AssistantViewModel, MutableList<String>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sent = mutableListOf<String>()
        val service = object : AdviceService { override suspend fun ask(question: String): Advice {
            sent += question; return Advice("Checks", "Read-only guidance", false)
        } }
        val vm = AssistantViewModel(service, {}, reader)
        val store = ViewModelStore().apply { put("assistant", vm) }
        try { vm.bindUser(1); vm.open(); block(vm, sent) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun randomSetsAreRelatedBoundedAndNeverImmediatelyRepeated() = runTest {
        val families = listOf(setOf("P0299", "P00AF", "P0045", "U010C", "P003A", "P0106", "P0069"),
            setOf("P0087", "P008A", "P016F", "P228E"), setOf("P2463", "P244B", "P0471", "P24A4"),
            setOf("P204F", "P20E8", "P2BA8"), setOf("P207F", "P2BA9", "P20EE"), setOf("P203F", "P2BA7"))
        val reader = VolvoDemoFaultReader(random = Random(42))
        var previous = emptySet<String>(); val seen = mutableSetOf<Set<String>>()
        repeat(500) {
            val result = reader.read(previous); val codes = result.faults.map { it.code }.toSet()
            assertTrue(result.simulated); assertEquals("Volvo D13C", result.vehicleModel); assertEquals("TESTVIN1233456566", result.vin)
            assertTrue(codes.size in 2..3); assertEquals(codes.size, result.faults.size)
            assertTrue(families.any { it.containsAll(codes) }, codes.toString())
            assertTrue(result.faults.all { it.description.isNotBlank() && it.status == null && it.occurrences == null })
            assertNotEquals(previous, codes); assertTrue(result.questionBlock().length <= 350)
            assertFalse(result.questionBlock().contains("demo", ignoreCase = true)); assertContains(result.questionBlock(), "VIN: TESTVIN1233456566")
            assertFalse(result.questionBlock().contains("regeneration", ignoreCase = true))
            seen += codes; previous = codes
        }
        assertTrue(seen.size >= 12)
        repeat(100) {
            val next = reader.read(emptySet()).faults.map { it.code }.toSet()
            assertNotEquals(previous, next); previous = next
        }
    }
    @Test fun readPrefillsOnlyAndRetainsTypedAndVoiceSymptomsAcrossRereads() = fixture { vm, sent ->
        vm.question("Power loss on hills.")
        vm.readFaultCodes(::capture); runCurrent()
        val before = vm.state.value.ecuContext!!.faults.flatMap { it.codes }.map { it.code }.toSet()
        assertContains(vm.state.value.question, "Volvo D13C")
        assertContains(vm.state.value.question, "Power loss on hills.")
        vm.voiceResult(vm.beginVoiceInput()!!, "Started yesterday.")
        vm.readFaultCodes(::capture); runCurrent()
        assertContains(vm.state.value.question, "Power loss on hills.")
        assertContains(vm.state.value.question, "Started yesterday.")
        assertEquals(1, Regex("ECU fault codes").findAll(vm.state.value.question).count())
        assertNotEquals(before, vm.state.value.ecuContext!!.faults.flatMap { it.codes }.map { it.code }.toSet())
        assertTrue(sent.isEmpty()); assertFalse(vm.state.value.busy)
        vm.send(); runCurrent()
        assertEquals(vm.state.value.question.trim(), sent.single())
        assertEquals(1, vm.state.value.turns.size)
        val completed = vm.state.value.ecuContext
        vm.readFaultCodes(::capture); runCurrent(); assertEquals(completed, vm.state.value.ecuContext)
    }
    @Test fun insufficientSpaceAndEditedBlocksNeverDiscardUserInput() = fixture { vm, _ ->
        vm.question("x".repeat(600)); vm.readFaultCodes(::capture); runCurrent()
        assertEquals("x".repeat(600), vm.state.value.question); assertNull(vm.state.value.ecuContext)
        assertNotNull(vm.state.value.error)
        vm.question(""); vm.readFaultCodes(::capture); runCurrent()
        vm.question(vm.state.value.question.replace("Volvo D13C", "Edited vehicle"))
        val edited = vm.state.value.question; val context = vm.state.value.ecuContext
        vm.readFaultCodes(::capture); runCurrent()
        assertEquals(edited, vm.state.value.question); assertEquals(context, vm.state.value.ecuContext)
        assertNotNull(vm.state.value.error)
        vm.question(""); vm.readFaultCodes(::capture); runCurrent()
        assertContains(vm.state.value.question, "Volvo D13C"); assertNull(vm.state.value.error)
    }
    @Test fun existingEcuContextCannotBeReplacedByDemo() = fixture { vm, _ ->
        val demo = capture(VolvoDemoFaultReader(random = Random(1)).read(emptySet()))
        vm.open("Existing ECU snapshot", demo.copy(origin = "Remote adapter", simulated = false))
        vm.readFaultCodes(::capture); runCurrent()
        assertEquals("Existing ECU snapshot", vm.state.value.question)
        assertFalse(vm.state.value.ecuContext!!.simulated)
    }
    @Test fun lateReadCannotPopulateAnotherAccount() = fixture(reader = object : FaultCodeReader {
        override suspend fun read(previousCodes: Set<String>): FaultCodeSnapshot = withContext(NonCancellable) {
            delay(1000); VolvoDemoFaultReader(random = Random(1)).read(previousCodes)
        }
    }) { vm, _ ->
        vm.readFaultCodes(::capture); runCurrent(); vm.bindUser(2); advanceTimeBy(1001); runCurrent()
        assertFalse(vm.state.value.open); assertEquals("", vm.state.value.question); assertNull(vm.state.value.ecuContext)
    }
    @Test fun failedReadPreservesQuestionAndCanRetry() = fixture(reader = object : FaultCodeReader {
        var fail = true
        override suspend fun read(previousCodes: Set<String>): FaultCodeSnapshot {
            if (fail) { fail = false; error("Unavailable") }
            return VolvoDemoFaultReader(random = Random(1)).read(previousCodes)
        }
    }) { vm, _ ->
        vm.question("Original symptoms"); vm.readFaultCodes(::capture); runCurrent()
        assertEquals("Original symptoms", vm.state.value.question); assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
        vm.readFaultCodes(::capture); runCurrent(); assertContains(vm.state.value.question, "Original symptoms")
        assertNull(vm.state.value.error)
    }
    @Test fun snapshotHasExplicitDemoMetadataAndCompanyRouting() = runTest {
        val snapshot = VolvoDemoFaultReader(vin = "DEMO0000000000001", random = Random(1)).read(emptySet())
        val report = capture(snapshot)
        assertEquals("Volvo D13C", report.vehicleModel); assertEquals("DEMO0000000000001", report.vin)
        assertEquals("e2fc8a58-4981-40d7-bbaa-93566b200711", report.companyId)
        assertTrue(report.simulated); assertTrue(report.hasEvidence); assertTrue(report.values.isEmpty())
        assertEquals(snapshot.faults, report.faults.single().codes)
        val json = Json { encodeDefaults = true }
        val fields = json.parseToJsonElement(json.encodeToString(DiagnosticReport.serializer(), report)).jsonObject
        assertEquals("Volvo D13C", fields["vehicleModel"]!!.jsonPrimitive.content)
        assertTrue(fields["simulated"]!!.jsonPrimitive.boolean)
        assertContains(report.asText(), "SIMULATED DATA")
    }
}
