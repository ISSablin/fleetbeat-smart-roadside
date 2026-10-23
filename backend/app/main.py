from contextlib import asynccontextmanager
from typing import Annotated, Literal

import httpx

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel
from sqlalchemy.exc import SQLAlchemyError
from sqlmodel import Session, text

from app.auth.routes import router as auth_router
from app.remote import router as remote_router
from app.advice import router as advice_router
from app.report_routes import router as report_router
from app.auth.tokens import TokenService
from app.config import Settings
from app.db import get_session, open_database


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    service: str
    database: Literal["ok"] = "ok"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings if settings is not None else Settings.from_env()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        tokens = TokenService(settings)
        engine = open_database(settings)
        application.state.settings = settings
        application.state.tokens = tokens
        application.state.engine = engine
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0), follow_redirects=False) as ai_http:
                application.state.ai_http = ai_http
                yield
        finally:
            engine.dispose()

    application = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        lifespan=lifespan,
    )

    application.include_router(auth_router)
    application.include_router(remote_router)
    application.include_router(advice_router)
    application.include_router(report_router)

    @application.get("/health", response_model=HealthResponse, tags=["health"])
    def health(session: Annotated[Session, Depends(get_session)]) -> HealthResponse:
        try:
            session.exec(text("SELECT 1")).one()
        except SQLAlchemyError as exc:
            raise HTTPException(status_code=503, detail="Database unavailable") from exc
        return HealthResponse(service=settings.app_name)

    return application


app = create_app()
