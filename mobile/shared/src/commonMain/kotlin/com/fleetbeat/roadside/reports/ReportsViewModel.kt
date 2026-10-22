package com.fleetbeat.roadside.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fleetbeat.roadside.remote.RemoteState
import com.fleetbeat.roadside.diagnostics.DiagnosticsState
import com.fleetbeat.roadside.assistant.AssistantState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReportsState(val open: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val exporting: Boolean = false, val reports: List<DiagnosticReport> = emptyList(), val selected: DiagnosticReport? = null,
    val draft: DiagnosticReport? = null, val draftTitle: String = "", val error: String? = null, val notice: String? = null)
data class ReportExport(val token: Int, val filename: String)

class ReportsViewModel(private val store: ReportStore, private val namespace: String,
    private val wallClock: () -> Long, private val monotonicClock: () -> Long,
    private val timestamp: (Long) -> String, private val newId: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(ReportsState())
    val state = mutable.asStateFlow()
    private var localSession: Int? = null
    private var localId: String? = null
    private var lastLocal: Any? = null
    private var remoteId: String? = null
    private var wasLinked = false
    private var assistantSession: Int? = null
    private var assistantId: String? = null
    private var lastRemote: Any? = null
    private var lastAssistant: Any? = null
    private val automaticWrites = kotlinx.coroutines.sync.Mutex()
    private var owner: String? = null
    private var generation = 0
    private var operation: Job? = null
    private var exportJob: Job? = null
    private var exportSequence = 0
    private data class PendingExport(val token: Int, val epoch: Int, val owner: String, val text: String)
    private var pendingExport: PendingExport? = null

    fun bindUser(id: Int?) {
        val next = id?.let { "${namespace.trimEnd('/')}:$it" }
        if (owner != next) {
            generation++; operation?.cancel(); exportJob?.cancel(); pendingExport = null
            localSession = null; localId = null; lastLocal = null
            remoteId = null; wasLinked = false; assistantSession = null; assistantId = null; lastRemote = null; lastAssistant = null
            owner = next; mutable.value = ReportsState()
        }
    }
    private fun persistAutomatically(report: DiagnosticReport) {
        val account = owner ?: return
        val epoch = generation
        viewModelScope.launch {
            automaticWrites.lock()
            try {
                store.upsert(account, report)
                if (owner == account) mutable.update { current -> current.copy(
                    error = null,
                    reports = if (current.open) (current.reports.filterNot { it.id == report.id } + report).sortedByDescending { it.capturedAt } else current.reports,
                    selected = if (current.selected?.id == report.id) report else current.selected) }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) {
                if (generation == epoch) {
                    lastRemote = null; lastAssistant = null; lastLocal = null
                    mutable.update { it.copy(error = "Automatic report save failed. Check available storage; delete older reports if storage is full.") }
                }
            } finally { automaticWrites.unlock() }
        }
    }
    fun captureLocalContext(source: DiagnosticsState): DiagnosticReport {
        val time = wallClock()
        return localReport(source, newId(), time, timestamp(time), monotonicClock())
    }
    fun autoLocal(source: DiagnosticsState) {
        if (owner == null || !source.open || source.session == 0) return
        if (localSession != source.session) { localSession = source.session; localId = newId(); lastLocal = null }
        val key = listOf(source.values, source.faults, source.adapterName, source.adapterVersion, source.adapterVoltage, source.dataSpeed ?: source.speed)
        if (lastLocal == key) return
        val report = captureLocalContext(source).copy(id = localId!!)
        if (!report.hasEvidence) return
        lastLocal = key
        persistAutomatically(report)
    }
    fun autoRemote(source: RemoteState) {
        if (owner == null) return
        if (source.linked && !wasLinked) { remoteId = newId(); lastRemote = null }
        wasLinked = source.linked
        if (!source.linked) return
        val key = listOf(source.requestedValues, source.faultsReadAt, source.ecuFaults, source.values, source.faults)
        if (key == lastRemote) return
        val report = captureContext(source).copy(id = remoteId ?: newId().also { remoteId = it })
        if (!report.hasEvidence) return
        lastRemote = key
        persistAutomatically(report)
    }
    fun autoAssistant(source: AssistantState) {
        if (owner == null || !source.open) return
        if (assistantSession != source.session) { assistantSession = source.session; assistantId = newId(); lastAssistant = null }
        if (source.turns.isEmpty()) return
        val key = listOf(source.turns, source.question, source.notes)
        if (key == lastAssistant) return
        val time = wallClock()
        val context = source.ecuContext
        val report = (context ?: DiagnosticReport(id = assistantId!!, title = "Diagnostic assistance report",
            capturedAt = time, capturedLabel = timestamp(time), simulated = false,
            origin = "AI diagnostic", values = emptyList(), faults = emptyList())).copy(
            id = assistantId!!, title = if (context != null) "ECU analysis report" else "Diagnostic assistance report",
            capturedAt = time, capturedLabel = timestamp(time), ecuCapturedLabel = context?.capturedLabel,
            turns = source.turns.toList(), notes = source.notes,
            additionalInput = source.question.takeIf { it != source.turns.last().question }.orEmpty())
        lastAssistant = key
        persistAutomatically(report)
    }
    fun prepare(source: RemoteState) {
        if (owner == null || source.busy || mutable.value.saving) return
        val time = wallClock()
        val draft = remoteReport(source, newId(), time, timestamp(time), monotonicClock())
        if (!draft.hasEvidence) { mutable.update { it.copy(notice = "Read ECU data before saving a report.") }; return }
        mutable.update { it.copy(draft = draft, draftTitle = draft.title, error = null, notice = null) }
    }
    fun captureContext(source: RemoteState): DiagnosticReport {
        val time = wallClock()
        return remoteReport(source, newId(), time, timestamp(time), monotonicClock())
    }
    fun prepareAssistant(source: AssistantState) {
        if (owner == null || source.busy || source.voiceActive || source.turns.isEmpty() || mutable.value.saving) return
        val time = wallClock()
        val context = source.ecuContext
        val base = context ?: DiagnosticReport(id = newId(), title = "Diagnostic assistance report",
            capturedAt = time, capturedLabel = timestamp(time), simulated = false,
            origin = "AI diagnostic", values = emptyList(), faults = emptyList())
        val draft = base.copy(id = newId(), title = if (context != null) "ECU analysis report" else base.title,
            capturedAt = time, capturedLabel = timestamp(time), ecuCapturedLabel = context?.capturedLabel,
            turns = source.turns.toList(), additionalInput = source.question.takeIf { it != source.turns.last().question }.orEmpty())
        mutable.update { it.copy(draft = draft, draftTitle = draft.title, error = null, notice = null) }
    }
    fun draftNotes(value: String) { if (!mutable.value.saving) mutable.update { it.copy(draft = it.draft?.copy(notes = value.take(4000))) } }
    fun draftTitle(value: String) { if (!mutable.value.saving) mutable.update { it.copy(draftTitle = value.take(80)) } }
    fun dismissDraft() { if (!mutable.value.saving) mutable.update { it.copy(draft = null, error = null) } }
    fun saveDraft() {
        val account = owner ?: return
        val draft = mutable.value.draft ?: return
        if (mutable.value.saving) return
        val title = mutable.value.draftTitle.trim().replace('\n', ' ')
        if (title.isBlank()) { mutable.update { it.copy(error = "Enter a report title.") }; return }
        val epoch = generation
        mutable.update { it.copy(saving = true, error = null) }
        operation = viewModelScope.launch {
            try {
                store.save(account, draft.copy(title = title))
                if (generation == epoch) mutable.update { it.copy(draft = null, notice = "Report saved on this device.") }
            } catch (error: CancellationException) { throw error }
              catch (error: Exception) { if (generation == epoch) mutable.update { it.copy(error = if (error is IllegalStateException || error is IllegalArgumentException)
                    error.message ?: "Could not save report." else "Could not save report. Check available storage and try again.") } }
            finally { if (generation == epoch) mutable.update { it.copy(saving = false) } }
        }
    }
    fun open() { if (owner != null) { mutable.update { it.copy(open = true, selected = null, notice = null) }; reload() } }
    fun reload() {
        val account = owner ?: return
        operation?.cancel()
        val epoch = generation
        mutable.update { it.copy(loading = true, error = null) }
        operation = viewModelScope.launch {
            try {
                val result = store.load(account)
                if (generation == epoch) mutable.update { it.copy(reports = result.reports,
                    notice = if (result.unreadableCount > 0) "${result.unreadableCount} saved reports could not be read." else null) }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) { if (generation == epoch) mutable.update { it.copy(error = "Could not load saved reports.") } }
            finally { if (generation == epoch) mutable.update { it.copy(loading = false) } }
        }
    }
    fun select(id: String) { if (!mutable.value.loading) mutable.value.reports.find { it.id == id }?.let { report ->
        mutable.update { it.copy(selected = report, error = null, notice = null) } } }
    fun back() {
        if (mutable.value.saving || mutable.value.exporting) return
        if (mutable.value.selected != null) mutable.update { it.copy(selected = null, error = null, notice = null) }
        else { generation++; operation?.cancel(); pendingExport = null; mutable.update { ReportsState() } }
    }
    fun deleteSelected() {
        val account = owner ?: return
        val report = mutable.value.selected ?: return
        if (mutable.value.loading || mutable.value.exporting) return
        val epoch = generation
        mutable.update { it.copy(loading = true, error = null) }
        operation = viewModelScope.launch {
            try {
                store.delete(account, report.id)
                if (generation == epoch) {
                    mutable.update { it.copy(selected = null, reports = it.reports.filterNot { row -> row.id == report.id }, notice = "Report deleted.") }
                }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) { if (generation == epoch) mutable.update { it.copy(error = "Could not delete report.") } }
            finally { if (generation == epoch) mutable.update { it.copy(loading = false) } }
        }
    }
    fun beginExport(): ReportExport? {
        val account = owner ?: return null
        val report = mutable.value.selected ?: return null
        if (mutable.value.loading || mutable.value.exporting) return null
        val token = ++exportSequence
        pendingExport = PendingExport(token, generation, account, report.asText())
        mutable.update { it.copy(exporting = true, error = null, notice = null) }
        return ReportExport(token, "fleetbeat-report-${report.id.take(8)}.txt")
    }
    fun cancelExport(token: Int, failed: Boolean = false) {
        val pending = pendingExport ?: return
        if (pending.token != token || pending.epoch != generation) return
        pendingExport = null
        mutable.update { it.copy(exporting = false, notice = if (failed) null else "Export cancelled.",
            error = if (failed) "Could not open the file picker." else null) }
    }
    fun writeExport(token: Int, write: suspend (String) -> Unit) {
        val pending = pendingExport ?: return
        if (pending.token != token || pending.epoch != generation || pending.owner != owner) return
        pendingExport = null
        exportJob = viewModelScope.launch {
            try {
                write(pending.text)
                if (generation == pending.epoch) mutable.update { it.copy(notice = "Report exported.") }
            } catch (error: CancellationException) { throw error }
              catch (_: Exception) { if (generation == pending.epoch) mutable.update { it.copy(error = "Could not export report. Try another location.") } }
            finally { if (generation == pending.epoch) mutable.update { it.copy(exporting = false) } }
        }
    }
}
