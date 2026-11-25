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

function parseReleaseNotes(raw: string | undefined): string[] {
  if (!raw || raw.trim().length === 0) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.map((item) => String(item).trim()).filter(Boolean);
    }
  } catch {
    return raw
      .split(/\r?\n|\|/)
      .map((item) => item.trim())
      .filter(Boolean);
  }
  return [];
}

export function buildAppUpdateManifest(env: NodeJS.ProcessEnv = process.env): AppUpdateManifest | null {
  const apkUrl = (env.LATEST_APK_URL ?? "").trim();
  const sha256 = (env.LATEST_APK_SHA256 ?? "").trim().toLowerCase();
  const versionName = (env.LATEST_VERSION_NAME ?? "").trim();
  const versionCodeRaw = Number.parseInt(env.LATEST_VERSION_CODE ?? "", 10);

  if (!apkUrl || !/^[0-9a-f]{64}$/.test(sha256) || !versionName || !Number.isFinite(versionCodeRaw) || versionCodeRaw < 1) {
    return null;
  }

  const minRaw = Number.parseInt(env.MIN_SUPPORTED_VERSION_CODE ?? "1", 10);
  const minSupportedVersionCode = Number.isFinite(minRaw) && minRaw >= 1 ? minRaw : 1;
  const releasedAt = (env.LATEST_RELEASED_AT ?? "").trim() || new Date().toISOString();
  const signingCertSha256 = (env.LATEST_SIGNING_CERT_SHA256 ?? "").trim().toLowerCase();

  let apkSizeBytes = Number.parseInt(env.LATEST_APK_SIZE_BYTES ?? "0", 10);
  if (!Number.isFinite(apkSizeBytes) || apkSizeBytes < 0) apkSizeBytes = 0;

  const apkFilePath = env.LATEST_APK_FILE_PATH?.trim();
  if (apkSizeBytes === 0 && apkFilePath && fs.existsSync(apkFilePath)) {
    apkSizeBytes = fs.statSync(apkFilePath).size;
  }

  return {
    versionCode: versionCodeRaw,
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
