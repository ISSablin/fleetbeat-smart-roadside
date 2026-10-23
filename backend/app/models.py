from datetime import datetime, timezone

from sqlmodel import Field, SQLModel


class User(SQLModel, table=True):
    __tablename__ = "users"

    id: int | None = Field(default=None, primary_key=True)
    login: str = Field(index=True, unique=True, max_length=128)
    password_hash: str
    is_active: bool = Field(default=True)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class RemoteSession(SQLModel, table=True):
    __tablename__ = "remote_sessions"

    id: int | None = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="users.id", index=True)
    session_code: str = Field(unique=True, index=True, max_length=16)
    status: str = Field(default="waiting")
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    expires_at: datetime
    master_joined_at: datetime | None = None
