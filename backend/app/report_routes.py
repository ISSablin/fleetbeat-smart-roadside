"""Mobile authenticates with its user JWT; the FleetBeat key remains server-side."""
from typing import Annotated
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Request
from app.auth.routes import current_user
from app.models import User
from app.report_contract import LocalReport
from app.report_sync import COMPANY_ID, SyncLedger, synchronize_report

router = APIRouter(prefix="/api/v1/assistant-reports", tags=["report upload"])


@router.post("/sync")
async def sync_report(report: LocalReport, request: Request,
                      user: Annotated[User, Depends(current_user)]) -> dict:
    if report.companyId != COMPANY_ID:
        raise HTTPException(status_code=403, detail="Company is not authorized")
    try:
        UUID(report.id)
    except ValueError:
        raise HTTPException(status_code=422, detail="Invalid local report ID") from None
    if len(report.model_dump_json().encode()) > 524288:
        raise HTTPException(status_code=413, detail="Report is too large")
    settings = request.app.state.settings
    ledger = SyncLedger(settings.data_dir / "report-uploads.sqlite")
    return await synchronize_report(settings, ledger, str(user.id), report, request.app.state.ai_http)
