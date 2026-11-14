import { CheckCircle2, CircleDashed, MinusCircle, Plus, X } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/form';
import { Card, CardHeader } from '@/components/ui/misc';
import { useAddQuestion, useGapList, useRemoveQuestion, type GapDto } from '@/data/live';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

const KIND_LABEL: Record<GapDto['kind'], string> = {
  MISSING_OWNER: 'Owner or access', MISSING_EVIDENCE: 'Missing evidence', CONFLICT: 'Conflict', MISSING_CHECKLIST: 'Checklist', OTHER: 'Question',
};
const open = (g: GapDto) => g.status === 'OPEN' || g.status === 'UNANSWERED';

/**
 * The exit interview from the lead's side: the questions Baton generated from what the sources could not answer,
 * and the lead's own. Anything the lead types here goes first, is kept when the reconstruction runs again, and is
 * answered by the person leaving like any other question.
 */
export function ExitQuestions({ handoverId, leaverName }: { handoverId?: string | null; leaverName?: string }) {
  const gaps = useGapList(handoverId);
  const add = useAddQuestion(handoverId);
  const remove = useRemoveQuestion(handoverId);
  const [text, setText] = useState('');
  const [all, setAll] = useState(false);
  if (!handoverId) return null;
  const list = gaps.data ?? [];
  const shown = all ? list : list.slice(0, 6);
  const err = add.error instanceof ApiError ? add.error : null;
  const fieldError = (err?.details as Record<string, string> | undefined)?.question;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (text.trim().length < 3) return;
    add.mutate(text.trim(), { onSuccess: () => { setText(''); add.reset(); } });
  };

  return (
    <Card className="flex flex-col gap-4">
      <CardHeader
        title="Exit interview questions"
        sub={`${leaverName ?? 'The person leaving'} answers these. Baton writes the ones the sources can’t answer; add your own below.`}
      />
      <form onSubmit={submit} className="flex flex-col gap-2">
        <label htmlFor="own-question" className="text-caption font-medium text-ink-700">Your own question</label>
        <Textarea id="own-question" rows={2} value={text} maxLength={500} onChange={e => { setText(e.target.value); if (add.isError) add.reset(); }}
          placeholder="e.g. Who at the vendor do we call when the spreadsheet export breaks?" aria-describedby="own-question-note" />
        <div className="flex flex-wrap items-center gap-3">
          <p id="own-question-note" className={cn('min-w-[200px] flex-1 text-caption', fieldError || err ? 'text-conflict' : 'text-ink-500')}>
            {fieldError ?? (err ? err.message : 'It goes to the top of their list, and stays when you reconstruct again. Don’t paste keys or passwords.')}
          </p>
          <Button type="submit" kind="primary" disabled={text.trim().length < 3 || add.isPending}><Plus className="size-4" aria-hidden />{add.isPending ? 'Adding…' : 'Add question'}</Button>
        </div>
      </form>

      <ul className="flex flex-col divide-y divide-line" aria-label="Questions">
        {shown.map(g => (
          <li key={g.id} className="flex items-start gap-3 py-2.5">
            {g.status === 'ANSWERED' ? <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-verified" aria-label="Answered" />
              : g.status === 'SKIPPED' ? <MinusCircle className="mt-0.5 size-4 shrink-0 text-inferred" aria-label="Skipped" />
                : <CircleDashed className="mt-0.5 size-4 shrink-0 text-ink-400" aria-label="Waiting for an answer" />}
            <span className="min-w-0 flex-1">
              <span className={cn('block text-small', open(g) ? 'font-medium text-ink-900' : 'text-ink-700')}>{g.question}</span>
              <span className="block text-caption text-ink-500">
                {g.custom ? <span className="font-medium text-brand-700">Yours</span> : KIND_LABEL[g.kind]}
                {g.status === 'ANSWERED' && g.answer ? ` · Answer: ${g.answer.length > 120 ? g.answer.slice(0, 120) + '…' : g.answer}` : g.status === 'SKIPPED' ? ' · Skipped' : ' · Waiting'}
              </span>
            </span>
            {g.custom && open(g) && (
              <button type="button" aria-label={`Remove your question: ${g.question}`} title="Remove" disabled={remove.isPending} onClick={() => remove.mutate(g.id)}
                className="rounded-md p-1 text-ink-400 hover:bg-subtle hover:text-conflict cursor-pointer disabled:opacity-40"><X className="size-4" /></button>
            )}
          </li>
        ))}
        {list.length === 0 && <li className="py-3 text-small text-ink-500">No questions yet. Baton writes its own when it reconstructs; you can add yours now.</li>}
      </ul>
      {list.length > 6 && (
        <button type="button" onClick={() => setAll(a => !a)} className="self-start text-small font-medium text-brand-700 hover:underline cursor-pointer">
          {all ? 'Show fewer' : `Show all ${list.length} questions`}
        </button>
      )}
    </Card>
  );
}
