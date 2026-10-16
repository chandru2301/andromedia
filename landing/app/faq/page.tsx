import type { Metadata } from "next";
import { FaqPage } from "@/components/landing/faq-page";
import { SiteFooter } from "@/components/landing/site-footer";
import { SiteHeader } from "@/components/landing/site-header";
import { faqMeta } from "@/lib/faq-content";

export const metadata: Metadata = {
  title: faqMeta.title,
  description: faqMeta.description,
};

export default function FaqRoute() {
  return (
    <>
      <SiteHeader />
      <FaqPage />
      <SiteFooter />
    </>
  );
}
