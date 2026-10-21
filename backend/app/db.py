from collections.abc import Generator

from fastapi import Request
from sqlalchemy.engine import Engine, URL
from sqlmodel import Session, SQLModel, create_engine

from app.config import Settings
from app import models  # Register table definitions before create_all().


def open_database(settings: Settings) -> Engine:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    engine = create_engine(
        URL.create("sqlite", database=str(settings.database_path)),
        connect_args={"check_same_thread": False},
    )
    try:
        SQLModel.metadata.create_all(engine)
    except Exception:
        engine.dispose()
        raise
    return engine


def get_session(request: Request) -> Generator[Session, None, None]:
    with Session(request.app.state.engine) as session:
        yield session
