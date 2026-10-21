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

Choose the remote CAN speed and **Start listening** to view J1939 broadcasts and DM1 faults. Decoding and stale-value handling are shared with local Bluetooth. No fault-clearing, actuator, DPF regeneration or security-access procedures are included. Requested ECU reads are available for the DIAG engine profile as described below. Other ECU profiles are not supported yet.

This app provides the technician side. A vehicle-side app or simulator must already be hosting the session. The default broker is `cardb3.truck-inspect.com:8822`, using the existing deployment's MQTT/TCP transport. Override it at build time with `-PmqttHost=your-broker.example.com -PmqttPort=1883`; this is independent of `-PapiBaseUrl`. Alternate brokers must authenticate the API's tokens and authorize session topics. Broker authentication/authorization configuration is not bundled with the backend here.

**Stop listening** waits for an adapter response. **Disconnect**, Back and sign-out release the MQTT connection and clear remote readings. When a stream has been requested, disconnect attempts to stop it before closing; loss of network connectivity can prevent that stop command from reaching the remote side. There is no automatic reconnect or background diagnostic service.

The backend exposes authenticated `POST /api/v1/remote/sessions`, `GET /api/v1/remote/sessions/{code}` and `POST /api/v1/remote/sessions/join`. Creation allocates a server-generated code valid for 24 hours; joining records the join time. These routes store metadata only. Session codes identify MQTT topics; they do not replace broker authorization. The local backend seeds no simulator sessions. Mobile defaults continue to use the existing deployed API and DIAG simulator.


## Requested ECU diagnostics

Join **DIAG**, then use **Read ECU values** or **Read ECU fault codes**. Stop broadcast listening first. These are one-shot simulated ECU snapshots, with their read times shown separately from broadcast measurements. Requested values include engine RPM and coolant temperature. Fault records include raw three-byte codes, ECU status bytes and catalog descriptions when available; a failed description lookup preserves the raw records.

The app loads protocol 5935 from the deployed catalog and requires its supported 500 kbit/s, 29-bit CAN address pair: tester TX `18DA00F9`, ECU RX `18DAF900`. It confirms active mode and the exact receive filter before sending any ECU request. The implementation sends only three fixed reads: `22 F4 0C` (RPM), `22 F4 67` (coolant) and `19 02 AF` (fault records). It does not execute catalog initialization blobs or arbitrary commands. Other sessions continue to support broadcasts; selecting a catalog control unit does not enable unsupported requested reads.

ECU replies use classical CAN ISO-TP with normal addressing. The receiver validates message lengths, sequence numbers, source ID and the expected service/DID. It sends flow control for multi-frame responses, limits payloads to 1024 bytes, bounds response-pending replies, and applies transfer/request timeouts. CAN FD, ISO-TP extended addressing and segmented request transmission are not supported.

The DIAG simulator's fault layout is explicitly `59 02` followed by four-byte records (three-byte code and status). It omits the standard UDS status-availability byte. That parser is confined to the DIAG bench profile and must not be reused as a generic UDS fault parser.

After a successful read, the app stops the remote stream. Disconnect, Back, sign-out and broker loss cancel pending ECU reads. A read failure reports an error rather than an empty fault report. Clearing faults, actuator commands, security access and regeneration are not included. Physical ECU requested reads over Bluetooth have not been enabled.


## Diagnostic assistant

Open **Diagnostic assistant** from the account screen. Type a question or choose **Voice input** to speak. Android's installed speech recognition service returns text, which is appended to the question for editing. It may require internet access and its own microphone permission. The app receives text and does not save audio recordings. Cancelled or failed voice input preserves existing text. Questions remain limited to 600 characters; review the result and tap **Get guidance** to send it. The question is sent to the configured account API and its AI provider. No measurements, account details or adapter commands are automatically attached. The signed-in account token authorizes the request; AI gateway credentials and the provider API key stay on the server.

After a requested ECU read, **Ask AI about these results** prepares an editable question containing the simulator/profile context, snapshot ages, requested values and up to five fault records. Opening this preview disconnects the remote session. Review and edit the exact text before sending it; preparing a question does not call the AI service.

The assistant offers explanations and diagnostic checks. It has no connection to the adapter command path and cannot execute AI-suggested procedures. Responses are labelled as AI-generated. Failed requests, server fallbacks and missing AI configuration are shown as errors rather than generated guidance. There are no automatic retries. **Stop waiting** cancels the mobile wait; it cannot cancel work already running on the server. Back and sign-out clear the mobile question and response. The app does not persist an assistant conversation history.

