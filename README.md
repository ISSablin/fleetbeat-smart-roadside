# FleetBeat Smart Roadside

## Backend

Requires Python 3.11 or newer; verified with Python 3.12. Dependency versions are pinned in `backend/constraints.txt`. Run from the project root:

```bash
python3.12 -m venv backend/.venv
backend/.venv/bin/python -m pip install -r backend/requirements-dev.txt
cd backend
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

Open http://127.0.0.1:8000/docs for API documentation.

`GET /health` checks database connectivity and returns:

```json
{"status":"ok","service":"FleetBeat Smart Roadside","database":"ok"}
```

Database failures return HTTP 503. The service creates its SQLite database on startup at `backend/data/roadside.db`; importing the application does not create database files. Set `ROADSIDE_DATA_DIR` to override the storage directory. Relative overrides are resolved from the process working directory. Existing records survive restarts. Startup creates missing tables; it does not upgrade existing table schemas.

The user schema stores a unique login, password hash, active flag and creation timestamp. There are currently no account-management or login endpoints and no seeded accounts.

## Tests

```bash
cd backend
.venv/bin/python -m pytest
```

Tests use temporary databases to check startup, health, storage configuration, persistence across application restarts and database failure reporting.

## Docker

```bash
docker build -t fleetbeat-roadside-api ./backend
docker run --rm -p 127.0.0.1:8000:8000 -v fleetbeat-roadside-data:/data fleetbeat-roadside-api
```

The named volume preserves the database when the container is removed. Runtime data and local environment files are excluded from Git.
