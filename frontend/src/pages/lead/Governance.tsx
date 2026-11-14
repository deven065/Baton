import { AlertTriangle, ArrowRight, Check, CheckCircle2, ExternalLink, FileWarning, GitCommitHorizontal, Lock, RotateCcw, ScanSearch, ShieldCheck, X } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Chip, SensitivityChip } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Banner, Card, CardHeader, PageHeader, QueryState } from '@/components/ui/misc';
import {
  fmtDate, sens, useAccessMap, useChangeSourceAccess, useStaleDocs, useStaleMutations,
  type AccessMapDto, type SensitivityDto, type StaleFindingDto,
} from '@/data/live';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

const LEVELS: SensitivityDto[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];
const LEVEL_NAME: Record<SensitivityDto, string> = { PUBLIC: 'Public', INTERNAL: 'Internal', CONFIDENTIAL: 'Confidential', RESTRICTED: 'Restricted' };
const errText = (e: unknown) => {
  if (!e) return null;
  const d = e instanceof ApiError ? (e.details as Record<string, string> | undefined) : undefined;
  return d ? Object.values(d).join(' ') : (e as Error).message;
};

// ───────────────────────────── Stale docs ─────────────────────────────

const VERB: Record<StaleFindingDto['kind'], string> = { RENAMED: 'renamed it to', REPLACED: 'replaced it with', REMOVED: 'removed it', VERSION: 'moved to' };

