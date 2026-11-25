import fs from "node:fs";
import path from "node:path";

export interface AppUpdateManifest {
  versionCode: number;
  versionName: string;
  minSupportedVersionCode: number;
  releasedAt: string;
  apkUrl: string;
  apkSizeBytes: number;
  sha256: string;
  signingCertSha256: string;
  releaseNotes: string[];
  mandatory: boolean;
}

const DEFAULT_NOTES = [
  "Offline-first Room SQLite vault with FTS4 instant search",
  "Automatic Pay & Track notification capture and Magic Rules",
  "Verified HTTPS updates with SHA-256 and APK signature checks",
];

function parseReleaseNotes(raw: string | undefined): string[] {
  if (!raw || raw.trim().length === 0) return DEFAULT_NOTES;
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      const cleaned = parsed.map((item) => String(item).trim()).filter(Boolean);
      if (cleaned.length > 0) return cleaned;
    }
  } catch {
    const split = raw
      .split(/\r?\n|\|/)
      .map((item) => item.trim())
      .filter(Boolean);
    if (split.length > 0) return split;
  }
  return DEFAULT_NOTES;
}

export function buildAppUpdateManifest(env: NodeJS.ProcessEnv = process.env): AppUpdateManifest {
  const versionCodeRaw = Number.parseInt(env.LATEST_VERSION_CODE ?? "1", 10);
  const versionCode = Number.isFinite(versionCodeRaw) && versionCodeRaw >= 1 ? versionCodeRaw : 1;

  const minRaw = Number.parseInt(env.MIN_SUPPORTED_VERSION_CODE ?? "1", 10);
  const minSupportedVersionCode = Number.isFinite(minRaw) && minRaw >= 1 ? minRaw : 1;

  const versionName = (env.LATEST_VERSION_NAME ?? "1.0.0").trim() || "1.0.0";
  const releasedAt = (env.LATEST_RELEASED_AT ?? "2026-11-25T10:30:00Z").trim();
  const apkUrl = (env.LATEST_APK_URL ?? "https://ledgerflow-api.onrender.com/v1/app/download/latest.apk").trim();

  const sha256 = (env.LATEST_APK_SHA256 ?? "").trim().toLowerCase();
  const signingCertSha256 = (env.LATEST_SIGNING_CERT_SHA256 ?? "").trim().toLowerCase();

  let apkSizeBytes = Number.parseInt(env.LATEST_APK_SIZE_BYTES ?? "0", 10);
  if (!Number.isFinite(apkSizeBytes) || apkSizeBytes < 0) apkSizeBytes = 0;

  const apkFilePath = env.LATEST_APK_FILE_PATH?.trim();
  if (apkSizeBytes === 0 && apkFilePath && fs.existsSync(apkFilePath)) {
    apkSizeBytes = fs.statSync(apkFilePath).size;
  }

  return {
    versionCode,
    versionName,
    minSupportedVersionCode,
    releasedAt,
    apkUrl,
    apkSizeBytes,
    sha256,
    signingCertSha256,
    releaseNotes: parseReleaseNotes(env.LATEST_RELEASE_NOTES),
    mandatory: (env.LATEST_UPDATE_MANDATORY ?? "").trim().toLowerCase() === "true",
  };
}

export function resolveLatestApkPath(env: NodeJS.ProcessEnv = process.env): string | null {
  const configured = env.LATEST_APK_FILE_PATH?.trim();
  if (!configured) return null;
  const resolved = path.resolve(configured);
  if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) return null;
  return resolved;
}
