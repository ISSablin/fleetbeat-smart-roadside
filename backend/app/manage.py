"""Local account administration: python -m app.manage create-user EMAIL."""
import argparse
import getpass
import sys

from pydantic import EmailStr, TypeAdapter, ValidationError
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session

from app.auth.security import hash_password
from app.config import Settings
from app.db import open_database
from app.models import User


def main() -> None:
    parser = argparse.ArgumentParser(description="FleetBeat Smart Roadside Assistant account administration")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create-user")
    create.add_argument("email")
    create.add_argument("--password-stdin", action="store_true",
                        help="Read password from stdin instead of a hidden prompt")
    args = parser.parse_args()
    try:
        login = str(TypeAdapter(EmailStr).validate_python(args.email)).casefold()
        if len(login) > 128:
            raise ValueError("Email is too long")
        password = sys.stdin.readline().rstrip("\r\n") if args.password_stdin else getpass.getpass("Password: ")
        encoded = hash_password(password)
    except (ValueError, ValidationError) as exc:
        parser.exit(1, f"Invalid email or password: {exc}\n")
    engine = open_database(Settings.from_env())
    try:
        with Session(engine) as session:
            session.add(User(login=login, password_hash=encoded))
            try:
                session.commit()
            except IntegrityError:
                session.rollback()
                parser.exit(1, "Account already exists\n")
        print(f"Created account: {login}")
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
