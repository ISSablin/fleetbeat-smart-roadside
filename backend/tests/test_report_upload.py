import asyncio
import json
from dataclasses import replace
from pathlib import Path
import httpx
import pytest
from app.config import Settings
from app.report_contract import prepare_report, LocalReport, ProposedCreateReport
from app.report_upload import upload_report, ReportUploadError
from app.report_sync import SyncLedger, synchronize_report


def report():
    value = json.loads((Path(__file__).parents[2] / "contracts/assistant-report.proposed.example.json").read_text())
    value["id"] = value.pop("clientReportId")
    value.pop("reportRevision")
    return value


SETTINGS = Settings(report_upload_enabled=True, report_upload_url="https://fleetbeat.example/reports",
                    report_upload_api_key="test-key")
REMOTE_ID = "68c29c5e-6c35-44d4-8a03-b6141f8b98f9"


def unexpected(request):
    pytest.fail("Unexpected network request")


def success(request):
    body = json.loads(request.content)
    status = 201 if request.method == "POST" else 200
    return httpx.Response(status, json={"statusCode":status, "data":dict(body,id=REMOTE_ID), "message":"Success"})


def run_upload(handler, value=None, settings=SETTINGS, receipt=None):
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
            return await upload_report(settings, value if value is not None else report(), client, receipt)
    return asyncio.run(run())


def test_disabled_and_unsafe_configuration_do_not_send():
    assert run_upload(unexpected, {}, Settings()) is None
    for config in (replace(SETTINGS, report_upload_url="http://unsafe.example"),
                   replace(SETTINGS, report_upload_method="PUT")):
        with pytest.raises(ReportUploadError):
            run_upload(unexpected, settings=config)


def test_post_nullable_arrays_auth_and_patch_to_saved_server_id():
    calls = []
    def handle(request):
        calls.append(request)
        assert request.headers["Authorization"] == "Api-Key test-key"
        assert "X-API-Key" not in request.headers
        body = json.loads(request.content)
        assert "id" not in body and "clientReportId" not in body
        assert body["profile"] is None and body["adapterVersion"] is None
        assert body["adapterVoltage"] is None and body["ecuCapturedLabel"] is None
        assert body["faults"][0]["codes"][0]["status"] is None
        assert body["values"] == [] and body["turns"][0]["causes"] == []
        assert body["vin"] == "TESTVIN1233456566"
        return success(request)
    value = report()
    receipt = run_upload(handle, value)
    assert receipt.client_report_id == value["id"] and receipt.remote_report_id == REMOTE_ID
    assert run_upload(unexpected, value, receipt=receipt) == receipt
    changed = run_upload(handle, dict(value, notes="new driver observation"), receipt=receipt)
    assert changed.remote_report_id == receipt.remote_report_id
    assert [r.method for r in calls] == ["POST", "PATCH"]
    assert calls[1].url.path.endswith("/"+REMOTE_ID)
    with pytest.raises(ReportUploadError, match="different report"):
        run_upload(unexpected, dict(value,id="different"), receipt=receipt)


def test_missing_vehicle_identity_is_blocked_without_inventing_it():
    with pytest.raises(ReportUploadError, match="Vehicle model and VIN"):
        run_upload(unexpected, dict(report(), vin=None))


def test_assessment_and_stations_are_structured_without_duplicate_prose():
    value = report()
    value["turns"][0]["assessment"] = dict(severity="HIGH",action="SEEK_SERVICE",categories=["truck_repair"],
        location=dict(lat=37.396944,lng=-122.144424,label="Palo Alto",emulated=True),
        services=[dict(id="shop",name="Repair shop",category="truck_repair",lat=37.4,lng=-122.1,phone="123",distance_km=2)])
    value.update(adapterName="ITD_ELD",busSpeedKbit=500)
    def handle(request):
        body = json.loads(request.content)
        turn = body["turns"][0]
        assert "assessment" not in turn
        assert turn["incidentSeverity"] == "HIGH"
        assert turn["recommendedAction"] == "SEEK_SERVICE"
        for text in ["Severity:", "Recommended action:", "Nearby service:", "Repair shop", "123"]:
            assert text not in turn["body"]
        assert turn["recommendedStations"][0]["name"] == "Repair shop"
        assert turn["recommendedStations"][0]["phone"] == "123"
        assert turn["recommendedStations"][0]["distance_km"] == 2
        assert turn["recommendedStations"][0]["address"] is None
        assert "ITD_ELD" in body["notes"] and "500" in body["notes"]
        assert "adapterName" not in body and "busSpeedKbit" not in body
        return success(request)
    assert run_upload(handle,value)
    assert value["turns"][0]["assessment"]["severity"] == "HIGH"


@pytest.mark.parametrize("status,outcome",[(422,"blocked"),(401,"blocked"),(503,"uncertain"),(408,"uncertain")])
def test_http_failures(status,outcome):
    with pytest.raises(ReportUploadError) as error:
        run_upload(lambda r:httpx.Response(status))
    assert error.value.outcome == outcome


@pytest.mark.parametrize("exception,outcome",[(httpx.ConnectTimeout,"retryable"),(httpx.ReadTimeout,"uncertain"),(httpx.WriteError,"uncertain")])
def test_transport_failures(exception,outcome):
    def fail(request):raise exception("sensitive details")
    with pytest.raises(ReportUploadError) as error:run_upload(fail)
    assert error.value.outcome == outcome and "sensitive" not in str(error.value)


