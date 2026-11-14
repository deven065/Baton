import { AlertTriangle, Check, CheckCircle2, ExternalLink, Layers, MessageCircleQuestion, Radar, RotateCcw, Search, ShieldCheck, Sparkles, UserX, Users, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Chip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Avatar, Banner, Card, EvidenceQuote, PageHeader, Progress, QueryState } from '@/components/ui/misc';
import {
  SOURCE_TYPE_LABEL, useCapture, useCaptureScan, useDecisions, useFindExperts, useProjectAsk, useReviewDecision,
  type DecisionDto, type ExpertDto, type ExpertsDto, type ProjectAnswerDto, type SourceTypeDto,
} from '@/data/live';
import type { Status } from '@/data/types';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

type Tab = 'ask' | 'decisions' | 'experts';
const TABS: { id: Tab; label: string }[] = [{ id: 'ask', label: 'Ask' }, { id: 'decisions', label: 'Decisions' }, { id: 'experts', label: 'Who knows what' }];

const day = (iso?: string | null) => (iso ? new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '');
const initials = (n: string) => n.split(/\s+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
const toneOf = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 0) % 6;
const first = (n: string) => n.split(/\s+/)[0] ?? n;
const ago = (iso: string) => {
  const m = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  return m < 2 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} days ago`;
};

/**
 * Project knowledge: what the team has decided and why, who knows what, and answers with their sources. It is built as
 * sources are read, not at the end, and shows only what the signed-in person is allowed to see.
 */
export function KnowledgePage() {
  const { project, role } = useSession();
  const [params, setParams] = useSearchParams();
  const tab = (TABS.find(t => t.id === params.get('tab'))?.id ?? 'ask') as Tab;
  const setTab = (t: Tab) => setParams(p => { const n = new URLSearchParams(p); n.set('tab', t); n.delete('q'); return n; }, { replace: true });
  if (!project) return <QueryState empty emptyText="You are not on a project yet." />;
  return (
    <>
      <PageHeader title="Project knowledge" sub={`What ${project.name} has decided and why, who knows what, and answers that show their sources. Captured automatically as your sources are read, so nothing waits for the last two weeks.`} />
      <CaptureStrip projectId={project.id} canScan={role === 'lead'} />
      <div role="tablist" aria-label="Project knowledge" className="flex gap-1 border-b border-line">
        {TABS.map(t => (
          <button key={t.id} role="tab" type="button" aria-selected={tab === t.id} onClick={() => setTab(t.id)}
            className={cn('-mb-px border-b-2 px-3 py-2.5 text-small font-medium cursor-pointer', tab === t.id ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}>
            {t.label}
          </button>
        ))}
      </div>
      {tab === 'ask' && <AskTab projectId={project.id} initial={params.get('q') ?? ''} />}
      {tab === 'decisions' && <DecisionsTab projectId={project.id} canReview={role === 'lead'} />}
      {tab === 'experts' && <ExpertsTab projectId={project.id} initial={params.get('q') ?? ''} />}
    </>
  );
}

// ───────────────────────────── always-on capture ─────────────────────────────

function CaptureStrip({ projectId, canScan }: { projectId: string; canScan: boolean }) {
  const { data: c } = useCapture(projectId);
  const scan = useCaptureScan(projectId);
  if (!c) return null;
  const live = c.sources.filter(s => s.live && s.autoSync);
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
        <span className="flex items-center gap-2.5">
          <span className="relative grid size-9 place-items-center rounded-full bg-brand-50 text-brand-700">
            <Radar className="size-[18px]" aria-hidden />
            {c.watching && <span className="absolute -top-0.5 -right-0.5 size-2.5 animate-pulse rounded-full border-2 border-card bg-verified" aria-label="Watching" />}
          </span>
          <span>
            <span className="block text-small font-semibold text-ink-900">Always-on capture</span>
            <span className="block text-caption text-ink-500">
              {c.watching ? `Watching ${live.length} live source${live.length === 1 ? '' : 's'}, checked every ${c.checkEveryMinutes} min` : 'Reads every source as it is added'}
              {c.lastCapturedAt ? ` · updated ${ago(c.lastCapturedAt)}` : ''}
            </span>
          </span>
        </span>
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-small">
          <div><dt className="sr-only">Decisions captured</dt><dd><strong className="text-h3 font-semibold">{c.decisions}</strong> <span className="text-ink-500">decisions</span></dd></div>
          <div><dt className="sr-only">With the reason</dt><dd><strong className="text-h3 font-semibold">{c.withReason}</strong> <span className="text-ink-500">with the why</span></dd></div>
          <div><dt className="sr-only">New this week</dt><dd><strong className="text-h3 font-semibold">{c.decisionsThisWeek}</strong> <span className="text-ink-500">this week</span></dd></div>
        </dl>
        {canScan && <Button className="ml-auto" onClick={() => scan.mutate()} disabled={scan.isPending}>{scan.isPending ? 'Reading…' : 'Capture now'}</Button>}
      </div>
      {c.sources.length > 0 && (
        <ul className="flex flex-wrap gap-1.5" aria-label="Sources being read">
          {c.sources.map(s => (
            <li key={s.id} className="flex items-center gap-1.5 rounded-full border border-line px-2.5 py-1 text-caption text-ink-700" title={s.live ? (s.autoSync ? 'Read again automatically' : 'Automatic sync is off') : 'Read when added'}>
              <span className={cn('size-1.5 rounded-full', s.live && s.autoSync ? 'bg-verified' : 'bg-ink-300')} aria-hidden />
              {s.title}
              <span className="text-ink-400">· {SOURCE_TYPE_LABEL[s.type as SourceTypeDto] ?? s.type} · {s.decisions}</span>
            </li>
          ))}
        </ul>
      )}
      {c.sources.length > 0 && !c.watching && <p className="text-caption text-ink-500">Connect GitHub, Slack, Jira or Google Drive on the Sources page and Baton keeps reading them in the background, so decisions are captured the day they are made.</p>}
    </Card>
  );
}

// ───────────────────────────── ask ─────────────────────────────

const CONFIDENCE: Record<ProjectAnswerDto['confidence'], { status: Status; label: string; why: string }> = {
  HIGH: { status: 'verified', label: 'High confidence', why: 'A recorded decision or verified fact, confirmed or backed by more than one source.' },
  MEDIUM: { status: 'single', label: 'Medium confidence', why: 'Found in the project’s sources. Check the quote below.' },
  LOW: { status: 'inferred', label: 'Not sure', why: 'Only one loose passage supports this.' },
  NONE: { status: 'missing', label: 'I don’t know', why: 'Nothing the project has written answers this.' },
};
const KIND: Record<string, string> = { DECISION: 'Decision', CLAIM: 'Handover fact', PASSAGE: 'Passage' };

function AskTab({ projectId, initial }: { projectId: string; initial: string }) {
  const ask = useProjectAsk(projectId);
  const { data: log } = useDecisions(projectId);
  const [q, setQ] = useState(initial);
  const [asked, setAsked] = useState('');
  const sourcesRef = useRef<HTMLOListElement>(null);
  const go = (text: string) => {
    const t = text.trim();
    if (t.length < 3) return;
    setAsked(t);
    ask.mutate(t);
  };
  useEffect(() => { if (initial) go(initial); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  const examples = useMemo(() => {
    const t = log?.topics.slice(0, 3).map(x => `What did we decide about ${x.name}, and why?`) ?? [];
    return [...t, 'Who understands payments?'].slice(0, 4);
  }, [log]);
  const a = ask.data;
  const err = ask.error instanceof ApiError ? ask.error : null;
  const submit = (e: FormEvent) => { e.preventDefault(); go(q); };
  const cite = (n: number) => sourcesRef.current?.querySelector(`[data-n="${n}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });

  return (
    <>
      <Card className="flex flex-col gap-3">
        <form onSubmit={submit} className="flex gap-2">
          <label className="flex flex-1 items-center gap-2 rounded-lg border border-line-strong bg-card px-3 py-2.5 focus-within:border-brand-600">
            <Search className="size-4 text-ink-400" aria-hidden />
            <input value={q} onChange={e => setQ(e.target.value)} maxLength={500} placeholder="e.g. Why did we choose Kafka over RabbitMQ?" aria-label="Your question" className="w-full bg-transparent text-body outline-none" />
          </label>
          <Button kind="primary" type="submit" disabled={ask.isPending || q.trim().length < 3}>{ask.isPending ? 'Looking…' : 'Ask'}</Button>
        </form>
        <div className="flex flex-wrap gap-2">
          {examples.map(x => <button key={x} type="button" onClick={() => { setQ(x); go(x); }} className="rounded-full border border-line px-3 py-1 text-caption text-ink-700 hover:bg-canvas cursor-pointer">{x}</button>)}
        </div>
      </Card>
      {err && <Banner tone="conflict" icon={MessageCircleQuestion}>{err.message}</Banner>}
      {a && (
        <Card className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-caption text-ink-500">You asked: <span className="text-ink-900">{asked}</span></p>
            <span className="flex items-center gap-1.5 text-caption text-ink-500">{a.mode === 'ai' ? <><Sparkles className="size-3.5" aria-hidden />Written by your company’s AI from the sources below</> : <><Search className="size-3.5" aria-hidden />Taken straight from the sources</>}</span>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={CONFIDENCE[a.confidence].status} label={CONFIDENCE[a.confidence].label} />
            <span className="text-caption text-ink-500">{CONFIDENCE[a.confidence].why}</span>
          </div>
          {a.confidence === 'NONE'
            ? <Banner tone="missing" icon={MessageCircleQuestion}>{a.answer}</Banner>
            : <p className="text-body-l text-ink-900"><WithCitations text={a.answer} onCite={cite} /></p>}
          {a.askInstead && <AskInstead who={a.askInstead} />}
          {a.sources.length > 0 && (
            <div className="flex flex-col gap-2">
              <p className="overline text-ink-400">Based on</p>
              <ol ref={sourcesRef} className="flex flex-col gap-2">
                {a.sources.map(s => (
                  <li key={s.n} data-n={s.n} className="flex flex-col gap-1.5 rounded-xl border border-line p-3">
                    <span className="flex flex-wrap items-center gap-2 text-caption">
                      <span className="grid size-5 place-items-center rounded-full bg-brand-50 font-semibold text-brand-700">{s.n}</span>
                      <span className="overline text-ink-500">{KIND[s.kind] ?? s.kind}</span>
                      <span className="text-ink-500">{s.detail}</span>
                      {s.url && <a href={s.url} target="_blank" rel="noreferrer" className="ml-auto flex items-center gap-1 font-medium text-brand-700 hover:underline">Open<ExternalLink className="size-3" aria-hidden /></a>}
                    </span>
                    {s.kind !== 'PASSAGE' && <span className="text-small font-medium">{s.title}</span>}
                    {s.quote && <EvidenceQuote source={`${s.source}${s.author ? ` · ${s.author}` : ''}${s.at ? ` · ${day(s.at)}` : ''}`} quote={s.quote} />}
                  </li>
                ))}
              </ol>
            </div>
          )}
        </Card>
      )}
    </>
  );
}

