# Andro Landing UI — Complete Documentation

Marketing landing page for **Andro**: a local-first context layer that indexes Git, Slack, Notion, Jira, Linear, and Zoho CRM, then grounds Cursor / Claude Code answers over MCP.

This document describes the **UI as implemented** in the `landing/` Next.js app — layout, sections, design system, content model, motion, SEO, and launch-gating behavior.

---

## 1. Purpose and positioning

| Aspect | Detail |
| --- | --- |
| Product | Andro |
| Tagline / headline | “Your AI trusts your CRM. It shouldn't.” |
| Audience | Developers / teams using AI coding agents (Cursor, Claude Code) and CRM-grounded questions |
| Core claim | Same question Zoho’s AI failed — Andro grounded against a local index |
| Trust claim | Proof first (Zia vs Andro), then local infrastructure / no Andro data plane |
| Proof style | Real screenshots (`zoho.png` / `andro.png`); honest waitlist / disabled / hidden states |

The page is **proof-led and launch-honest**: unfinished surfaces (billing, demo booking, public GitHub, testimonials) render as waitlist, disabled, or hidden — never fake checkout or invented quotes.

---

## 2. Project identity

| Item | Value |
| --- | --- |
| Location | `/landing` (standalone from `andromedia` backend and `andro` product frontend) |
| Framework | Next.js **16** App Router (Turbopack in dev) |
| UI | React **19**, Tailwind CSS **v4**, shadcn/ui (`radix-nova` / Geist + Lucide) |
| Motion | `motion` (Framer Motion successor), reduced-motion aware |
| Fonts | **Geist** (sans / headings), **IBM Plex Mono** (technical / code-flavored text) |
| Theme | Dark-only (`html` always has `class="dark"`) |
| Canonical content | `lib/site-content.ts` — all copy, URLs, connector status, pricing flags |
| Site URL | `lib/site-url.ts` via `NEXT_PUBLIC_SITE_URL` (fallback `http://localhost:3000`) |

### Local commands

```bash
cd landing
npm install
npm run dev      # http://localhost:3000
npm run lint
npm run build
```

---

## 3. Information architecture

Single route: `/` (`app/page.tsx`). Sections assemble top → bottom:

```text
SiteHeader (sticky)
└── main
    ├── Hero
    ├── ContrastSection      (#the-difference — side-by-side Zia vs Andro screenshots)
    ├── HowItWorks           (#how-it-works)
    ├── TrustSection         (proof lead + local-first)
    ├── ConnectorGrid        (#sources + Zoho depth note)
    ├── PricingSection       (#pricing — waitlist when !billingLive)
    ├── SocialProof          (renders null until quote set)
    └── FooterCta
SiteFooter
```

### Anchor navigation

| Label | Target |
| --- | --- |
| How it works | `#how-it-works` |
| Sources | `#sources` |
| Pricing | `#pricing` |
| Get started (header CTA) | `/docs` via `siteConfig.getStartedUrl` |

Mobile: primary nav links are hidden below `md`; brand + Get started button remain.

---

## 4. Section-by-section UI

### 4.1 Site header (`site-header.tsx`)

- Sticky bar: `backdrop-blur`, semi-transparent black, hairline bottom border.
- Left: monospace lowercase brand (`andro`).
- Center (md+): muted nav links that brighten on hover.
- Right: primary `Button` → Get started.

### 4.2 Hero (`hero.tsx`)

**First viewport composition**

1. Stacked headline: “Your AI trusts your CRM.” / “It shouldn't.”
2. One supporting paragraph (local index + MCP grounding).
3. CTA group: primary “Get started free”; secondary “Watch demo” (disabled until `demoBookingUrl`).
4. Full-width `andro.png` product shot.
5. Caption: CRM License Renewal grounded answer (Zia contrast lives in the proof section below).

**Background**

