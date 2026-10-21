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

The app supports sign-in, profile refresh, sign-out and truck selection. Passwords are not persisted. Android session tokens are encrypted with a device Keystore key, excluded from backup, and checked against the server when the app starts.

## Vehicle catalog

After signing in, choose **Select vehicle**. Select a truck brand, model, year and control unit. Each list supports search. Back returns to the previous list and preserves its search; backing out of the brand list returns to the account screen. A selected vehicle and unit remain available during activity recreation; they are not persisted across process termination. Signing out clears the selection.

The account API defaults to `https://api3.truck-inspect.com`; the vehicle catalog defaults to `https://cardb3.truck-inspect.com`. Both use the same signed-in bearer token. Override the catalog address independently with `-PcarApiBaseUrl=https://your-car-api.example.com`. Alternate services must trust the account API's signing keys and token issuer.

The vehicle picker browses catalog information only. Selecting a control unit does not connect to an adapter or send commands to a vehicle.

Run mobile catalog tests on your computer without an emulator:

```bash
cd mobile
./gradlew :shared:testAndroidHostTest
```

## Bluetooth adapter and truck broadcasts

Open **Adapter & live data** from the account screen or selected control-unit summary. Power the ITD/ELD BLE adapter, disconnect other apps from it, and choose **Scan adapters**. Select the discovered adapter to connect.

Android 12 and later require Bluetooth scan/connect permissions. Android 11 and earlier require Location permission and enabled Location services for BLE scanning. The app requests these permissions when you scan. Location coordinates are not collected.

The adapter exposes its firmware and voltage independently of CAN bus data. Choose the bus speed (250 or 500 kbit/s), then **Start listening**. Listening starts only after the adapter confirms the requested configuration. The app uses 29-bit J1939 listen mode and does not send diagnostic requests or fault-clearing commands to the truck.

Supported broadcast measurements include engine RPM, vehicle speed, coolant temperature, vehicle voltage, engine hours and total distance. Unavailable data remains unavailable; measurements older than five seconds are marked stale. Active DM1 fault reports include source address, SPN, FMI and occurrence count when supplied. Multi-packet BAM reports are assembled separately for each source; legacy SPN encodings are identified without guessing their SPN.

A Bluetooth connection does not prove the truck is broadcasting. If the frame count remains zero, check adapter wiring, ignition, the selected speed and whether the connected ECU sends J1939. Catalog selection does not turn a truck-wide broadcast into a measurement from the selected control unit.

**Stop listening** stops the stream. **Disconnect**, leaving the adapter screen or signing out releases the connection. The app does not run a background diagnostic service.


## Remote diagnostics

Open **Remote diagnostics** from the account screen. Enter the session code supplied by the vehicle-side app, then **Join session**. **Use DIAG simulator** fills the shared diagnostic simulator code; its readings are simulated and its listening settings are shared with other clients.

The technician app first joins through the authenticated account API, then connects to MQTT with the current account login and access token. It subscribes to `adapter/{code}/up` and sends adapter commands to `adapter/{code}/down`. A matching adapter status response is required before the screen shows a connection. **Check adapter** sends another status request. The last response age and received/sent byte counts help distinguish a reachable broker from a responding adapter.

Choose the remote CAN speed and **Start listening** to view J1939 broadcasts and DM1 faults. Decoding and stale-value handling are shared with local Bluetooth. No fault-clearing, actuator, DPF regeneration or security-access procedures are included. Vehicle-specific requested diagnostics are not available yet.

This app provides the technician side. A vehicle-side app or simulator must already be hosting the session. The default broker is `cardb3.truck-inspect.com:8822`, using the existing deployment's MQTT/TCP transport. Override it at build time with `-PmqttHost=your-broker.example.com -PmqttPort=1883`; this is independent of `-PapiBaseUrl`. Alternate brokers must authenticate the API's tokens and authorize session topics. Broker authentication/authorization configuration is not bundled with the backend here.

**Stop listening** waits for an adapter response. **Disconnect**, Back and sign-out release the MQTT connection and clear remote readings. When a stream has been requested, disconnect attempts to stop it before closing; loss of network connectivity can prevent that stop command from reaching the remote side. There is no automatic reconnect or background diagnostic service.

The backend exposes authenticated `POST /api/v1/remote/sessions`, `GET /api/v1/remote/sessions/{code}` and `POST /api/v1/remote/sessions/join`. Creation allocates a server-generated code valid for 24 hours; joining records the join time. These routes store metadata only. Session codes identify MQTT topics; they do not replace broker authorization. The local backend seeds no simulator sessions. Mobile defaults continue to use the existing deployed API and DIAG simulator.
