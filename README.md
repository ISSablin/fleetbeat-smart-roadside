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

The user schema stores a unique email login, bcrypt password hash, active flag and creation timestamp. No accounts are seeded. Create an account locally with the administration command below. RS256 signing keys are generated on startup and persist under the data directory; keep this directory across restarts and out of source control.

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

## Accounts and authentication

Create a user from the backend directory (the password prompt is hidden):

```bash
.venv/bin/python -m app.manage create-user technician@example.com
```

Passwords must be between 1 and 72 UTF-8 bytes. Logins are email addresses, stored case-insensitively.

- `POST /api/v1/auth/login` accepts `{"login":"technician@example.com","password":"..."}` and returns a bearer token valid for 24 hours.
- `GET /api/v1/auth/me` accepts `Authorization: Bearer TOKEN` and returns the active user's profile. Password hashes are never included.
- Missing, invalid or expired credentials return 401; disabled accounts return 403.

Sign-out removes the saved token from the mobile device. It does not revoke copies of that token; they expire after 24 hours. Disabling an account prevents profile access even with an existing token.

## Android app

The Kotlin Multiplatform project is under `mobile/`. Its Android application uses shared Kotlin UI and authentication code. An iOS application is not included yet.

Requires JDK 21, Android SDK Platform 37 and Build Tools 36. Set `JAVA_HOME` and `ANDROID_HOME` for your installation, or configure the SDK in Android Studio. Build:

```bash
cd mobile
./gradlew :androidApp:assembleDebug
```

The app connects to `https://api3.truck-inspect.com` by default. Sign in with an account on that server. Enable USB debugging on your Android device, then use Android SDK platform-tools:

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n com.fleetbeat.roadside/.MainActivity
```

For multiple connected devices, add `-s DEVICE_SERIAL` to each adb command. The default server is reached directly over the device internet connection; no local backend or USB port forwarding is required.

Override the API URL at build time with `-PapiBaseUrl=https://your-api.example.com`. To use a local backend instead, build with `-PapiBaseUrl=http://127.0.0.1:8000`, start that backend, and run `adb reverse tcp:8000 tcp:8000`; local and deployed accounts are separate. HTTP is enabled only in debug builds; release builds require HTTPS. The app ID is `com.fleetbeat.roadside`, so it can coexist with other installed apps.

The app supports sign-in, profile refresh and sign-out. Passwords are not persisted. Android session tokens are encrypted with a device Keystore key, excluded from backup, and checked against the server when the app starts.
