"""Diagnostic session metadata. Adapter bytes travel through the MQTT broker."""
from datetime import datetime, timedelta, timezone
import secrets
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.exc import IntegrityError
from sqlmodel import select

from app.auth.routes import SessionDep, current_user
from app.models import RemoteSession, User

router = APIRouter(prefix="/api/v1/remote", tags=["remote diagnostics"])
ActiveUser = Annotated[User, Depends(current_user)]
ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"


class SessionCode(BaseModel):
    session_code: str = Field(min_length=4, max_length=16)

    @field_validator("session_code", mode="before")
    @classmethod
    def normalize(cls, raw):
        if not isinstance(raw, str):
            raise ValueError("Session code must be text")
        code = raw.strip().upper()
        if not code.isascii() or not code.isalnum() or code == "REGEN":
            raise ValueError("Invalid diagnostic session code")
        return code


class SessionRead(SessionCode):
    id: int
    user_id: int
    status: Literal["waiting", "joined"]
    created_at: datetime
    expires_at: datetime
    master_joined_at: datetime | None


def read(row: RemoteSession) -> SessionRead:
    return SessionRead.model_validate(row.model_dump())


def find(code: str, session: SessionDep) -> RemoteSession:
    row = session.exec(select(RemoteSession).where(RemoteSession.session_code == code)).first()
    expiry = row.expires_at.replace(tzinfo=timezone.utc) if row else None
    if row is None or expiry <= datetime.now(timezone.utc):
        raise HTTPException(status_code=404, detail="Session not found or expired")
    return row


@router.post("/sessions", response_model=SessionRead, status_code=201)
def create_session(user: ActiveUser, session: SessionDep) -> SessionRead:
    # Generate codes on the server; lab identifiers cannot be claimed by clients.
    for _ in range(8):
        row = RemoteSession(user_id=user.id, session_code="".join(secrets.choice(ALPHABET) for _ in range(7)),
                            expires_at=datetime.now(timezone.utc) + timedelta(hours=24))
        session.add(row)
        try:
            session.commit()
        except IntegrityError:
            session.rollback()
            continue
        session.refresh(row)
        return read(row)
    raise HTTPException(status_code=503, detail="Could not allocate session code")


@router.get("/sessions/{session_code}", response_model=SessionRead)
def get_session(session_code: str, user: ActiveUser, session: SessionDep) -> SessionRead:
    try:
        code = SessionCode(session_code=session_code).session_code
    except ValueError as error:
        raise HTTPException(status_code=422, detail="Invalid diagnostic session code") from error
    return read(find(code, session))


@router.post("/sessions/join", response_model=SessionRead)
def join_session(body: SessionCode, user: ActiveUser, session: SessionDep) -> SessionRead:
    row = find(body.session_code, session)
    row.status = "joined"
    row.master_joined_at = datetime.now(timezone.utc)
    session.add(row)
    session.commit()
    session.refresh(row)
    return read(row)
