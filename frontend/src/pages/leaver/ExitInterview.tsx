import { AlertTriangle, CheckCircle2, Info, Lock } from 'lucide-react';
import { useState } from 'react';
import { AccountMenu, FocusShell } from '@/components/layout/shells';
import { SensitivityChip } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/form';
import { Banner, Card, EvidenceQuote, Progress, QueryState, StatusCircle } from '@/components/ui/misc';
import { evidenceLabel, sens, useAnswerGapLive, useClaim, useGapList, type GapDto } from '@/data/live';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

const KIND: Record<GapDto['kind'], { label: string; why: string }> = {
  MISSING_OWNER: { label: 'Owner or access', why: 'No source names who owns this or how it is handed over.' },
  MISSING_EVIDENCE: { label: 'Missing evidence', why: 'This came up in the sources but nothing confirms it.' },
  CONFLICT: { label: 'Conflict', why: 'Two sources disagree. Your answer decides which one is current.' },
  MISSING_CHECKLIST: { label: 'Checklist', why: 'Every handover should cover this, and no source does.' },
  OTHER: { label: 'Question from the lead', why: 'Your project lead asked this while reviewing.' },
};
// Client-side hint only; the server redacts personal data and refuses keys and passwords.
const SECRET = /(api[_-]?key|password|passwd|secret|token)\s*[:=]|sk-[a-z0-9]{12,}|AKIA[0-9A-Z]{16}/i;
const open = (g: GapDto) => g.status === 'OPEN' || g.status === 'UNANSWERED';

export default function ExitInterview() {
  const { handover } = useSession();
  const q = useGapList(handover?.id);
  const gaps = [...(q.data ?? [])].sort((a, b) => a.priority - b.priority);
  const answer = useAnswerGapLive(handover?.id);
  const [viewId, setViewId] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const view = gaps.find(g => g.id === viewId) ?? gaps.find(open) ?? gaps[gaps.length - 1];
  const done = gaps.filter(g => !open(g)).length;
  const idx = view ? gaps.indexOf(view) : 0;
  const text = view ? drafts[view.id] ?? '' : '';
  const hasSecret = SECRET.test(text);
  const err = answer.error instanceof ApiError ? answer.error.message : null;

  const submit = (skip?: boolean) => {
    if (!view) return;
    answer.mutate(
      skip ? { id: view.id, skip: true, reason: 'Departing member does not know' } : { id: view.id, answer: text.trim() },
      { onSuccess: () => { setViewId(null); answer.reset(); } },
    );
  };

  return (
    <FocusShell
      title={`Exit interview · ${handover?.title ?? ''}`}
      right={
        <div className="flex items-center gap-4">
          <span className="hidden text-small text-ink-500 sm:inline">{done} of {gaps.length} answered</span>
          <Progress value={gaps.length ? done / gaps.length : 0} tone="bg-verified" className="w-[140px]" label="Questions answered" />
          <AccountMenu />
        </div>
      }
    >
      <QueryState loading={q.isLoading} error={q.error} empty={!q.isLoading && gaps.length === 0} emptyText="No questions for you yet. Your lead will start the reconstruction first." />
      {gaps.length > 0 && (
        <div className="grid items-start gap-8 lg:grid-cols-[320px_1fr]">
          <nav aria-label="Questions" className="flex flex-col gap-1">
            <p className="overline text-ink-400">Only what sources can’t answer</p>
            <p className="mb-2 text-small text-ink-500">{gaps.length} questions instead of a 20-page document.</p>
            {gaps.map((g, i) => {
              const on = g.id === view?.id;
              return (
                <button key={g.id} type="button" onClick={() => { setViewId(g.id); answer.reset(); }} aria-current={on ? 'step' : undefined} className={cn('flex items-center gap-2.5 rounded-lg p-2.5 text-left cursor-pointer', on ? 'bg-card ring-[1.5px] ring-brand-600' : 'hover:bg-card')}>
                  <StatusCircle state={open(g) ? 'todo' : g.status === 'SKIPPED' ? 'warn' : 'done'} size={20} />
                  <span className="min-w-0">
                    <span className={cn('line-clamp-2 block text-small font-medium', open(g) && !on ? 'text-ink-500' : 'text-ink-900')}>{i + 1}. {g.question}</span>
                    <span className="block text-caption text-ink-400">{KIND[g.kind].label}</span>
                  </span>
                </button>
              );
            })}
          </nav>

          {view && (
            <Card className="flex flex-col gap-5 p-6 shadow-pop sm:p-8">
              <p className="overline text-brand-600">Question {idx + 1} of {gaps.length} · {KIND[view.kind].label}</p>
              <h1 className="text-h2 font-semibold sm:text-h1">{view.question}</h1>
              <Why gap={view} />
              {!open(view) ? (
                <Banner tone={view.status === 'SKIPPED' ? 'inferred' : 'verified'} icon={view.status === 'SKIPPED' ? Info : CheckCircle2}>
                  <span className="block font-medium">{view.status === 'SKIPPED' ? 'Skipped — routed to your lead' : 'Your answer (stored as cited testimony)'}</span>
                  {view.status !== 'SKIPPED' && <span className="mt-1 block">{view.answer}</span>}
                </Banner>
              ) : (
                <>
                  <div className="flex flex-col gap-2">
                    <label htmlFor="answer" className="text-caption font-medium text-ink-700">Your answer</label>
                    <Textarea id="answer" rows={5} value={text} onChange={e => setDrafts(d => ({ ...d, [view.id]: e.target.value }))} placeholder="Write what you know. Short is fine." aria-describedby="answer-note" />
                    <p id="answer-note" className={cn('flex items-center gap-1.5 text-caption', hasSecret ? 'text-conflict' : 'text-ink-500')}>
                      <Lock className={cn('size-3', hasSecret ? 'text-conflict' : 'text-verified')} aria-hidden />
                      {hasSecret ? 'This looks like a password or key. Don’t paste secrets — say where they are kept instead.' : 'Emails and phone numbers you type are redacted before AI processing.'}
                    </p>
                  </div>
                  {err && <Banner tone="conflict" icon={AlertTriangle}>{err}</Banner>}
                  <div className="flex flex-wrap items-center gap-2">
                    <Button kind="ghost" disabled={answer.isPending} onClick={() => submit(true)}>I don’t know</Button>
                    <span className="flex-1" />
                    <Button kind="primary" size="lg" disabled={!text.trim() || hasSecret || answer.isPending} onClick={() => submit()}>{answer.isPending ? 'Saving…' : 'Save and next'}</Button>
                  </div>
                </>
              )}
              <hr className="border-line" />
              <p className="text-caption text-ink-500">Your answers become cited testimony. Your project lead reviews them before anything is approved or shared.</p>
            </Card>
          )}
        </div>
      )}
    </FocusShell>
  );
}

function Why({ gap }: { gap: GapDto }) {
  const claim = useClaim(gap.kind === 'CONFLICT' ? null : gap.claimId);
  const ev = claim.data?.evidence[0];
  return (
    <div className="flex flex-col gap-2.5 rounded-xl bg-missing-bg p-4">
      <p className="flex items-center gap-2 text-small font-medium text-missing"><Info className="size-3.5" aria-hidden />Why Baton is asking{claim.data && <SensitivityChip level={sens(claim.data.sensitivity)} />}</p>
      <p className="text-small">{KIND[gap.kind].why}</p>
      {ev && <EvidenceQuote source={evidenceLabel(ev)} quote={`“${ev.quote}”`} />}
    </div>
  );
}
