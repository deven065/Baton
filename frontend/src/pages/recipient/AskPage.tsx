import { CornerDownRight, GitCompare, MessageCircleQuestion, Search, Send, Sparkles } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Banner, Card, CardHeader, EvidenceQuote, PageHeader } from '@/components/ui/misc';
import { STATUS, STATUS_LABEL, fmtDate, fmtDateTime, typeLabel, useAsk, useMyQuestions, type RecipientViewDto } from '@/data/live';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';
import { RecipientGate } from './RecipientPages';

const EXAMPLES = ['Who owns the payments work?', 'What are the biggest risks?', 'Which accounts do I need access to?', 'Who do I contact at the vendor?'];

/** Ask the handover: answers come only from the approved version this person can see, with the claims behind them. */
export function AskPage() {
  return <RecipientGate>{v => <Ask v={v} />}</RecipientGate>;
}

function Ask({ v }: { v: RecipientViewDto }) {
  const { ask, send } = useAsk(v.shareId);
  const { data: questions = [] } = useMyQuestions(v.shareId);
  const [q, setQ] = useState('');
  const [asked, setAsked] = useState('');
  const leaver = v.handover.departingName.split(' ')[0];
  const go = (text: string) => {
    const t = text.trim();
    if (t.length < 3) return;
    setAsked(t);
    send.reset();
    ask.mutate(t);
  };
  const submit = (e: FormEvent) => { e.preventDefault(); go(q); };
  const err = (ask.error ?? send.error) instanceof ApiError ? (ask.error ?? send.error) as ApiError : null;
  const a = ask.data;

  return (
    <>
      <PageHeader title="Ask the handover" sub={`Answers come only from version ${v.versionNo}, and only from what you’re allowed to see. Each one shows its sources.`} />
      <Card className="flex flex-col gap-3">
        <form onSubmit={submit} className="flex gap-2">
          <label className="flex flex-1 items-center gap-2 rounded-lg border border-line-strong bg-card px-3 py-2.5 focus-within:border-brand-600">
            <Search className="size-4 text-ink-400" aria-hidden />
            <input value={q} onChange={e => setQ(e.target.value)} maxLength={500} placeholder="e.g. Who has the Razorpay live key?" aria-label="Your question" className="w-full bg-transparent text-body outline-none" autoFocus />
          </label>
          <Button kind="primary" type="submit" disabled={ask.isPending || q.trim().length < 3}>{ask.isPending ? 'Looking…' : 'Ask'}</Button>
        </form>
        <div className="flex flex-wrap gap-2">
          {EXAMPLES.map(x => <button key={x} type="button" onClick={() => { setQ(x); go(x); }} className="rounded-full border border-line px-3 py-1 text-caption text-ink-700 hover:bg-canvas cursor-pointer">{x}</button>)}
        </div>
      </Card>

      {err && <Banner tone="conflict" icon={MessageCircleQuestion}>{err.message}</Banner>}

      {a && (
        <Card className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-caption text-ink-500">You asked: <span className="text-ink-900">{asked}</span></p>
            <span className="flex items-center gap-1.5 text-caption text-ink-500">{a.mode === 'ai' ? <><Sparkles className="size-3.5" aria-hidden />Written by your company’s AI from the handover</> : <><Search className="size-3.5" aria-hidden />Best match in the handover</>}</span>
          </div>
          {a.found
            ? <p className="text-body-l text-ink-900">{a.answer}</p>
            : <Banner tone="inferred" icon={MessageCircleQuestion}>{a.answer}</Banner>}
          {a.citations.length > 0 && (
            <div className="flex flex-col gap-2">
              <p className="overline text-ink-400">{a.found ? 'Based on' : 'Closest in the handover'}</p>
              {a.citations.map(c => (
                <div key={c.claimId} className="flex flex-col gap-1.5 rounded-xl border border-line p-3">
                  <span className="flex flex-wrap items-center gap-2"><span className="overline text-ink-500">{typeLabel(c.type)}</span><StatusBadge status={STATUS[c.status]} label={STATUS_LABEL[c.status]} /></span>
                  <span className="text-small font-medium">{c.title}</span>
                  {c.quote && <EvidenceQuote source={c.source ?? 'Source'} quote={c.quote} />}
                </div>
              ))}
            </div>
          )}
          <div className={cn('flex flex-wrap items-center gap-3 rounded-xl p-3', a.found ? 'bg-canvas' : 'bg-inferred-bg')}>
            <span className="flex-1 text-small text-ink-700">{a.found ? `Not what you needed? ${leaver} can answer it.` : `Send this question to ${leaver}. Their answer is added to the handover with them as the source.`}</span>
            {send.isSuccess
              ? <span className="text-small font-medium text-verified">Sent to {leaver}</span>
              : <Button kind={a.found ? 'secondary' : 'primary'} onClick={() => send.mutate(asked)} disabled={send.isPending}><Send className="size-4" aria-hidden />{send.isPending ? 'Sending…' : `Ask ${leaver}`}</Button>}
          </div>
        </Card>
      )}

      <section className="flex flex-col gap-3">
        <CardHeader title="Your questions" sub={`Questions you sent to ${leaver}`} />
        <ul className="flex flex-col gap-2">
          {questions.map(x => (
            <li key={x.id}>
              <Card className="flex flex-col gap-2">
                <span className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-small font-medium">{x.question}</span>
                  <span className="text-caption text-ink-400">{fmtDate(x.askedAt)}</span>
                </span>
                {x.status === 'ANSWERED' && x.answer && <p className="flex gap-2 text-small text-ink-700"><CornerDownRight className="mt-0.5 size-4 shrink-0 text-verified" aria-hidden /><span><strong>{x.answeredByLeaver ? leaver : 'Answer'}:</strong> {x.answer}{x.answeredAt && <span className="text-caption text-ink-400"> · {fmtDateTime(x.answeredAt)}</span>}</span></p>}
                {x.status === 'SKIPPED' && <p className="flex gap-2 text-small text-ink-700"><CornerDownRight className="mt-0.5 size-4 shrink-0 text-ink-400" aria-hidden /><span><strong>Your lead:</strong> {x.answer || 'Not answered by the person leaving.'}</span></p>}
                {(x.status === 'OPEN' || x.status === 'UNANSWERED') && <p className="text-caption text-inferred">Waiting for {leaver}</p>}
              </Card>
            </li>
          ))}
          {questions.length === 0 && <li className="text-small text-ink-500">None yet.</li>}
        </ul>
      </section>
    </>
  );
}

