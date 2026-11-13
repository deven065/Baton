import { AlertTriangle, AudioLines, Check, FileText, RefreshCw, X, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { PlayMomentButton } from './recording';

/** The Baton mark: a lowercase b whose stem is the baton and whose amber bowl is being handed on. */
export function LogoMark({ size = 28, dark, animate, className }: { size?: number; dark?: boolean; animate?: boolean; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden className={cn('logo-mark', animate && 'logo-draw', className)}>
      <path className="logo-stem" d="M7.25 4.5V26.5" stroke={dark ? '#FFFFFF' : '#0E1726'} strokeWidth="5.5" strokeLinecap="round" />
      <path className="logo-bowl" d="M13.32 14.3A7 7 0 1 1 13.32 24.7" stroke="#E8A33D" strokeWidth="5.5" strokeLinecap="round" />
    </svg>
  );
}

export function Logo({ dark, size = 28, big, animate }: { dark?: boolean; size?: number; big?: boolean; animate?: boolean }) {
  return (
    <span className="logo inline-flex items-center gap-2">
      <LogoMark size={size} dark={dark} animate={animate} />
      <span className={cn('logo-word font-semibold tracking-[-0.035em]', big ? 'text-h1' : 'text-[21px] leading-7', dark ? 'text-white' : 'text-ink-900')}>baton</span>
    </span>
  );
}

const AV_TONES = [
  'bg-brand-100 text-brand-700',
  'bg-verified-bg text-verified',
  'bg-inferred-bg text-inferred',
  'bg-missing-bg text-missing',
  'bg-single-bg text-single',
  'bg-confidential-bg text-confidential',
];

export function Avatar({ initials, tone = 0, size = 'md', ring, className }: { initials: string; tone?: number; size?: 'sm' | 'md' | 'lg'; ring?: boolean; className?: string }) {
  const s = { sm: 'size-6 text-caption', md: 'size-8 text-caption', lg: 'size-11 text-h3' }[size];
  return (
    <span aria-hidden className={cn('grid shrink-0 place-items-center rounded-full font-medium', s, AV_TONES[tone % AV_TONES.length], ring && 'ring-2 ring-card', className)}>
      {initials}
    </span>
  );
}

export function AvatarStack({ people }: { people: { initials: string; tone: number }[] }) {
  return (
    <span className="flex -space-x-1.5">
      {people.map(p => <Avatar key={p.initials} initials={p.initials} tone={p.tone} size="sm" ring />)}
    </span>
  );
}

export function Card({ className, children, as: As = 'section' }: { className?: string; children: ReactNode; as?: 'section' | 'div' | 'article' }) {
  return <As className={cn('rounded-xl border border-line bg-card p-5 shadow-card', className)}>{children}</As>;
}

export function CardHeader({ title, sub, right }: { title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <div>
        <h2 className="text-h3 font-semibold">{title}</h2>
        {sub && <p className="text-small text-ink-500">{sub}</p>}
      </div>
      {right}
    </div>
  );
}

export function PageHeader({ title, sub, right }: { title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="max-w-3xl">
        <h1 className="text-h1 font-semibold">{title}</h1>
        {sub && <p className="mt-1 text-body text-ink-500">{sub}</p>}
      </div>
      {right && <div className="flex gap-2">{right}</div>}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="rounded border border-line-strong bg-card px-1.5 font-mono text-caption text-ink-700">{children}</kbd>;
}

export function Progress({ value, tone = 'bg-brand-600', className, label }: { value: number; tone?: string; className?: string; label?: string }) {
  return (
    <div role="progressbar" aria-label={label} aria-valuenow={Math.round(value * 100)} aria-valuemin={0} aria-valuemax={100} className={cn('h-1.5 w-full overflow-hidden rounded-full bg-subtle', className)}>
      <div className={cn('h-full rounded-full', tone)} style={{ width: `${Math.max(3, value * 100)}%` }} />
    </div>
  );
}

export function Segments({ n, total, tone = 'bg-brand-600' }: { n: number; total: number; tone?: string }) {
  return (
    <span className="flex gap-[3px]" aria-hidden>
      {Array.from({ length: total }, (_, i) => <span key={i} className={cn('h-1 w-[26px] rounded-full', i < n ? tone : 'bg-line')} />)}
    </span>
  );
}

export type StepState = 'done' | 'run' | 'todo' | 'fail' | 'warn';
const STEP: Record<StepState, { cls: string; Icon?: LucideIcon; label: string }> = {
  done: { cls: 'bg-verified text-white', Icon: Check, label: 'Done' },
  run: { cls: 'bg-brand-600 text-white', Icon: RefreshCw, label: 'Running' },
  todo: { cls: 'bg-subtle border border-line-strong', label: 'Not started' },
  fail: { cls: 'bg-conflict text-white', Icon: X, label: 'Blocked' },
  warn: { cls: 'bg-inferred text-white', Icon: AlertTriangle, label: 'Needs attention' },
};
export function StatusCircle({ state, size = 22 }: { state: StepState; size?: number }) {
  const s = STEP[state];
  return (
    <span role="img" aria-label={s.label} className={cn('grid shrink-0 place-items-center rounded-full', s.cls)} style={{ width: size, height: size }}>
      {s.Icon && <s.Icon style={{ width: size * 0.55, height: size * 0.55 }} strokeWidth={2.5} className={state === 'run' ? 'animate-spin [animation-duration:2.5s]' : ''} />}
    </span>
  );
}

const BANNER = {
  single: 'bg-single-bg [&>svg]:text-single',
  verified: 'bg-verified-bg [&>svg]:text-verified',
  inferred: 'bg-inferred-bg [&>svg]:text-inferred',
  missing: 'bg-missing-bg [&>svg]:text-missing',
  conflict: 'bg-conflict-bg [&>svg]:text-conflict',
};
export function Banner({ tone = 'single', icon: Icon, children, className }: { tone?: keyof typeof BANNER; icon: LucideIcon; children: ReactNode; className?: string }) {
  return (
    <div className={cn('flex gap-2.5 rounded-xl p-3.5 text-small text-ink-900', BANNER[tone], className)}>
      <Icon className="mt-px size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  );
}

/** Marks a screen whose backend endpoint does not exist yet, so nobody mistakes demo data for real data. */
export function DemoNotice({ what }: { what: string }) {
  return (
    <div role="note" className="flex items-center gap-2 rounded-lg border border-dashed border-line-strong bg-card px-3 py-2 text-caption text-ink-500">
      <span className="rounded bg-subtle px-1.5 py-0.5 font-semibold tracking-wide text-ink-700 uppercase">Demo data</span>
      {what}
    </div>
  );
}

/** Loading / error / empty placeholder for API-backed sections. */
export function QueryState({ loading, error, empty, emptyText = 'Nothing here yet.' }: { loading?: boolean; error?: unknown; empty?: boolean; emptyText?: string }) {
  if (loading) {
    return (
      <div role="status" aria-label="Loading" className="flex flex-col gap-3 py-2">
        {[92, 76, 84].map((w, i) => (
          <div key={i} className="flex items-center gap-3 rounded-xl border border-line bg-card p-4">
            <span className="skeleton size-9 shrink-0 rounded-lg" />
            <span className="flex flex-1 flex-col gap-2">
              <span className="skeleton h-3 rounded" style={{ width: `${w}%` }} />
              <span className="skeleton h-2.5 rounded" style={{ width: `${w - 38}%` }} />
            </span>
          </div>
        ))}
      </div>
    );
  }
  if (error) return <p role="alert" className="p-6 text-center text-small text-conflict">{(error as Error).message || 'Something went wrong.'}</p>;
  if (empty) return <p className="p-6 text-center text-small text-ink-500">{emptyText}</p>;
  return null;
}

export function IconTile({ icon: Icon, className, size = 36 }: { icon: LucideIcon; className?: string; size?: number }) {
  return (
    <span className={cn('grid shrink-0 place-items-center rounded-lg bg-subtle text-ink-700', className)} style={{ width: size, height: size }} aria-hidden>
      <Icon style={{ width: size / 2, height: size / 2 }} />
    </span>
  );
}

/** The "receipt": a verbatim source quote, checked against stored text by the citation verifier. */
export function EvidenceQuote({ source, quote, matched = true, className, play }: {
  source: string; quote: string; matched?: boolean; className?: string;
  /** A quote from a meeting recording: play the moment it was said. */
  play?: { sourceId: string; at: number } | null;
}) {
  return (
    <figure className={cn('rounded-md border-l-[3px] border-baton bg-paper p-3', className)}>
      <figcaption className="flex items-center gap-1.5 font-mono text-caption text-ink-500">
        {play ? <AudioLines className="size-3" aria-hidden /> : <FileText className="size-3" aria-hidden />}
        <span className="min-w-0 flex-1">{source}</span>
        {play && <PlayMomentButton sourceId={play.sourceId} at={play.at} label={source} />}
      </figcaption>
      <blockquote className="mt-1.5 font-mono text-small leading-5 text-ink-900">{quote}</blockquote>
      {matched && (
        <p className="mt-1.5 flex items-center gap-1 text-caption font-medium text-verified">
          <Check className="size-3" strokeWidth={2.5} aria-hidden /> Quote matched by verifier · 100%
        </p>
      )}
    </figure>
  );
}

export function StatCard({ label, value, note, valueClass }: { label: string; value: string; note: string; valueClass?: string }) {
  const shown = useCountUp(value);
  return (
    <Card className="flex flex-col gap-1">
      <p className="text-small font-medium text-ink-500">{label}</p>
      <p className={cn('text-stat font-semibold tracking-tight tabular-nums', valueClass)}>{shown}</p>
      <p className="text-caption font-normal text-ink-500">{note}</p>
    </Card>
  );
}

const STEPS = ['Connect', 'Reconstruct', 'Verify', 'Review', 'Handover'] as const;
export function WorkflowStepper({ current }: { current: number }) {
  return (
    <ol className="flex flex-wrap items-center gap-2.5" aria-label="Handover workflow">
      {STEPS.map((s, i) => {
        const done = i < current, on = i === current;
        return (
          <li key={s} className="flex items-center gap-2.5" aria-current={on ? 'step' : undefined}>
            {i > 0 && <span className={cn('h-0.5 w-7 rounded', i <= current ? 'bg-brand-600' : 'bg-line-strong')} aria-hidden />}
            <span className={cn('grid size-6 place-items-center rounded-full text-caption font-medium', done ? 'bg-verified text-white' : on ? 'bg-brand-600 text-white' : 'border border-line-strong bg-card text-ink-500')}>
              {done ? <Check className="size-3" strokeWidth={3} /> : i + 1}
            </span>
            <span className={cn('text-small', on ? 'font-medium text-ink-900' : done ? 'text-ink-700' : 'text-ink-400')}>{s}</span>
          </li>
        );
      })}
    </ol>
  );
}

export function Table({ columns, children, label }: { columns: { label: string; className?: string }[]; children: ReactNode; label: string }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-line bg-card shadow-card">
      <table className="w-full text-left text-small" aria-label={label}>
        <thead className="border-b border-line bg-canvas">
          <tr>
            {columns.map(c => (
              <th key={c.label} scope="col" className={cn('overline px-4 py-2.5 text-ink-500 whitespace-nowrap', c.className)}>{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-line">{children}</tbody>
      </table>
    </div>
  );
}
export function Td({ children, className }: { children?: ReactNode; className?: string }) {
  return <td className={cn('px-4 py-3 align-middle text-ink-700', className)}>{children}</td>;
}

/** Counts a whole number up from 0 when it first appears (and glides when it changes). Other values pass through. */
function useCountUp(value: string) {
  const target = /^\d{1,7}$/.test(value) ? Number(value) : null;
  const [n, setN] = useState(target ?? 0);
  const from = useRef(0);
  useEffect(() => {
    if (target === null) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) { setN(target); from.current = target; return; }
    const start = performance.now();
    const begin = from.current;
    const dur = 700 + Math.min(500, Math.abs(target - begin) * 8);
    let raf = 0;
    const tick = (t: number) => {
      const k = Math.min(1, (t - start) / dur);
      const eased = 1 - Math.pow(1 - k, 3);
      setN(Math.round(begin + (target - begin) * eased));
      if (k < 1) raf = requestAnimationFrame(tick);
      else from.current = target;
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target]);
  return target === null ? value : String(n);
}
