import { AlertTriangle, Check, Keyboard, Layers, Lock, ShieldAlert, Star, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { FilterChip, SensitivityChip, StatusBadge, statusDot } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/form';
import { Banner, Card, EvidenceQuote, Kbd, QueryState } from '@/components/ui/misc';
import {
  CLAIM_TYPE_LABEL, STATUS, evidenceLabel, fmtDateTime, sens, typeLabel, useClaim, useClaimList, usePatchClaim, useRevisions,
  type ClaimDto, type ClaimStatusDto, type ReviewStateDto,
} from '@/data/live';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

type FilterKey = 'ALL' | 'TODO' | ClaimStatusDto;
const FILTERS: { key: FilterKey; label: string }[] = [
  { key: 'ALL', label: 'All' }, { key: 'TODO', label: 'To review' }, { key: 'INFERRED', label: 'Inferred' },
  { key: 'CONFLICTING', label: 'Conflicting' }, { key: 'MISSING_EVIDENCE', label: 'Missing evidence' }, { key: 'STALE', label: 'Stale' },
];
const STATUSES: ClaimStatusDto[] = ['VERIFIED', 'SINGLE_SOURCE', 'INFERRED', 'CONFLICTING', 'MISSING_EVIDENCE', 'STALE'];
const STATUS_NAME: Record<ClaimStatusDto, string> = { VERIFIED: 'Verified', SINGLE_SOURCE: 'Single source', INFERRED: 'Inferred', CONFLICTING: 'Conflicting', MISSING_EVIDENCE: 'Missing evidence', STALE: 'Stale' };
const STATE_LABEL: Record<ReviewStateDto, string> = { PENDING: 'To review', ACCEPTED: 'Accepted', REJECTED: 'Rejected', NEEDS_INFO: 'Needs info', EDITED: 'Edited' };
// Allowed transitions (backend ReviewStateMachine).
const CAN: Record<ReviewStateDto, ReviewStateDto[]> = {
  PENDING: ['ACCEPTED', 'REJECTED', 'NEEDS_INFO'], REJECTED: ['PENDING'], NEEDS_INFO: ['PENDING'], ACCEPTED: [], EDITED: ['ACCEPTED'],
};

function matches(c: ClaimDto, f: FilterKey, type: string | null) {
  if (type && c.type !== type) return false;
  if (f === 'ALL') return true;
  if (f === 'TODO') return c.reviewState === 'PENDING' || c.reviewState === 'NEEDS_INFO';
  return c.status === f;
}

export default function Review() {
  const { handover } = useSession();
  const list = useClaimList(handover?.id);
  const claims = useMemo(() => list.data ?? [], [list.data]);
  const patch = usePatchClaim(handover?.id);
  const [filter, setFilter] = useState<FilterKey>('ALL');
  const [type, setType] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');

  // ?q= comes from the header search box.
  const [params, setParams] = useSearchParams();
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const visible = useMemo(() => claims.filter(c => matches(c, filter, type)
    && (!q || [c.title, c.body, c.subject, typeLabel(c.type)].some(v => v?.toLowerCase().includes(q)))), [claims, filter, type, q]);
  const selId = selectedId ?? visible[0]?.id ?? null;
  const detail = useClaim(selId);
  const revisions = useRevisions(selId);
  const selected = detail.data ?? claims.find(c => c.id === selId);
  const count = (f: FilterKey) => claims.filter(c => matches(c, f, null)).length;
  const types = Object.keys(CLAIM_TYPE_LABEL).filter(t => claims.some(c => c.type === t));

  const act = useCallback((reviewState: ReviewStateDto) => {
    if (!selected || !CAN[selected.reviewState].includes(reviewState)) return;
    patch.mutate({ id: selected.id, version: selected.version, reviewState });
  }, [patch, selected]);

  const startEdit = useCallback(() => { if (selected) { setDraft(selected.title); setEditing(true); } }, [selected]);

  const move = useCallback((d: 1 | -1) => {
    const i = visible.findIndex(c => c.id === selId);
    const next = visible[Math.min(visible.length - 1, Math.max(0, i + d))];
    if (next) { setSelectedId(next.id); setEditing(false); patch.reset(); }
  }, [visible, selId, patch]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target;
      if (editing || (t instanceof Element && t.closest('input, textarea, select, [contenteditable]')) || e.metaKey || e.ctrlKey || e.altKey) return;
      const k = e.key.toLowerCase();
      if (k === 'j') move(1);
      else if (k === 'k') move(-1);
      else if (k === 'a') act('ACCEPTED');
      else if (k === 'r') act('REJECTED');
      else if (k === 'n') act('NEEDS_INFO');
      else if (k === 'e') startEdit();
      else return;
      e.preventDefault();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [act, move, editing, startEdit]);

  const toReview = count('TODO');
  const err = patch.error instanceof ApiError ? patch.error : null;

  return (
    <>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-h1 font-semibold">Review claims</h1>
          <p className="mt-1 text-ink-500">{claims.length} claims · {toReview} to review · every claim links to its receipt</p>
        </div>
        <div className="flex gap-2"><Link to="/app/view-as"><Button>View as…</Button></Link><Link to="/app/approval"><Button kind="primary">Go to approval</Button></Link></div>
      </div>
      <div className="flex flex-wrap items-center gap-2" role="toolbar" aria-label="Filter claims">
        {FILTERS.map(f => <FilterChip key={f.key} active={filter === f.key} onClick={() => setFilter(f.key)}>{f.label} {count(f.key)}</FilterChip>)}
        {q && <button type="button" onClick={() => setParams({})} className="flex items-center gap-1 rounded-full bg-brand-50 px-3 py-1 text-small font-medium text-brand-700 cursor-pointer">Matching “{params.get('q')}” · {visible.length}<X className="size-3.5" aria-label="Clear search" /></button>}
      </div>
      <QueryState loading={list.isLoading} error={list.error} empty={!list.isLoading && claims.length === 0} emptyText="No claims yet. Run reconstruction first." />

      {claims.length > 0 && (
        <div className="grid items-start gap-4 lg:grid-cols-[200px_1fr] xl:grid-cols-[200px_1fr_420px]">
          <nav aria-label="Claim types" className="hidden flex-col gap-0.5 rounded-xl border border-line bg-card p-2 lg:flex">
            <p className="overline px-2 py-1.5 text-ink-400">Claim type</p>
            {[null, ...types].map(t => {
              const n = t ? claims.filter(c => c.type === t).length : claims.length;
              const on = type === t;
              return (
                <button key={t ?? 'all'} type="button" onClick={() => setType(t)} aria-pressed={on} className={cn('flex justify-between rounded-md px-2 py-1.5 text-left text-small font-medium cursor-pointer', on ? 'bg-brand-50 text-brand-700' : 'text-ink-700 hover:bg-canvas')}>
                  {t ? typeLabel(t) : 'All types'}<span className={cn('text-caption', on ? 'text-brand-700' : 'text-ink-400')}>{n}</span>
                </button>
              );
            })}
            <p className="overline px-2 pt-3.5 pb-1.5 text-ink-400">Status</p>
            {STATUSES.map(s => (
              <span key={s} className="flex items-center justify-between px-2 py-1.5 text-small text-ink-700">
                <span className="flex items-center gap-2"><span className={cn('size-2 rounded-full', statusDot(STATUS[s]))} aria-hidden />{STATUS_NAME[s]}</span>
                <span className="text-caption text-ink-400">{claims.filter(c => c.status === s).length}</span>
              </span>
            ))}
          </nav>

          <section aria-label="Claims" className="overflow-hidden rounded-xl border border-line bg-card">
            <div className="flex items-center justify-between border-b border-line bg-canvas px-4 py-2.5">
              <span className="text-small font-medium">{visible.length} claims</span>
              <span className="text-caption text-ink-500">Critical first, then by confidence</span>
            </div>
            {visible.length === 0 && <p className="p-6 text-center text-small text-ink-500">No claims match this filter.</p>}
            <ul>
              {visible.map(c => {
                const sel = c.id === selId;
                return (
                  <li key={c.id} className={cn('border-b border-line last:border-b-0', sel ? 'bg-brand-50 shadow-[inset_2px_0_0_var(--color-brand-600)]' : 'hover:bg-canvas')}>
                    <button type="button" onClick={() => { setSelectedId(c.id); setEditing(false); patch.reset(); }} aria-current={sel} className="flex w-full gap-3 px-4 py-3.5 text-left cursor-pointer">
                      <span className="flex min-w-0 flex-1 flex-col gap-1.5">
                        <span className="flex flex-wrap items-center gap-1.5">
                          <StatusBadge status={STATUS[c.status]} label={STATUS_NAME[c.status]} />
                          <SensitivityChip level={sens(c.sensitivity)} />
                          {c.critical && <span className="flex items-center gap-0.5 text-caption font-medium text-inferred"><Star className="size-3" aria-hidden />Critical</span>}
                          {c.flags.includes('PROMPT_INJECTION_SUSPECTED') && <span className="flex items-center gap-0.5 text-caption font-medium text-conflict"><ShieldAlert className="size-3" aria-hidden />Check source</span>}
                        </span>
                        <span className="text-small text-ink-900">{c.title}</span>
                        <span className="text-caption text-ink-500">{typeLabel(c.type)} · confidence {c.confidence.toFixed(2)}{c.subject ? ` · ${c.subject}` : ''}</span>
                      </span>
                      <ReviewTag state={c.reviewState} />
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>

          {selected && (
            <Card className="flex flex-col gap-4 lg:col-span-2 xl:col-span-1 xl:sticky xl:top-20" as="article">
              <div className="flex items-center justify-between">
                <span className="font-mono text-caption text-ink-500">{typeLabel(selected.type)} · v{selected.version} · {selected.origin.toLowerCase().replace('_', ' ')}</span>
                <SensitivityChip level={sens(selected.sensitivity)} />
              </div>
              {editing ? (
                <div className="flex flex-col gap-2">
                  <Textarea aria-label="Claim text" rows={3} value={draft} onChange={e => setDraft(e.target.value)} autoFocus maxLength={300} />
                  <div className="flex gap-2">
                    <Button kind="primary" disabled={!draft.trim() || patch.isPending} onClick={() => patch.mutate({ id: selected.id, version: selected.version, title: draft.trim(), reason: 'Edited in review' }, { onSuccess: () => setEditing(false) })}>Save edit</Button>
                    <Button kind="ghost" onClick={() => setEditing(false)}>Cancel</Button>
                  </div>
                </div>
              ) : (
                <h2 className="text-h3 font-semibold">{selected.title}</h2>
              )}
              {selected.body && <p className="text-small text-ink-700">{selected.body}</p>}
              <div className="flex flex-wrap items-center gap-2">
                <StatusBadge status={STATUS[selected.status]} label={STATUS_NAME[selected.status]} />
                <span className={cn('text-small font-medium', selected.confidence >= 0.8 ? 'text-verified' : selected.confidence >= 0.6 ? 'text-single' : 'text-inferred')}>Confidence {selected.confidence.toFixed(2)}</span>
                <ReviewTag state={selected.reviewState} />
              </div>
              {selected.flags.includes('PROMPT_INJECTION_SUSPECTED') && (
                <Banner tone="conflict" icon={ShieldAlert}>The source text contains instructions aimed at the AI. They were treated as data, but read the quote carefully before accepting.</Banner>
              )}
              {selected.reasons.length > 0 && (
                <div className="flex flex-col gap-1.5 rounded-xl bg-canvas p-3.5">
                  <p className="overline text-ink-400">Why this status and confidence</p>
                  <ul className="list-disc pl-4 text-small text-ink-700">{selected.reasons.map(r => <li key={r}>{r}</li>)}</ul>
                </div>
              )}
              <p className="overline text-ink-400">Evidence</p>
              {detail.isLoading && <p className="text-small text-ink-500">Loading evidence…</p>}
              {!detail.isLoading && selected.evidence.length === 0 && (
                <Banner tone="inferred" icon={Layers}>No verified quote. Recipients see this only as “Likely, not confirmed”, and only if you accept it.</Banner>
              )}
              {selected.evidence.map(e => <EvidenceQuote key={e.id} source={evidenceLabel(e)} quote={`“${e.quote}”`} matched={e.matchScore >= 0.999} />)}
              {selected.unverifiedCitations > 0 && <p className="text-caption text-ink-500">{selected.unverifiedCitations} citation{selected.unverifiedCitations > 1 ? 's' : ''} hidden: you can’t see that source.</p>}
              <Banner icon={Lock}>Sensitivity inherited: {sens(selected.sensitivity)} — the highest level of its evidence. An edit cannot lower it.</Banner>
              {err && <Banner tone="conflict" icon={AlertTriangle}>{err.code === 'CLAIM_STALE' ? 'Someone else changed this claim. It has been reloaded — try again.' : err.message}</Banner>}
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {selected.reviewState === 'REJECTED' || selected.reviewState === 'NEEDS_INFO' ? (
                  <Button kind="primary" className="col-span-2 sm:col-span-4" disabled={patch.isPending} onClick={() => act('PENDING')}>Restore to review</Button>
                ) : (
                  <>
                    <Button kind="primary" disabled={patch.isPending || !CAN[selected.reviewState].includes('ACCEPTED')} onClick={() => act('ACCEPTED')}>Accept</Button>
                    <Button disabled={patch.isPending} onClick={startEdit}>Edit</Button>
                    <Button disabled={patch.isPending || !CAN[selected.reviewState].includes('NEEDS_INFO')} onClick={() => act('NEEDS_INFO')}>Needs info</Button>
                    <Button kind="ghost" disabled={patch.isPending || !CAN[selected.reviewState].includes('REJECTED')} onClick={() => act('REJECTED')}>Reject</Button>
                  </>
                )}
              </div>
              <p className="flex flex-wrap items-center gap-1.5 text-caption text-ink-500">
                <Keyboard className="size-3.5 text-ink-400" aria-hidden />
                <Kbd>J</Kbd><Kbd>K</Kbd> move <Kbd>A</Kbd> accept <Kbd>E</Kbd> edit <Kbd>R</Kbd> reject <Kbd>N</Kbd> needs info
              </p>
              <hr className="border-line" />
              <p className="overline text-ink-400">Revision history</p>
              <ul className="flex flex-col gap-3">
                {revisions.data?.length === 0 && <li className="text-small text-ink-500">No changes yet.</li>}
                {revisions.data?.map(r => (
                  <li key={r.version} className="flex flex-col">
                    <span className="text-small">
                      <span className="font-medium">{r.actorName}</span>{' '}
                      {String(r.before.reviewState ?? '') !== String(r.after.reviewState ?? '') ? `${STATE_LABEL[r.before.reviewState as ReviewStateDto] ?? r.before.reviewState} → ${STATE_LABEL[r.after.reviewState as ReviewStateDto] ?? r.after.reviewState}` : 'edited the claim'}
                      {r.reason ? ` — ${r.reason}` : ''}
                    </span>
                    <span className="text-caption text-ink-400">v{r.version} · {fmtDateTime(r.at)}</span>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      )}
    </>
  );
}

function ReviewTag({ state }: { state: ReviewStateDto }) {
  if (state === 'ACCEPTED' || state === 'EDITED')
    return <span className="flex shrink-0 items-center gap-1 text-caption font-medium text-verified"><Check className="size-3" strokeWidth={2.5} aria-hidden />{STATE_LABEL[state]}</span>;
  if (state === 'REJECTED')
    return <span className="flex shrink-0 items-center gap-1 text-caption font-medium text-ink-400"><X className="size-3" aria-hidden />Rejected</span>;
  return <span className={cn('shrink-0 text-caption', state === 'NEEDS_INFO' ? 'font-medium text-missing' : 'text-ink-400')}>{STATE_LABEL[state]}</span>;
}
