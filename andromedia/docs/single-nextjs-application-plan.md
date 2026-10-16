# Single Next.js Application Plan (Andro + Landing)

## Goal

Unify the current frontend split into a single Next.js application:

- `landing/` (Next.js App Router marketing site)
- `andro/` (Vite + React product UI: Sources + Investigate + Settings)

Backend (`andromedia/` Spring Boot multi-module) remains unchanged.

## Current State Analysis

### 1) `landing` (already Next.js)

- Framework: Next.js 16 App Router, React 19, Tailwind v4.
- Uses route `"/"` for the marketing page.
- UI structure is section-based and content-driven (`landing/lib/site-content.ts`).
- Has design system + motion primitives already compatible with Next client components.

### 2) `andro` (Vite SPA)

- Framework: Vite + React 19 + Tailwind v4.
- App entry is SPA state-based page switching (`src/App.tsx`) between:
  - Sources dashboard (`src/pages/DashboardPage.tsx`)
  - Investigate page (`src/pages/investigation-page.tsx`)
- Uses providers in `src/main.tsx`:
  - `ThemeProvider`
  - `AndroConfigProvider`
  - `Toaster`
- Calls backend via `import.meta.env.VITE_ANDROMEDIA_API_URL` fallback to `http://localhost:8083` (`src/lib/andromediaApi.ts`).

## Recommended Unification Architecture

Use `landing` as the base Next.js app and migrate `andro` into it.

### Why this direction

- `landing` is already App Router and actively maintained.
- Avoids running two React apps and two build systems.
- Lets marketing + product share one design system, one deployment, one auth/session model later.
- Lowest total migration risk vs rewriting `landing` into Vite.

## Target Route Model

```text
app/
  (marketing)/
    page.tsx                -> "/"
  app/                      -> "/app" (product shell)
    page.tsx                -> Sources dashboard default
    investigate/page.tsx    -> Investigate
    settings/page.tsx       -> optional dedicated settings route
```

### Mapping from current `andro` UI

- `activePage === "sources"` -> `/app`
- `activePage === "investigate"` -> `/app/investigate`
- Settings dialog can remain modal-first in shell or move to `/app/settings`.

## Folder Strategy

Inside `landing/`:

```text
components/
  landing/                  # existing marketing components
  app-shell/                # migrated product layout (top nav, status bar)
  ui/                       # shared shadcn primitives
hooks/
  useAndroConfig.tsx
  useIndexOverview.ts
  useTheme.tsx
lib/
  api/andromedia.ts         # migrated from andro/src/lib/andromediaApi.ts
app/
  (marketing)/...
  app/...
```

Keep branding assets from both projects under `public/images/` and `public/brands/` with normalized names.

## Technical Migration Workstreams

### Workstream A: Tooling/Base

1. Keep `landing` scripts as the only frontend scripts (`dev/build/lint`).
2. Remove Vite-specific assumptions from migrated code:
   - Replace `import.meta.env.VITE_*` with `process.env.NEXT_PUBLIC_*`.
3. Add env contract in Next:
   - `NEXT_PUBLIC_ANDROMEDIA_API_URL` (default `http://localhost:8083`).

### Workstream B: Providers and App Shell

1. Port `AndroConfigProvider`, `ThemeProvider`, `Toaster` into Next component tree.
2. Mount providers in a route-group layout for `/app` only, not marketing pages.
3. Port `AppShell`, `TopNavbar`, and `StatusBar` into `components/app-shell/`.

### Workstream C: Page Migration

1. Move `DashboardPage` to `/app/page.tsx`.
2. Move `InvestigationPage` to `/app/investigate/page.tsx`.
3. Keep the same UI logic first; defer behavior changes until parity is verified.

### Workstream D: API Layer

1. Move `andro/src/lib/andromediaApi.ts` into `landing/lib/api/andromedia.ts`.
2. Replace env lookup:
   - from `import.meta.env.VITE_ANDROMEDIA_API_URL`
   - to `process.env.NEXT_PUBLIC_ANDROMEDIA_API_URL`.
3. Keep browser `fetch` contract unchanged to minimize backend impact.

### Workstream E: Styling and Assets

1. Reconcile CSS tokens and Tailwind utility usage between both apps.
2. Keep dark theme semantics from landing as the canonical theme.
3. Audit icon sets and duplicate UI primitives to avoid drift.

### Workstream F: Cutover

1. Run product parity checks on:
   - Sources integration cards
   - MCP tools toggles
   - Investigate query flow and evidence rendering
2. Point deployment to single Next app.
3. Archive/deprecate `andro/` Vite app after parity sign-off.

## Suggested Phased Rollout

### Phase 1: Shell + routing skeleton

- Create `/app` route group and provider layout.
- Port shell and static placeholders.

### Phase 2: Sources dashboard parity

- Migrate dashboard page and config hooks.
- Verify index overview + integration actions.

### Phase 3: Investigate parity

- Migrate investigate page and API calls.
- Validate result formatting, evidence, and trust-state UX.

### Phase 4: Cleanup and consolidation

- Remove Vite-only codepaths and duplicate assets/components.
- Update docs, CI, and local setup instructions.

## Risks and Mitigations

1. **Env variable mismatch**
   - Risk: Product pages fail due to unresolved `VITE_*` usage.
   - Mitigation: Introduce `NEXT_PUBLIC_ANDROMEDIA_API_URL` adapter first and grep for `import.meta.env`.

2. **Provider lifecycle differences**
   - Risk: State resets across route transitions.
   - Mitigation: Keep providers in `/app` layout so state survives page navigation.

3. **UI regressions due to token differences**
   - Risk: Inconsistent spacing/colors between migrated and native Next components.
   - Mitigation: Define landing tokens as source of truth and run visual checks per page.

4. **Client/server boundary issues**
   - Risk: Components relying on browser APIs break if treated as server components.
   - Mitigation: Mark migrated interactive components with `"use client"` at file top.

## Acceptance Criteria

- One frontend app (`landing`) serves both marketing and product UI.
- Product routes accessible at `/app` and `/app/investigate`.
- No dependency on Vite runtime or `import.meta.env`.
- Build and run via Next only (`npm run dev`, `npm run build` in `landing`).
- `andro/` can be frozen/deprecated without losing functionality.

## Concrete First Implementation Slice

If implementing incrementally, start with this minimum vertical slice:

1. Add `/app/page.tsx` in `landing`.
2. Port `AndroConfigProvider`, `ThemeProvider`, `Toaster` into `landing`.
3. Migrate `DashboardPage` + `andromediaApi` with env adapter.
4. Verify end-to-end interaction with backend at `http://localhost:8083`.

This yields early proof that a single Next.js app can host product functionality before migrating investigate and remaining UI.
