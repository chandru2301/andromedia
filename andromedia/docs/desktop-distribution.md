# Shipping the andro desktop app to trial users

How a user who has never seen the repo gets a working trial:

```
landing /download  →  installer  →  app opens on sign-in  →  Trial → hosted API
```

## Cutting a release

```bash
cd andro && npm version 0.1.0 --no-git-tag-version   # optional; CI sets it from the tag
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release-desktop.yml` builds on three runners (electron-builder
cannot cross-compile) and publishes to a GitHub Release:

| Runner | Artifact |
|---|---|
| `ubuntu-latest` | `andro-<version>-linux-x64.AppImage` |
| `windows-latest` | `andro-<version>-win-x64.exe` (NSIS) |
| `macos-latest` | `andro-<version>-mac-x64.dmg` |

`workflow_dispatch` builds the same thing from a manual version input.

## Where installers are published

Installers go to a **separate public repo**, `chandruSofttwig/andro-releases`,
while this source repo stays private. Release assets on a private repo require
an authenticated token, so a public download link would 404 for everyone. (Same
split Obsidian uses: `obsidianmd/obsidian-releases` is public, the app is not.)

The repo holds no code — only Releases.

## Required secrets

| Secret | Why |
|---|---|
| `ANDRO_CLERK_PUBLISHABLE_KEY` | Clerk **production** key (`pk_live_…`). CI writes it to `.env.production.local`. |
| `ANDRO_RELEASES_TOKEN` | Fine-grained PAT, `contents: write` on **andro-releases only**. |

The job **fails fast** if either is missing, or if the Clerk key is a `pk_test_`
key — an installer without a live key ships an app where Trial sign-in cannot
work, and test instances are rate limited and show dev banners.

The built-in `GITHUB_TOKEN` is scoped to the repo running the workflow and
**cannot** publish to another repo, which is why the PAT exists. This workflow
therefore only needs `contents: read` on the source repo.

### One-time setup

```bash
gh repo create chandruSofttwig/andro-releases --public \
  --description "Installers for the Andro desktop app"
```

Then create the PAT (Settings → Developer settings → Fine-grained tokens):
repository access **andro-releases only**, permission **Contents: Read and
write**. Add it to the source repo as `ANDRO_RELEASES_TOKEN`.

The target repo is named in two places — keep them in sync:
`build.publish` in [`andro/package.json`](../../andro/package.json) and
`desktopRelease.releasesRepo` in [`landing/lib/site-content.ts`](../../landing/lib/site-content.ts).

## Build-time config

Vite inlines `VITE_*` at build time, so a released build talks to whatever was
set when it was built. [`andro/.env.production`](../../andro/.env.production) is
committed and holds the public values:

| Var | Value |
|---|---|
| `VITE_ANDROMEDIA_API_URL` | `https://tuuf7zapih.ap-south-1.awsapprunner.com` (Trial) |
| `VITE_AUTH_WEB_URL` | `https://andro-app.vercel.app` |
| `VITE_CLERK_PUBLISHABLE_KEY` | blank — injected by CI |

`.env.production.local` (gitignored via `*.local`) overrides it and is where the
secret lands. Local Pro still defaults to the user's own `localhost:8083`.

Verify before announcing a build:

```bash
cd andro && npm run build
grep -o "https://tuuf7zapih[a-z0-9.-]*" dist/assets/*.js | sort -u   # must appear
```

## Open blocker

**Unsigned builds.** No signing identity in CI
(`CSC_IDENTITY_AUTO_DISCOVERY=false`, `mac.identity: null`), so Windows shows a
SmartScreen block and macOS says the app is damaged. The download page carries
the click-through steps per OS. Proper fix is an EV/OV cert for Windows and an
Apple Developer ID (~$99/yr) plus notarization.

Until the first release exists in `andro-releases`, the GitHub API returns 404,
the download page's fetch resolves to null, and it renders "builds aren't
published yet" — no dead buttons. Nothing to toggle; it lights up on its own.

## Before the download link is public

The **HARD GATE** from [`mvp-readiness-mcp-check.md`](mvp-readiness-mcp-check.md)
applies directly here: trial users all land on the hosted App Runner API whose
Groq / DeepSeek / RDS keys were exposed in describe output. Rotate and confirm
before publishing a download link.
