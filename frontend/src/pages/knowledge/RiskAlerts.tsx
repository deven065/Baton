import { AlertTriangle, ArrowRight, ShieldAlert, ShieldCheck, UserX } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Card, CardHeader, EvidenceQuote } from '@/components/ui/misc';
import { fmtDate, useRiskAlerts, type RiskAlertDto } from '@/data/live';
import { cn } from '@/lib/utils';

const SEVERITY: Record<RiskAlertDto['severity'], { label: string; bar: string; chip: string }> = {
  CRITICAL: { label: 'Critical', bar: 'bg-conflict', chip: 'bg-conflict-bg text-conflict' },
  HIGH: { label: 'High', bar: 'bg-missing', chip: 'bg-missing-bg text-missing' },
  MEDIUM: { label: 'Medium', bar: 'bg-inferred', chip: 'bg-inferred-bg text-inferred' },
  LOW: { label: 'Low', bar: 'bg-single', chip: 'bg-single-bg text-single' },
};

/**
 * Knowledge-risk alerts in plain sentences: "Only Priya has touched payments in the last 6 months", and what to do about
 * it. {@code compact} shows the worst three, for the dashboard.
 */
export function RiskAlerts({ projectId, compact }: { projectId?: string | null; compact?: boolean }) {
  const { data, isLoading, error } = useRiskAlerts(projectId);
  const [open, setOpen] = useState<number | null>(null);
  if (isLoading || error || !data) return null;
  const items = compact ? data.items.slice(0, 3) : data.items;
  const s = data.summary;

  if (data.items.length === 0) {
    return (
      <Card className="flex items-center gap-3">
        <ShieldCheck className="size-5 shrink-0 text-verified" aria-hidden />
        <p className="text-small text-ink-700"><strong className="text-ink-900">No knowledge risks found.</strong> Nobody is the only person to have worked on a topic in the last {Math.round(data.windowDays / 30)} months. Alerts appear as soon as sources show a topic resting on one person.</p>
      </Card>
    );
  }

  return (
    <Card className="flex flex-col gap-4">
      <CardHeader
        title={<span className="flex items-center gap-2"><ShieldAlert className="size-5 text-conflict" aria-hidden />Knowledge risk alerts</span>}
        sub={`Worked out from who wrote what in the last ${Math.round(data.windowDays / 30)} months, and who is leaving.`}
        right={
          <span className="flex flex-wrap gap-1.5 text-caption font-medium">
            {s.critical > 0 && <span className={cn('rounded-full px-2 py-0.5', SEVERITY.CRITICAL.chip)}>{s.critical} critical</span>}
            {s.high > 0 && <span className={cn('rounded-full px-2 py-0.5', SEVERITY.HIGH.chip)}>{s.high} high</span>}
            {s.medium > 0 && <span className={cn('rounded-full px-2 py-0.5', SEVERITY.MEDIUM.chip)}>{s.medium} medium</span>}
          </span>
        }
      />
      <ul className="flex flex-col gap-2.5">
        {items.map((a, i) => {
          const sev = SEVERITY[a.severity];
          const expanded = open === i;
          return (
            <li key={`${a.kind}-${a.topic ?? ''}-${a.person ?? ''}-${i}`} className="flex overflow-hidden rounded-xl border border-line">
              <span className={cn('w-1.5 shrink-0', sev.bar)} aria-hidden />
              <div className="flex min-w-0 flex-1 flex-col gap-2 p-3.5">
                <div className="flex flex-wrap items-start gap-2">
                  <span className={cn('rounded-full px-2 py-0.5 text-caption font-semibold', sev.chip)}>{sev.label}</span>
                  {a.leaving && <span className="flex items-center gap-1 rounded-full bg-conflict-bg px-2 py-0.5 text-caption font-medium text-conflict"><UserX className="size-3" aria-hidden />Leaving</span>}
                  <p className="min-w-[220px] flex-1 text-small font-medium text-ink-900">{a.text}</p>
                </div>
                {a.suggestion && <p className="flex items-start gap-1.5 text-small text-ink-700"><ArrowRight className="mt-0.5 size-3.5 shrink-0 text-brand-600" aria-hidden />{a.suggestion}</p>}
                {!compact && (
                  <div className="flex flex-wrap items-center gap-3 text-caption">
                    {a.topic && <Link to={`/app/knowledge?tab=experts&q=${encodeURIComponent(a.topic)}`} className="font-medium text-brand-700 hover:underline">Who knows about {a.topic}?</Link>}
                    {a.evidence.length > 0 && (
                      <button type="button" onClick={() => setOpen(expanded ? null : i)} aria-expanded={expanded} className="font-medium text-ink-500 hover:text-ink-900 cursor-pointer">
                        {expanded ? 'Hide evidence' : `Show evidence (${a.evidence.length})`}
                      </button>
                    )}
                    {a.lastActive && <span className="text-ink-400">last active {fmtDate(a.lastActive)}</span>}
                  </div>
                )}
                {expanded && a.evidence.map((e, k) => <EvidenceQuote key={k} source={`${e.source}${e.at ? ` · ${fmtDate(e.at)}` : ''}`} quote={e.quote} matched={e.sourceType !== 'CLAIM'} />)}
              </div>
            </li>
          );
        })}
      </ul>
      {compact && data.items.length > 3 && (
        <Link to="/app/risk" className="flex items-center gap-1 self-start text-small font-medium text-brand-700 hover:underline">See all {data.items.length} alerts<ArrowRight className="size-3.5" aria-hidden /></Link>
      )}
      {!compact && <p className="flex items-center gap-1.5 text-caption text-ink-500"><AlertTriangle className="size-3.5" aria-hidden />Alerts only count sources you can see, so they never point at material you are not cleared for.</p>}
    </Card>
  );
}
