import { FolderOpen, MessagesSquare, AlertTriangle, FileText, GitBranch, Hash, KanbanSquare, MessageSquareQuote, NotebookPen, RefreshCw, ShieldCheck, Upload, X, type LucideIcon } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { Chip, SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Field, Input, Segmented } from '@/components/ui/form';
import { Banner, Card, CardHeader, IconTile, PageHeader, Progress, QueryState, WorkflowStepper } from '@/components/ui/misc';
import { SOURCE_TYPE_LABEL, fmtDateTime, sens, useCreateSource, useSourceList, useSyncSource, type SensitivityDto, type SourceDto, type SourceTypeDto, useIntegrations, type IntegrationKind,
} from '@/data/live';
import type { Status } from '@/data/types';
import { useSession } from '@/lib/auth';
import { ApiError } from '@/lib/http';

const KIND_ICON: Record<SourceTypeDto, LucideIcon> = {
  GITHUB: GitBranch, SLACK: MessagesSquare, GOOGLE_DRIVE: FolderOpen, TASK_CSV: KanbanSquare, DOCUMENT: FileText, MEETING_NOTES: NotebookPen, CHAT_EXPORT: Hash, TESTIMONY: MessageSquareQuote,
};
const STATE: Record<SourceDto['status'], { s: Status; l: string }> = {
  PENDING: { s: 'stale', l: 'Queued' }, SYNCING: { s: 'single', l: 'Syncing…' }, READY: { s: 'verified', l: 'Ready' }, FAILED: { s: 'conflicting', l: 'Failed' },
};

