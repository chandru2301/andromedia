import { BRAND_META } from "@/data/integration-brands";
import type { IntegrationId } from "@/types/product-config";
import { cn } from "@/lib/utils";

const sizeMap = {
  sm: 20,
  md: 24,
  lg: 28,
} as const;

/** Official brand logo SVG (multicolor where provided by the brand). */
export function IntegrationIcon({
  id,
  size = "sm",
  className,
}: {
  id: IntegrationId;
  size?: keyof typeof sizeMap;
  className?: string;
}) {
  const meta = BRAND_META[id];
  const px = sizeMap[size];

  return (
    <img
      src={meta.logo}
      alt={meta.title}
      width={px}
      height={px}
      className={cn("shrink-0 object-contain", className)}
      draggable={false}
    />
  );
}
