import { SignIn } from "@clerk/nextjs";
import { AuthShell } from "@/components/landing/auth-shell";
import { androClerkAppearance } from "@/lib/clerk-appearance";

export default function SignInPage() {
  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in to open your Andro trial — investigate across Slack, Notion, Jira, Linear, and Zoho with a shared memory."
    >
      <SignIn
        routing="path"
        path="/sign-in"
        signUpUrl="/sign-up"
        forceRedirectUrl="/app"
        appearance={androClerkAppearance}
      />
    </AuthShell>
  );
}
