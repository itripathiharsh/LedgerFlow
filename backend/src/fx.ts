export interface FxRatesResponse {
  base: string;
  source: "frankfurter" | "offline-fallback";
  updatedAt: string;
  rates: Record<string, number>;
}

const USD_BASE_RATES: Record<string, number> = {
  USD: 1.0,
  EUR: 0.925,
  GBP: 0.79,
  INR: 84.4,
  JPY: 154.2,
  CAD: 1.4,
  AUD: 1.54,
  CHF: 0.88,
  CZK: 23.4,
  PLN: 4.1,
  SGD: 1.34,
  AED: 3.6725,
};

interface CacheEntry {
  data: FxRatesResponse;
  expiresAt: number;
}

const cache = new Map<string, CacheEntry>();
const MAX_CACHE_BASES = 20;
const SIX_HOURS_MS = 6 * 60 * 60 * 1000;

export function buildFallbackRates(base: string): FxRatesResponse {
  const normalizedBase = base.toUpperCase();
  const baseFactor = USD_BASE_RATES[normalizedBase] ?? 1.0;
  const rates: Record<string, number> = {};
  for (const [code, usdRate] of Object.entries(USD_BASE_RATES)) {
    rates[code] = Number((usdRate / baseFactor).toFixed(6));
  }
  return {
    base: normalizedBase,
    source: "offline-fallback",
    updatedAt: new Date().toISOString(),
    rates,
  };
}

export async function fetchFxRates(base = "USD"): Promise<FxRatesResponse> {
  const normalizedBase = base.toUpperCase();
  const now = Date.now();
  const cached = cache.get(normalizedBase);
  if (cached && cached.expiresAt > now) {
    return cached.data;
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 3500);
  try {
    const res = await fetch(`https://api.frankfurter.dev/v1/latest?base=${encodeURIComponent(normalizedBase)}`, {
      signal: controller.signal,
    });
    if (!res.ok) {
      throw new Error(`Upstream status ${res.status}`);
    }
    const json = (await res.json()) as { base?: string; date?: string; rates?: Record<string, number> };
    if (!json.rates || typeof json.rates !== "object") {
      throw new Error("Missing rates payload");
    }
    const result: FxRatesResponse = {
      base: normalizedBase,
      source: "frankfurter",
      updatedAt: json.date ? `${json.date}T00:00:00Z` : new Date().toISOString(),
      rates: { ...json.rates, [normalizedBase]: 1.0 },
    };
    if (cache.size >= MAX_CACHE_BASES && !cache.has(normalizedBase)) {
      const oldestKey = cache.keys().next().value;
      if (oldestKey !== undefined) cache.delete(oldestKey);
    }
    cache.set(normalizedBase, { data: result, expiresAt: now + SIX_HOURS_MS });
    return result;
  } catch {
    return buildFallbackRates(normalizedBase);
  } finally {
    clearTimeout(timer);
  }
}
