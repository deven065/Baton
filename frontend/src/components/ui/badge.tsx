import { Lock, X } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import type { Sensitivity, Status } from '@/data/types';

const STATUS: Record<Status, { label: string; fg: string; bg: string; dot: string }> = {
  verified: { label: 'Verified', fg: 'text-verified', bg: 'bg-verified-bg', dot: 'bg-verified' },
  single: { label: 'Single source', fg: 'text-single', bg: 'bg-single-bg', dot: 'bg-single' },
  inferred: { label: 'Inferred', fg: 'text-inferred', bg: 'bg-inferred-bg', dot: 'bg-inferred' },
  conflicting: { label: 'Conflicting', fg: 'text-conflict', bg: 'bg-conflict-bg', dot: 'bg-conflict' },
  missing: { label: 'Missing evidence', fg: 'text-missing', bg: 'bg-missing-bg', dot: 'bg-missing' },
  stale: { label: 'Stale', fg: 'text-stale', bg: 'bg-stale-bg', dot: 'bg-stale' },
};

export const statusDot = (s: Status) => STATUS[s].dot;
export const statusLabel = (s: Status) => STATUS[s].label;

/** Evidence status — computed by code. Colour always comes with a text label. */
export function StatusBadge({ status, label, className }: { status: Status; label?: string; className?: string }) {
  const s = STATUS[status];
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-caption font-medium whitespace-nowrap', s.bg, s.fg, className)}>
      <span className={cn('size-1.5 rounded-full', s.dot)} aria-hidden />
      {label ?? s.label}
    </span>
  );
}

const SENS: Record<Sensitivity, string> = {
  Public: 'bg-subtle text-ink-500',
  Internal: 'bg-single-bg text-single',
  Confidential: 'bg-confidential-bg text-confidential',
  Restricted: 'bg-ink-900 text-white',
};

/** A claim inherits the highest sensitivity of its evidence. */
export function SensitivityChip({ level, className }: { level: Sensitivity; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-caption font-medium whitespace-nowrap', SENS[level], className)}>
      <Lock className="size-[11px]" strokeWidth={2.25} aria-hidden />
      {level}
    </span>
  );
}

export function Chip({ children, active, removable, className }: { children: ReactNode; active?: boolean; removable?: boolean; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-caption font-medium whitespace-nowrap',
        active ? 'bg-ink-900 text-white' : 'bg-subtle text-ink-700',
        className,
      )}
    >
      {children}
      {removable && <X className="size-3 text-ink-400" aria-hidden />}
    </span>
  );
}

export function FilterChip({ children, active, onClick }: { children: ReactNode; active?: boolean; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-full px-2.5 py-1 text-caption font-medium whitespace-nowrap cursor-pointer transition-colors',
        active ? 'bg-ink-900 text-white' : 'bg-card text-ink-700 border border-line hover:bg-subtle',
      )}
    >
      {children}
    </button>
  );
}
