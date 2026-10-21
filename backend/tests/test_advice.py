import httpx
from fastapi.testclient import TestClient
import pytest
from sqlmodel import Session, select

from app.auth.security import hash_password
from app.config import Settings
from app.main import create_app
from app.models import User


class Gateway:
    def __init__(self):
        self.calls = []
        self.response = httpx.Response(200, json={"text": "Read-only checks", "stub": False})
        self.error = None

    async def post(self, url, *, headers, json):
        self.calls.append((url, headers, json))
        if self.error:
            raise self.error
        return self.response


@pytest.fixture
def advice(tmp_path):
    app = create_app(Settings(data_dir=tmp_path, ai_gateway_url="https://gateway.example", ai_client_token="server-only-test-token"))
    with TestClient(app) as client:
        with Session(app.state.engine) as db:
            db.add(User(login="tech@example.com", password_hash=hash_password("test123")))
            db.commit()
        token = client.post("/api/v1/auth/login", json={"login": "tech@example.com", "password": "test123"}).json()["access_token"]
        gateway = Gateway()
        app.state.ai_http = gateway
        yield app, client, {"Authorization": "Bearer " + token}, gateway


def ask(client, headers, text="Explain a coolant sensor fault"):
    return client.post("/api/v1/help-wizard/advice", headers=headers,
                       json={"entry": "freeform", "locale": "eng", "user_text": text})


def test_bounded_gateway_contract_and_no_secret_in_reply(advice):
    _, client, headers, gateway = advice
    reply = ask(client, headers)
    assert reply.status_code == 200
    assert reply.json()["body"] == "Read-only checks" and reply.json()["stub"] is False
    url, auth, body = gateway.calls[0]
    assert url == "https://gateway.example/v1/complete"
    assert auth == {"Authorization": "Bearer server-only-test-token"}
    assert body["max_tokens"] == 1024
    assert body["prompt"] == "Explain a coolant sensor fault"
    assert "regeneration" in body["system"] and "tool" in body["system"]
    assert "tools" not in body and "model" not in body
    assert "server-only-test-token" not in reply.text


def test_auth_and_input_limits_prevent_gateway_calls(advice):
    app, client, headers, gateway = advice
    assert ask(client, {}).status_code == 401
    for text in ["", "  ", "x" * 601]:
        assert ask(client, headers, text).status_code == 422
    with Session(app.state.engine) as db:
        user = db.exec(select(User)).one()
        user.is_active = False
        db.add(user); db.commit()
    assert ask(client, headers).status_code == 403
    assert gateway.calls == []


@pytest.mark.parametrize("response,expected", [
    (httpx.Response(429), 503),
    (httpx.Response(500, text="private gateway error"), 502),
    (httpx.Response(200, json={"text": "Echo", "stub": True}), 503),
    (httpx.Response(200, json={"text": "", "stub": False}), 502),
    (httpx.Response(200, json={"text": "x" * 12001, "stub": False}), 502),
    (httpx.Response(200, text="not JSON"), 502),
    (httpx.Response(200, json=[]), 503),
])
def test_failed_or_stub_response_is_not_generated_guidance(advice, response, expected):
    _, client, headers, gateway = advice
    gateway.response = response
    result = ask(client, headers)
    assert result.status_code == expected
    assert "private gateway error" not in result.text


@pytest.mark.parametrize("error,expected", [(httpx.ReadTimeout("provider timeout"), 504),
                                            (httpx.ConnectError("private connection detail"), 502)])
def test_gateway_transport_errors(advice, error, expected):
    _, client, headers, gateway = advice
    gateway.error = error
    result = ask(client, headers)
    assert result.status_code == expected
    assert "private" not in result.text


def test_missing_configuration_is_explicit(advice):
    app, client, headers, gateway = advice
    app.state.settings = Settings(data_dir=app.state.settings.data_dir)
    assert ask(client, headers).status_code == 503
    assert gateway.calls == []


def test_server_settings_do_not_display_secret(monkeypatch):
    monkeypatch.setenv("ROADSIDE_AI_GATEWAY_URL", "https://gateway.example")
    monkeypatch.setenv("ROADSIDE_AI_CLIENT_TOKEN", "private-test-token")
    settings = Settings.from_env()
    assert settings.ai_client_token == "private-test-token"
    assert "private-test-token" not in repr(settings)
