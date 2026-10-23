# Assistant report upload contract

## Current wire format

The external API was verified on 2026-10-22: POST returns 201; PATCH /{serverId} returns 200; GET round-trips the submitted data. `companyId`, vehicleModel and vin are supplied on creation. The configured test VIN `TESTVIN1233456566` is accepted despite having 16 characters.

Unknown profile, adapterVersion, adapterVoltage, ecuCapturedLabel, measurement values and fault status remain explicit nulls. Arrays are always present: empty values/faults/turns/causes are valid. An empty fault section’s codes means that ECU reported no matching faults; an empty faults array means no fault response was captured. Never create fake readings to fill an array.

`fleetbeat-assistant-report.example.json` is an example of the actual external request. The proposed schema/example files describe the richer local/prepared model, not additional fields required from the external API. Local-only `id`, clientReportId and reportRevision are not sent as external resource IDs.

The external shape keeps vehicleModel and vin. Adapter name and bus speed are retained in notes. Severity and action are exported as dedicated fields. Recommended stations are exported in `turns[].recommendedStations`; local JSON retains the original assessment object. Location, category context and service notices remain in body text.

## Mobile relay and identity

The phone sends the full canonical local report to `POST /api/v1/assistant-reports/sync` on its account backend using its user JWT. The relay validates company and user access. The demo company is e2fc8a58-4981-40d7-bbaa-93566b200711; other company IDs are rejected. The FleetBeat API key stays on the server.

The relay’s `report-uploads.sqlite` ledger maps (user ID, local report ID) to the server-generated UUID and last published content revision. It records a sending state before POST. A repeated acknowledged snapshot returns the saved receipt without another external request. Later snapshots PATCH the known server ID; stale snapshots cannot overwrite newer published content. No client-side server-ID replacement or local filename change occurs.

A timeout or process failure during creation can leave an uncertain outcome; the relay holds that record for confirmation rather than blindly POSTing again. Full-snapshot PATCH to a known server ID can be retried. Lost phone responses are recovered through the relay ledger. For this demo there is no automatic reconciliation of ambiguous creates or a server management UI; inspect the ledger and confirm the external record before resolving such a row.

## Local queue

Local report files are the durable pending content. Upload receipts/fingerprints are stored in a separate private, account-scoped directory. Updates are coalesced; pending uploads retry while the app is running and signed in, resume on sign-in/restart, and can be retriggered from report details. Missing vehicle information is shown for correction, not fabricated. Upload errors never prevent a local save. Deleting a local file does not delete its external copy.

## Server configuration

Set ROADSIDE_REPORT_UPLOAD_ENABLED, ROADSIDE_REPORT_UPLOAD_URL, ROADSIDE_REPORT_UPLOAD_METHOD=POST, and ROADSIDE_REPORT_UPLOAD_API_KEY on the backend. Subsequent updates automatically use PATCH. Authentication toward FleetBeat is `Authorization: Api-Key <key>`. HTTPS is required and redirects are disabled. Keep the key out of committed files and the APK.


## Formal incident assessment fields

Verified additive extension supported by the external API. Every external report and each item in `turns` includes `incidentSeverity` and `recommendedAction`. Keys are sent explicitly, with null when there is no assessment. Existing local reports already retain the typed assessment object; no parsing of AI prose is used to produce these fields.

- `incidentSeverity`: LOW, MODERATE, HIGH, CRITICAL, or null.
- `recommendedAction`: STOP_IMMEDIATELY, PROCEED_WITH_CAUTION, REPORT_ON_ARRIVAL, SEEK_SERVICE, or null.

Report-level fields mirror the latest AI turn. Each turn preserves its own historical assessment. A report without AI turns, or a latest turn without an assessment, has null summary fields. The recommended UI labels are Low / Moderate / High / Critical, and Stop immediately when safe / Proceed with caution / Report on arrival / Seek service. Values are AI assessments, not observed ECU severity or authorization to drive.

The populated example and `fleetbeat-assistant-report.schema.json` define the proposed wire format for the endpoint author. The station-array extension is prepared locally and awaits API acceptance before deployment. Wire format v3 removes appended severity/action and station details from body text. The upload fingerprint/revision includes a v3 prefix so existing records will refresh through PATCH after deployment.

## Recommended stations (pending API support)

Each turn includes `recommendedStations`, an array in recommendation order, or `[]` when no stations were found or no assessment exists. Station keys are `id`, `name`, `category`, `lat`, `lng`, `address`, `city`, `state`, `phone`, `website`, and `distance_km`. Optional contact/address/distance values are explicit nulls. Coordinates and distance are numbers; phone is a string. Each turn preserves its own station recommendations; the latest turn supplies the current list. No root-level station list is duplicated. The relay does not append severity, action, or station details to `body`; diagnostic prose itself remains unchanged.
