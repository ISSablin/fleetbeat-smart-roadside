from datetime import datetime, timedelta, timezone
import os

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from app.config import Settings


class TokenService:
    def __init__(self, settings: Settings):
        self.settings = settings
        path = settings.data_dir / "keys" / "jwt-private.pem"
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not path.exists():
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            encoded = key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
            # Exclusive creation prevents a second process from replacing the key.
            try:
                with open(path, "xb") as stream:
                    os.chmod(path, 0o600)
                    stream.write(encoded)
            except FileExistsError:
                pass
        self.private_key = serialization.load_pem_private_key(path.read_bytes(), password=None)
        if not isinstance(self.private_key, rsa.RSAPrivateKey):
            raise ValueError("JWT signing key must be RSA")
        self.public_key = self.private_key.public_key()

    def issue(self, user_id: int) -> str:
        now = datetime.now(timezone.utc)
        return jwt.encode(
            {"sub": str(user_id), "typ": "user", "iss": self.settings.jwt_issuer,
             "aud": self.settings.jwt_audience, "iat": now,
             "exp": now + timedelta(seconds=self.settings.token_ttl_seconds)},
            self.private_key, algorithm="RS256",
        )

    def user_id(self, token: str) -> int:
        payload = jwt.decode(
            token, self.public_key, algorithms=["RS256"],
            issuer=self.settings.jwt_issuer, audience=self.settings.jwt_audience,
            options={"require": ["sub", "typ", "iss", "aud", "iat", "exp"]},
        )
        if payload["typ"] != "user" or not payload["sub"].isdigit():
            raise jwt.InvalidTokenError("Invalid user subject")
        user_id = int(payload["sub"])
        if not 0 < user_id < 2**63:
            raise jwt.InvalidTokenError("Invalid user subject")
        return user_id
