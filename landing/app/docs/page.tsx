import type { Metadata } from "next";
import { DocsPage } from "@/components/landing/docs-page";
import { SiteFooter } from "@/components/landing/site-footer";
import { SiteHeader } from "@/components/landing/site-header";
import { docsMeta } from "@/lib/docs-content";

export const metadata: Metadata = {
  title: docsMeta.title,
  description: docsMeta.description,
};

export default function DocsRoute() {
  return (
    <>
      <SiteHeader />
      <DocsPage />
      <SiteFooter />
    </>
  );
}
