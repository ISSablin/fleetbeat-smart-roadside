"""Server-side FleetBeat POST/PATCH transport. API keys never reach the phone."""
from dataclasses import dataclass
from urllib.parse import urlsplit
from uuid import UUID
import httpx
from app.config import Settings
from app.report_contract import prepare_report


class ReportUploadError(Exception):
    """Safe error with an explicit recovery category; never include response bodies."""
    def __init__(self, message: str, *, outcome: str = "blocked"):
        super().__init__(message)
        self.outcome = outcome  # blocked, retryable (not sent), uncertain (may exist)


@dataclass(frozen=True)
class UploadReceipt:
    client_report_id: str
    company_id: str
    revision: str
    remote_report_id: str


async def upload_report(settings: Settings, report: dict, client: httpx.AsyncClient,
                        receipt: UploadReceipt | None = None) -> UploadReceipt | None:
    if not settings.report_upload_enabled:
        return None
    parsed = urlsplit(settings.report_upload_url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or settings.report_upload_method != "POST"
            or not settings.report_upload_api_key):
        raise ReportUploadError("Invalid FleetBeat report upload configuration")
    prepared = prepare_report(report)
    if receipt:
        if (receipt.client_report_id, receipt.company_id) != (prepared.clientReportId, prepared.companyId):
            raise ReportUploadError("Upload receipt belongs to a different report")
        if receipt.revision == prepared.reportRevision:
            return receipt
    if not prepared.vehicleModel or not prepared.vehicleModel.strip() or not prepared.vin or not prepared.vin.strip():
        raise ReportUploadError("Vehicle model and VIN are required before upload")
    body = fleetbeat_body(prepared.model_dump(exclude={"clientReportId", "reportRevision"}))
    method = "PATCH" if receipt else "POST"
    url = settings.report_upload_url.rstrip("/") + ("/" + str(UUID(receipt.remote_report_id)) if receipt else "")
    try:
        response = await client.request(method, url, json=body,
            headers={"Authorization": "Api-Key " + settings.report_upload_api_key,
                     "Idempotency-Key": f"{prepared.companyId}:{prepared.clientReportId}:{prepared.reportRevision}"},
            timeout=httpx.Timeout(30.0, connect=10.0), follow_redirects=False)
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.PoolTimeout):
        raise ReportUploadError("FleetBeat connection unavailable; request was not sent", outcome="retryable") from None
    except httpx.HTTPError:
        raise ReportUploadError("FleetBeat upload outcome is unknown; reconcile before retrying", outcome="uncertain") from None
    if response.status_code >= 500 or response.status_code == 408:
        raise ReportUploadError("FleetBeat upload outcome is unknown; reconcile before retrying", outcome="uncertain")
    if method == "PATCH" and response.status_code == 204:
        return UploadReceipt(prepared.clientReportId, prepared.companyId, prepared.reportRevision, receipt.remote_report_id)
    if response.status_code not in {200, 201}:
        raise ReportUploadError("FleetBeat rejected the report upload")
    try:
        payload = response.json()
        data = payload["data"]
        remote_id = str(UUID(data["id"]))
        if payload["statusCode"] != response.status_code:
            raise ValueError()
        if data.get("companyId") != prepared.companyId:
            raise ValueError()
        if receipt and remote_id != receipt.remote_report_id:
            raise ValueError()
    except (ValueError, TypeError, KeyError, AttributeError):
        raise ReportUploadError("FleetBeat success response is incomplete; reconcile before retrying", outcome="uncertain") from None
    return UploadReceipt(prepared.clientReportId, prepared.companyId, prepared.reportRevision, remote_id)


def fleetbeat_body(content: dict) -> dict:
    """Export typed assessment fields and stations without duplicating them in prose.

    model_dump includes explicit null metadata and empty arrays. Do not exclude
    null values: the external API requires keys even when their values are null.
    """
    extras = []
    for key, label in (("adapterName", "Adapter name"), ("busSpeedKbit", "CAN speed (kbit/s)")):
        value = content.pop(key, None)
        if value is not None:
            extras.append(f"{label}: {value}")
    if extras:
        content["notes"] = "\n".join(filter(None, [content["notes"], *extras]))
    # Report-level summary reflects the latest AI answer, not the highest
    # severity ever seen. An unassessed latest turn must remain unknown.
    content["incidentSeverity"] = None
    content["recommendedAction"] = None
    for turn in content["turns"]:
        assessment = turn.pop("assessment", None)
        turn["incidentSeverity"] = assessment["severity"] if assessment else None
        turn["recommendedAction"] = assessment["action"] if assessment else None
        turn["recommendedStations"] = assessment["services"] if assessment else []
        content["incidentSeverity"] = turn["incidentSeverity"]
        content["recommendedAction"] = turn["recommendedAction"]
        if assessment:
            location = assessment["location"]
            lines = [turn["body"], "",
                     f"Location: {location['label']} ({location['lat']}, {location['lng']}); emulated GPS: {location['emulated']}",
                     "Nearby service categories: " + ", ".join(assessment["categories"])]
            if assessment.get("serviceNotice"):
                lines.append(assessment["serviceNotice"])
            turn["body"] = "\n".join(lines)
    return content
