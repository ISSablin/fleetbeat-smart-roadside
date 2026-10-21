package com.fleetbeat.roadside.reports

import androidx.lifecycle.ViewModelStore
import com.fleetbeat.roadside.diagnostics.ecu.*
import com.fleetbeat.roadside.remote.RemoteState
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

private const val ID = "00000001-0000-0000-0000-000000000001"
private fun source() = RemoteState(code = "DIAG", version = "emu-mqtt-1", voltage = 13.8,
    requestedValues = listOf(RequestedValue("Engine RPM", 650.0, "rpm", 1000)),
    ecuFaults = listOf(EcuFault("0C4900", 47, "3145", "Sensor fault")), faultsReadAt = 2000)
private fun report(id: String = ID) = remoteReport(source(), id, 10000, "2026-10-21 00:00:00 UTC", 9000)

class ReportStorageTest {
    @Test fun aiOnlyReportPersistsConversationDraftAndNotes() = runBlocking {
        val turn = ReportTurn("Engine stalls", "Checks", "Check sensor wiring", listOf("Wiring"))
        val ai = report().copy(values = emptyList(), faults = emptyList(), simulated = false,
            turns = listOf(turn, turn.copy(question = "Only when hot")), additionalInput = "I checked the connector",
            notes = "Repair scheduled", ecuCapturedLabel = "Original ECU time")
        assertTrue(ai.hasEvidence)
        val store = AndroidReportStore(temporary.newFolder())
        store.save("owner", ai)
        assertEquals(ai, store.load("owner").reports.single())
        val text = ai.asText()
        listOf("Engine stalls", "Only when hot", "Check sensor wiring", "I checked the connector", "Repair scheduled", "Original ECU time").forEach { assertContains(text, it) }
    }
    @Test fun oldReportJsonStillDecodes() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = false }
        val old = json.encodeToString(DiagnosticReport.serializer(), report())
        val decoded = json.decodeFromString(DiagnosticReport.serializer(), old)
        assertTrue(decoded.turns.isEmpty())
        assertEquals(report(), decoded)
    }

    @get:Rule val temporary = TemporaryFolder()
    @Test fun capturePreservesSimulatorProvenanceAgeAndDescriptions() {
        val values = mutableListOf(RequestedValue("Engine RPM", 650.0, "rpm", 1000))
        val snapshot = remoteReport(source().copy(requestedValues = values), ID, 10000, "UTC", 9000)
        values.clear()
        assertEquals(650.0, snapshot.values.single().value)
        assertEquals(8, snapshot.values.single().ageSeconds)
        assertTrue(snapshot.values.single().staleAtCapture)
        assertTrue(snapshot.simulated)
        val text = snapshot.asText()
        assertContains(text, "SIMULATED DATA")
        assertContains(text, "0C4900")
        assertContains(text, "Sensor fault")
        assertContains(text, "status 0x2F")
        assertContains(text, "relative to capture time")
    }
    @Test fun missingFaultResponseIsDifferentFromExplicitEmptyReport() {
        val missing = remoteReport(source().copy(ecuFaults = emptyList(), faultsReadAt = null), ID, 1, "UTC", 9000)
        val empty = remoteReport(source().copy(ecuFaults = emptyList()), ID, 1, "UTC", 9000)
        assertTrue(missing.faults.isEmpty())
        assertContains(missing.asText(), "does not mean")
        assertEquals(1, empty.faults.size)
        assertContains(empty.asText(), "reported no matching faults")
        val live = remoteReport(source().copy(code = "PRIVATE-CODE"), ID, 1, "UTC", 9000)
        assertFalse(live.simulated)
        assertFalse(live.asText().contains("PRIVATE-CODE"))
    }
    @Test fun automaticUpdateReplacesFileAndKeepsValidJson() = runBlocking {
        val store = AndroidReportStore(temporary.newFolder())
        store.upsert("owner", report())
        store.upsert("owner", report().copy(notes = "Updated automatically"))
        assertEquals("Updated automatically", store.load("owner").reports.single().notes)
        assertEquals(0, store.load("owner").unreadableCount)
    }
    @Test fun storeSurvivesRecreationAndSeparatesAccountAndServer() = runBlocking {
        val directory = temporary.newFolder()
        AndroidReportStore(directory).save("https://api.example:1", report())
        val reopened = AndroidReportStore(directory)
        assertEquals(report(), reopened.load("https://api.example:1").reports.single())
        assertTrue(reopened.load("https://api.example:2").reports.isEmpty())
        assertTrue(reopened.load("https://other.example:1").reports.isEmpty())
        assertFalse(directory.walkTopDown().any { it.name.contains("api.example") })
    }
    @Test fun duplicateAndUnsafeIdentifiersCannotOverwriteReport() = runBlocking {
        val directory = temporary.newFolder()
        val store = AndroidReportStore(directory)
        store.save("owner", report())
        assertFailsWith<IllegalStateException> { store.save("owner", report().copy(title = "Overwrite")) }
        assertFailsWith<IllegalArgumentException> { store.save("owner", report("../outside")) }
        assertFailsWith<IllegalArgumentException> { store.delete("owner", "../outside") }
        assertEquals(report().title, store.load("owner").reports.single().title)
        assertTrue(directory.walkTopDown().none { it.extension == "tmp" })
    }
    @Test fun corruptAndFutureFilesDoNotHideReadableReports() = runBlocking {
        val directory = temporary.newFolder()
        val store = AndroidReportStore(directory)
        store.save("owner", report())
        val folder = directory.listFiles()!!.single()
        File(folder, "00000002-0000-0000-0000-000000000002.json").writeText("broken JSON")
        File(folder, "00000003-0000-0000-0000-000000000003.json").writeText(
            File(folder, "$ID.json").readText().replace(ID, "00000003-0000-0000-0000-000000000003").replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        val result = store.load("owner")
        assertEquals(1, result.reports.size)
        assertEquals(2, result.unreadableCount)
    }
    @Test fun deletionIsScopedToOwnerAndDoesNotAffectAnotherReport() = runBlocking {
        val store = AndroidReportStore(temporary.newFolder())
        store.save("first", report()); store.save("second", report())
        store.delete("first", ID)
        assertTrue(store.load("first").reports.isEmpty())
        assertEquals(1, store.load("second").reports.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
    private class MemoryStore : ReportStore {
        val rows = mutableMapOf<String, MutableList<DiagnosticReport>>()
        var slowLoad = false
        var failDelete = false
        override suspend fun load(owner: String): StoredReports {
            val result = rows[owner].orEmpty().toList()
            if (slowLoad) withContext(NonCancellable) { delay(100) }
            return StoredReports(result)
        }
        override suspend fun save(owner: String, report: DiagnosticReport) { rows.getOrPut(owner) { mutableListOf() }.add(report) }
        override suspend fun upsert(owner: String, report: DiagnosticReport) {
            rows.getOrPut(owner) { mutableListOf() }.apply { removeAll { it.id == report.id }; add(report) }
        }
        override suspend fun delete(owner: String, id: String) {
            check(!failDelete)
            rows[owner]?.removeAll { it.id == id }
        }
    }
    private fun fixture(block: suspend TestScope.(ReportsViewModel, MemoryStore) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val memory = MemoryStore()
        val vm = ReportsViewModel(memory, "https://api.example", { 10000 }, { 9000 }, { "UTC" }, { ID })
        val store = ViewModelStore(); store.put("reports", vm)
        try { vm.bindUser(1); block(vm, memory) }
        finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun remoteAutoSaveUpdatesOneReportWithoutManualAction() = fixture { vm, memory ->
        val linked = source().copy(linked = true)
        vm.autoRemote(linked); runCurrent()
        assertEquals(1, memory.rows.values.single().size)
        vm.autoRemote(linked.copy(now = 50000)); runCurrent()
        assertEquals(1, memory.rows.values.single().size)
        vm.autoRemote(linked.copy(ecuFaults = emptyList(), faultsReadAt = 4000)); runCurrent()
        assertEquals(0, memory.rows.values.single().single().codeCount)
    }
    @Test fun aiAutoSavePreservesAdditionalInputAndNotes() = fixture { vm, memory ->
        val turn = ReportTurn("Question", "Answer", "Guidance")
        val source = com.fleetbeat.roadside.assistant.AssistantState(open = true, session = 1,
            question = "Question", turns = listOf(turn), ecuContext = report())
        vm.autoAssistant(source); runCurrent()
        vm.autoAssistant(source.copy(question = "Additional observation", notes = "Check tomorrow")); runCurrent()
        val saved = memory.rows.values.single().single()
        assertEquals("Additional observation", saved.additionalInput)
        assertEquals("Check tomorrow", saved.notes)
        assertEquals(listOf(turn), saved.turns)
        assertEquals(report().values, saved.values)
        assertEquals(report().capturedLabel, saved.ecuCapturedLabel)
        vm.bindUser(2); vm.autoAssistant(source.copy(notes = "Other account")); runCurrent()
        assertEquals("Check tomorrow", memory.rows["https://api.example:1"]!!.single().notes)
    }
    @Test fun previewDoesNotPersistUntilSaveAndDoubleTapSavesOnce() = fixture { vm, memory ->
        vm.prepare(source())
        assertTrue(memory.rows.isEmpty())
        vm.draftTitle("My engine report")
        vm.saveDraft(); vm.saveDraft(); runCurrent()
        assertEquals("My engine report", memory.rows.values.single().single().title)
        assertNull(vm.state.value.draft)
        vm.open(); runCurrent(); vm.select(ID)
        assertEquals(ID, vm.state.value.selected?.id)
    }
    @Test fun lateLoadCannotRevealPreviousAccountReports() = fixture { vm, memory ->
        memory.rows["https://api.example:1"] = mutableListOf(report())
        memory.slowLoad = true
        vm.open(); runCurrent(); vm.bindUser(2)
        vm.open(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertTrue(vm.state.value.reports.isEmpty())
        assertNull(vm.state.value.selected)
    }
    @Test fun exportRequiresCurrentOwnerAndUsesCapturedText() = fixture { vm, _ ->
        vm.prepare(source()); vm.saveDraft(); runCurrent()
        vm.open(); runCurrent(); vm.select(ID)
        val ticket = vm.beginExport()!!
        var text = ""
        vm.writeExport(ticket.token) { text = it }; runCurrent()
        assertContains(text, "650.0 rpm")
        assertContains(text, "0C4900")
        assertEquals("Report exported.", vm.state.value.notice)
        val stale = vm.beginExport()!!
        vm.bindUser(2)
        var written = false
        vm.writeExport(stale.token) { written = true }; runCurrent()
        assertFalse(written)
        assertTrue(vm.state.value.reports.isEmpty())
    }
    @Test fun cancelAndWriteFailurePreserveSavedReport() = fixture { vm, _ ->
        vm.prepare(source()); vm.saveDraft(); runCurrent(); vm.open(); runCurrent(); vm.select(ID)
        vm.cancelExport(vm.beginExport()!!.token)
        assertFalse(vm.state.value.exporting)
        assertEquals(ID, vm.state.value.selected?.id)
        vm.writeExport(vm.beginExport()!!.token) { error("No disk space") }; runCurrent()
        assertFalse(vm.state.value.exporting)
        assertNotNull(vm.state.value.error)
        assertEquals(ID, vm.state.value.selected?.id)
    }
    @Test fun emptyCaptureCannotBecomeReportAndDeleteFailureKeepsSelection() = fixture { vm, memory ->
        vm.prepare(RemoteState(code = "DIAG"))
        assertNull(vm.state.value.draft)
        vm.prepare(source()); vm.saveDraft(); runCurrent(); vm.open(); runCurrent(); vm.select(ID)
        memory.failDelete = true
        vm.deleteSelected(); runCurrent()
        assertEquals(ID, vm.state.value.selected?.id)
        assertNotNull(vm.state.value.error)
        memory.failDelete = false
        vm.deleteSelected(); runCurrent()
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.reports.isEmpty())
    }
}
