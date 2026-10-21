import httpx
import pytest
import asyncio
from dataclasses import replace
from app.config import Settings
from app.report_upload import upload_report, ReportUploadError


def test_upload_is_disabled_by_default():
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda request: pytest.fail("Unexpected upload"))) as client:
            assert await upload_report(Settings(), {"id": "report-1"}, client) is False
    asyncio.run(run())


def test_configured_transport_and_failure():
    calls = []
    def handle(request):
        calls.append(request)
        return httpx.Response(201)
    settings = Settings(report_upload_enabled=True, report_upload_url="https://fleetbeat.example/reports",
                        report_upload_method="PUT", report_upload_api_key="test-key")
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            assert await upload_report(settings, {"id": "report-1"}, client)
            with pytest.raises(ReportUploadError):
                await upload_report(replace(settings, report_upload_url="http://unsafe.example"), {"id": "report-1"}, client)
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(503))) as client:
            with pytest.raises(ReportUploadError):
                await upload_report(settings, {"id": "report-1"}, client)
    asyncio.run(run())
    assert len(calls) == 1
    assert calls[0].method == "PUT"
    assert calls[0].headers["X-API-Key"] == "test-key"
    assert calls[0].headers["Idempotency-Key"].startswith("report-1:")
