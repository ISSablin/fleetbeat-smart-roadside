package com.fleetbeat.roadside.reports

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val reportUploadJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
fun reportFingerprint(report: DiagnosticReport): String = digest("fleetbeat-report-wire-v3\n" + reportUploadJson.encodeToString(report))

class AndroidReportUploadStateStore(private val root: File) : ReportUploadStateStore {
    private fun file(owner: String) = File(root, "${digest(owner)}.json")
    override suspend fun load(owner: String): Map<String, ReportUploadState> = withContext(Dispatchers.IO) {
        val source = file(owner)
        if (!source.exists()) emptyMap() else reportUploadJson.decodeFromString(source.readText(Charsets.UTF_8))
    }
    override suspend fun write(owner: String, states: Map<String, ReportUploadState>) = withContext(Dispatchers.IO) {
        check(root.isDirectory || root.mkdirs())
        val target = file(owner)
        val temporary = File(root, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(reportUploadJson.encodeToString(states).toByteArray(Charsets.UTF_8)); it.fd.sync()
            }
            check(temporary.renameTo(target))
        } finally { temporary.delete() }
    }
}
