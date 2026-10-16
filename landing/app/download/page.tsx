import type { Metadata } from "next";
import { DownloadPage } from "@/components/landing/download-page";
import { SiteFooter } from "@/components/landing/site-footer";
import { SiteHeader } from "@/components/landing/site-header";
import { getLatestDesktopRelease } from "@/lib/desktop-releases";

export const metadata: Metadata = {
  title: "Download",
  description:
    "Download the Andro desktop app for Windows, macOS or Linux and start a trial against Andro's hosted service.",
};

export default async function DownloadRoute() {
  const release = await getLatestDesktopRelease();

  return (
    <>
      <SiteHeader />
      <DownloadPage release={release} />
      <SiteFooter />
    </>
  );
}
