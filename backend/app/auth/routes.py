from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
import jwt
from pydantic import BaseModel, EmailStr, Field, SecretStr, field_validator
from sqlmodel import Session, select

from app.auth.security import hash_password, verify_password
from app.db import get_session
from app.models import User

router = APIRouter(prefix="/api/v1/auth", tags=["authentication"])
SessionDep = Annotated[Session, Depends(get_session)]
bearer = HTTPBearer(auto_error=False)
dummy_hash = hash_password("not-a-real-account")


def unauthorized() -> HTTPException:
    return HTTPException(status_code=401, detail="Invalid or expired credentials",
                         headers={"WWW-Authenticate": "Bearer"})


class LoginRequest(BaseModel):
    login: EmailStr = Field(max_length=128)
    password: SecretStr

    @field_validator("password")
    @classmethod
    def valid_password_length(cls, value: SecretStr) -> SecretStr:
        if not 1 <= len(value.get_secret_value().encode("utf-8")) <= 72:
            raise ValueError("Password must contain between 1 and 72 UTF-8 bytes")
        return value


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int


class UserResponse(BaseModel):
    id: int
    login: str
    is_active: bool


@router.post("/login", response_model=TokenResponse)
def login(body: LoginRequest, request: Request, session: SessionDep) -> TokenResponse:
    user = session.exec(select(User).where(User.login == str(body.login).casefold())).first()
    valid = verify_password(body.password.get_secret_value(), user.password_hash if user else dummy_hash)
    if user is None or not valid:
        raise unauthorized()
    if not user.is_active:
        raise HTTPException(status_code=403, detail="Account is inactive")
    return TokenResponse(access_token=request.app.state.tokens.issue(user.id),
                         expires_in=request.app.state.settings.token_ttl_seconds)


def current_user(request: Request, session: SessionDep,
                 credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> User:
    if credentials is None:
        raise unauthorized()
    try:
        user_id = request.app.state.tokens.user_id(credentials.credentials)
    except jwt.InvalidTokenError:
        raise unauthorized()
    user = session.get(User, user_id)
    if user is None:
        raise unauthorized()
    if not user.is_active:
        raise HTTPException(status_code=403, detail="Account is inactive")
    return user


@router.get("/me", response_model=UserResponse)
def me(user: Annotated[User, Depends(current_user)]) -> UserResponse:
    return UserResponse(id=user.id, login=user.login, is_active=user.is_active)
