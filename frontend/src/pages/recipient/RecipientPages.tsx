import { Check, EyeOff, HelpCircle, Inbox, KeyRound, ShieldCheck } from 'lucide-react';
import { useState } from 'react';
import { Chip, FilterChip, SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Segmented } from '@/components/ui/form';
import { Banner, Card, CardHeader, EvidenceQuote, PageHeader, QueryState } from '@/components/ui/misc';
import {
  LEVELS, STATUS, STATUS_LABEL, fmtDate, sens, typeLabel, useMyShares, useRecipientMutations, useShareView,
  type MyShareDto, type RecipientViewDto, type SnapshotClaim,
} from '@/data/live';
import { useAuth } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { WhatChanged } from './AskPage';
import { cn } from '@/lib/utils';

const PICK_KEY = 'baton.share';

/** The share this recipient is reading: the one they picked last, else the newest. */
export function useCurrentShare() {
  const mine = useMyShares();
  const [picked, setPicked] = useState<string | null>(() => { try { return localStorage.getItem(PICK_KEY); } catch { return null; } });
  const list = mine.data ?? [];
  const current: MyShareDto | undefined = list.find(s => s.id === picked) ?? list[0];
  const view = useShareView(current?.id);
  const pick = (id: string) => { setPicked(id); try { localStorage.setItem(PICK_KEY, id); } catch { /* private mode */ } };
  return { mine, list, current, view, pick };
}

export function RecipientGate({ children }: { children: (v: RecipientViewDto) => React.ReactNode }) {
  const { mine, current, view } = useCurrentShare();
  if (mine.isLoading || (current && view.isLoading)) return <QueryState loading />;
  if (!current) {
    return (
      <Card className="flex flex-col items-center gap-3 py-12 text-center">
        <span className="grid size-12 place-items-center rounded-xl bg-brand-50 text-brand-700"><Inbox className="size-6" aria-hidden /></span>
        <h1 className="text-h2 font-semibold">No handover shared with you yet</h1>
        <p className="max-w-[440px] text-small text-ink-500">When a project lead approves a handover and shares it with you, it appears here. Shares expire, and only show what your clearance and groups allow.</p>
      </Card>
    );
  }
  if (view.error) {
    const e = view.error instanceof ApiError ? view.error : null;
    return <Banner tone="conflict" icon={EyeOff}>{e?.message ?? 'This handover could not be opened.'}</Banner>;
  }
  return view.data ? <>{children(view.data)}</> : null;
}

const PHASES = [{ k: 'DAY1', l: 'Day 1' }, { k: 'WEEK1', l: 'Week 1' }, { k: 'MONTH1', l: 'Month 1' }] as const;
type PhaseLabel = (typeof PHASES)[number]['l'];

function Checklist({ v, compact }: { v: RecipientViewDto; compact?: boolean }) {
  const { check } = useRecipientMutations(v.shareId);
  const [period, setPeriod] = useState<PhaseLabel>('Day 1');
  const phase = PHASES.find(p => p.l === period)!.k;
  const items = v.checklist.filter(i => i.phase === phase);
  const byId = new Map(v.claims.map(c => [c.id, c]));
  const done = items.filter(i => i.done).length;
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex items-center justify-between"><h2 className="text-h3 font-semibold">Your checklist</h2><span className="text-caption text-ink-500">{done} of {items.length} done</span></div>
      <Segmented label="Checklist period" options={PHASES.map(p => p.l)} value={period} onChange={setPeriod} />
      <ul className="flex flex-col gap-2">
        {items.map(i => {
          const c = i.claimId ? byId.get(i.claimId) : undefined;
          return (
            <li key={i.id} className={cn('flex gap-3 rounded-xl border border-line p-3', i.done ? 'bg-canvas' : 'bg-card')}>
              <Checkbox checked={i.done} onChange={d => check.mutate({ itemId: i.id, done: d })} />
              <span className="flex flex-col gap-1.5">
                <span className={cn('text-small', i.done ? 'text-ink-500 line-through' : 'text-ink-900')}>{i.text}</span>
                {!compact && c && <span className="flex flex-wrap items-center gap-2"><Chip className="bg-brand-50 text-brand-700">{typeLabel(c.type)}</Chip><StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} /></span>}
              </span>
            </li>
          );
        })}
        {items.length === 0 && <li className="text-small text-ink-500">Nothing for {period.toLowerCase()}.</li>}
      </ul>
    </Card>
  );
}

