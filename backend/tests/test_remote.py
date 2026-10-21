from datetime import datetime, timedelta, timezone

from fastapi.testclient import TestClient
import pytest
from sqlmodel import Session, select

from app.auth.security import hash_password
from app.config import Settings
from app.main import create_app
from app.models import RemoteSession, User


@pytest.fixture
def remote(tmp_path):
    settings = Settings(data_dir=tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        with Session(app.state.engine) as db:
            db.add(User(login="tech@example.com", password_hash=hash_password("test123")))
            db.commit()
        token = client.post("/api/v1/auth/login", json={"login": "tech@example.com", "password": "test123"}).json()["access_token"]
        yield app, client, {"Authorization": "Bearer " + token}, settings


def test_create_join_and_persistence(remote):
    app, client, headers, settings = remote
    created = client.post("/api/v1/remote/sessions", headers=headers)
    assert created.status_code == 201
    body = created.json()
    code = body["session_code"]
    assert len(code) == 7 and body["status"] == "waiting"
    joined = client.post("/api/v1/remote/sessions/join", json={"session_code": " " + code.lower() + " "}, headers=headers)
    assert joined.status_code == 200
    assert joined.json()["status"] == "joined" and joined.json()["master_joined_at"]
    with TestClient(create_app(settings)) as restarted:
        assert restarted.get(f"/api/v1/remote/sessions/{code}", headers=headers).json()["id"] == body["id"]


def test_authentication_required_and_inactive_user_rejected(remote):
    app, client, headers, _ = remote
    assert client.post("/api/v1/remote/sessions").status_code == 401
    assert client.post("/api/v1/remote/sessions/join", json={"session_code": "DIAG"}).status_code == 401
    assert client.get("/api/v1/remote/sessions/DIAG").status_code == 401
    with Session(app.state.engine) as db:
        user = db.exec(select(User)).one()
        user.is_active = False
        db.add(user); db.commit()
    assert client.post("/api/v1/remote/sessions", headers=headers).status_code == 403


def test_unknown_expired_and_procedure_codes(remote):
    app, client, headers, _ = remote
    for code in ["REGEN", "bad/code", "a", "x" * 17, "АБВГ"]:
        assert client.post("/api/v1/remote/sessions/join", json={"session_code": code}, headers=headers).status_code == 422
    assert client.post("/api/v1/remote/sessions/join", json={"session_code": "NONE"}, headers=headers).status_code == 404
    code = client.post("/api/v1/remote/sessions", headers=headers).json()["session_code"]
    with Session(app.state.engine) as db:
        row = db.exec(select(RemoteSession)).one()
        row.expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        db.add(row); db.commit()
    assert client.get(f"/api/v1/remote/sessions/{code}", headers=headers).status_code == 404
    assert client.post("/api/v1/remote/sessions/join", json={"session_code": code}, headers=headers).status_code == 404
