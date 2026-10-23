"""Lossless local report contract and proposed FleetBeat create contract.

The external API does not yet implement the proposed contract. Never fill absent
observations with invented readings to satisfy its current validators.
"""
import hashlib
import json
from typing import Literal
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field, model_validator


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class ReportValue(ContractModel):
    label: str
    value: float | None
    unit: str
    ageSeconds: int = Field(ge=0)
    staleAtCapture: bool
    origin: str
    sourceAddress: int | None = None


class ReportCode(ContractModel):
    code: str
    status: int | None = None
    label: str = ""
    description: str = ""
    occurrences: int | None = None


class ReportFaults(ContractModel):
    origin: str
    ageSeconds: int = Field(ge=0)
    staleAtCapture: bool
    codes: list[ReportCode]
    sourceAddress: int | None = None


class DiagnosticLocation(ContractModel):
    lat: float
    lng: float
    label: str
    emulated: bool = True


class NearbyService(ContractModel):
    id: str
    name: str
    category: str
    lat: float
    lng: float
    address: str | None = None
    city: str | None = None
    state: str | None = None
    phone: str | None = None
    website: str | None = None
    distance_km: float | None = None


class RoadsideAssessment(ContractModel):
    severity: Literal["LOW", "MODERATE", "HIGH", "CRITICAL"]
    action: Literal["STOP_IMMEDIATELY", "PROCEED_WITH_CAUTION", "REPORT_ON_ARRIVAL", "SEEK_SERVICE"]
    categories: list[str]
    location: DiagnosticLocation
    services: list[NearbyService] = Field(default_factory=list)
    serviceNotice: str | None = None


class ReportTurn(ContractModel):
    question: str
    title: str
    body: str
    causes: list[str] = Field(default_factory=list)
    assessment: RoadsideAssessment | None = None


class ReportContent(ContractModel):
    schemaVersion: Literal[1] = 1
    companyId: str
    title: str
    capturedAt: int = Field(ge=0, description="Unix epoch milliseconds; latest local save")
    capturedLabel: str
    simulated: bool
    origin: str
    vehicleModel: str | None = None
    vin: str | None = None
    adapterName: str | None = None
    busSpeedKbit: int | None = None
    profile: str | None = None
    adapterVersion: str | None = None
    adapterVoltage: float | None = None
    turns: list[ReportTurn] = Field(default_factory=list)
    additionalInput: str = ""
    notes: str = ""
    ecuCapturedLabel: str | None = None
    broadcastFrames: int = Field(default=0, ge=0)
    values: list[ReportValue]
    faults: list[ReportFaults]

    @model_validator(mode="after")
    def validate_identity_and_evidence(self):
        UUID(self.companyId)
        if not (self.values or self.faults or self.turns):
            raise ValueError("A report must contain measurements, an ECU response, or an AI turn")
        return self


class LocalReport(ReportContent):
    id: str = Field(min_length=1)


class ProposedCreateReport(ReportContent):
    """Proposal for the endpoint author; not the deployed request contract."""
    clientReportId: str = Field(min_length=1)
    reportRevision: str = Field(pattern="^[0-9a-f]{64}$")


def prepare_report(report: dict) -> ProposedCreateReport:
    local = LocalReport.model_validate(report)
    content = local.model_dump(exclude={"id"})
    # Hash normalized content, including companyId, rather than display/server IDs.
    revision = hashlib.sha256(b"fleetbeat-report-wire-v3\n" + json.dumps(content, sort_keys=True,
        separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()).hexdigest()
    return ProposedCreateReport(**content, clientReportId=local.id, reportRevision=revision)



def fleetbeat_report_schema() -> dict:
    """External wire schema, including derived typed assessment summaries."""
    schema = ReportContent.model_json_schema()
    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    schema["title"] = "FleetBeatAssistantReport"
    schema["description"] = "Proposed external wire format with formal incident severity and driver action; null means not assessed."
    for name in ("adapterName", "busSpeedKbit"):
        schema["properties"].pop(name)
    assessment = schema["$defs"]["RoadsideAssessment"]["properties"]
    import copy
    severity = {"anyOf": [copy.deepcopy(assessment["severity"]), {"type": "null"}],
                "description": "AI-assessed incident severity; null if no assessment exists."}
    action = {"anyOf": [copy.deepcopy(assessment["action"]), {"type": "null"}],
              "description": "AI-recommended driver action; null if no assessment exists."}
    turn = schema["$defs"]["ReportTurn"]
    turn["properties"].pop("assessment")
    turn["properties"]["recommendedStations"] = {"type": "array", "items": {"$ref": "#/$defs/NearbyService"},
        "description": "Recommended stations for this turn, in recommendation order; empty when none are available."}
    for target in (schema, turn):
        target["properties"]["incidentSeverity"] = copy.deepcopy(severity)
        target["properties"]["recommendedAction"] = copy.deepcopy(action)
        target["required"] = list(target["properties"])
    # Every nullable key is still sent explicitly, matching the agreed API convention.
    for definition in schema["$defs"].values():
        if definition.get("type") == "object":
            definition["required"] = list(definition.get("properties", {}))
    return schema
