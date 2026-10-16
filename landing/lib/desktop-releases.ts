/**
 * Resolves the installers published by .github/workflows/release-desktop.yml
 * from the GitHub Releases API.
 *
 * Returns a null release rather than throwing whenever downloads are not
 * available yet — no repo configured, no release published, API unreachable or
 * rate limited — so the download page always renders something honest.
 */

import { desktopRelease } from "@/lib/site-content";

export type PlatformId = "windows" | "macos" | "linux";

export interface ReleaseAsset {
  platform: PlatformId;
  name: string;
  url: string;
  sizeBytes: number;
}

export interface DesktopReleaseInfo {
  version: string;
  /** Pre-formatted UTC date (YYYY-MM-DD). Formatting on the client instead
   *  would hydrate differently to the server's locale and timezone. */
  publishedOn: string;
  assets: ReleaseAsset[];
}

interface GitHubAsset {
  name: string;
  browser_download_url: string;
  size: number;
}

interface GitHubRelease {
  tag_name: string;
  published_at: string;
  draft: boolean;
  prerelease: boolean;
  assets: GitHubAsset[];
}

function platformForAsset(name: string): PlatformId | null {
  const lower = name.toLowerCase();
  if (lower.endsWith(".exe")) return "windows";
  if (lower.endsWith(".dmg")) return "macos";
  if (lower.endsWith(".appimage")) return "linux";
  return null;
}

export async function getLatestDesktopRelease(): Promise<DesktopReleaseInfo | null> {
  const repo = desktopRelease.releasesRepo;
  if (!repo) {
    return null;
  }

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { Accept: "application/vnd.github+json" },
      // Cache for an hour: releases change rarely and the API is rate limited.
      next: { revalidate: 3600 },
    });
    if (!res.ok) {
      return null;
    }

    const release = (await res.json()) as GitHubRelease;
    if (release.draft) {
      return null;
    }

    const assets = release.assets
      .map((asset) => {
        const platform = platformForAsset(asset.name);
        return platform
          ? {
              platform,
              name: asset.name,
              url: asset.browser_download_url,
              sizeBytes: asset.size,
            }
          : null;
      })
      .filter((asset): asset is ReleaseAsset => asset !== null);

    if (assets.length === 0) {
      return null;
    }

    return {
      version: release.tag_name.replace(/^v/, ""),
      publishedOn: release.published_at.slice(0, 10),
      assets,
    };
  } catch {
    return null;
  }
}

export function formatSize(bytes: number): string {
  return `${(bytes / 1024 / 1024).toFixed(0)} MB`;
}
