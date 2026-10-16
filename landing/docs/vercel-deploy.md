# Vercel deploy (landing)

## Project settings

| Setting | Value |
|---------|--------|
| Root Directory | `landing` |
| Framework Preset | Next.js |
| Build Command | `next build` (default) |
| Install Command | `npm install` (default) |
| Node | 20.x recommended |

Production API (existing App Runner):

`https://tuuf7zapih.ap-south-1.awsapprunner.com`

## Environment variables

Copy from [`.env.example`](../.env.example) into Vercel → Settings → Environment Variables (Production + Preview as needed):

| Name | Required | Notes |
|------|----------|--------|
| `NEXT_PUBLIC_ANDROMEDIA_API_URL` | yes | App Runner URL, no trailing slash. Use **`--no-sensitive`** so Next.js can inline it at build time. |
| `NEXT_PUBLIC_SITE_URL` | yes | Canonical site URL (e.g. `https://andro-app.vercel.app`) |
| `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY` | yes | Clerk |
| `CLERK_SECRET_KEY` | yes | Clerk |
| `NEXT_PUBLIC_ANDRO_DEV_MODE` | no | Must be unset/`false` in production |
| `RESEND_API_KEY` | no | Waitlist / feedback |
| `FEEDBACK_TO_EMAIL` | no | Feedback inbox |
| `RESEND_FROM` | no | Optional from-address |

## Deploy

### Dashboard

1. Import the GitHub repo into Vercel.
2. Set **Root Directory** to `landing`.
3. Add env vars above.
4. Deploy.

### CLI

```bash
npm i -g vercel
cd landing
vercel link
vercel env add NEXT_PUBLIC_ANDROMEDIA_API_URL
# …add remaining vars, or paste in dashboard
vercel --prod
```

## After deploy

1. Confirm App Runner `ANDRO_CORS_ORIGINS` includes your Vercel origin.
2. In Clerk, allow the Vercel domain for sign-in redirects.
3. Open `/app`, sign in, configure a source, run Investigate.

Full AWS glue: [aws-apprunner-rds-vercel.md](../../andromedia/docs/aws-apprunner-rds-vercel.md).