/** Documentation that still describes something a later code change renamed, replaced, removed or upgraded. */
export function StaleDocs() {
  const { project } = useSession();
  const { data = [], isLoading, error } = useStaleDocs(project?.id);
  const { scan, decide } = useStaleMutations(project?.id);
  const [tab, setTab] = useState<StaleFindingDto['status']>('OPEN');
  const shown = data.filter(f => f.status === tab);
  const count = (s: StaleFindingDto['status']) => data.filter(f => f.status === s).length;
  const byDoc = useMemo(() => {
    const m = new Map<string, StaleFindingDto[]>();
    shown.forEach(f => m.set(f.docTitle, [...(m.get(f.docTitle) ?? []), f]));
    return [...m.entries()];
  }, [shown]);

  return (
    <>
      <PageHeader
        title="Stale docs"
        sub="Documents that still describe something a later code change renamed, replaced, removed or upgraded. Checked every time a source is read, by rules you can trace: one line of the document, one commit or pull request."
        right={<Button onClick={() => scan.mutate()} disabled={scan.isPending}><ScanSearch className="size-4" aria-hidden />{scan.isPending ? 'Checking…' : 'Check again'}</Button>}
      />
      <div role="tablist" aria-label="Findings" className="flex gap-1 border-b border-line">
        {([['OPEN', 'To decide'], ['CONFIRMED', 'Out of date'], ['DISMISSED', 'Still correct']] as const).map(([s, label]) => (
          <button key={s} role="tab" type="button" aria-selected={tab === s} onClick={() => setTab(s)}
            className={cn('-mb-px border-b-2 px-3 py-2.5 text-small font-medium cursor-pointer', tab === s ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}>
            {label} · {count(s)}
          </button>
        ))}
      </div>
      <QueryState loading={isLoading} error={error} empty={!isLoading && shown.length === 0}
        emptyText={tab === 'OPEN'
          ? 'Nothing to decide. Baton compares your documents with commit messages and closed pull requests: connect a GitHub repository, or upload a git log (git log --date=iso --pretty="%h %ad %an: %s") as a document.'
          : 'Nothing here yet.'} />
      {decide.error && <Banner tone="conflict" icon={AlertTriangle}>{errText(decide.error)}</Banner>}
      <div className="reveal-list flex flex-col gap-4">
        {byDoc.map(([doc, findings]) => (
          <Card key={doc} className="flex flex-col gap-4">
            <CardHeader title={doc} sub={`${findings.length} passage${findings.length === 1 ? '' : 's'} may be out of date`} />
            <ul className="flex flex-col divide-y divide-line">
              {findings.map(f => (
                <li key={f.id} className="flex flex-col gap-3 py-3 first:pt-0 last:pb-0">
                  <div className="grid gap-3 lg:grid-cols-[1fr_auto_1fr] lg:items-center">
                    <figure className="rounded-lg border border-line bg-canvas p-3">
                      <figcaption className="mb-1 flex items-center gap-1.5 text-caption font-medium text-ink-500"><FileWarning className="size-3.5" aria-hidden />The document says</figcaption>
                      <blockquote className="text-small text-ink-900"><Highlight text={f.docQuote} term={f.term} /></blockquote>
                    </figure>
                    <ArrowRight className="mx-auto hidden size-4 text-ink-400 lg:block" aria-hidden />
                    <figure className="rounded-lg border border-line bg-canvas p-3">
                      <figcaption className="mb-1 flex items-center gap-1.5 text-caption font-medium text-ink-500">
                        <GitCommitHorizontal className="size-3.5" aria-hidden />
                        {f.changeUrl ? <a href={f.changeUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 hover:underline">{f.changeRef}<ExternalLink className="size-3" aria-hidden /></a> : f.changeRef}
                        <span>· {fmtDate(f.changeAt)}</span>
                      </figcaption>
                      <p className="text-small text-ink-900">{f.changeText}</p>
                    </figure>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="min-w-[200px] flex-1 text-small text-ink-700">
                      <code className="rounded bg-subtle px-1 font-mono text-caption">{f.term}</code>: {f.changeRef} {VERB[f.kind]}
                      {f.replacement && <> <code className="rounded bg-subtle px-1 font-mono text-caption">{f.replacement}</code></>}.
                    </p>
                    {f.status === 'OPEN' ? (
                      <>
                        <Button kind="ghost" onClick={() => decide.mutate({ id: f.id, status: 'DISMISSED' })} disabled={decide.isPending}><Check className="size-4" aria-hidden />Still correct</Button>
                        <Button kind="primary" onClick={() => decide.mutate({ id: f.id, status: 'CONFIRMED' })} disabled={decide.isPending}><FileWarning className="size-4" aria-hidden />Out of date</Button>
                      </>
                    ) : (
                      <Button kind="ghost" onClick={() => decide.mutate({ id: f.id, status: 'OPEN' })} disabled={decide.isPending}><RotateCcw className="size-4" aria-hidden />Undo</Button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </Card>
        ))}
      </div>
      <p className="text-caption text-ink-500">Open findings also become questions in the exit interview, so the person leaving can say what each document should say now. You only see findings whose document and code change you are cleared for.</p>
    </>
  );
}

function Highlight({ text, term }: { text: string; term: string }) {
  const i = text.toLowerCase().indexOf(term.toLowerCase());
  if (i < 0) return <>{text}</>;
  return <>{text.slice(0, i)}<mark className="rounded bg-inferred/25 px-0.5 text-ink-900">{text.slice(i, i + term.length)}</mark>{text.slice(i + term.length)}</>;
}

// ───────────────────────────── Access ─────────────────────────────

/** Per-team access control: who on the project can see which source, why not, and changing it. */
export function AccessPage() {
  const { project } = useSession();
  const { data, isLoading, error } = useAccessMap(project?.id);
  const [editing, setEditing] = useState<AccessMapDto['sources'][number] | null>(null);
  return (
    <>
      <PageHeader title="Who can see what"
        sub="Every source has a level and, if you choose, the teams allowed to see it. A person sees a source only when their clearance covers its level and they are in one of its teams. Claims follow the strictest source they cite." />
      <QueryState loading={isLoading} error={error} empty={!isLoading && !!data && data.sources.length === 0} emptyText="No sources yet. Add one on the Sources page." />
      {data && data.sources.length > 0 && (
        <Card className="overflow-x-auto p-0">
          <table className="w-full min-w-[640px] border-collapse text-small" aria-label="Who can see which source">
            <thead>
              <tr className="border-b border-line text-left">
                <th scope="col" className="sticky left-0 z-10 bg-card px-4 py-3 font-medium text-ink-500">Source</th>
                {data.people.map(p => (
                  <th key={p.userId} scope="col" className="px-3 py-3 text-center font-medium">
                    <span className="block text-ink-900">{p.name.split(' ')[0]}</span>
                    <span className="block text-caption font-normal text-ink-500">{p.role === 'PROJECT_LEAD' ? 'Lead' : 'Member'} · {LEVEL_NAME[p.clearance]}</span>
                    {p.teams.length > 0 && <span className="block text-caption font-normal text-ink-400">{p.teams.join(', ')}</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {data.sources.map(s => (
                <tr key={s.id} className="border-b border-line last:border-0">
                  <th scope="row" className="sticky left-0 z-10 bg-card px-4 py-3 text-left font-normal">
                    <span className="block font-medium text-ink-900">{s.title}</span>
                    <span className="mt-1 flex flex-wrap items-center gap-1.5">
                      <SensitivityChip level={sens(s.sensitivity)} />
                      {s.teams.map(t => <Chip key={t}>{t}</Chip>)}
                      {s.editable && <button type="button" onClick={() => setEditing(s)} className="text-caption font-medium text-brand-700 hover:underline cursor-pointer">Change</button>}
                    </span>
                  </th>
                  {data.people.map(p => {
                    const ok = p.canSee.includes(s.id);
                    const why = p.blocked[s.id];
                    return (
                      <td key={p.userId} className="px-3 py-3 text-center" title={ok ? `${p.name} can see this` : `${p.name} cannot see this: it ${why}`}>
                        {ok ? <CheckCircle2 className="mx-auto size-[18px] text-verified" aria-label="Can see" />
                          : <span className="inline-flex flex-col items-center gap-0.5"><Lock className="size-4 text-ink-400" aria-label="Cannot see" /><span className="max-w-[120px] text-caption text-ink-500">{why}</span></span>}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {data && <Banner icon={ShieldCheck}>Changes apply at once, everywhere the source’s text is used (its passages, search and every claim built on it), and are recorded in the audit log. Approved versions already shared keep the snapshot their recipients were given. Teams are managed by an admin under People & groups.</Banner>}
      {editing && data && <EditAccess source={editing} teams={data.teams} onClose={() => setEditing(null)} />}
    </>
  );
}

function EditAccess({ source, teams, onClose }: { source: AccessMapDto['sources'][number]; teams: string[]; onClose: () => void }) {
  const { project } = useSession();
  const change = useChangeSourceAccess(project?.id);
  const [level, setLevel] = useState<SensitivityDto>(source.sensitivity);
  const [chosen, setChosen] = useState<string[]>(source.teams);
  const toggle = (t: string) => setChosen(c => (c.includes(t) ? c.filter(x => x !== t) : [...c, t]));
  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4" role="dialog" aria-modal="true" aria-label={`Who can see ${source.title}`}>
      <div className="absolute inset-0 bg-ink-900/50" onClick={onClose} />
      <div className="relative flex w-full max-w-[480px] flex-col gap-4 rounded-xl bg-card p-6 shadow-pop">
        <div className="flex items-start justify-between gap-3">
          <div><h2 className="text-h3 font-semibold">Who can see “{source.title}”</h2><p className="text-small text-ink-500">Applies to the source and everything built on it.</p></div>
          <button type="button" aria-label="Close" onClick={onClose} className="p-1 text-ink-500 cursor-pointer"><X className="size-[18px]" /></button>
        </div>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-caption font-medium text-ink-700">Level: people need at least this clearance</legend>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {LEVELS.map(l => (
              <button key={l} type="button" aria-pressed={level === l} onClick={() => setLevel(l)}
                className={cn('rounded-lg border px-2 py-2 text-small font-medium cursor-pointer', level === l ? 'border-brand-600 bg-brand-50 text-brand-700' : 'border-line-strong hover:bg-subtle')}>
                {LEVEL_NAME[l]}
              </button>
            ))}
          </div>
        </fieldset>
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-caption font-medium text-ink-700">Teams: leave all unticked for everyone on the project</legend>
          {teams.length === 0 ? <p className="text-small text-ink-500">No teams yet. An admin can create them under People & groups.</p> : (
            <div className="flex flex-wrap gap-2">
              {teams.map(t => (
                <label key={t} className={cn('flex items-center gap-2 rounded-full border px-3 py-1.5 text-small cursor-pointer', chosen.includes(t) ? 'border-brand-600 bg-brand-50 text-brand-700' : 'border-line-strong')}>
                  <input type="checkbox" className="accent-brand-600" checked={chosen.includes(t)} onChange={() => toggle(t)} />{t}
                </label>
              ))}
            </div>
          )}
        </fieldset>
        {change.error && <Banner tone="conflict" icon={AlertTriangle}>{errText(change.error)}</Banner>}
        <div className="flex justify-end gap-2">
          <Button kind="ghost" onClick={onClose}>Cancel</Button>
          <Button kind="primary" disabled={change.isPending} onClick={() => change.mutate({ id: source.id, sensitivity: level, aclGroups: chosen }, { onSuccess: onClose })}>
            {change.isPending ? 'Saving…' : 'Save access'}
          </Button>
        </div>
      </div>
    </div>
  );
}
