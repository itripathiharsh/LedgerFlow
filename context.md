# LedgerFlow — Engineering Context & Work Log

> Living engineering record for **LedgerFlow** (`D:\all farzi project\LedgerFlow`). Every architectural decision, module built, bug fix applied, and verification result is tracked here.
> Storage Rule (`RULES.md`): **100% `D:` drive only** — zero source, caches, SDKs, temp files, or artifacts written to `C:`.

---

## 1. Origin & Reverse-Engineering Foundation
- **Source Reference**: Reverse-engineered `com.droid4you.application.wallet` (`9.3.14`, `versionCode: 90192`) in [WALLET_RE_REPORT.md](file:///D:/REA/targets/wallet_9.3.14/analysis/WALLET_RE_REPORT.md).
- **Design System & Mockups**: Created in Stitch (`projects/15468545163502176659`, asset `assets/787260451962715661` — *LedgerFlow Clean Emerald System*) and exported to [DESIGN_SYSTEM.md](file:///D:/REA/designs/ledgerflow_stitch/DESIGN_SYSTEM.md) / [index.html](file:///D:/REA/designs/ledgerflow_stitch/index.html).

### Legacy Flaws Fixed by Design in LedgerFlow
1. **Database Security & Complexity**: Consolidated legacy multi-store architecture (Couchbase Lite + separate SQLite projection + Room) onto a single **Room SQLite + FTS4** store (`LedgerDatabase`) with WAL mode, atomic multi-table transaction updates, soft-delete tombstones (`deletedAtEpochMs`), and `AndroidKeyStore` AES-256-GCM encryption (`VaultCryptoEngine`) for tokens/credentials plus salted PBKDF2 PIN hashing.
2. **Network & TLS Hygiene**: Disabled cleartext HTTP in release (`res/xml/network_security_config.xml`), separated debug-only loopback rules (`src/debug/res/xml/network_security_config.xml`), disabled Android cloud/device backups (`android:allowBackup="false"` + `res/xml/data_extraction_rules.xml`), and gated API tokens cleanly without blocking interceptor coroutines.
3. **Cold Start, Size & Bloat**: Removed paid/heavy analytics & tracking SDKs and enabled R8 code/resource shrinking on release (`4,280,594` bytes release APK).

---

## 2. Implemented End-to-End Architecture

### A. Offline-First Android App (`D:\all farzi project\LedgerFlow\android`)
- **UI**: Kotlin `2.2.21` + Jetpack Compose + Material 3 (`Clean Emerald` theme in `ui/theme/Theme.kt`).
  - **Main Shell (`MainActivity.kt`)**: `FragmentActivity` host with `FLAG_SECURE` window protection, biometric/PIN lock overlay (`VaultSecurity.kt`), 5-tab bottom bar (`Home`, `Records`, `Budgets`, `Invest`, `Hub`), center `+` FAB (`AddTransactionSheet`), and `PrivacyVaultScreen` access.
  - **7 Core Screens (`ui/screens/`)**: `HomeScreen.kt`, `RecordsScreen.kt`, `AddTransactionSheet.kt` (live math calculator keypad + Auto-Rule hints), `BudgetsScreen.kt`, `AccountsHubScreen.kt`, `InvestScreen.kt`, `PrivacyVaultScreen.kt`.
- **Local Persistence (`data/local/`)**:
  - Entities (`Entities.kt`): `AccountEntity`, `CategoryEntity`, `TransactionEntity`, `TransactionFtsEntity` (`@Fts4`), `BudgetEntity`, `PlannedPaymentEntity`, `HoldingEntity`, `MagicRuleEntity`.
  - Atomic ledger (`LedgerDatabase.kt`): `recordTransactionAtomic`, `softDeleteTransactionAtomic`, and `executePlannedPaymentAtomic` keep balances and FTS4 search synchronized offline.
  - Starter Demo Seed (`SeedData.kt`): Idempotent offline starter accounts, categories, transactions, budgets, planned payments, holdings, and magic auto-rules.
- **Pay & Track Notification Capture (`service/PayAndTrackNotificationListenerService.kt`)**:
  - Opt-in local `NotificationListenerService` parsing payment notifications via `NotificationParser.parseGooglePayNotification` and applying `MagicRuleEngine` directly into SQLite offline.
- **Background Sync & Auto-Updates (`work/SyncWorker.kt`, `work/UpdateCheckWorker.kt`, `update/`, `domain/AppUpdatePolicy.kt`)**:
  - `LedgerFlowApplication` implements `Configuration.Provider` (with `WorkManagerInitializer` disabled via manifest merge marker for reliable on-demand init across devices and Robolectric).
  - `SyncWorker`: periodic (6-hour) and on-change `WorkManager` jobs constrained to `NetworkType.CONNECTED`.
  - Bidirectional incremental sync against Supabase REST endpoints (`updated_at` watermark + conflict handling) plus local JSON vault export/backup.
  - **Automatic App Update System (`AppUpdateManager.kt`, `AppUpdatePolicy.kt`, `UpdateInstallReceivers.kt`, `UpdateDialog.kt`, `UpdateCheckWorker.kt`)**:
    - Periodic 12-hour `WorkManager` update check (`NetworkType.CONNECTED`) + throttled foreground online check (silent and non-interrupting when offline).
    - Enforces HTTPS update URLs in release, `versionCode > installedVersionCode` downgrade protection, streamed SHA-256 checksum comparison (`MessageDigest.isEqual`), and APK signing certificate verification (`GET_SIGNING_CERTIFICATES` / `GET_SIGNATURES`) against the installed app's own signing key (and optional pinned cert digest).
    - In-app `AppUpdateDialog` shows new version, size, security notice, release notes, **"Update Now"** progress/install action, and **"Later"** snooze option (`dismissedUpdateVersionCode`).
    - Streams verified APK into `PackageInstaller.Session` (with `REQUEST_INSTALL_PACKAGES` permission handling and `FileProvider` fallback) and automatically reopens `MainActivity` after installation completes via `AppReplacedReceiver` (`Intent.ACTION_MY_PACKAGE_REPLACED` gated on `pendingUpdateRelaunch`) plus tap-to-open fallback notification on strict BAL devices.
- **Online-Only AdMob Banner (`ui/ads/OnlineAdBanner.kt`)**:
  - `DELAY_APP_MEASUREMENT_INIT = true` in `AndroidManifest.xml`; `AdMobGate.ensureInitializedWhenOnline` and `OnlineOnlyAdBanner` observe `NetworkMonitor.observeOnline` and only initialize/load ads when connectivity exists, collapsing to zero footprint offline.

### B. Cloud Database & Auth (`Supabase` Free Tier)
- **Project**: `ledgerflow` (Ref: `rzlenoldloktswymuooz`, Region: `ap-south-1`, URL: `https://rzlenoldloktswymuooz.supabase.co`).
- **Live Schema Migration**: `20261125084828` (`ledgerflow_schema_v1`) applied across 8 tables: `profiles`, `accounts`, `categories`, `transactions`, `budgets`, `planned_payments`, `holdings`, `magic_rules`, with `public.set_updated_at()` triggers (`SECURITY INVOKER`, empty `search_path`), composite sync indexes, and strict `TO authenticated` RLS (`(select auth.uid())`).
- **Local Reference SQL**: [0001_ledgerflow_core.sql](file:///D:/all%20farzi%20project/LedgerFlow/supabase/migrations/0001_ledgerflow_core.sql), [0002_rls_policies.sql](file:///D:/all%20farzi%20project/LedgerFlow/supabase/migrations/0002_rls_policies.sql).

### C. Custom API Service (`D:\all farzi project\LedgerFlow\backend`, Render Free Tier)
- **Express + TypeScript API (`src/server.ts`, `src/fx.ts`, `src/enricher.ts`, `src/updates.ts`)** configured via [render.yaml](file:///D:/all%20farzi%20project/LedgerFlow/render.yaml):
  - `GET /health`
  - `GET /v1/fx/rates` (FX quotes with offline-safe fallback rates)
  - `GET /v1/app/update` & `GET /v1/app/download/latest.apk` (free self-hosted update manifest + optional APK stream)
  - `POST /v1/enrich` (merchant cleanup & category suggestion)
  - `POST /v1/import/csv` (CSV statement normalization)
  - `POST /v1/open-banking/sandbox/connect` (free-tier Open Banking sandbox bank sync)
- Designed as strictly optional enhancement for the Android app.

---

## 3. Environment Verified on `D:`
- **JDK**: Eclipse Temurin JDK `17.0.21+9` (`D:\dev\jdk`; configured via `JAVA_HOME` + `org.gradle.java.home` instead of PATH JDK 25)
- **Android SDK**: `D:\dev\android-sdk` (`compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`)
- **Gradle User Home & Caches**: `D:\dev\gradle-home` (Gradle `8.14.6`, AGP `8.13.2`, Kotlin `2.2.21`)
- **Temp & npm Cache**: `D:\dev\Temp`, `D:\dev\Cache\npm`

---

## 4. Work Log & Verification Results
- **2026-11-25 13:46 IST**: Initialized `D:\all farzi project\LedgerFlow`, created `context.md` and `buisness.md`, and verified local `D:` toolchain.
- **2026-11-25 14:15–14:28 IST**: Provisioned Supabase project `rzlenoldloktswymuooz` (`ap-south-1`), applied schema & RLS (`ledgerflow_schema_v1`, `0` security advisor lints), built & tested Render Express backend (`4/4` Node tests passing), and authored the Android Kotlin/Compose project.
- **2026-11-25 14:36–14:46 IST**: First unit test run caught 4 real bugs — fixed `NotificationParser` merchant capture bleeding across notification `title` into `text`, updated `MagicRuleEngine.sanitizeFtsQuery` to split punctuation tokens (`Trader Joe's #groceries` -> `Trader* Joe* s* groceries*`), implemented `WorkManager` `Configuration.Provider` on `LedgerFlowApplication` (eliminating `WorkManagerInitializer` `IllegalStateException`), added Android 12+ `data_extraction_rules.xml`, moved adaptive icons to `mipmap-anydpi` with `<monochrome>`, and resolved compiler deprecation warnings.
- **2026-11-25 14:52 IST (Core MVP Verification Run)**: Validated `8/8` Android unit/Robolectric tests, `4/4` Node backend tests, Debug + R8 Release APK builds, and `0` Supabase security advisor lints.
- **2026-11-25 16:20 IST (Automatic App Update Verification Run)**:
  - **Added**: Backend `/v1/app/update` + `/v1/app/download/latest.apk` (`backend/src/updates.ts`), Android `AppUpdatePolicy` + `AppUpdateManager` + `UpdateInstallReceivers` (`UpdateInstallStatusReceiver`, `AppReplacedReceiver`) + `UpdateCheckWorker` + `UpdateDialog` + `PrivacyVaultScreen` manual update card, and encrypted `VaultSessionState` fields (`dismissedUpdateVersionCode`, `cachedUpdateJson`, `pendingUpdateRelaunch`).
  - **Android Unit + Robolectric (`.\gradlew.bat testDebugUnitTest --no-daemon`)**: **15 / 15 passed** (`AppUpdatePolicyTest`: `3/3`, `AppUpdateRobolectricTest`: `4/4`, `FinanceMathTest`: `6/6`, `LedgerDatabaseRobolectricTest`: `2/2`, `0` failed, `0` skipped).
  - **Android Debug APK (`.\gradlew.bat assembleDebug --no-daemon`)**: Built [app-debug.apk](file:///D:/all%20farzi%20project/LedgerFlow/android/app/build/outputs/apk/debug/app-debug.apk) (`26,484,707` bytes).
  - **Android R8 Release APK (`.\gradlew.bat assembleRelease --no-daemon`)**: Built [app-release-unsigned.apk](file:///D:/all%20farzi%20project/LedgerFlow/android/app/build/outputs/apk/release/app-release-unsigned.apk) (`4,264,210` bytes).
  - **Android Lint (`.\gradlew.bat lintDebug --no-daemon`)**: `0` errors / fatal issues (`1` non-dependency hint on `fullBackupContent`).
  - **Node Backend (`npm run build && npm test`)**: **5 / 5 passed** (`fail 0`, `skipped 0`, `duration_ms 529.01`).
- **2026-11-25 17:17 IST (Deep Architectural Audit & Remediation Run)**:
  - **Critical / High Fixes Applied**:
    - Fixed sync pull HTTP error handling & watermark corruption in [LedgerSyncRepository.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/sync/LedgerSyncRepository.kt) (`401` token refresh on pull, fail-closed non-2xx guard before watermark advance, server `updated_at` watermark tracking, LWW `shouldApplyRemote` preventing stale remote rows from overwriting newer synced or local-dirty records, cloud `lastCloudUserId` isolation on account switch, paginated 500-row loops in [SupabaseSyncClient.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/remote/SupabaseSyncClient.kt)).
    - Fixed shared `syncRepository` & `updateManager` process singletons in [LedgerFlowApplication.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/LedgerFlowApplication.kt), [SyncWorker.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/work/SyncWorker.kt), and [UpdateCheckWorker.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/work/UpdateCheckWorker.kt) so background & UI workers serialize via shared mutexes.
    - Made `recordTransactionAtomic` idempotent against same-ID updates, added `Calendar` month/year due-date progression (`advanceDueDateEpochMs`), and skipped soft-deleted bills in [LedgerDatabase.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/local/LedgerDatabase.kt).
    - Hardened [VaultSecurity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/security/VaultSecurity.kt) with `.corrupt` backup preservation on decrypt error + process-wide synchronized state cache.
    - Tightened [AppUpdateManager.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/update/AppUpdateManager.kt) (single-flight mutexes, HTTPS redirect verification, usable disk space check, active `apkContentsSigners` enforcement) and fixed non-dismissible dialog deadlock in [UpdateDialog.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/components/UpdateDialog.kt).
    - Fixed [PayAndTrackNotificationListenerService.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/service/PayAndTrackNotificationListenerService.kt) `EXTRA_BIG_TEXT` extraction, atomic `ConcurrentHashMap.compute` deduplication, and bounded TTL pruning.
  - **Medium UI / Backend Fixes Applied**:
    - Backend [fx.ts](file:///D:/all%20farzi%20project/LedgerFlow/backend/src/fx.ts) (multi-base cache map + `finally { clearTimeout }`), [enricher.ts](file:///D:/all%20farzi%20project/LedgerFlow/backend/src/enricher.ts) (parenthesized negative CSV amounts), and [server.ts](file:///D:/all%20farzi%20project/LedgerFlow/backend/src/server.ts) (120 req/min rate limiter, `res.sendFile` Range-capable APK downloads, cross-platform `pathToFileURL` entrypoint).
    - Android UI fixes: thread-safe `AdMobGate` mutex + completion listener in [OnlineAdBanner.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/ads/OnlineAdBanner.kt), `Locale.US` decimal formatting & CSV parsing (`BigDecimal`), background `Dispatchers.IO` 2 MB-capped CSV URI reader, negative savings-rate formatting in [HomeScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/HomeScreen.kt), empty-state percent guards in [BudgetsScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/BudgetsScreen.kt) & [InvestScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/InvestScreen.kt), and `ON_RESUME` notification listener refresh in [AccountsHubScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/AccountsHubScreen.kt).
  - **Final Post-Audit Verification Results**:
    - **Android Unit & Integration (`.\gradlew.bat testDebugUnitTest --no-daemon`)**: **20 / 20 passed** (`AppUpdatePolicyTest`: `3/3`, `AppUpdateRobolectricTest`: `4/4`, `FinanceMathTest`: `6/6`, `LedgerDatabaseRobolectricTest`: `4/4`, `SyncRepositoryIntegrationTest`: `3/3`; `0` failed, `0` skipped).
    - **Debug APK (`assembleDebug`)**: `26,484,707` bytes; **R8 Release APK (`assembleRelease`)**: `4,280,594` bytes (`lintVitalRelease` passed).
    - **Android Lint (`lintDebug`)**: `0` errors/fatal issues (`2` non-dependency hints: `DataExtractionRules` + `UsableSpace`).
    - **Android Secret Scan**: `0` service-role / private-key hits.
    - **Node Backend (`npm run build && npm test`)**: **5 / 5 passed** (`fail 0`, `skipped 0`, `duration_ms 2285.0707`).
    - **Supabase Live Audit (`rzlenoldloktswymuooz`)**: `10` active RLS policies across `8` public tables, `0` security advisor lints.

---

## GitHub Publication & Signed Release APK (`v1.0.0`)
- **Repository**: `https://github.com/itripathiharsh/LedgerFlow` (`main` branch).
- **Direct APK Download**: `https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.0.0/LedgerFlow-v1.0.0.apk`.
- **Portability & Repo Hygiene**:
  - Removed machine-local `org.gradle.java.home` from tracked [gradle.properties](file:///D:/all%20farzi%20project/LedgerFlow/android/gradle.properties) into local Gradle user properties (`D:\dev\gradle-home\gradle.properties`), then re-ran `.\gradlew.bat testDebugUnitTest --no-daemon` (`BUILD SUCCESSFUL`).
  - Hardened [.gitignore](file:///D:/all%20farzi%20project/LedgerFlow/.gitignore) to ignore `local.properties`, `.env`, `.env.*` (except `.env.example`), `*.apk`, `*.aab`, `*.jks`, `*.keystore`, `*.pepk`, `node_modules/`, and build outputs.
  - Added root [README.md](file:///D:/all%20farzi%20project/LedgerFlow/README.md) with APK download links, SHA-256 verification metadata, architecture overview, and local setup instructions.
- **Release Signing Verification (`LedgerFlow-v1.0.0.apk`)**:
  - Zipaligned & signed release APK (v2 + v3 RSA-2048, keystore kept outside Git on `D:\dev\keystores\ledgerflow-release.jks`), size `4,333,857` bytes.
  - **APK SHA-256**: `912953708e44d264cb53e7c658baa8b1923d074345d3ab5857ea0204bdf13f06`.
  - **Signing Certificate SHA-256**: `40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0`.

---

## Physical Device Inspection & `v1.0.1` Hotfix (`motorola_edge_50_pro`, Android 16)
- **Root Diagnosis on Connected Device (`ZD222KQP8M`)**:
  - Initial ADB check found `com.ledgerflow.app` was **not yet installed** (although `/sdcard/Download/LedgerFlow-v1.0.0.apk` existed with exact SHA-256 `912953708...`), while another browser app (`io.github.itripathiharsh.aegis`) was foregrounded and had startup crash entries (`lateinit property tabManager has not been initialized`) in `dumpsys dropbox`.
  - Installing `LedgerFlow` over ADB launched cleanly (`0` crashes in `logcat -b crash`), while live interaction testing on the dark-mode Android 16 phone exposed 4 UI/UX defects plus 1 auth-sync bug:
    1. Invisible white status bar icons on light Alabaster UI when phone Dark Mode was enabled -> fixed with explicit `SystemBarStyle.light(...)` in [MainActivity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/MainActivity.kt) and `android:windowLightStatusBar=true` in [themes.xml](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/res/values/themes.xml).
    2. System Back inside `AddTransactionScreen` exited the app -> fixed with `BackHandler` in [MainActivity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/MainActivity.kt).
    3. Hardcoded `color = SlateInk` in `AppTypography` caused dark-on-dark unreadable Snackbar text -> fixed by removing hardcoded colors from `AppTypography` and adding `inverseSurface`/`inverseOnSurface` in [Theme.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/theme/Theme.kt), plus explicit white-on-slate `Snackbar` styling in [MainActivity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/MainActivity.kt).
    4. Unfilled `OnlineOnlyAdBanner` rendered an empty bordered stub card -> fixed border/padding visibility until `adLoaded == true` in [OnlineAdBanner.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/ads/OnlineAdBanner.kt).
    5. `signInSupabase` in [LedgerViewModel.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/LedgerViewModel.kt) left `_syncInProgress = true` before calling `syncNow()`, blocking post-login sync.
- **`v1.0.1` Verification & GitHub Release**:
  - `testDebugUnitTest` (`20/20` passed, `0` failed, `0` skipped), `lintDebug` (`0` errors), `assembleRelease` (`BUILD SUCCESSFUL`).
  - Signed `LedgerFlow-v1.0.1.apk` (`versionCode: 2`, `4,333,857` bytes, SHA-256 `eef5a362002157adabd6dd70b008985fba27ff4c43513807a58210cb3a2f1652`, signing cert `40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0`).
  - Installed & verified live on `ZD222KQP8M` (dark status bar icons, crisp Snackbar contrast, Back dismiss on `+` modal, zero empty ad stub, `0` crashes).
  - Direct APK Download (`v1.0.1`): `https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.0.1/LedgerFlow-v1.0.1.apk`.

---

## Google Play Protect Sideload Fix (`v1.0.2`, `versionCode 3`)
- **Root Cause**: Sideloading from Chrome/Files (`com.google.android.apps.nbu.files`) triggered Google Play Protect's Enhanced Fraud Protection hard block (`"App blocked to protect your device — This app can request access to sensitive data..."`) strictly because [AndroidManifest.xml](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/AndroidManifest.xml) declared `BIND_NOTIFICATION_LISTENER_SERVICE` on `PayAndTrackNotificationListenerService` (Play Protect blocks sideloaded APKs declaring `RECEIVE_SMS`, `READ_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE`, or `BIND_ACCESSIBILITY_SERVICE`, unlike Play Store installs).
- **Resolution & Flavor Architecture**:
  - Split `distribution` Gradle product flavors (`direct` default + `playStore`) in [build.gradle.kts](file:///D:/all%20farzi%20project/LedgerFlow/android/app/build.gradle.kts).
  - Extracted shared parser, deduplication, and Room capture logic into [PayAndTrackCapture.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/service/PayAndTrackCapture.kt).
  - Moved `PayAndTrackNotificationListenerService` + `BIND_NOTIFICATION_LISTENER_SERVICE` strictly into `src/playStore/` ([AndroidManifest.xml](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/playStore/AndroidManifest.xml), [PayAndTrackNotificationListenerService.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/playStore/java/com/ledgerflow/app/service/PayAndTrackNotificationListenerService.kt)).
  - Added zero-permission **Share-to-Track** (`ACTION_SEND` `text/plain`) in [MainActivity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/MainActivity.kt), **Smart Paste** dialog in [AccountsHubScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/AccountsHubScreen.kt), and `Rs.` / `INR` support in [FinanceMath.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/domain/FinanceMath.kt).
- **Verification (`v1.0.2`)**:
  - `testDirectDebugUnitTest`: **20 / 20 passed** (`0` failed, `0` skipped); `lintDirectDebug`: `0` errors (`26` warnings); `assembleDirectRelease` + `compilePlayStoreDebugKotlin` passed.
  - Signed `LedgerFlow-v1.0.2.apk` (`4,333,857` bytes, SHA-256 `715ea43120769e09582e828339205b7ccf8a2f7851f7b2543e61ebb1d748a932`, signing cert `40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0`) verified **0** Play-Protect restricted permissions (`BIND_NOTIFICATION_LISTENER_SERVICE`, `RECEIVE_SMS`, `READ_SMS`, `BIND_ACCESSIBILITY_SERVICE`).
  - Direct APK Download (`v1.0.2`): `https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.0.2/LedgerFlow-v1.0.2.apk`.

---

## Clean First-Launch & In-App Reset (`v1.0.3`, `versionCode 4`)
- **Clean Start by Default**:
  - Updated [LedgerFlowApplication.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/LedgerFlowApplication.kt) to initialize via `SeedData.ensureMinimalSetup(...)` in [SeedData.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/local/SeedData.kt) — creating only 8 starter categories + 1 `$0.00` `Cash Wallet`, with zero fake bank balances, transactions, budgets, bills, or holdings.
  - Cleared pre-filled test values (`$48.50`, `Trader Joe's`, `weekly`) from [AddTransactionSheet.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/AddTransactionSheet.kt) and added flat zero-state chart rendering when holdings are empty in [InvestScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/InvestScreen.kt).
- **In-App Data Reset (`Delete Data & Start Fresh`)**:
  - Added atomic `resetAllDataFresh()` / `resetAndLoadDemoData()` in [LedgerDatabase.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/local/LedgerDatabase.kt) backed by DAO `deleteAll()` calls in [LedgerDaos.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/data/local/LedgerDaos.kt) (deleting `transactions` first so Room FTS4 triggers stay consistent) plus `VaultStore.resetSyncAndCloudState()` in [VaultSecurity.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/security/VaultSecurity.kt).
  - Exposed **"Delete Data & Start Fresh"** (+ optional **"Load Demo"**) in [PrivacyVaultScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/PrivacyVaultScreen.kt), **"Start Fresh (Reset Ledger)"** in [AccountsHubScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/AccountsHubScreen.kt), and a conditional 1-tap **"Sample Demo Data Loaded -> Start Fresh"** banner in [HomeScreen.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/main/java/com/ledgerflow/app/ui/screens/HomeScreen.kt) whenever demo seed rows exist.
- **Verification (`v1.0.3`)**:
  - Added `resetAllDataFresh_clearsDemoAndRebuildsCleanFts4` in [LedgerDatabaseRobolectricTest.kt](file:///D:/all%20farzi%20project/LedgerFlow/android/app/src/test/java/com/ledgerflow/app/LedgerDatabaseRobolectricTest.kt).
  - `.\gradlew.bat testDirectDebugUnitTest lintDirectDebug assembleDirectRelease compilePlayStoreDebugKotlin --no-daemon`: **21 / 21 passed** (`0` failed, `0` skipped), `0` Lint errors (`26` warnings).
  - Signed `LedgerFlow-v1.0.3.apk` (`4,333,857` bytes, SHA-256 `2b1a680bb277dfc7a2b72ab6780e79ba4a8d7af9088472447c088a3f77ae6c11`, signing cert `40413065bb787030d0bdc97f3afda5c9e3017b2adb5ec07fc502e376defb02a0`, `0` restricted permissions).
  - Direct APK Download (`v1.0.3`): `https://github.com/itripathiharsh/LedgerFlow/releases/download/v1.0.3/LedgerFlow-v1.0.3.apk`.




