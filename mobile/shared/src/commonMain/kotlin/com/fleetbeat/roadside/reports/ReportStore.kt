package com.fleetbeat.roadside.reports

data class StoredReports(val reports: List<DiagnosticReport>, val unreadableCount: Int = 0)
interface ReportStore {
    suspend fun load(owner: String): StoredReports
    suspend fun save(owner: String, report: DiagnosticReport)
    suspend fun upsert(owner: String, report: DiagnosticReport) { save(owner, report) }
    suspend fun delete(owner: String, id: String)
}
