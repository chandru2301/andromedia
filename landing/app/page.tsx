import { SiteHeader } from "@/components/landing/site-header";
import { Hero } from "@/components/landing/hero";
import { MemoryPitch } from "@/components/landing/memory-pitch";
import { ConnectorGrid } from "@/components/landing/connector-grid";
import { HowItWorks } from "@/components/landing/how-it-works";
import { TrustSection } from "@/components/landing/trust-section";
import { PricingSection } from "@/components/landing/pricing-section";
import { SocialProof } from "@/components/landing/social-proof";
import { FooterCta } from "@/components/landing/footer-cta";
import { SiteFooter } from "@/components/landing/site-footer";

export default function Home() {
  return (
    <>
      <SiteHeader />
      <main className="flex-1">
        <Hero />
        <MemoryPitch />
        <ConnectorGrid />
        <HowItWorks />
        <TrustSection />
        <PricingSection />
        <SocialProof />
        <FooterCta />
      </main>
      <SiteFooter />
    </>
  );
}
