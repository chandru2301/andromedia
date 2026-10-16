import { SignUp } from "@clerk/nextjs";
import { AuthShell } from "@/components/landing/auth-shell";
import { androClerkAppearance } from "@/lib/clerk-appearance";

export default function SignUpPage() {
  return (
    <AuthShell
      title="Create your account"
      subtitle="Start a hosted trial in minutes. Connect sources, ask grounded questions, and see conflicts before they become surprises."
    >
      <SignUp
        routing="path"
        path="/sign-up"
        signInUrl="/sign-in"
        forceRedirectUrl="/app"
        appearance={androClerkAppearance}
      />
    </AuthShell>
  );
}
