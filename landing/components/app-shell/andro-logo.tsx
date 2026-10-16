import Image from "next/image";
import { siteConfig } from "@/lib/site-content";

export function AndroLogo() {
  return (
    <span className="flex items-center gap-2 font-mono text-sm font-medium tracking-tight">
      <Image src="/brand/andro-mark.png" alt="" width={20} height={20} className="size-5" priority />
      {siteConfig.name.toLowerCase()}
    </span>
  );
}