function Acknowledge({ v }: { v: RecipientViewDto }) {
  const { acknowledge } = useRecipientMutations(v.shareId);
  const who = v.handover.departingName.split(' ')[0];
  if (v.acknowledgedAt) return <Banner tone="verified" icon={Check}>You acknowledged this handover on {fmtDate(v.acknowledgedAt)}. Your lead has been told it landed.</Banner>;
  return (
    <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-card p-5">
      <span className="grid size-10 place-items-center rounded-lg bg-verified-bg text-verified"><Check className="size-5" aria-hidden /></span>
      <span className="flex-1"><span className="block font-medium">Read through Start here and your Day 1 list?</span><span className="block text-small text-ink-500">Acknowledging tells your lead that {who}’s handover landed.</span></span>
      <Button kind="primary" size="lg" disabled={acknowledge.isPending} onClick={() => acknowledge.mutate()}>I’ve read this handover</Button>
    </div>
  );
}

function Hidden({ n }: { n: number }) {
  if (!n) return null;
  return <p className="flex items-center gap-1.5 text-caption text-ink-500"><EyeOff className="size-3.5" aria-hidden />{n} item{n > 1 ? 's are' : ' is'} above your clearance or outside your groups and not shown.</p>;
}

function ClaimLine({ c }: { c: SnapshotClaim }) {
  return (
    <span className="flex flex-1 flex-col items-start gap-1">
      <span className="text-small font-medium">{c.title}</span>
      <span className="flex flex-wrap items-center gap-2"><StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} /><SensitivityChip level={sens(LEVELS[c.sensitivity])} /></span>
    </span>
  );
}

export function StartHere() {
  const { user } = useAuth();
  const { list, current, pick } = useCurrentShare();
  const first = user?.name.split(' ')[0] ?? 'there';
  return (
    <RecipientGate>
      {v => {
        const risks = v.claims.filter(c => c.type === 'RISK' || c.type === 'BLOCKER').sort((a, b) => Number(b.critical) - Number(a.critical)).slice(0, 5);
        const facts = v.claims.filter(c => c.status === 'VERIFIED').length;
        return (
          <>
            {list.length > 1 && (
              <div className="flex flex-wrap items-center gap-2"><span className="text-caption text-ink-500">Handovers shared with you:</span>
                {list.map(s => <FilterChip key={s.id} active={s.id === current?.id} onClick={() => pick(s.id)}>{s.handoverTitle}</FilterChip>)}
              </div>
            )}
            <section className="flex flex-col gap-5 rounded-2xl bg-inverse p-6 sm:p-8">
              <p className="overline text-baton">Start here · {v.roleLabel}</p>
              <h1 className="text-h1 font-semibold text-white">Welcome, {first}. Here’s what you’re picking up from {v.handover.departingName}.</h1>
              <div className="reveal-list grid gap-3 md:grid-cols-3">
                {[
                  ['Project', v.handover.project.name, v.handover.project.description],
                  ['Handover', v.handover.title, `${v.handover.scenario.toLowerCase()} exit`],
                  ['What you can see', `${v.claims.length} items · ${facts} verified`, `Approved v${v.versionNo} by ${v.approvedBy}`],
                ].map(([l, val, note]) => (
                  <div key={l} className="flex flex-col gap-1.5 rounded-xl bg-inverse-raised p-4">
                    <span className="text-caption text-inverse-muted">{l}</span>
                    <span className="font-medium text-white">{val}</span>
                    {note && <span className="text-caption text-inverse-muted first-letter:uppercase">{note}</span>}
                  </div>
                ))}
              </div>
            </section>
            <Hidden n={v.hiddenCount} />
            <WhatChanged v={v} />
            <div className="grid items-start gap-6 lg:grid-cols-[1fr_480px]">
              <Card className="flex flex-col">
                <CardHeader title="Top risks and blockers" sub="Critical ones first" />
                <ol className="mt-1 divide-y divide-line">
                  {risks.map((r, i) => (
                    <li key={r.id} className="flex items-center gap-3 py-3">
                      <span className="w-4 text-h3 font-semibold text-ink-300">{i + 1}</span>
                      <ClaimLine c={r} />
                      {r.subject && <span className="text-caption text-ink-700">{r.subject}</span>}
                    </li>
                  ))}
                  {risks.length === 0 && <li className="py-3 text-small text-ink-500">No risks recorded in what you can see.</li>}
                </ol>
              </Card>
              <Checklist v={v} />
            </div>
            <Acknowledge v={v} />
          </>
        );
      }}
    </RecipientGate>
  );
}