function WithCitations({ text, onCite }: { text: string; onCite: (n: number) => void }) {
  return (
    <>
      {text.split(/(\[\d+])/g).map((part, i) => {
        const m = /^\[(\d+)]$/.exec(part);
        return m
          ? <button key={i} type="button" onClick={() => onCite(Number(m[1]))} aria-label={`Source ${m[1]}`} className="mx-0.5 rounded bg-brand-50 px-1 align-super text-caption font-semibold text-brand-700 cursor-pointer">{m[1]}</button>
          : <span key={i}>{part}</span>;
      })}
    </>
  );
}

function AskInstead({ who }: { who: NonNullable<ProjectAnswerDto['askInstead']> }) {
  return (
    <div className="flex flex-col gap-2 rounded-xl bg-inferred-bg p-3.5">
      <div className="flex items-center gap-3">
        <Avatar initials={initials(who.name)} tone={toneOf(who.name)} />
        <div className="min-w-0 flex-1">
          <p className="text-small font-semibold text-ink-900">Ask {first(who.name)}</p>
          <p className="text-small text-ink-700">{who.reason}</p>
        </div>
        {who.leaving && <span className="flex items-center gap-1 rounded-full bg-conflict-bg px-2 py-0.5 text-caption font-medium text-conflict"><UserX className="size-3" aria-hidden />Leaving</span>}
      </div>
      {who.evidence.slice(0, 2).map((e, i) => <EvidenceQuote key={i} source={`${e.source}${e.at ? ` · ${day(e.at)}` : ''}`} quote={e.quote} />)}
    </div>
  );
}

