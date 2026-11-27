# LedgerFlow

Offline-first personal finance, expense tracking, budgeting, multi-account ledger, and investment analytics Android app with optional Supabase cloud sync, a lightweight Node.js/TypeScript companion backend, and verified in-app APK self-updates.

---

## Download Android App (APK)

Install the latest signed release directly on Android (`minSdk 26` / Android 8.0+):

- **Direct APK Download (`v1.6.0`)**: [**Download `LedgerFlow-v1.6.0.apk`**](https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.6.0/LedgerFlow-v1.6.0.apk)
- **All Releases**: [github.com/itripathiharsh/LedgerFlow/releases/latest](https://github.com/itripathiharsh/LedgerFlow/releases/latest)
- **Auto-Update Manifest**: [`update-manifest.json`](https://github.com/itripathiharsh/LedgerFlow/releases/latest/download/update-manifest.json)

| Artifact | Value |
| --- | --- |
| Application ID | `com.ledgerflow.app` |
| Version | `1.6.0` (`versionCode: 17`) |
| Distribution Flavor | `direct` (Play Protect sideload-safe — zero SMS, Accessibility, or NotificationListener permissions) |
| Min / Target SDK | `26` / `36` |
| APK File Size | `4,858,239` bytes (~`4.63 MiB`) |
| APK SHA-256 | `5343df64b2f38614c648f85f2f716753fb67ca81707f0af9dcc76759f388800a` |
| Signing Cert SHA-256 (`v2` + `v3` RSA-2048) | `40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0` |

### Verifying & Installing the APK
1. Download [`LedgerFlow-v1.6.0.apk`](https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.6.0/LedgerFlow-v1.6.0.apk) on your Android device (future updates are delivered and installed automatically in-app).
2. When prompted, allow installing unknown apps for your browser or file manager.
3. Optional checksum check before install:
   ```bash
   sha256sum LedgerFlow-v1.6.0.apk
   # Expected: 5343df64b2f38614c648f85f2f716753fb67ca81707f0af9dcc76759f388800a
   ```

---

## Key Architecture & Features

- **Offline-First Core (`android/`)**: Local-first Room SQLite ledger with FTS4 transaction search, WorkManager background sync & update checks, and Material 3 Compose UI across 7 screens starting cleanly at zero balance.
- **Pay & Track Auto-Capture**: Zero-permission **Share-to-Track** (`ACTION_SEND`) & **Smart Paste** UPI/card/wallet text parser (`PayAndTrackCapture.kt`) in the `direct` APK (plus optional `NotificationListenerService` in the `playStore` flavor), with user-configurable merchant/category auto-rules and 60s duplicate suppression.
- **Security & Encrypted Storage**: AES-256-GCM `VaultStore` (`VaultSecurity.kt`), live ECB FX caching (`api.frankfurter.dev`), strict release AdMob gating (`OnlineAdBanner.kt`), and zero embedded backend service-role secrets.
- **Verified In-App Auto-Updater**: Checks on launch and every 12h via WorkManager (`UpdateCheckWorker.kt`), modal dialog (**Update Now** / **Later** in `UpdateDialog.kt`), SHA-256 + signing certificate pinning verification (`AppUpdateManager.kt`, `AppUpdatePolicy.kt`), and `PackageInstaller` session restart flow (`UpdateInstallReceivers.kt`).
- **Cloud Sync & Database (`supabase/`)**: Bidirectional incremental Supabase sync with Row-Level Security policies (`0001_ledgerflow_core.sql`, `0002_rls_policies.sql`).
- **Companion Backend (`backend/`, `render.yaml`)**: Optional Node.js + TypeScript Express API (`/health`, `/v1/fx/rates`, `/v1/enrich`, `/v1/import/csv`, and env-configured `/v1/app/update`).

---

## Repository Structure

```text
LedgerFlow/
├── android/                  # Kotlin + Jetpack Compose Android client (Room, WorkManager, Supabase sync, Auto-Updater)
├── backend/                  # Express + TypeScript companion API for Render free tier
├── supabase/
│   └── migrations/           # PostgreSQL schema & RLS policy migrations
├── render.yaml               # Render blueprint configuration for backend deployment
├── context.md                # Technical architecture, audit log, and release notes
└── buisness.md               # Product and monetization decisions
```

---

## Local Development Setup

### 1. Android App (`android/`)
Configure optional overrides in `android/local.properties` (ignored by Git) or via environment variables:

```properties
SUPABASE_URL=https://rzlenoldloktswymuooz.supabase.co
SUPABASE_PUBLISHABLE_KEY=<your-publishable-key>
UPDATE_MANIFEST_URL=https://github.com/itripathiharsh/LedgerFlow/releases/latest/download/update-manifest.json
EXPECTED_SIGNING_CERT_SHA256=40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0
ADMOB_APP_ID=<your-admob-app-id>
ADMOB_BANNER_UNIT_ID=<your-admob-banner-unit-id>
```

Run tests and builds from `android/`:

```bash
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebug
./gradlew assembleRelease
```

### 2. Backend Service (`backend/`)
Copy [`backend/.env.example`](backend/.env.example) to `backend/.env` and customize values:

```bash
cd backend
npm install
npm run build
npm test
npm start
```

### 3. Supabase Schema (`supabase/`)
Apply [`supabase/migrations/0001_ledgerflow_core.sql`](supabase/migrations/0001_ledgerflow_core.sql) and [`supabase/migrations/0002_rls_policies.sql`](supabase/migrations/0002_rls_policies.sql) to your Supabase project before enabling cloud sync.
