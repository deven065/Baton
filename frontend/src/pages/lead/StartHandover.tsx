import { AlertTriangle, X } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Field, Input, Radio } from '@/components/ui/form';
import { Banner } from '@/components/ui/misc';
import { useCreateHandover, useProject } from '@/data/live';
import { useAuth, useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';

const SCENARIOS = [
  { id: 'PLANNED', title: 'Planned exit', desc: 'They are still around: the full flow, including their exit interview.' },
  { id: 'SUDDEN', title: 'Sudden exit', desc: 'They are unavailable: Baton works from sources alone, open questions go to you.' },
  { id: 'SPLIT', title: 'Split handover', desc: 'Two or more successors each get their own view of the same handover.' },
] as const;

/** Lead: start a handover for someone on the project team who is leaving. */
export function StartHandoverModal({ onClose }: { onClose: () => void }) {
  const { project, user } = useSession();
  const { refresh } = useAuth();
  const navigate = useNavigate();
  const { data: detail } = useProject(project?.id);
  const create = useCreateHandover(project?.id);
  const [person, setPerson] = useState('');
  const [title, setTitle] = useState('');
  const [scenario, setScenario] = useState<(typeof SCENARIOS)[number]['id']>('PLANNED');
  const ref = useRef<HTMLDivElement>(null);
  const others = (detail?.members ?? []).filter(m => m.userId !== user.id);
  const chosen = others.find(m => m.userId === person);

  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    ref.current?.focus();
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { departingUserId: person, title: title.trim() || `${chosen?.displayName ?? 'Handover'} — handover`, scenario },
      { onSuccess: async () => { await refresh(); onClose(); navigate('/app/sources'); } },
    );
  };
  const err = create.error instanceof ApiError ? create.error : null;
  const details = (err?.details ?? null) as Record<string, string> | null;

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      <div className="absolute inset-0 bg-ink-900/50" onClick={onClose} />
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="start-handover" className="relative w-full max-w-[540px] overflow-hidden rounded-xl bg-card shadow-pop outline-none">
        <div className="flex items-center justify-between border-b border-line px-6 py-4">
          <h2 id="start-handover" className="text-h3 font-semibold">Start a handover</h2>
          <button type="button" aria-label="Close" onClick={onClose} className="p-1 text-ink-500 cursor-pointer"><X className="size-[18px]" /></button>
        </div>
        <form onSubmit={submit} className="flex max-h-[75dvh] flex-col gap-4 overflow-y-auto p-6">
          <Field label="Who is leaving?" hint={others.length === 0 ? <p className="text-caption text-inferred">Add the person to your team first (Team in the sidebar).</p> : undefined}>
            {id => (
              <select id={id} autoFocus value={person} onChange={e => setPerson(e.target.value)} className="w-full rounded-lg border border-line-strong bg-card px-3 py-2.5 text-body">
                <option value="">Choose a team member…</option>
                {others.map(m => <option key={m.userId} value={m.userId}>{m.displayName}</option>)}
              </select>
            )}
          </Field>
          <Field label="Title">{id => <Input id={id} value={title} onChange={e => setTitle(e.target.value)} placeholder={chosen ? `${chosen.displayName} — handover` : 'e.g. Priya’s handover (graduating)'} maxLength={200} />}</Field>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1.5 text-caption font-medium text-ink-700">Scenario</legend>
            {SCENARIOS.map(s => (
              <Radio key={s.id} name="scenario" checked={scenario === s.id} onChange={() => setScenario(s.id)}>
                <span className="text-small font-medium">{s.title}</span>
                <span className="text-caption text-ink-500">{s.desc}</span>
              </Radio>
            ))}
          </fieldset>
          {err && <Banner tone="conflict" icon={AlertTriangle}>{details ? Object.values(details).join(' ') : err.message}</Banner>}
          <div className="flex justify-end gap-2">
            <Button type="button" kind="ghost" onClick={onClose}>Cancel</Button>
            <Button type="submit" kind="primary" disabled={!person || create.isPending}>{create.isPending ? 'Starting…' : 'Start handover'}</Button>
          </div>
        </form>
      </div>
    </div>
  );
}
