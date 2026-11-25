# LedgerFlow — Business, Monetization & Product Decisions (`buisness.md`)

> Authoritative record of all product, monetization, cost-control, privacy, and compliance decisions for **LedgerFlow**.

---

## 1. Core Product Positioning
- **Value Proposition**: Fast, calm, offline-first personal finance, expense tracking, budgeting, multi-currency wallet, planned bills, investment tracking, and optional Google Wallet auto-capture without bloat or paywalls.
- **Offline-First Guarantee**: Every financial workflow (Room ledger CRUD, FTS4 search, budgets, forecasts, charts, recurring payments, Pay & Track listener, PIN/biometric vault lock, JSON/CSV exports) works **100% locally without internet**. Cloud outages, Render free-tier spin-down delays, or airplane mode never block user access to their financial data.

---

## 2. Pricing & Access Model
- **Launch Pricing**: **100% Free** to every user.
- **No Paywalls / No Usage Caps**:
  - **No** "Pro" subscription tier at launch.
  - **No** limits on number of accounts, budgets, rules, multi-currency wallets, or CSV/JSON exports.
  - **No** paywall on Pay & Track notification capture, Open Banking sandbox sync, or cloud backup.

---

## 3. Monetization Strategy (Google AdMob)
- **Primary Revenue Channel**: Google AdMob advertisements (`play-services-ads` `24.2.0`).
- **Implemented Strict Ad UX & Network Rules**:
  1. **Online-Only Loading**: `AndroidManifest.xml` sets `DELAY_APP_MEASUREMENT_INIT = true`, and `OnlineOnlyAdBanner` (`ui/ads/OnlineAdBanner.kt`) gates `MobileAds.initialize` and `AdRequest` strictly behind `NetworkMonitor.observeOnline`. When offline, **zero UI footprint** and zero retry requests are emitted.
  2. **Minimal & Non-Intrusive Placement**: Compact banner only on secondary scroll content — never blocking transaction entry, calculator keypad, search typing, or Vault lock screens.
  3. **Zero Blocking**: Ad load failures fail silently (`adLoaded = false`) with no blocking dialogs or timers.
  4. **Test-ID Safety Pre-Release**: Current [local.properties](file:///D:/all%20farzi%20project/LedgerFlow/android/local.properties) and `app/build.gradle.kts` default to Google's official AdMob test IDs (`ca-app-pub-3940256099942544~3347511713` and `ca-app-pub-3940256099942544/6300978111`) to prevent invalid-traffic policy violations during development.

---

## 4. Infrastructure & Cost Decisions (Near-Zero Operating Cost)
- **Local Storage**: On-device Room SQLite + FTS4 (`$0/month`).
- **Cloud Database & Auth**: **Supabase Free Tier** — provisioned live project `ledgerflow` (`rzlenoldloktswymuooz`, `ap-south-1` Mumbai, PostgreSQL + Auth + RLS across 8 tables, `$0/month`).
- **Custom Backend APIs**: **Render Free Tier** Web Service blueprint [render.yaml](file:///D:/all%20farzi%20project/LedgerFlow/render.yaml) (`Node.js / TypeScript`) for stateless merchant enrichment, FX rates, CSV parsing, and Open Banking sandbox testing — `$0/month`, treated as optional enrichment so Render sleep delays never affect core UX.
- **Zero Paid External APIs**:
  - Dropped legacy paid Open Banking aggregators (SaltEdge/Yodlee) and paid telemetry/marketing SDKs (Mixpanel/MoEngage/Facebook SDK), keeping release APK size at `4.08 MiB` (`4,280,594` bytes).
  - **Zero-Cost In-App Self-Updating**: Uses our free Render `/v1/app/update` endpoint (or static HTTPS GitHub Releases JSON via `UPDATE_MANIFEST_URL`) + native Android `PackageInstaller` with SHA-256 and APK signing key verification instead of paid distribution/MDM/Firebase App Distribution services.

---

## 5. Privacy & Play Policy Commitments
- Encrypt saved cloud tokens/credentials with `AndroidKeyStore` (`AES/GCM/NoPadding`) and protect PIN verification with salted PBKDF2 (`VaultSecurity.kt`).
- Block cloud backups and device-to-device data extraction (`android:allowBackup="false"` + [data_extraction_rules.xml](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/res/xml/data_extraction_rules.xml)) and protect the screen switcher (`FLAG_SECURE` when blur-in-switcher is enabled).
- Opt-in local-only `NotificationListenerService` (**Pay & Track** parses payment notifications strictly on-device).
- Release traffic strictly enforces HTTPS (`cleartextTrafficPermitted="false"`).
- Full JSON & CSV local export built into `PrivacyVaultScreen`.

---

## 6. Production Release Handoff Checklist (Before Play Store Upload)
1. **Replace Test AdMob IDs**: Set live `ADMOB_APP_ID` and `ADMOB_BANNER_UNIT_ID` in `android/local.properties` or CI secrets, and add Google UMP consent dialog for EEA/UK users.
2. **Release Signing**: Generate an upload keystore on `D:` and wire `signingConfig` into `android/app/build.gradle.kts` (`app-release-unsigned.apk` is built & R8-verified).
3. **Deploy Render Backend**: Connect repository to Render using root `render.yaml` (Free plan) and set `RENDER_API_URL` in `android/local.properties` (defaults to `https://ledgerflow-api.onrender.com`).
4. **Publish Future Releases**: Build a signed APK, compute its SHA-256 and signing certificate fingerprint, and publish via `LATEST_VERSION_CODE`, `LATEST_VERSION_NAME`, `LATEST_APK_URL`, `LATEST_APK_SHA256`, `LATEST_SIGNING_CERT_SHA256`, and `LATEST_RELEASE_NOTES` on Render (or point `UPDATE_MANIFEST_URL` to a free HTTPS GitHub Releases JSON).

---

## 7. Decision Log
| Date | Decision | Rationale |
|---|---|---|
| 2026-11-25 | Offline-first Room SQLite + optional background Supabase/Render sync | Zero latency offline, resilient to free-tier cold starts/outages |
| 2026-11-25 | 100% Free at launch (no subscriptions, no limits) | Maximize user adoption & trust over legacy paywalled trackers |
| 2026-11-25 | Minimal online-only AdMob monetization (`DELAY_APP_MEASUREMENT_INIT`) | Generates revenue with `$0` user cost and zero offline impact |
| 2026-11-25 | Free-tier-only infra (`Supabase ap-south-1` + `Render` blueprint, zero paid SDKs) | Keeps launch operating cost near `$0/month` and APK under `4.1 MiB` |
| 2026-11-25 | Free HTTPS + APK-signature-verified self-updater (`UpdateCheckWorker`, "Update Now" / "Later") | Delivers seamless updates without Play Store lock-in or cost while protecting offline UX & binary trust |
| 2026-11-25 | Added IP rate limiting (120 req/min) & multi-base FX caching on Render backend | Protects free-tier Render hours & bandwidth against burst traffic while offline fallbacks preserve UX |
| 2026-11-25 | Added release signing + UMP consent + device verification gates before Play Store GA | Audit confirmed release APK (`4,280,594` B) is still unsigned with AdMob test IDs and requires live hardware smoke validation |
