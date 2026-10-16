import { cn } from '@/lib/utils';

export type ClientIconId = 'claude' | 'cursor' | 'windsurf';

interface ClientIconProps {
  id: ClientIconId;
  size?: number;
  className?: string;
}

/** Simple original glyphs standing in for each MCP client — not traced brand marks. */
function ClaudeGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M12 2.5l1.8 6.2 6.2 1.8-6.2 1.8L12 18.5l-1.8-6.2-6.2-1.8 6.2-1.8L12 2.5z"
        fill="#DA7756"
      />
    </svg>
  );
}

function CursorGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M5 3l14 8.2-6.3 1.4-2.9 6-4.8-15.6z" fill="currentColor" />
    </svg>
  );
}

function WindsurfGlyph() {
  return (
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path
        d="M3 15c2.5 1.6 4.8 1.6 7 0 2.5-1.8 4.5-1.8 7 0 1 .7 2.2 1 4 .8"
        stroke="#2AA9B8"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
      <path
        d="M3 19c2.5 1.6 4.8 1.6 7 0 2.5-1.8 4.5-1.8 7 0 1 .7 2.2 1 4 .8"
        stroke="#2AA9B8"
        strokeWidth="1.8"
        strokeLinecap="round"
        opacity="0.5"
      />
      <path d="M11 4v11" stroke="#2AA9B8" strokeWidth="1.8" strokeLinecap="round" />
      <path d="M11 4l5 3.5-5 2.5V4z" fill="#2AA9B8" />
    </svg>
  );
}

const GLYPHS: Record<ClientIconId, () => React.ReactElement> = {
  claude: ClaudeGlyph,
  cursor: CursorGlyph,
  windsurf: WindsurfGlyph,
};

export function ClientIcon({ id, size = 20, className }: ClientIconProps) {
  const Glyph = GLYPHS[id];
  return (
    <span
      className={cn('inline-flex shrink-0 items-center justify-center', className)}
      style={{ width: size, height: size }}
    >
      <Glyph />
    </span>
  );
}
