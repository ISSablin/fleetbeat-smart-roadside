import bcrypt


def hash_password(password: str) -> str:
    raw = password.encode("utf-8")
    if not raw or len(raw) > 72:
        raise ValueError("Password must contain between 1 and 72 UTF-8 bytes")
    return bcrypt.hashpw(raw, bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except ValueError:
        return False
