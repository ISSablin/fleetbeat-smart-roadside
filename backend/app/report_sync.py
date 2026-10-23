"""Authenticated report relay with a small durable local-to-server ID ledger.

A create with an unknown outcome is held for reconciliation, never blindly
repeated. PATCH sends the complete snapshot and can safely be retried.
"""
from contextlib import contextmanager
import asyncio
import sqlite3
from pathlib import Path
from app.report_contract import LocalReport, prepare_report
from app.report_upload import UploadReceipt, ReportUploadError, upload_report

COMPANY_ID = "e2fc8a58-4981-40d7-bbaa-93566b200711"


class SyncLedger:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS report_uploads (
                owner TEXT NOT NULL, client_id TEXT NOT NULL, remote_id TEXT,
                revision TEXT NOT NULL DEFAULT '', captured_at INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL DEFAULT 'pending', PRIMARY KEY(owner,client_id))""")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def claim(self, owner, client_id, revision, captured_at):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("INSERT OR IGNORE INTO report_uploads(owner,client_id) VALUES(?,?)", (owner, client_id))
            row = dict(db.execute("SELECT * FROM report_uploads WHERE owner=? AND client_id=?", (owner,client_id)).fetchone())
            if row["state"] in {"sending", "uncertain"}:
                return "uncertain", row
            if row["revision"] == revision or captured_at < row["captured_at"]:
                return "synced", row
            db.execute("UPDATE report_uploads SET state='sending' WHERE owner=? AND client_id=?", (owner,client_id))
            return "claimed", row

    def finish(self, owner, client_id, receipt, captured_at):
        with self.connect() as db:
            db.execute("UPDATE report_uploads SET state='synced',remote_id=?,revision=?,captured_at=? WHERE owner=? AND client_id=?",
                (receipt.remote_report_id,receipt.revision,captured_at,owner,client_id))

    def fail(self, owner, client_id, state):
        with self.connect() as db:
            db.execute("UPDATE report_uploads SET state=? WHERE owner=? AND client_id=?", (state,owner,client_id))


async def synchronize_report(settings, ledger: SyncLedger, owner: str, report: LocalReport, client) -> dict:
    if report.companyId != COMPANY_ID:
        raise ValueError("Company is not authorized")
    if not settings.report_upload_enabled:
        return {"status":"pending", "message":"FleetBeat upload is not enabled yet"}
    if not report.vehicleModel or not report.vehicleModel.strip() or not report.vin or not report.vin.strip():
        return {"status":"blocked", "message":"Vehicle model and VIN are required before upload"}
    prepared = prepare_report(report.model_dump())
    state, row = ledger.claim(owner, report.id, prepared.reportRevision, report.capturedAt)
    if state == "synced":
        return {"status":"synced", "remoteReportId":row["remote_id"]}
    if state == "uncertain":
        return {"status":"uncertain", "remoteReportId":row["remote_id"],
                "message":"Previous upload may still be processing; server confirmation is required"}
    receipt = UploadReceipt(report.id, report.companyId, row["revision"], row["remote_id"]) if row["remote_id"] else None
    try:
        result = await upload_report(settings, report.model_dump(), client, receipt)
        ledger.finish(owner, report.id, result, report.capturedAt)
        return {"status":"synced", "remoteReportId":result.remote_report_id}
    except asyncio.CancelledError:
        ledger.fail(owner, report.id, "pending" if receipt else "uncertain")
        raise
    except ReportUploadError as error:
        # Repeating an entire PATCH snapshot to a known ID does not create duplicates.
        state = "uncertain" if error.outcome == "uncertain" and receipt is None else "pending"
        ledger.fail(owner, report.id, state)
        return {"status":state if error.outcome != "blocked" else "blocked",
                "remoteReportId":row["remote_id"], "message":str(error)}
    except Exception:
        ledger.fail(owner, report.id, "pending" if receipt else "uncertain")
        raise
