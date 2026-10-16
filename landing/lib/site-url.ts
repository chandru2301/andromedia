/**
 * Shared site URL for metadata, sitemap, and robots.
 * Set NEXT_PUBLIC_SITE_URL to the real production domain before deploy.
 */
export function getSiteUrl(): string {
  const fromEnv = process.env.NEXT_PUBLIC_SITE_URL?.trim();
  if (fromEnv) {
    return fromEnv.replace(/\/$/, "");
  }
  return "http://localhost:3000";
}
