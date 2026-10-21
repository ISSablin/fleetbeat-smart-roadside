"""Future server-side report transport. No route or background job invokes it yet.

The mobile app saves JSON locally. A future authenticated ingestion endpoint and
persistent outbox must be connected before automatic uploads can be enabled.
"""
from urllib.parse import urlsplit
import hashlib
import json
import httpx
from app.config import Settings


class ReportUploadError(Exception):
    """Safe failure message; do not expose response bodies or credentials."""


async def upload_report(settings: Settings, report: dict, client: httpx.AsyncClient) -> bool:
    if not settings.report_upload_enabled:
        return False
    url = settings.report_upload_url
    parsed = urlsplit(url)
    method = settings.report_upload_method
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or method not in {"POST", "PUT"}
            or not settings.report_upload_api_key):
        raise ReportUploadError("Invalid FleetBeat report upload configuration")
    report_id = report.get("id")
    if not isinstance(report_id, str) or not report_id:
        raise ReportUploadError("Report identifier is required")
    # Updated sessions share a report ID; each distinct JSON revision needs its own key.
    revision = hashlib.sha256(json.dumps(report, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    try:
        response = await client.request(method, url, json=report,
            headers={"X-API-Key": settings.report_upload_api_key,
                     "Idempotency-Key": f"{report_id}:{revision}"},
            timeout=httpx.Timeout(30.0, connect=10.0), follow_redirects=False)
        if not 200 <= response.status_code < 300:
            raise ReportUploadError("FleetBeat rejected the report upload")
    except httpx.HTTPError:
        raise ReportUploadError("FleetBeat report upload is unavailable") from None
    return True