export function ChecklistPage() {
  return (
    <RecipientGate>
      {v => (
        <>
          <PageHeader title="Continuation checklist" sub="Generated from the approved claims, each item linked to the claim behind it." />
          <div className="max-w-[720px]"><Checklist v={v} /></div>
          <Acknowledge v={v} />
        </>
      )}
    </RecipientGate>
  );
}

const OWNERISH = new Set(['OWNER', 'CONTACT', 'ACCESS', 'DEPENDENCY']);

export function Ownership() {
  const [filter, setFilter] = useState<'all' | 'access' | 'unknown'>('all');
  return (
    <RecipientGate>
      {v => {
        const rows = v.claims.filter(c => OWNERISH.has(c.type))
          .filter(c => filter === 'all' || (filter === 'access' ? c.type === 'ACCESS' : !c.subject));
        return (
          <>
            <PageHeader
              title="Who to ask about what"
              sub="Owners, contacts and access to transfer, with the evidence behind each one."
              right={<><FilterChip active={filter === 'all'} onClick={() => setFilter('all')}>All</FilterChip><FilterChip active={filter === 'access'} onClick={() => setFilter('access')}>Access</FilterChip><FilterChip active={filter === 'unknown'} onClick={() => setFilter('unknown')}>No owner</FilterChip></>}
            />
            <Hidden n={v.hiddenCount} />
            <ul className="reveal-list grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {rows.map(c => (
                <li key={c.id}>
                  <Card as="article" className="flex h-full flex-col gap-3">
                    <div className="flex items-start justify-between gap-2"><span className="overline text-ink-500">{typeLabel(c.type)}</span><StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} /></div>
                    <h2 className="text-h3 font-semibold">{c.title}</h2>
                    <p className={cn('flex items-center gap-1.5 text-small', c.subject ? 'text-ink-700' : 'text-missing')}>
                      {c.subject ? (c.type === 'ACCESS' ? <KeyRound className="size-3.5" aria-hidden /> : <ShieldCheck className="size-3.5 text-verified" aria-hidden />) : <HelpCircle className="size-3.5" aria-hidden />}
                      {c.subject ?? 'Nobody named — ask your lead'}
                    </p>
                    {c.evidence[0] && <EvidenceQuote source={`${c.evidence[0].source}${c.evidence[0].date ? ' · ' + fmtDate(c.evidence[0].date) : ''}`} quote={c.evidence[0].quote} className="mt-auto" />}
                  </Card>
                </li>
              ))}
            </ul>
            {rows.length === 0 && <p className="text-small text-ink-500">Nothing here.</p>}
          </>
        );
      }}
    </RecipientGate>
  );
}

export function RecipientTimeline() {
  return (
    <RecipientGate>
      {v => {
        const byId = new Map(v.claims.map(c => [c.id, c]));
        return (
          <>
            <PageHeader title="How we got here" sub="Decisions and changes, each linked to its source." />
            <Card className="p-6">
              <ol className="flex flex-col gap-4">
                {v.timeline.map(d => {
                  const c = byId.get(d.claimId);
                  return (
                    <li key={d.at + d.claimId} className="grid grid-cols-[72px_1fr] gap-3">
                      <span className="text-small font-medium">{fmtDate(d.at)}</span>
                      <span className="flex flex-col gap-1">
                        <span className="flex items-center gap-2"><span className="overline text-ink-500">{d.kind.replace('_', ' ').toLowerCase()}</span>{c && <StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} />}</span>
                        <span className="font-medium">{d.title}</span>
                        {c?.evidence[0] && <span className="text-caption text-ink-500">{c.evidence[0].source}</span>}
                      </span>
                    </li>
                  );
                })}
                {v.timeline.length === 0 && <li className="text-small text-ink-500">No dated events in what you can see.</li>}
              </ol>
            </Card>
          </>
        );
      }}
    </RecipientGate>
  );
}
