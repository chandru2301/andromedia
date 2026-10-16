# Andro product frontend (deprecated)

**This Vite app is deprecated.** Product UI now lives in the unified Next.js app under [`../landing/`](../landing/).

Use these routes instead:

| Old (Vite) | New (Next.js) |
| --- | --- |
| Sources dashboard | [`http://localhost:3000/app`](../landing/app/app/page.tsx) |
| Investigate | [`http://localhost:3000/app/investigate`](../landing/app/app/investigate/page.tsx) |

## Running the unified app

```bash
cd ../landing
npm install
npm run dev
```

Start the Andromedia backend on port 8083, then open `http://localhost:3000/app`.

This directory is kept for reference during migration cleanup. Do not add new features here.
