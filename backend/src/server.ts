import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import cors from "cors";
import express from "express";
import helmet from "helmet";
import { z } from "zod";
import { enrichMerchant, parseCsvStatement } from "./enricher.js";
import { fetchFxRates } from "./fx.js";
import { buildAppUpdateManifest, resolveLatestApkPath } from "./updates.js";

const enrichRequestSchema = z.object({
  items: z
    .array(
      z.object({
        merchant: z.string().min(1).max(200),
        note: z.string().max(400).optional().default(""),
      })
    )
    .min(1)
    .max(50),
});

const csvImportSchema = z.object({
  csv: z.string().min(1).max(500_000),
  defaultCurrency: z.string().length(3).optional().default("USD"),
});

const sandboxConnectSchema = z.object({
  providerCode: z.string().min(2).max(60),
  accountName: z.string().min(1).max(80).optional(),
  currency: z.string().length(3).optional().default("USD"),
});

export function createApp() {
  const app = express();
  app.set("trust proxy", 1);
  app.use(helmet());
  app.use(cors());
  app.use(express.json({ limit: "512kb" }));

  // Lightweight bounded sliding-window rate limiter to protect free-tier quota from abuse.
  const rateBuckets = new Map<string, { count: number; resetAt: number }>();
  const RATE_WINDOW_MS = 60_000;
  const RATE_MAX_REQUESTS = 120;
  app.use((req, res, next) => {
    if (req.path === "/health") {
      next();
      return;
    }
    const now = Date.now();
    const key = req.ip || "unknown";
    let bucket = rateBuckets.get(key);
    if (!bucket || bucket.resetAt <= now) {
      if (rateBuckets.size > 2000) rateBuckets.clear();
      bucket = { count: 0, resetAt: now + RATE_WINDOW_MS };
      rateBuckets.set(key, bucket);
    }
    bucket.count += 1;
    if (bucket.count > RATE_MAX_REQUESTS) {
      res.status(429).json({ error: "Rate limit exceeded; retry shortly" });
      return;
    }
    next();
  });

  app.get("/health", (_req, res) => {
    res.json({
      status: "ok",
      service: "ledgerflow-api",
      tier: "free",
      offlineCompatible: true,
      timestamp: new Date().toISOString(),
    });
  });

  app.get("/v1/app/update", (_req, res) => {
    res.setHeader("Cache-Control", "no-store");
    res.json(buildAppUpdateManifest());
  });

  app.get("/v1/app/download/latest.apk", (_req, res) => {
    const apkPath = resolveLatestApkPath();
    if (!apkPath) {
      res.status(404).json({ error: "No local release APK configured on this host" });
      return;
    }
    res.sendFile(apkPath, {
      headers: {
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Disposition": 'attachment; filename="ledgerflow-update.apk"',
        "Cache-Control": "no-store",
      },
    });
  });

  app.get("/v1/fx/rates", async (req, res) => {
    const rawBase = typeof req.query.base === "string" ? req.query.base : "USD";
    const base = /^[A-Za-z]{3}$/.test(rawBase) ? rawBase.toUpperCase() : "USD";
    const data = await fetchFxRates(base);
    res.json(data);
  });

  app.post("/v1/enrich", (req, res) => {
    const parsed = enrichRequestSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid enrichment payload", details: parsed.error.flatten() });
      return;
    }
    const results = parsed.data.items.map((item) => enrichMerchant(item.merchant, item.note));
    res.json({ count: results.length, items: results });
  });

  app.post("/v1/import/csv", (req, res) => {
    const parsed = csvImportSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid CSV payload", details: parsed.error.flatten() });
      return;
    }
    const rows = parseCsvStatement(parsed.data.csv, parsed.data.defaultCurrency);
    res.json({ importedCount: rows.length, rows });
  });

  // Free-tier open-banking sandbox connector (zero paid aggregator API keys needed).
  app.post("/v1/open-banking/sandbox/connect", (req, res) => {
    const parsed = sandboxConnectSchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: "Invalid sandbox bank payload", details: parsed.error.flatten() });
      return;
    }
    const now = new Date();
    const fourDaysAgo = new Date(now.getTime() - 4 * 86_400_000).toISOString();
    const yesterday = new Date(now.getTime() - 86_400_000).toISOString();
    res.json({
      connectionId: `sbx_${parsed.data.providerCode.toLowerCase()}_${now.getTime()}`,
      providerCode: parsed.data.providerCode,
      accountName: parsed.data.accountName ?? `${parsed.data.providerCode} Checking`,
      currency: parsed.data.currency.toUpperCase(),
      balanceMinor: 318000,
      sampleTransactions: [
        {
          merchant: "Trader Joe's",
          amountMinor: -4850,
          txType: "EXPENSE",
          currency: parsed.data.currency.toUpperCase(),
          occurredAt: yesterday,
          sourceTag: "BANK_SYNC",
          suggestedCategory: "Groceries",
        },
        {
          merchant: "Employer Direct Payroll",
          amountMinor: 271000,
          txType: "INCOME",
          currency: parsed.data.currency.toUpperCase(),
          occurredAt: fourDaysAgo,
          sourceTag: "BANK_SYNC",
          suggestedCategory: "Income",
        },
      ],
    });
  });

  return app;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const port = Number.parseInt(process.env.PORT ?? "10000", 10);
  const app = createApp();
  app.listen(port, () => {
    console.log(`ledgerflow-api listening on :${port}`);
  });
}