The section renders a vendored, mint-tuned React Bits `FloatingLines` (three.js) behind the copy — [`components/landing/hero-background.tsx`](../components/landing/hero-background.tsx) wraps [`components/react-bits/floating-lines.tsx`](../components/react-bits/floating-lines.tsx) with a restrained gradient (`#1a1a1a` → `#3dd68c` → `#111111`), slow animation speed, and a bottom fade mask so it doesn't collide with the product screenshot. It renders nothing under `prefers-reduced-motion`, matching `Reveal`.

**Typography**

- Headline: `clamp(2.75rem, 8vw, 5.5rem)`, semibold, tight tracking, `text-balance`.
- Subtext: muted gray, max-width ~2xl, centered.

**Image**

| Prop | Value |
| --- | --- |
| Path | `/images/andro.png` |
| Loading | `priority` + responsive `sizes` |
| Frame | `rounded-xl`, `border-border`, `bg-card` |

### 4.3 Contrast / proof (`contrast-section.tsx`)

`id="the-difference"`. Eyebrow + heading + shared question in mono. Content width `max-w-5xl`.

**Side-by-side raw screenshots** (`contrast.isProof: true`) — no card frames, labels, captions, or meta rows. Just the images edge to edge (`gap-2`, `overflow-hidden rounded-lg`):

| Panel | Asset |
| --- | --- |
| Zia (Zoho CRM) | `/images/zoho.png` |
| Andro | `/images/andro.png` |

Question shown: “Which account should we focus on most this quarter and why?”

Image `sizes`: `(max-width: 768px) 100vw, 512px`. Disclaimer markup remains in code for `isProof === false` but is not shown for the shipped CRM proof.

### 4.4 How it works (`how-it-works.tsx`)

Three equal cards (`md:grid-cols-3`): connect sources → ask in Cursor/Claude Code → grounded answers with sources.

### 4.5 Trust (`trust-section.tsx`)

Two stacked cards:

1. **Proof lead** — “Zoho’s own AI couldn’t answer this. Andro did.” + link to `#the-difference`.
2. **Local-first** — infrastructure you control, no Andro data plane, you choose the model + setup guide link.

### 4.6 Sources / connectors (`connector-grid.tsx`)

Responsive grid with LIVE / SOON badges. Zoho CRM has an info tooltip for the CRM intelligence layer; a footnote under the grid states depth honesty for other LIVE sources.

### 4.7 Pricing (`pricing-section.tsx`)

When `billingLive === false` (current): single waitlist card — **no Free/Pro dollar amounts**.

When `billingLive === true`: two plan cards from `pricing.plans`.

Cards use vendored React Bits `BorderGlow` ([`components/react-bits/border-glow.tsx`](../components/react-bits/border-glow.tsx)) with mint-tuned glow props (`#3dd68c` mesh, dark `#0a0a0a` fill) — pointer edge glow only (`animated={false}`).

### 4.8 Social proof (`social-proof.tsx`)

`socialProof` is `null` → component returns `null`. Set `{ quote, attribution }` in `site-content.ts` to show a real quote only.

### 4.9 Footer CTA + site footer

Command pill `docker compose up`; docs / GitHub / demo links with gating. Minimal copyright footer.

---

## 5. Design system

### 5.1 Design DNA

Dark-only, restrained, proof-led:

- Near-black background, white foreground
- Single muted gray for secondary text
- Hairline borders (`#1a1a1a`)
- **One** accent: mint `#3dd68c`

`:root` and `.dark` share the same palette so there is no light-mode flash.

### 5.2 Color tokens (`app/globals.css`)

| Token | Hex / role |
| --- | --- |
| `--background` | `#000000` |
| `--foreground` | `#ffffff` |
| `--card` | `#0a0a0a` |
| `--muted-foreground` | `#888888` |
| `--border` / `--accent` | `#1a1a1a` |
| `--mint` | `#3dd68c` |
| `--primary` | white button on black text |
| `--radius` | `0.5rem` base |

### 5.3 Typography