def test_published_schema_and_example_match_model():
    root = Path(__file__).parents[2]/"contracts"
    schema = json.loads((root/"assistant-report.proposed.schema.json").read_text())
    for key,value in ProposedCreateReport.model_json_schema().items():assert schema[key] == value
    assert prepare_report(report()).model_dump() == json.loads((root/"assistant-report.proposed.example.json").read_text())


def test_ledger_survives_restart_and_updates_instead_of_creating_duplicates(tmp_path):
    calls=[]
    def handle(request):calls.append(request.method);return success(request)
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            ledger=SyncLedger(tmp_path/"sync.sqlite")
            value=LocalReport.model_validate(report())
            first=await synchronize_report(SETTINGS,ledger,"user1",value,client)
            restarted=SyncLedger(tmp_path/"sync.sqlite")
            assert await synchronize_report(SETTINGS,restarted,"user1",value,client) == first
            updated=value.model_copy(update={"notes":"updated", "capturedAt":value.capturedAt+1})
            assert (await synchronize_report(SETTINGS,restarted,"user1",updated,client))["remoteReportId"] == REMOTE_ID
            # A delayed old snapshot must not overwrite the newer published report.
            await synchronize_report(SETTINGS,restarted,"user1",value,client)
    asyncio.run(run())
    assert calls == ["POST","PATCH"]


def test_uncertain_post_is_not_repeated_but_patch_can_retry(tmp_path):
    calls=[]
    def fail(request):calls.append(request.method);raise httpx.ReadTimeout("lost response")
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(fail)) as client:
            value=LocalReport.model_validate(report());ledger=SyncLedger(tmp_path/"sync.sqlite")
            assert (await synchronize_report(SETTINGS,ledger,"user1",value,client))["status"] == "uncertain"
            assert (await synchronize_report(SETTINGS,SyncLedger(tmp_path/"sync.sqlite"),"user1",value,client))["status"] == "uncertain"
    asyncio.run(run());assert calls == ["POST"]


def test_company_scope_and_disabled_relay(tmp_path):
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(unexpected)) as client:
            value=LocalReport.model_validate(report());ledger=SyncLedger(tmp_path/"sync.sqlite")
            assert (await synchronize_report(Settings(),ledger,"user1",value,client))["status"] == "pending"
            with pytest.raises(ValueError,match="Company"):
                await synchronize_report(SETTINGS,ledger,"user1",value.model_copy(update={"companyId":"other"}),client)
    asyncio.run(run())


def test_formal_assessment_fields_follow_latest_turn_and_preserve_history():
    from app.report_upload import fleetbeat_body
    value=report()
    first=value["turns"][0]
    first["assessment"]=dict(severity="HIGH",action="SEEK_SERVICE",categories=["truck_repair"],
        location=dict(lat=37.396944,lng=-122.144424,label="Palo Alto",emulated=True),services=[])
    value["turns"].append(dict(question="Follow-up",title="Monitor",body="Report changes on arrival",causes=[],
        assessment=dict(severity="LOW",action="REPORT_ON_ARRIVAL",categories=["truck_repair"],
        location=dict(lat=37.396944,lng=-122.144424,label="Palo Alto",emulated=True),services=[])))
    def wire():return fleetbeat_body(prepare_report(value).model_dump(exclude={"clientReportId","reportRevision"}))
    body=wire()
    assert body["incidentSeverity"] == "LOW" and body["recommendedAction"] == "REPORT_ON_ARRIVAL"
    assert body["turns"][0]["incidentSeverity"] == "HIGH"
    assert body["turns"][0]["recommendedAction"] == "SEEK_SERVICE"
    value["turns"].append(dict(question="Old-format answer",title="Guidance",body="Checks",causes=[]))
    body=wire()
    assert body["incidentSeverity"] is None and body["recommendedAction"] is None
    assert body["turns"][-1]["incidentSeverity"] is None
    assert body["turns"][-1]["recommendedStations"] == []
    value["turns"]=[]
    body=wire()
    assert body["incidentSeverity"] is None and body["recommendedAction"] is None


def test_formal_wire_schema_matches_published_schema():
    from app.report_contract import fleetbeat_report_schema
    path=Path(__file__).parents[2]/"contracts/fleetbeat-assistant-report.schema.json"
    assert json.loads(path.read_text()) == fleetbeat_report_schema()


def test_wire_upgrade_patches_existing_remote_record_without_creating_duplicate(tmp_path):
    calls=[]
    def handle(request):calls.append(request.method);return success(request)
    async def run():
        value=LocalReport.model_validate(report());ledger=SyncLedger(tmp_path/"sync.sqlite")
        with ledger.connect() as db:
            db.execute("INSERT INTO report_uploads(owner,client_id,remote_id,revision,captured_at,state) VALUES(?,?,?,?,?,?)",
                ("user1",value.id,REMOTE_ID,"legacy-wire-revision",value.capturedAt,"synced"))
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            result=await synchronize_report(SETTINGS,ledger,"user1",value,client)
            assert result["remoteReportId"] == REMOTE_ID
    asyncio.run(run())
    assert calls == ["PATCH"]
