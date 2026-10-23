from pathlib import Path

from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app.config import Settings
from app.main import create_app
from app.models import User


def test_health_initializes_database_and_uses_product_title(tmp_path: Path):
    settings = Settings(data_dir=tmp_path / "data")
    application = create_app(settings)
    assert not settings.database_path.exists()

    with TestClient(application) as client:
        assert settings.database_path.is_file()
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json() == {
            "status": "ok",
            "service": "FleetBeat Smart Roadside Assistant",
            "database": "ok",
        }
        assert client.get("/openapi.json").json()["info"]["title"] == settings.app_name
        with Session(application.state.engine) as session:
            assert session.exec(select(User)).all() == []


def test_user_persists_across_application_restarts(tmp_path: Path):
    settings = Settings(data_dir=tmp_path)
    first = create_app(settings)
    with TestClient(first):
        with Session(first.state.engine) as session:
            session.add(User(login="fixture@example.test", password_hash="test-fixture-only"))
            session.commit()

    second = create_app(settings)
    with TestClient(second) as client:
        assert client.get("/health").status_code == 200
        with Session(second.state.engine) as session:
            users = session.exec(select(User)).all()
            assert len(users) == 1
            assert users[0].login == "fixture@example.test"
            assert users[0].is_active is True


def test_environment_selects_storage_directory(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("ROADSIDE_DATA_DIR", str(tmp_path / "custom"))
    application = create_app()
    with TestClient(application) as client:
        assert client.get("/health").status_code == 200
        assert (tmp_path / "custom" / "roadside.db").is_file()


def test_health_reports_database_failure(tmp_path: Path):
    application = create_app(Settings(data_dir=tmp_path))
    with TestClient(application) as client:
        # Real SQLite failure: replace the engine with a read-only connection
        # to a file that does not exist. Restore it for normal app cleanup.
        from sqlmodel import create_engine

        original = application.state.engine
        unavailable = create_engine(
            "sqlite:///file:" + str(tmp_path / "missing.db") + "?mode=ro&uri=true",
            connect_args={"check_same_thread": False},
        )
        application.state.engine = unavailable
        try:
            response = client.get("/health")
            assert response.status_code == 503
            assert response.json() == {"detail": "Database unavailable"}
        finally:
            application.state.engine = original
            unavailable.dispose()
