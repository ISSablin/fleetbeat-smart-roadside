from dataclasses import replace
from pathlib import Path

from fastapi.testclient import TestClient
import pytest
from sqlmodel import Session, select

from app.auth.security import hash_password
from app.config import Settings
from app.main import create_app
from app.models import User


@pytest.fixture
def account(tmp_path: Path):
    settings = Settings(data_dir=tmp_path)
    app = create_app(settings)
    with TestClient(app) as client:
        with Session(app.state.engine) as session:
            session.add(User(login="tech@example.com", password_hash=hash_password("test123")))
            session.commit()
        yield app, client, settings


def sign_in(client):
    return client.post("/api/v1/auth/login", json={"login": "tech@example.com", "password": "test123"})


def test_login_profile_and_persisted_key(account):
    app, client, settings = account
    response = sign_in(client)
    assert response.status_code == 200
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["expires_in"] == 86400
    headers = {"Authorization": "Bearer " + body["access_token"]}
    assert client.get("/api/v1/auth/me", headers=headers).json() == {
        "id": 1, "login": "tech@example.com", "is_active": True}
    with TestClient(create_app(settings)) as restarted:
        assert restarted.get("/api/v1/auth/me", headers=headers).status_code == 200
    assert (settings.data_dir / "keys/jwt-private.pem").stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize("login,password", [("tech@example.com", "wrong"), ("missing@example.com", "test123")])
def test_bad_credentials(account, login, password):
    _, client, _ = account
    response = client.post("/api/v1/auth/login", json={"login": login, "password": password})
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


def test_invalid_input(account):
    _, client, _ = account
    for login, password in [("not-email", "test"), ("tech@example.com", ""), ("tech@example.com", "é" * 37)]:
        assert client.post("/api/v1/auth/login", json={"login": login, "password": password}).status_code == 422


def test_missing_tampered_and_expired_tokens(account):
    _, client, settings = account
    assert client.get("/api/v1/auth/me").status_code == 401
    assert client.get("/api/v1/auth/me", headers={"Authorization": "Bearer garbage"}).status_code == 401
    with TestClient(create_app(replace(settings, token_ttl_seconds=-1))) as expired:
        token = sign_in(expired).json()["access_token"]
        assert expired.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + token}).status_code == 401


def test_inactive_account_and_disabled_existing_session(account):
    app, client, _ = account
    token = sign_in(client).json()["access_token"]
    with Session(app.state.engine) as session:
        user = session.exec(select(User)).one()
        user.is_active = False
        session.add(user)
        session.commit()
    assert sign_in(client).status_code == 403
    assert client.get("/api/v1/auth/me", headers={"Authorization": "Bearer " + token}).status_code == 403


def test_account_command_creates_hashed_user_and_rejects_duplicates(tmp_path: Path):
    import os
    import subprocess
    import sys

    environment = dict(os.environ, ROADSIDE_DATA_DIR=str(tmp_path))
    command = [sys.executable, "-m", "app.manage", "create-user", "Tech@example.com", "--password-stdin"]
    directory = Path(__file__).resolve().parents[1]
    result = subprocess.run(command, cwd=directory, env=environment, input="test123\n", text=True, capture_output=True)
    assert result.returncode == 0, result.stderr
    application = create_app(Settings(data_dir=tmp_path))
    with TestClient(application) as client:
        assert sign_in(client).status_code == 200
        with Session(application.state.engine) as session:
            user = session.exec(select(User)).one()
            assert user.login == "tech@example.com"
            assert user.password_hash.startswith("$2")
            assert user.password_hash != "test123"
    duplicate = subprocess.run(command, cwd=directory, env=environment, input="different\n", text=True, capture_output=True)
    assert duplicate.returncode == 1
    assert "Account already exists" in duplicate.stderr
