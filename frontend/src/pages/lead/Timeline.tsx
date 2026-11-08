import { useState } from 'react';
import { FilterChip, StatusBadge } from '@/components/ui/badge';
import { Card, PageHeader, QueryState } from '@/components/ui/misc';
import { STATUS, STATUS_LABEL, useTimelineList, type TimelineDto } from '@/data/live';
import { useSession } from '@/lib/auth';
import { cn } from '@/lib/utils';

const KIND: Record<TimelineDto['kind'], { label: string; plural: string; dot: string; text: string }> = {
  DECISION: { label: 'Decision', plural: 'Decisions', dot: 'bg-brand-600', text: 'text-brand-600' },
  CHANGE: { label: 'Change', plural: 'Changes', dot: 'bg-inferred', text: 'text-inferred' },
  OPEN_DISCUSSION: { label: 'Unresolved', plural: 'Unresolved', dot: 'bg-missing', text: 'text-missing' },
  RISK: { label: 'Risk', plural: 'Risks', dot: 'bg-conflict', text: 'text-conflict' },
};
const KINDS = Object.keys(KIND) as TimelineDto['kind'][];

/** Shared with the recipient view; needs a handover the viewer can open. */
export function TimelineView({ legend = true, handoverId }: { legend?: boolean; handoverId?: string | null }) {
  const q = useTimelineList(handoverId);
  const [on, setOn] = useState<Set<string>>(new Set(KINDS));
  const items = (q.data ?? []).filter(d => on.has(d.kind));
  return (
    <>
      <div className="flex flex-wrap gap-1.5" role="toolbar" aria-label="Filter timeline">
        {KINDS.map(k => (
          <FilterChip key={k} active={on.has(k)} onClick={() => setOn(s => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; })}>{KIND[k].plural}</FilterChip>
        ))}
      </div>
      <div className={cn('grid items-start gap-6', legend && 'xl:grid-cols-[1fr_340px]')}>
        <Card className="p-6">
          <QueryState loading={q.isLoading} error={q.error} empty={!q.isLoading && items.length === 0} emptyText="Nothing on the timeline yet." />
          <ol>
            {items.map((d, i) => {
              const dt = new Date(d.at);
              return (
                <li key={`${d.at}-${d.title}`} className="grid grid-cols-[72px_32px_1fr] sm:grid-cols-[90px_40px_1fr]">
                  <span className="pt-0.5"><span className="block text-small font-medium">{dt.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}</span><span className="block text-caption text-ink-400">{dt.getFullYear()}</span></span>
                  <span className="flex flex-col items-center gap-1 pt-1" aria-hidden>
                    <span className={cn('size-3 rounded-full', KIND[d.kind].dot)} />
                    {i < items.length - 1 && <span className="w-0.5 flex-1 bg-line" />}
                  </span>
                  <span className="flex flex-col gap-1.5 pb-6">
                    <span className="flex flex-wrap items-center gap-2"><span className={cn('overline', KIND[d.kind].text)}>{KIND[d.kind].label}</span><StatusBadge status={STATUS[d.status]} label={STATUS_LABEL[d.status]} /></span>
                    <span className="font-medium">{d.title}</span>
                  </span>
                </li>
              );
            })}
          </ol>
        </Card>
        {legend && (
          <Card className="flex flex-col gap-2.5">
            <h2 className="text-h3 font-semibold">Reading the timeline</h2>
            {[['bg-brand-600', 'Decision — what was chosen and why'], ['bg-inferred', 'Change — a later source replaced an earlier one'], ['bg-missing', 'Unresolved — becomes an exit-interview question'], ['bg-conflict', 'Risk — flagged in the sources']].map(([c, l]) => (
              <p key={l} className="flex items-center gap-2.5 text-small text-ink-700"><span className={cn('size-2.5 shrink-0 rounded-full', c)} aria-hidden />{l}</p>
            ))}
          </Card>
        )}
      </div>
    </>
  );
}

export default function Timeline() {
  const { handover } = useSession();
  return (
    <>
      <PageHeader title="Decision timeline" sub="Dated decisions, changes and unresolved discussions, rebuilt from sources." />
      <TimelineView handoverId={handover?.id} />
    </>
  );
}
