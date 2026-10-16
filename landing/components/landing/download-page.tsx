"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Reveal } from "@/components/landing/reveal";
import { Button } from "@/components/ui/button";
import { desktopRelease, siteConfig } from "@/lib/site-content";
import {
  formatSize,
  type DesktopReleaseInfo,
  type PlatformId,
} from "@/lib/desktop-releases";

/** Best-effort OS guess so the right download is the obvious one. */
function detectPlatform(): PlatformId | null {
  if (typeof navigator === "undefined") return null;
  const ua = navigator.userAgent.toLowerCase();
  if (ua.includes("win")) return "windows";
  if (ua.includes("mac")) return "macos";
  if (ua.includes("linux") || ua.includes("x11")) return "linux";
  return null;
}

interface DownloadPageProps {
  release: DesktopReleaseInfo | null;
}

export function DownloadPage({ release }: DownloadPageProps) {
  // Resolved after mount: the server render must not depend on the visitor's OS.
  const [platform, setPlatform] = useState<PlatformId | null>(null);

  useEffect(() => {
    setPlatform(detectPlatform());
  }, []);

  const ordered = [...desktopRelease.platforms].sort((a, b) => {
    if (a.id === platform) return -1;
    if (b.id === platform) return 1;
    return 0;
  });

  return (
    <main className="flex-1 px-6 py-16 md:py-24">
      <div className="mx-auto max-w-3xl">
        <Reveal>
          <p className="font-mono text-xs uppercase tracking-widest text-muted-foreground">
            Desktop app
          </p>
          <h1 className="mt-4 text-balance text-4xl font-semibold tracking-tight md:text-5xl">
            Download Andro
          </h1>
          <p className="mt-4 text-sm leading-relaxed text-muted-foreground md:text-base">
            Install the desktop app, sign in, and ask your first question. The trial
            runs against Andro&rsquo;s hosted service — nothing to configure, no
            database or API to stand up yourself.
          </p>
        </Reveal>

        {release ? (
          <Reveal delay={0.08}>
            <p className="mt-8 font-mono text-xs text-muted-foreground">
              Version {release.version} · released {release.publishedOn}
            </p>
          </Reveal>
        ) : null}

        <Reveal delay={0.08}>
          <div className="mt-6 space-y-4">
            {ordered.map((spec) => {
              const asset = release?.assets.find((item) => item.platform === spec.id);
              const isDetected = spec.id === platform;

              return (
                <div
                  key={spec.id}
                  className={`rounded-lg border p-5 ${
                    isDetected ? "border-foreground/25 bg-muted/40" : "border-border"
                  }`}
                >
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h2 className="flex items-center gap-2 text-base font-medium tracking-tight">
                        {spec.label}
                        {isDetected ? (
                          <span className="rounded-full bg-foreground/10 px-2 py-0.5 font-mono text-[10px] uppercase tracking-wide">
                            your system
                          </span>
                        ) : null}
                      </h2>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {spec.requirement}
                      </p>
                    </div>

                    {asset ? (
                      <Button asChild>
                        <a href={asset.url}>
                          Download {spec.extensions[0]} ({formatSize(asset.sizeBytes)})
                        </a>
                      </Button>
                    ) : (
                      <Button disabled variant="outline">
                        {release ? "Not in this release" : "Coming soon"}
                      </Button>
                    )}
                  </div>

                  <ol className="mt-4 space-y-1.5 border-t border-border pt-4 text-xs leading-relaxed text-muted-foreground">
                    {spec.steps.map((step, index) => (
                      <li key={step} className="flex gap-2">
                        <span className="font-mono text-[10px] opacity-60">
                          {index + 1}
                        </span>
                        <span>{step}</span>
                      </li>
                    ))}
                  </ol>
                </div>
              );
            })}
          </div>
        </Reveal>

        {!release ? (
          <Reveal delay={0.12}>
            <div className="mt-8 rounded-lg border border-border bg-muted/30 p-5">
              <h2 className="text-sm font-medium tracking-tight">
                Builds aren&rsquo;t published yet
              </h2>
              <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
                Installers are on the way. In the meantime you can run Andro from
                source — the setup guide walks through the API and the desktop app.
              </p>
              <div className="mt-4 flex flex-wrap gap-3">
                <Button asChild variant="outline" size="sm">
                  <a href={siteConfig.setupGuidePath}>Setup guide</a>
                </Button>
                <Button asChild size="sm">
                  <Link href={siteConfig.trialUrl}>Try it in the browser</Link>
                </Button>
              </div>
            </div>
          </Reveal>
        ) : null}

        {!desktopRelease.signed ? (
          <Reveal delay={0.16}>
            <p className="mt-8 border-t border-border pt-6 text-xs leading-relaxed text-muted-foreground">
              <span className="font-medium text-foreground">
                A note on the security warning.
              </span>{" "}
              These builds are not yet code-signed, so Windows and macOS will warn
              that the developer is unverified. The per-platform steps above get you
              past it. Signed builds are coming.
            </p>
          </Reveal>
        ) : null}
      </div>
    </main>
  );
}