| Role | Font | Usage |
| --- | --- | --- |
| Body / headings | Geist | Headlines, body, UI |
| Technical | IBM Plex Mono | Brand, eyebrows, prices, chips, LIVE/SOON, command CTA |

### 5.4 Layout rhythm

- Section padding: typically `py-24`
- Content width: `max-w-5xl` (most), `max-w-4xl` (pricing), `max-w-3xl` (trust), header `max-w-6xl`
- Section separators: `border-t border-border`

---

## 6. Motion

`components/landing/reveal.tsx` — fade + 16px translate; `prefers-reduced-motion` disables animation.

---

## 7. Content model and launch gating

**Single source of truth:** `lib/site-content.ts`

| Flag / field | Current | UI effect |
| --- | --- | --- |
| `siteConfig.githubUrl` | `null` | Footer “GitHub” disabled |
| `siteConfig.demoBookingUrl` | `null` | Hero “Watch demo” / footer demo disabled |
| `pricing.billingLive` | `false` | Waitlist block, no dollar cards |
| `contrast.isProof` | `true` | Real `zoho.png` / `andro.png` panels |
| `socialProof` | `null` | Section hidden |
| `connectors[].note` | Zoho only | Tooltip + footnote |

---

## 8. SEO and metadata

- `getSiteUrl()` from `NEXT_PUBLIC_SITE_URL` (default localhost)
- Title / OG / Twitter: “Your AI trusts your CRM. It shouldn't.”
- OG/Twitter image: `/images/andro.png` (exists)
- `app/sitemap.ts`, `app/robots.ts` use the same helper

Set `NEXT_PUBLIC_SITE_URL` to the real domain before production — never claim a domain you do not own.

---

## 9. File map

```text
landing/
├── app/
│   ├── layout.tsx
│   ├── page.tsx
│   ├── globals.css
│   ├── sitemap.ts
│   ├── robots.ts
│   └── icon.svg
├── components/
│   ├── ui/
│   └── landing/
│       ├── site-header.tsx
│       ├── hero.tsx
│       ├── contrast-section.tsx
│       ├── how-it-works.tsx
│       ├── trust-section.tsx
│       ├── connector-grid.tsx
│       ├── pricing-section.tsx
│       ├── social-proof.tsx
│       ├── footer-cta.tsx
│       ├── site-footer.tsx
│       └── reveal.tsx
├── lib/
│   ├── site-content.ts
│   ├── site-url.ts
│   └── utils.ts
├── public/images/
│   ├── zoho.png          # Zia failure proof
│   ├── andro.png         # Andro success + hero / OG
│   └── dashboard-hero.png  # legacy capture (optional)
└── docs/
    └── landing-ui.md
```

---

## 10. Known gaps

| Gap | Status |
| --- | --- |
| Zia vs Andro CRM proof | Shipped (`zoho.png` / `andro.png`) |
| Cursor MCP without/with pair | Optional future capture |
| Billing / dollar pricing | Waitlist only until `billingLive` |
| Social proof | Gated (`socialProof = null`) |
| Production domain | Set `NEXT_PUBLIC_SITE_URL` before deploy |

---

## 11. Editing playbook

| Goal | Where to change |
| --- | --- |
| Headline / CTAs / pricing / connectors / proof captions | `lib/site-content.ts` |
| Site / OG URL | `NEXT_PUBLIC_SITE_URL` + `lib/site-url.ts` |
| Reorder sections | `app/page.tsx` |
| Visual tokens | `app/globals.css` |
| Section UI | `components/landing/*` |

---

## 12. Related docs

- [`landing/README.md`](../README.md) — setup, gating, deploy
- [`andromedia/docs/product-positioning.md`](../../andromedia/docs/product-positioning.md)
- [`andromedia/docs/application-setup-guide.md`](../../andromedia/docs/application-setup-guide.md)

---

*Keep this doc in sync with `site-content.ts` and section components when UI behavior changes.*