// ───────────────────────────── decisions ─────────────────────────────

function DecisionsTab({ projectId, canReview }: { projectId: string; canReview: boolean }) {
  const [q, setQ] = useState('');
  const [topic, setTopic] = useState('');
  const [person, setPerson] = useState('');
  const { data, isLoading, error, isFetching } = useDecisions(projectId, { q, topic, person });
  const review = useReviewDecision(projectId);
  const items = data?.items ?? [];
  return (
    <>
      <Card className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex min-w-[220px] flex-1 items-center gap-2 rounded-lg border border-line-strong bg-card px-3 py-2 focus-within:border-brand-600">
            <Search className="size-4 text-ink-400" aria-hidden />
            <input value={q} onChange={e => setQ(e.target.value)} placeholder="Search decisions, reasons and alternatives" aria-label="Search decisions" className="w-full bg-transparent text-small outline-none" />
          </label>
          <select aria-label="Topic" value={topic} onChange={e => setTopic(e.target.value)} className="rounded-lg border border-line-strong bg-card px-2.5 py-2 text-small">
            <option value="">All topics</option>
            {data?.topics.map(t => <option key={t.name} value={t.name}>{t.name} ({t.count})</option>)}
          </select>
          <select aria-label="Decided by" value={person} onChange={e => setPerson(e.target.value)} className="rounded-lg border border-line-strong bg-card px-2.5 py-2 text-small">
            <option value="">Everyone</option>
            {data?.people.map(p => <option key={p.name} value={p.name}>{p.name} ({p.count})</option>)}
          </select>
        </div>
        {data && <p className="text-caption text-ink-500" aria-live="polite">{items.length} shown of {data.total} decisions · {data.withReason} with the reason written down{isFetching ? ' · updating…' : ''}</p>}
      </Card>
      <QueryState loading={isLoading} error={error} empty={!isLoading && items.length === 0}
        emptyText={q || topic || person ? 'No decisions match.' : 'No decisions captured yet. Add a source (meeting notes, a PR thread, a chat export) and Baton finds the decisions in it, with the reasons.'} />
      <div className="reveal-list flex flex-col gap-3">
        {items.map(d => <DecisionCard key={d.id} d={d} canReview={canReview} pending={review.isPending} onReview={r => review.mutate({ id: d.id, review: r })} />)}
      </div>
      {review.error && <Banner tone="conflict" icon={AlertTriangle}>{(review.error as Error).message}</Banner>}
    </>
  );
}