Mobile uses authenticated `POST /api/v1/help-wizard/advice` on `apiBaseUrl`. Its independent request timeout is 75 seconds. The backend implements the freeform English advice subset of that route, requiring active-account authentication and limiting the question to 600 characters and upstream output to 1024 tokens. It requests text completion only, with no executable tools. No regeneration, clearing, actuator or security-access workflow is provided.

## Run the backend with Compose

The Compose file packages the account, session-metadata and diagnostic-advice API. It does not provision the vehicle catalog, MQTT broker, AI provider, administrator UI or realtime chat service. The mobile defaults continue to use the existing deployed services.

On a host with a running Docker engine and Docker Compose, run from the repository root:

```bash
cp .env.example .env
# Edit .env if enabling AI guidance.
docker compose -p fleetbeat-roadside config --quiet
docker compose -p fleetbeat-roadside up -d --build
curl --fail http://127.0.0.1:8000/health
docker compose -p fleetbeat-roadside exec api python -m app.manage create-user technician@example.com
```

The last command prompts for the account password. The stable project name `fleetbeat-roadside` keeps the same named data volume across checkouts. That volume stores SQLite records and signing keys. `docker compose -p fleetbeat-roadside down` stops containers while retaining the volume. Inspect health and startup failures with `docker compose -p fleetbeat-roadside ps` and `docker compose -p fleetbeat-roadside logs --tail=100 api`.

AI guidance is optional. `ROADSIDE_AI_GATEWAY_URL` points to a trusted gateway implementing `POST /v1/complete`. `ROADSIDE_AI_CLIENT_TOKEN` is that gateway's server-to-server client credential, **not the Anthropic API key** and not a technician's login token. The provider key stays on the gateway. With these settings absent, accounts and session routes remain available while advice returns HTTP 503. Real `.env` files are ignored by Git.

The API binds to host loopback port 8000. Put an HTTPS reverse proxy in front of it before connecting remote mobile clients. When changing the account API, configure the catalog and MQTT broker to trust that API's signing keys, issuer and audience before testing those integrations. Validate the new hostname and services before changing mobile `apiBaseUrl`. Compose runs the API only; the catalog, MQTT and AI services require separate configuration.


## Saved diagnostic reports

Remote diagnostic reports save automatically as readings and fault responses arrive. Each connection updates one report. AI sessions also save automatically, including additional typed or voice input and report notes. No manual save action is needed.

Open **Saved reports** from the account screen to review the history. Reports include capture time in UTC, source, adapter details, values, raw fault codes/status bytes and available catalog descriptions. Each reading and fault response retains its age and stale state at capture. DIAG reports are prominently labelled as simulated. An ECU response containing zero matching faults is kept distinct from receiving no fault response.

Reports are stored in private app files on this device, separately for each account and account-API address. Signing out hides reports but retains them for that account's next sign-in. Reports are not uploaded or synced. Up to 100 reports can be saved per account on the device; delete older reports to free capacity. Uninstalling the app removes these private reports. The app reports unreadable/unsupported files without presenting them as valid data.

From a report, choose **Export text** and pick a destination in Android's file picker. The UTF-8 text file includes the source, simulator label, capture-relative ages, values and fault details. Account credentials and remote session codes are not included. Exported copies are separate files in the chosen location and are not removed by **Delete report**, which requires confirmation and removes only the private saved copy. Export does not send a message to another person or call the AI service.

Report saving covers remote diagnostics and AI assistance. Local Bluetooth report capture and cloud history are not included.

AI sessions save automatically from Diagnostic assistant, including ECU snapshots when opened from results, all completed questions and answers, unsent input, and optional user notes. Each AI request sends the current question only; previous answers remain in the local session and report. Reports remain private to the signed-in account on this device and can be exported as text.

Reports save automatically on this device: one report per remote connection and one per AI session, updated with new results, completed AI exchanges, unsent additional input, and report notes. No Save report action is required. Existing reports remain readable. Local storage holds up to 100 reports per account; save failures are shown rather than deleting older reports.

Future FleetBeat upload configuration lives in `.env.example`: `ROADSIDE_REPORT_UPLOAD_ENABLED`, `ROADSIDE_REPORT_UPLOAD_URL`, `ROADSIDE_REPORT_UPLOAD_METHOD` (POST/PUT), and `ROADSIDE_REPORT_UPLOAD_API_KEY`. The backend transport in `backend/app/report_upload.py` sends JSON over HTTPS with X-API-Key and an idempotency key for each report revision. Upload remains disabled and unwired: mobile ingestion, a persistent retry outbox, account authorization, and the FleetBeat endpoint contract will be implemented when upload is enabled. API keys stay on the backend; none are packaged in the APK.
