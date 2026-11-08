import { Check, HelpCircle, KeyRound, Phone } from 'lucide-react';
import { useState } from 'react';
import { Chip, FilterChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Segmented } from '@/components/ui/form';
import { Avatar, Banner, Card, CardHeader, PageHeader } from '@/components/ui/misc';
import { useAcknowledge, useAcknowledged, useChecklist, useToggleChecklist } from '@/data/api';
import { OWNERSHIP, PEOPLE, RISKS, TIMELINE } from '@/data/mock';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/utils';

const PERIODS = ['Day 1', 'Week 1', 'Month 1'] as const;
type Period = (typeof PERIODS)[number];

function Checklist({ compact }: { compact?: boolean }) {
  const { data } = useChecklist();
  const toggle = useToggleChecklist();
  const [period, setPeriod] = useState<Period>('Day 1');
  const items = data?.[period] ?? [];
  const done = items.filter(i => i.done).length;
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-center justify-between"><h2 className="text-h3 font-semibold">Your checklist</h2><span className="text-caption text-ink-500">{done} of {items.length} done</span></div>
      <Segmented label="Checklist period" options={PERIODS} value={period} onChange={setPeriod} />
      <ul className="flex flex-col gap-2">
        {items.map(i => (
          <li key={i.id} className={cn('flex gap-3 rounded-xl border border-line p-3', i.done ? 'bg-canvas' : 'bg-card')}>
            <Checkbox checked={i.done} onChange={() => toggle.mutate({ id: i.id })} />
            <span className="flex flex-col gap-1.5">
              <span className={cn('text-small', i.done ? 'text-ink-500 line-through' : 'text-ink-900')}>{i.text}</span>
              {!compact && <span className="flex flex-wrap items-center gap-2"><Chip className="bg-brand-50 text-brand-700">{i.claim}</Chip>{i.who && <span className="text-caption text-ink-500">{i.who}</span>}</span>}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Acknowledge() {
  const { data: ack } = useAcknowledged();
  const mutate = useAcknowledge();
  if (ack) return <Banner tone="verified" icon={Check}>Thanks — Priya has been told the handover landed. You can still ask questions any time.</Banner>;
  return (
    <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-card p-5">
      <span className="grid size-10 place-items-center rounded-lg bg-verified-bg text-verified"><Check className="size-5" aria-hidden /></span>
      <span className="flex-1"><span className="block font-medium">Read through Start here and your Day 1 list?</span><span className="block text-small text-ink-500">Acknowledging tells Priya the handover landed. You can still ask questions afterwards.</span></span>
      <Button>Ask a question</Button>
      <Button kind="primary" size="lg" onClick={() => mutate.mutate()}>I’ve read this handover</Button>
    </div>
  );
}

export function StartHere() {
  const { user } = useAuth();
  const first = user?.name.split(' ')[0] ?? 'there';
  return (
    <>
      <section className="flex flex-col gap-5 rounded-2xl bg-inverse p-6 sm:p-8">
        <p className="overline text-baton">Start here</p>
        <h1 className="text-h1 font-semibold text-white">Welcome, {first}. Here’s what you’re picking up.</h1>
        <div className="grid gap-3 md:grid-cols-3">
          {[['Project goal', 'Campus food-rescue app linking mess halls with NGOs'], ['Current phase', 'Beta · v0.9 in closed testing with 40 volunteers'], ['Next deadline', '29 Nov 2026 · v1.0 on the Play Store']].map(([l, v]) => (
            <div key={l} className="flex flex-col gap-1.5 rounded-xl bg-inverse-raised p-4">
              <span className="text-caption text-inverse-muted">{l}</span>
              <span className="font-medium text-white">{v}</span>
              <StatusBadge status="verified" label="Fact · linked" className="self-start" />
            </div>
          ))}
        </div>
      </section>
      <div className="grid items-start gap-6 lg:grid-cols-[1fr_480px]">
        <Card className="flex flex-col">
          <CardHeader title="Top 5 risks" sub="Each with an owner" />
          <ol className="mt-1 divide-y divide-line">
            {RISKS.map((r, i) => (
              <li key={r.text} className="flex items-center gap-3 py-3">
                <span className="w-4 text-h3 font-semibold text-ink-300">{i + 1}</span>
                <span className="flex flex-1 flex-col items-start gap-1"><span className="text-small font-medium">{r.text}</span><StatusBadge status={r.status} label={r.label} /></span>
                <span className="flex items-center gap-2"><Avatar initials={PEOPLE[r.owner].initials} tone={PEOPLE[r.owner].tone} size="sm" /><span className="text-caption text-ink-700">{PEOPLE[r.owner].name.split(' ')[0]}</span></span>
              </li>
            ))}
          </ol>
        </Card>
        <Checklist />
      </div>
      <Acknowledge />
    </>
  );
}

export function ChecklistPage() {
  return (
    <>
      <PageHeader title="Continuation checklist" sub="Every item links to the claim and source behind it." />
      <div className="max-w-[720px]"><Checklist /></div>
      <Acknowledge />
    </>
  );
}

export function Ownership() {
  const { user } = useAuth();
  const [filter, setFilter] = useState<'all' | 'mine' | 'unknown'>('all');
  const rows = OWNERSHIP.filter(o => filter === 'all' || (filter === 'mine' ? o.owner === user?.id : o.owner === null));
  return (
    <>
      <PageHeader
        title="Who to ask about what"
        sub="Owners after the handover, with the evidence behind each one."
        right={<><FilterChip active={filter === 'all'} onClick={() => setFilter('all')}>All areas</FilterChip><FilterChip active={filter === 'mine'} onClick={() => setFilter('mine')}>Mine</FilterChip><FilterChip active={filter === 'unknown'} onClick={() => setFilter('unknown')}>Unknown</FilterChip></>}
      />
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {rows.map(o => {
          const p = o.owner ? PEOPLE[o.owner] : null;
          return (
            <li key={o.area}>
              <Card as="article" className="flex h-full flex-col gap-3">
                <div className="flex items-start justify-between gap-2"><h2 className="text-h3 font-semibold">{o.area}</h2><StatusBadge status={o.status} label={o.label} /></div>
                <div className="flex items-center gap-2.5">
                  {o.owner ? <Avatar initials={p ? p.initials : o.owner.split(' ').map(w => w[0]).join('')} tone={p ? p.tone : 4} /> : <span className="grid size-8 place-items-center rounded-full bg-missing-bg text-missing"><HelpCircle className="size-4" aria-hidden /></span>}
                  <span>
                    <span className={cn('block text-small font-medium', !o.owner && 'text-missing')}>{p ? p.name : o.owner ?? 'Unknown owner'}</span>
                    <span className="block text-caption text-ink-500">{o.note}</span>
                  </span>
                </div>
                {o.extra && (
                  <p className={cn('flex items-center gap-1.5 text-caption', o.extra === 'Access to transfer' ? 'text-missing' : 'text-ink-500', o.extra.startsWith('[') && 'font-mono')}>
                    {o.extra === 'Access to transfer' && <KeyRound className="size-3" aria-hidden />}
                    {o.extra.startsWith('[') && <Phone className="size-3" aria-hidden />}
                    {o.extra}
                  </p>
                )}
                {!o.owner && <Button className="mt-auto">Ask Priya</Button>}
              </Card>
            </li>
          );
        })}
      </ul>
      {rows.length === 0 && <p className="text-small text-ink-500">Nothing here.</p>}
    </>
  );
}

export function RecipientTimeline() {
  return (
    <>
      <PageHeader title="How we got here" sub="Decisions and changes, each linked to its source." />
      <Card className="p-6">
        <ol className="flex flex-col gap-4">
          {TIMELINE.map(d => (
            <li key={d.date + d.text} className="grid grid-cols-[72px_1fr] gap-3">
              <span className="text-small font-medium">{d.date}</span>
              <span className="flex flex-col gap-1"><span className="flex items-center gap-2"><span className="overline text-ink-500">{d.kind}</span><StatusBadge status={d.status} label={d.statusLabel} /></span><span className="font-medium">{d.text}</span></span>
            </li>
          ))}
        </ol>
      </Card>
    </>
  );
}
