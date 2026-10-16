"use client";

import { useEffect, useState } from "react";
import { useReducedMotion } from "motion/react";
import { FloatingLines } from "@/components/react-bits/floating-lines";

function usePrefersFinePointer() {
  const [finePointer, setFinePointer] = useState(true);

  useEffect(() => {
    const update = () => {
      const coarse = window.matchMedia("(pointer: coarse)").matches;
      const narrow = window.matchMedia("(max-width: 768px)").matches;
      setFinePointer(!coarse && !narrow);
    };
    update();
    window.addEventListener("resize", update);
    return () => window.removeEventListener("resize", update);
  }, []);

  return finePointer;
}

/**
 * Restrained, mint-tuned FloatingLines background for the hero.
 * Renders nothing under prefers-reduced-motion, matching Reveal's behavior.
 * Bottom fade keeps the effect from visually colliding with the product screenshot.
 */
export function HeroBackground() {
  const shouldReduceMotion = useReducedMotion();
  const finePointer = usePrefersFinePointer();

  if (shouldReduceMotion) {
    return null;
  }

  const interactive = finePointer;

  return (
    <div
      aria-hidden
      className={`absolute inset-0 isolate opacity-40 dark:opacity-100 [contain:strict] ${interactive ? "" : "pointer-events-none"}`}
    >
      <FloatingLines
        enabledWaves={["top", "middle", "bottom"]}
        lineCount={[4, 5, 4]}
        lineDistance={[9, 7, 11]}
        linesGradient={["#155d3a", "#3dd68c", "#eafbf1"]}
        animationSpeed={0.6}
        interactive={interactive}
        bendRadius={5.0}
        bendStrength={-0.5}
        parallax={interactive}
        parallaxStrength={0.12}
        mixBlendMode="screen"
        resolutionScale={finePointer ? 0.75 : 0.6}
        targetFps={30}
      />
      {/* Cheaper than mask-image on a live WebGL canvas */}
      <div className="absolute inset-0 bg-gradient-to-b from-transparent from-60% to-background" />
    </div>
  );
}