function DecisionCard({ d, canReview, pending, onReview }: { d: DecisionDto; canReview: boolean; pending: boolean; onReview: (r: DecisionDto['review']) => void }) {
  return (
    <Card className="flex flex-col gap-3">
      <div className="flex flex-wrap items-start gap-2">
        <h3 className="min-w-[220px] flex-1 text-body font-semibold text-ink-900">{d.title}</h3>
        {d.topic && <Chip>{d.topic}</Chip>}
        {d.review === 'CONFIRMED' && <span className="flex items-center gap-1 rounded-full bg-verified-bg px-2 py-0.5 text-caption font-medium text-verified"><CheckCircle2 className="size-3" aria-hidden />Confirmed by the lead</span>}
      </div>
      {d.reasoning
        ? <p className="rounded-lg bg-verified-bg px-3 py-2 text-small text-ink-900"><strong className="font-semibold">Why: </strong>{d.reasoning}</p>
        : <p className="rounded-lg bg-inferred-bg px-3 py-2 text-small text-ink-700"><strong className="font-semibold">No reason written down.</strong>{d.decidedBy ? ` Ask ${first(d.decidedBy)} why, and add it to a document.` : ' Whoever made this decision should write down why.'}</p>}
      {d.alternatives.length > 0 && <p className="text-small text-ink-700"><span className="font-medium text-ink-500">Instead of: </span>{d.alternatives.join(' · ')}</p>}
      <p className="flex flex-wrap items-center gap-x-2 text-caption text-ink-500">
        {d.decidedBy && <span className="flex items-center gap-1.5"><Avatar initials={initials(d.decidedBy)} tone={toneOf(d.decidedBy)} size="sm" /><span className="font-medium text-ink-700">{d.decidedBy}</span></span>}
        {d.decidedAt && <span>{day(d.decidedAt)}</span>}
        <span>· {SOURCE_TYPE_LABEL[d.sourceType as SourceTypeDto] ?? d.sourceType}</span>
      </p>
      <EvidenceQuote source={`${d.sourceTitle} · ${d.docTitle}`} quote={d.quote} />
      {canReview && (
        <div className="flex flex-wrap items-center justify-end gap-2">
          {d.review !== 'AUTO' && <Button kind="ghost" onClick={() => onReview('AUTO')} disabled={pending}><RotateCcw className="size-4" aria-hidden />Undo</Button>}
          {d.review !== 'REJECTED' && <Button kind="ghost" onClick={() => onReview('REJECTED')} disabled={pending}><X className="size-4" aria-hidden />Not a decision</Button>}
          {d.review !== 'CONFIRMED' && <Button kind="secondary" onClick={() => onReview('CONFIRMED')} disabled={pending}><Check className="size-4" aria-hidden />Confirm</Button>}
        </div>
      )}
    </Card>
  );
}

