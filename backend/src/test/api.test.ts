import assert from "node:assert/strict";
import test from "node:test";
import { enrichMerchant, parseCsvStatement } from "../enricher.js";
import { buildFallbackRates } from "../fx.js";
import { createApp } from "../server.js";
import { buildAppUpdateManifest } from "../updates.js";

test("enrichMerchant cleans POS suffixes and categorizes common merchants", () => {
  const coffee = enrichMerchant("Blue Bottle Coffee #421");
  assert.equal(coffee.cleanMerchant, "Blue Bottle Coffee");
  assert.equal(coffee.suggestedCategory, "Dining & Coffee");
  assert.equal(coffee.autoRuleMatched, true);

  const groceries = enrichMerchant("Whole Foods Market *99AB");
  assert.equal(groceries.cleanMerchant, "Whole Foods Market");
  assert.equal(groceries.suggestedCategory, "Groceries");
});

test("parseCsvStatement parses signed CSV and skips zero or invalid rows", () => {
  const csv = [
    "Date,Description,Amount,Currency,Note",
    "2026-11-24,\"Trader Joe's, Market\",-48.50,USD,Weekly groceries",
    "2026-11-24,City Garage,(12.75),USD,Parking",
    "2026-11-25,Employer Payroll,2710.00,USD,Paycheck",
    "2026-11-25,Noise Row,0.00,USD,Ignored",
  ].join("\n");

  const parsed = parseCsvStatement(csv);
  assert.equal(parsed.length, 3);
  assert.equal(parsed[0].amountMinor, -4850);
  assert.equal(parsed[0].txType, "EXPENSE");
  assert.equal(parsed[0].suggestedCategory, "Groceries");
  assert.equal(parsed[1].amountMinor, -1275);
  assert.equal(parsed[1].txType, "EXPENSE");
  assert.equal(parsed[2].amountMinor, 271000);
  assert.equal(parsed[2].txType, "INCOME");
});

test("buildFallbackRates returns normalized cross rates without internet", () => {
  const eurBase = buildFallbackRates("EUR");
  assert.equal(eurBase.base, "EUR");
  assert.equal(eurBase.source, "offline-fallback");
  assert.equal(eurBase.rates.EUR, 1);
  assert.ok(eurBase.rates.USD > 1);
});

test("Express API endpoints respond cleanly with validation", async () => {
  const app = createApp();
  const server = app.listen(0);
  const addr = server.address();
  assert.ok(addr && typeof addr === "object");
  const baseUrl = `http://127.0.0.1:${addr.port}`;

  try {
    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    const healthJson = (await health.json()) as { status: string; offlineCompatible: boolean };
    assert.equal(healthJson.status, "ok");
    assert.equal(healthJson.offlineCompatible, true);

    const badEnrich = await fetch(`${baseUrl}/v1/enrich`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ items: [] }),
    });
    assert.equal(badEnrich.status, 400);

    const goodEnrich = await fetch(`${baseUrl}/v1/enrich`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ items: [{ merchant: "Spotify Family #1" }] }),
    });
    assert.equal(goodEnrich.status, 200);
    const enrichJson = (await goodEnrich.json()) as { count: number; items: Array<{ suggestedCategory: string }> };
    assert.equal(enrichJson.count, 1);
    assert.equal(enrichJson.items[0].suggestedCategory, "Subscriptions");

    const updateRes = await fetch(`${baseUrl}/v1/app/update`);
    assert.equal(updateRes.status, 200);
    const updateJson = (await updateRes.json()) as { versionCode: number; versionName: string; releaseNotes: string[] };
    assert.ok(updateJson.versionCode >= 1);
    assert.ok(updateJson.versionName.length > 0);
    assert.ok(Array.isArray(updateJson.releaseNotes) && updateJson.releaseNotes.length > 0);

    const downloadMissing = await fetch(`${baseUrl}/v1/app/download/latest.apk`);
    assert.equal(downloadMissing.status, 404);

    // Rate-limit regression: verify sliding window triggers 429 cleanly after burst
    let sawRateLimit = false;
    for (let i = 0; i < 125; i++) {
      const r = await fetch(`${baseUrl}/v1/app/update`);
      if (r.status === 429) {
        sawRateLimit = true;
        break;
      }
    }
    assert.equal(sawRateLimit, true);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
});

test("buildAppUpdateManifest parses release env metadata and pipe notes", () => {
  const manifest = buildAppUpdateManifest({
    LATEST_VERSION_CODE: "4",
    LATEST_VERSION_NAME: "1.2.0",
    LATEST_APK_URL: "https://example.org/ledgerflow.apk",
    LATEST_APK_SHA256: "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
    LATEST_SIGNING_CERT_SHA256: "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
    LATEST_APK_SIZE_BYTES: "4231266",
    LATEST_RELEASE_NOTES: "Improved sync|Vault security hardening",
    LATEST_UPDATE_MANDATORY: "false",
  });
  assert.equal(manifest.versionCode, 4);
  assert.equal(manifest.versionName, "1.2.0");
  assert.equal(manifest.sha256, "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
  assert.deepEqual(manifest.releaseNotes, ["Improved sync", "Vault security hardening"]);
  assert.equal(manifest.mandatory, false);
});
