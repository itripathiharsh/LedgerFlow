export interface EnrichedMerchant {
  rawMerchant: string;
  cleanMerchant: string;
  suggestedCategory: string;
  suggestedLabels: string[];
  autoRuleMatched: boolean;
}

const MERCHANT_RULES: Array<{ pattern: RegExp; cleanName?: string; category: string; labels: string[] }> = [
  { pattern: /blue\s*bottle|starbucks|cafe|coffee|espresso/i, category: "Dining & Coffee", labels: ["coffee"] },
  { pattern: /whole\s*foods|trader\s*joe|aldi|tesco|grocery|supermarket|mart/i, category: "Groceries", labels: ["weekly"] },
  { pattern: /uber|lyft|metro|transit|shell|chevron|fuel|petrol/i, category: "Transport & Fuel", labels: ["commute"] },
  { pattern: /spotify|netflix|figma|apple\s*services|icloud|youtube/i, category: "Subscriptions", labels: ["recurring"] },
  { pattern: /salary|payroll|employer|dividend|interest/i, category: "Income", labels: ["inflow"] },
  { pattern: /rent|landlord|utility|electric|water|gas\s*bill/i, category: "Housing & Bills", labels: ["bills"] },
  { pattern: /pharmacy|clinic|hospital|dental|health/i, category: "Health", labels: ["medical"] },
];

export function enrichMerchant(rawMerchant: string, note = ""): EnrichedMerchant {
  const combined = `${rawMerchant} ${note}`.trim();
  const cleanMerchant = rawMerchant
    .replace(/\s+#\d+.*$/, "")
    .replace(/\s+\*\w+$/, "")
    .replace(/\s+/g, " ")
    .trim() || "Unknown Merchant";

  for (const rule of MERCHANT_RULES) {
    if (rule.pattern.test(combined)) {
      return {
        rawMerchant,
        cleanMerchant: rule.cleanName ?? cleanMerchant,
        suggestedCategory: rule.category,
        suggestedLabels: rule.labels,
        autoRuleMatched: true,
      };
    }
  }

  return {
    rawMerchant,
    cleanMerchant,
    suggestedCategory: "General",
    suggestedLabels: [],
    autoRuleMatched: false,
  };
}

export interface ParsedCsvRow {
  occurredAt: string;
  merchant: string;
  amountMinor: number;
  txType: "EXPENSE" | "INCOME";
  currency: string;
  note: string;
  suggestedCategory: string;
}

function splitCsvLine(line: string, delimiter: string): string[] {
  const result: string[] = [];
  let current = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i++;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (ch === delimiter && !inQuotes) {
      result.push(current.trim());
      current = "";
    } else {
      current += ch;
    }
  }
  result.push(current.trim());
  return result;
}

export function parseCsvStatement(csvContent: string, defaultCurrency = "USD"): ParsedCsvRow[] {
  const lines = csvContent
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);
  if (lines.length < 2) return [];

  const delimiter = lines[0].includes(";") && !lines[0].includes(",") ? ";" : ",";
  const headers = splitCsvLine(lines[0], delimiter).map((h) => h.toLowerCase());

  const dateIdx = headers.findIndex((h) => /date|time|posted/.test(h));
  const merchantIdx = headers.findIndex((h) => /merchant|payee|description|name/.test(h));
  const amountIdx = headers.findIndex((h) => /amount|value|sum/.test(h));
  const currencyIdx = headers.findIndex((h) => /curr/.test(h));
  const noteIdx = headers.findIndex((h) => /note|memo/.test(h));

  if (merchantIdx < 0 || amountIdx < 0) return [];

  const rows: ParsedCsvRow[] = [];
  for (let i = 1; i < lines.length; i++) {
    const cols = splitCsvLine(lines[i], delimiter);
    const cell = (cols[amountIdx] ?? "").trim();
    const isParensNegative = /^\(.*\)$/.test(cell);
    const rawAmt = cell.replace(/[^\d.-]/g, "");
    let numeric = Number.parseFloat(rawAmt);
    if (!Number.isFinite(numeric) || numeric === 0) continue;
    if (isParensNegative && numeric > 0) numeric = -numeric;

    const currency = (currencyIdx >= 0 && cols[currencyIdx] ? cols[currencyIdx] : defaultCurrency).toUpperCase().slice(0, 3);
    // Yen has no minor unit; every other supported currency uses hundredths.
    const amountMinor = Math.round(numeric * (currency === "JPY" ? 1 : 100));
    const txType: "EXPENSE" | "INCOME" = amountMinor < 0 ? "EXPENSE" : "INCOME";
    const merchant = cols[merchantIdx] || "Imported Entry";
    const note = noteIdx >= 0 ? (cols[noteIdx] ?? "") : "";
    const parsedDate = dateIdx >= 0 ? new Date(cols[dateIdx] ?? "") : new Date();
    const occurredAt = Number.isNaN(parsedDate.getTime()) ? new Date().toISOString() : parsedDate.toISOString();
    const enriched = enrichMerchant(merchant, note);

    rows.push({
      occurredAt,
      merchant: enriched.cleanMerchant,
      amountMinor,
      txType,
      currency,
      note,
      suggestedCategory: enriched.suggestedCategory,
    });
  }
  return rows;
}
