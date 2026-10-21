package com.fleetbeat.roadside.reports

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private app files, separated by account/API namespace. No credentials are stored in reports. */
class AndroidReportStore(private val root: File) : ReportStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val validId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun folder(owner: String): File {
        require(owner.isNotBlank())
        val key = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        return File(root, key)
    }
    private fun reportFile(owner: String, id: String): File {
        require(validId.matches(id)) { "Invalid report identifier." }
        return File(folder(owner), "$id.json")
    }
    override suspend fun load(owner: String): StoredReports = withContext(Dispatchers.IO) { mutex.withLock {
        var unreadable = 0
        val reports = folder(owner).listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { file ->
            try {
                require(file.length() in 1..524288 && validId.matches(file.name.removeSuffix(".json")))
                json.decodeFromString<DiagnosticReport>(file.readText(Charsets.UTF_8)).also {
                    require(it.schemaVersion == 1 && it.id == file.name.removeSuffix(".json") && it.hasEvidence)
                }
            } catch (_: Exception) { unreadable++; null }
        }.sortedByDescending { it.capturedAt }
        StoredReports(reports, unreadable)
    } }
    override suspend fun save(owner: String, report: DiagnosticReport) = write(owner, report, false)
    override suspend fun upsert(owner: String, report: DiagnosticReport) = write(owner, report, true)
    private suspend fun write(owner: String, report: DiagnosticReport, replace: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        require(report.schemaVersion == 1 && report.hasEvidence && report.title.isNotBlank() && report.title.length <= 80)
        val file = reportFile(owner, report.id)
        check(replace || !file.exists()) { "This report has already been saved." }
        val directory = file.parentFile!!
        check(directory.isDirectory || directory.mkdirs()) { "Could not create report storage." }
        check(file.exists() || directory.listFiles().orEmpty().count { it.name.endsWith(".json") } < 100) { "Report storage is full. Delete an older report before saving another." }
        val bytes = json.encodeToString(report).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 524288) { "The report is too large to save." }
        val temporary = File(directory, "${report.id}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            check(temporary.renameTo(file)) { "Could not finish saving the report." }
        } finally { temporary.delete() }
    } }
    override suspend fun delete(owner: String, id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val file = reportFile(owner, id)
        check(!file.exists() || file.delete()) { "Could not delete the report." }
    } }
}