// ───────────────────────────── who knows what ─────────────────────────────

const VERDICT: Record<ExpertsDto['verdict']['level'], { tone: 'conflict' | 'missing' | 'inferred' | 'verified' | 'single'; label: string }> = {
  CRITICAL: { tone: 'conflict', label: 'Critical' }, HIGH: { tone: 'missing', label: 'High risk' }, MEDIUM: { tone: 'inferred', label: 'Medium risk' },
  LOW: { tone: 'verified', label: 'Shared' }, NONE: { tone: 'single', label: 'No one' },
};

function ExpertsTab({ projectId, initial }: { projectId: string; initial: string }) {
  const find = useFindExperts(projectId);
  const { data: log } = useDecisions(projectId);
  const [q, setQ] = useState(initial);
  const go = (text: string) => { if (text.trim().length >= 2) find.mutate(text.trim()); };
  useEffect(() => { if (initial) go(initial); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  const r = find.data;
  const err = find.error instanceof ApiError ? find.error : null;
  const topics = log?.topics.slice(0, 5).map(t => t.name) ?? [];
  return (
    <>
      <Card className="flex flex-col gap-3">
        <form onSubmit={(e: FormEvent) => { e.preventDefault(); go(q); }} className="flex gap-2">
          <label className="flex flex-1 items-center gap-2 rounded-lg border border-line-strong bg-card px-3 py-2.5 focus-within:border-brand-600">
            <Users className="size-4 text-ink-400" aria-hidden />
            <input value={q} onChange={e => setQ(e.target.value)} maxLength={200} placeholder="Who understands the billing module?" aria-label="What do you need an expert on" className="w-full bg-transparent text-body outline-none" />
          </label>
          <Button kind="primary" type="submit" disabled={find.isPending || q.trim().length < 2}>{find.isPending ? 'Looking…' : 'Find'}</Button>
        </form>
        {topics.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 text-caption text-ink-500">Try
            {topics.map(t => <button key={t} type="button" onClick={() => { setQ(t); go(t); }} className="rounded-full border border-line px-3 py-1 text-ink-700 hover:bg-canvas cursor-pointer">{t}</button>)}
          </div>
        )}
      </Card>
      {err && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
      {r && (
        <>
          <Banner tone={VERDICT[r.verdict.level].tone} icon={r.verdict.level === 'LOW' ? ShieldCheck : AlertTriangle}>
            <span className="flex flex-wrap items-center gap-2">
              <strong className="font-semibold">{VERDICT[r.verdict.level].label}</strong>
              <span>{r.verdict.message}</span>
              {r.verdict.backup && <Chip>Backup: {r.verdict.backup}</Chip>}
            </span>
          </Banner>
          <ul className="flex flex-col gap-3">
            {r.experts.map((e, i) => <ExpertCard key={e.name} e={e} top={i === 0} spof={i === 0 && r.verdict.singlePointOfFailure} />)}
          </ul>
          {r.experts.length > 0 && <p className="text-caption text-ink-500">Ranked by what each person actually wrote about {r.terms.join(' ')} in sources you can see: line by line in chats, by author in documents, plus owners and decision makers. Recent work counts more.</p>}
        </>
      )}
    </>
  );
}

function ExpertCard({ e, top, spof }: { e: ExpertDto; top: boolean; spof: boolean }) {
  return (
    <li>
      <Card className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Avatar initials={initials(e.name)} tone={toneOf(e.name)} size="lg" />
          <div className="min-w-[160px] flex-1">
            <p className="flex flex-wrap items-center gap-2 text-body font-semibold text-ink-900">{e.name}
              {top && <span className="rounded-full bg-brand-50 px-2 py-0.5 text-caption font-medium text-brand-700">Best person to ask</span>}
              {spof && <span className="flex items-center gap-1 rounded-full bg-missing-bg px-2 py-0.5 text-caption font-medium text-missing"><AlertTriangle className="size-3" aria-hidden />Single point of failure</span>}
              {e.leaving && <span className="flex items-center gap-1 rounded-full bg-conflict-bg px-2 py-0.5 text-caption font-medium text-conflict"><UserX className="size-3" aria-hidden />Leaving</span>}
            </p>
            <p className="text-caption text-ink-500">{e.onProject ? (e.role === 'PROJECT_LEAD' ? 'Project lead' : 'Team member') : 'Not on this project'} · {e.passages} passage{e.passages === 1 ? '' : 's'}{e.lastActive ? ` · last active ${day(e.lastActive)}` : ''}</p>
          </div>
          <div className="w-[150px]">
            <p className="mb-1 text-right text-caption font-medium text-ink-700">{Math.round(e.share * 100)}% of what is written</p>
            <Progress value={e.share} tone={top && spof ? 'bg-missing' : 'bg-brand-600'} label={`${e.name}'s share`} />
          </div>
        </div>
        <ul className="flex flex-col gap-2">
          {e.evidence.map((ev, i) => (
            <li key={i}>
              {ev.why && <p className="mb-1 flex items-center gap-1 text-caption font-medium text-brand-700"><Layers className="size-3" aria-hidden />{ev.why}</p>}
              <EvidenceQuote source={`${ev.source}${ev.at ? ` · ${day(ev.at)}` : ''}`} quote={ev.quote} matched={ev.sourceType !== 'CLAIM'} />
            </li>
          ))}
        </ul>
      </Card>
    </li>
  );
}