/** Shown on Start here after a recipient was moved to a newer version. */
export function WhatChanged({ v }: { v: RecipientViewDto }) {
  if (!v.previousVersionNo || v.changes.length === 0) return null;
  return (
    <Card className="flex flex-col gap-2 border-brand-600/40">
      <h2 className="flex items-center gap-2 text-h3 font-semibold"><GitCompare className="size-5 text-brand-600" aria-hidden />What changed since version {v.previousVersionNo}</h2>
      <ul className="flex flex-col gap-1.5">
        {v.changes.slice(0, 12).map(c => (
          <li key={c.kind + c.claimId} className={cn('flex gap-2.5 rounded-md px-3 py-1.5 text-small', c.kind === 'ADDED' ? 'bg-verified-bg' : c.kind === 'REMOVED' ? 'bg-conflict-bg' : 'bg-inferred-bg')}>
            <span aria-hidden className="font-mono">{c.kind === 'ADDED' ? '+' : c.kind === 'REMOVED' ? '−' : '~'}</span>
            <span className="flex flex-col"><span className="text-caption text-ink-500">{typeLabel(c.type)} · {c.kind.toLowerCase()}</span><span className={cn('text-ink-900', c.kind === 'REMOVED' && 'line-through')}>{c.after ?? c.before}</span></span>
          </li>
        ))}
      </ul>
    </Card>
  );
}