function SourceRow({ s, onSync, syncing }: { s: SourceDto; onSync?: () => void; syncing?: boolean }) {
  const st = STATE[s.status];
  return (
    <Card as="article" className="flex flex-col gap-3 p-4">
      <div className="flex items-center gap-3.5">
        <IconTile icon={KIND_ICON[s.type]} size={40} className="text-ink-900" />
        <div className="min-w-0 flex-1">
          <h3 className="font-medium">{s.title}</h3>
          <p className="text-caption text-ink-500">
            {SOURCE_TYPE_LABEL[s.type]}
            {typeof s.config.filename === 'string' && ` · ${s.config.filename}`}
            {typeof s.config.repo === 'string' && ` · ${s.config.repo}`}
            {` · ${s.documentCount} document${s.documentCount === 1 ? '' : 's'} · ${s.chunkCount} chunks`}
          </p>
          {(s.status === 'SYNCING' || s.status === 'PENDING') && <Progress value={s.status === 'SYNCING' ? 0.5 : 0.1} className="mt-1.5 max-w-[260px]" label="Ingestion progress" />}
          {s.lastError && <p className="mt-1 text-caption text-conflict">{s.lastError}</p>}
        </div>
        <div className="flex flex-col items-end gap-1 text-right">
          <StatusBadge status={st.s} label={st.l} />
          <span className="text-caption text-ink-500">{s.redactionCount} redacted{s.lastSyncedAt ? ` · ${fmtDateTime(s.lastSyncedAt)}` : ''}</span>
        </div>
        {(s.type === 'GITHUB' || s.type === 'SLACK' || s.type === 'GOOGLE_DRIVE') && onSync && (
          <button type="button" aria-label={`Re-sync ${s.title}`} disabled={syncing} onClick={onSync} className="rounded p-1 text-ink-400 hover:text-ink-900 cursor-pointer disabled:opacity-40"><RefreshCw className="size-[18px]" /></button>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2 sm:pl-[54px]">
        <span className="text-caption text-ink-400">Sensitivity</span>
        <SensitivityChip level={sens(s.sensitivity)} />
        <span className="mx-1 h-4 w-px bg-line" aria-hidden />
        <span className="text-caption text-ink-400">Access groups</span>
        {s.aclGroups.length ? s.aclGroups.map(g => <Chip key={g}>{g}</Chip>) : <span className="text-caption text-ink-500">everyone in the project</span>}
      </div>
    </Card>
  );
}

export default function Sources() {
  const { project, handover } = useSession();
  const list = useSourceList(project?.id);
  const sync = useSyncSource(project?.id);
  const [modal, setModal] = useState(false);
  const sources = list.data ?? [];
  const chunks = sources.reduce((n, s) => n + s.chunkCount, 0);
  const redactions = sources.reduce((n, s) => n + s.redactionCount, 0);
  const busy = sources.filter(s => s.status === 'PENDING' || s.status === 'SYNCING').length;
  const failed = sources.filter(s => s.status === 'FAILED').length;
  const ready = sources.filter(s => s.status === 'READY').length;

  return (
    <>
      <WorkflowStepper current={0} />
      <PageHeader
        title="Connect authorised sources"
        sub="Add what the team already has. Each source needs an authorisation confirmation, a sensitivity level and access groups."
        right={<Button kind="primary" onClick={() => setModal(true)}>Add source</Button>}
      />
      <div className="grid gap-6 xl:grid-cols-[1fr_380px]">
        <div className="flex flex-col gap-3">
          <QueryState loading={list.isLoading} error={list.error} />
          {sources.map(s => <SourceRow key={s.id} s={s} onSync={() => sync.mutate(s.id)} syncing={sync.isPending} />)}
          <button type="button" onClick={() => setModal(true)} className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-line-strong bg-card p-7 text-center hover:bg-canvas cursor-pointer">
            <IconTile icon={Upload} size={40} className="bg-brand-50 text-brand-700" />
            <span className="font-medium">Add an export file</span>
            <span className="max-w-[520px] text-caption text-ink-500">Markdown, text, PDF, Word, task CSV (Jira, Trello, GitHub Projects), Slack JSON, or a GitHub repository</span>
          </button>
        </div>
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-1.5">
            <CardHeader title="Ingestion" sub={busy ? 'Updating every 2 seconds' : 'All sources processed'} right={<StatusBadge status={busy ? 'single' : failed ? 'conflicting' : 'verified'} label={busy ? `${busy} running` : failed ? `${failed} failed` : 'Idle'} />} />
            <p className="flex items-end gap-2 pt-1.5"><span className="text-stat font-semibold">{ready}/{sources.length}</span><span className="pb-1 text-small text-ink-500">sources ready</span></p>
            <Progress value={sources.length ? ready / sources.length : 0} label="Sources ready" />
            <dl className="mt-2 grid grid-cols-2 gap-2">
              <div className="rounded-lg bg-canvas px-3 py-2"><dt className="text-caption text-ink-500">Chunks indexed</dt><dd className="text-h3 font-semibold">{chunks.toLocaleString()}</dd></div>
              <div className="rounded-lg bg-canvas px-3 py-2"><dt className="text-caption text-ink-500">Items redacted</dt><dd className="text-h3 font-semibold">{redactions}</dd></div>
            </dl>
          </Card>
          <Banner tone="verified" icon={ShieldCheck}>Secrets and personal data are replaced with tokens such as [EMAIL_1] before any AI call. Originals are stored encrypted (AES-GCM).</Banner>
          <Card className="flex flex-col gap-2.5">
            <h2 className="text-h3 font-semibold">{busy ? 'Waiting for ingestion' : 'Ready to reconstruct'}</h2>
            <p className="text-small text-ink-500">{busy ? 'Reconstruction can start once every source has finished syncing.' : handover ? `Rebuild ${handover.departingName}’s handover from these sources.` : 'Create a handover first (API: POST /api/projects/{id}/handovers).'}</p>
            <Link to="/app/reconstruct"><Button kind="primary" className="w-full" disabled={!!busy || !handover}>Go to reconstruction</Button></Link>
          </Card>
        </div>
      </div>
      {modal && <AddSourceModal onClose={() => setModal(false)} />}
    </>
  );
}

const TYPES: { id: SourceTypeDto; label: string; accept?: string }[] = [
  { id: 'CHAT_EXPORT', label: 'Chat export (Slack JSON)', accept: '.json,.zip' },
  { id: 'MEETING_NOTES', label: 'Meeting notes', accept: '.md,.txt,.pdf,.docx' },
  { id: 'DOCUMENT', label: 'Document', accept: '.md,.txt,.pdf,.docx' },
  { id: 'TASK_CSV', label: 'Task board CSV', accept: '.csv' },
  { id: 'GITHUB', label: 'GitHub repository (live)' },
  { id: 'SLACK', label: 'Slack channel (live)' },
  { id: 'GOOGLE_DRIVE', label: 'Google Drive folder (live)' },
];
const LIVE: Partial<Record<SourceTypeDto, { kind: IntegrationKind; field: string; placeholder: string; help: string; needs: boolean }>> = {
  GITHUB: { kind: 'GITHUB', field: 'Repository (owner/name)', placeholder: 'northfield-club/annapurna', help: 'Public repositories work without a connection; private ones need a GitHub token.', needs: false },
  SLACK: { kind: 'SLACK', field: 'Channel', placeholder: '#dev', help: 'The last 90 days of the channel. Add your Slack app to the channel first (/invite @app).', needs: true },
  GOOGLE_DRIVE: { kind: 'GOOGLE_DRIVE', field: 'Folder link', placeholder: 'https://drive.google.com/drive/folders/…', help: 'Google Docs and text files in the folder. Share the folder with your service account as Viewer.', needs: true },
};
const LEVELS = ['Public', 'Internal', 'Confidential', 'Restricted'] as const;

function AddSourceModal({ onClose }: { onClose: () => void }) {
  const { project, user } = useSession();
  const create = useCreateSource(project?.id);
  const [type, setType] = useState<SourceTypeDto>('CHAT_EXPORT');
  const [title, setTitle] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [repo, setRepo] = useState('');
  const { data: integrations = [] } = useIntegrations();
  const [level, setLevel] = useState<(typeof LEVELS)[number]>('Internal');
  const [groups, setGroups] = useState<string[]>([]);
  const [groupDraft, setGroupDraft] = useState('');
  const [authorised, setAuthorised] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const t = TYPES.find(x => x.id === type)!;
  const live = LIVE[type];
  const connected = !!live && integrations.some(i => i.kind === live.kind && i.connected);
  const blocked = !!live && live.needs && !connected;
  const clearanceIdx = LEVELS.indexOf(user.clearance);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    ref.current?.focus();
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const err = create.error instanceof ApiError ? create.error : null;
  const fieldErrors = (err?.details && typeof err.details === 'object' ? err.details : {}) as Record<string, string>;
  const ok = authorised && title.trim() && !blocked && (live ? repo.trim() : file);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!ok) return;
    create.mutate(
      { type, title: title.trim(), sensitivity: level.toUpperCase() as SensitivityDto, aclGroups: groups, file: file ?? undefined, repo: type === 'GITHUB' ? repo.trim() || undefined : undefined,
        channel: type === 'SLACK' ? repo.trim() : undefined, folder: type === 'GOOGLE_DRIVE' ? repo.trim() : undefined },
      { onSuccess: onClose },
    );
  };
  const addGroup = () => {
    const g = groupDraft.trim().toLowerCase();
    if (g && !groups.includes(g)) setGroups([...groups, g]);
    setGroupDraft('');
  };

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      <div className="absolute inset-0 bg-ink-900/50" onClick={onClose} />
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby="add-source-title" className="relative w-full max-w-[600px] overflow-hidden rounded-xl bg-card shadow-pop outline-none">
        <form onSubmit={submit}>
          <div className="flex items-center justify-between border-b border-line px-6 py-4">
            <div className="flex items-center gap-3">
              <IconTile icon={KIND_ICON[type]} className="text-ink-900" />
              <h2 id="add-source-title" className="text-h3 font-semibold">Add a source</h2>
            </div>
            <button type="button" aria-label="Close" onClick={onClose} className="p-1 text-ink-500 cursor-pointer"><X className="size-[18px]" /></button>
          </div>
          <div className="flex max-h-[70dvh] flex-col gap-[18px] overflow-y-auto p-6">
            <Field label="Type">
              {id => (
                <select id={id} value={type} onChange={e => { setType(e.target.value as SourceTypeDto); setFile(null); setRepo(''); }} className="w-full rounded-lg border border-line-strong bg-card px-3 py-2.5 text-body outline-none focus:border-brand-600">
                  {TYPES.map(x => <option key={x.id} value={x.id}>{x.label}</option>)}
                </select>
              )}
            </Field>
            <Field label="Title" hint={fieldErrors.title && <p className="text-caption text-conflict">{fieldErrors.title}</p>}>
              {id => <Input id={id} value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. Slack #dev" maxLength={200} invalid={!!fieldErrors.title} />}
            </Field>
            {live ? (
              <>
                {blocked && <Banner tone="inferred" icon={AlertTriangle}>{SOURCE_TYPE_LABEL[type]} isn’t connected for this workspace yet. An admin connects it under <strong>Organisation → Connections</strong>.</Banner>}
                <Field label={live.field} hint={<p className="text-caption text-ink-500">{live.help}{type === 'GITHUB' && (connected ? ' A token is connected.' : '')}</p>}>{id => <Input id={id} value={repo} onChange={e => setRepo(e.target.value)} placeholder={live.placeholder} disabled={blocked} autoCapitalize="none" spellCheck={false} />}</Field>
              </>
            ) : (
              <Field label="File" hint={<p className={fieldErrors.file ? 'text-caption text-conflict' : 'text-caption text-ink-500'}>{fieldErrors.file ?? 'Up to 25 MB.'}</p>}>
                {id => <input id={id} type="file" accept={t.accept} onChange={e => { const f = e.target.files?.[0] ?? null; setFile(f); if (f && !title) setTitle(f.name.replace(/\.[^.]+$/, '')); }} className="text-small file:mr-3 file:rounded-lg file:border file:border-line-strong file:bg-card file:px-3 file:py-2 file:text-small file:font-medium" />}
              </Field>
            )}
            <div className="flex flex-col gap-1.5">
              <span className="text-caption font-medium text-ink-700">Sensitivity</span>
              <Segmented label="Sensitivity" options={LEVELS.slice(0, clearanceIdx + 1)} value={level} onChange={setLevel} />
              <span className="text-caption text-ink-500">You can’t classify a source above your own clearance ({user.clearance}).</span>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="groups" className="text-caption font-medium text-ink-700">Access groups <span className="font-normal text-ink-500">(empty = everyone in the project)</span></label>
              <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-line-strong p-2">
                {groups.map(g => (
                  <button key={g} type="button" onClick={() => setGroups(groups.filter(x => x !== g))} aria-label={`Remove ${g}`} className="cursor-pointer"><Chip removable>{g}</Chip></button>
                ))}
                <input
                  id="groups" value={groupDraft} onChange={e => setGroupDraft(e.target.value)} placeholder="Add group…" list="known-groups"
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addGroup(); } }} onBlur={addGroup}
                  className="min-w-[120px] flex-1 bg-transparent text-small outline-none"
                />
                <datalist id="known-groups">{['engineering', 'design', 'operations'].map(g => <option key={g} value={g} />)}</datalist>
              </div>
              {fieldErrors.aclGroups && <p className="text-caption text-conflict">{fieldErrors.aclGroups}</p>}
            </div>
            <div className="rounded-xl border border-baton bg-paper p-3.5">
              <Checkbox
                checked={authorised}
                onChange={setAuthorised}
                label={<><span className="block font-medium text-ink-900">I confirm I’m authorised to share this source with Baton for this handover.</span><span className="mt-1 block text-caption text-ink-500">Baton redacts emails, phone numbers, API keys and passwords before any AI call. Only redacted text reaches the model.</span></>}
              />
            </div>
            {err && (!Object.keys(fieldErrors).length || err.code === 'PLAN_LIMIT') && <Banner tone="conflict" icon={AlertTriangle}>{err.message}</Banner>}
          </div>
          <div className="flex justify-end gap-2 border-t border-line bg-canvas px-6 py-4">
            <Button type="button" kind="ghost" onClick={onClose}>Cancel</Button>
            <Button type="submit" kind="primary" disabled={!ok || create.isPending}>{create.isPending ? 'Uploading…' : 'Connect source'}</Button>
          </div>
        </form>
      </div>
    </div>
  );
}
