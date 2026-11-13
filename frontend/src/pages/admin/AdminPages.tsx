import { AlertTriangle, Check, Copy, Download, KeyRound, Layers, Plus, Search, Trash2, UserPlus, Users, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Chip, SensitivityChip } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox, Field, Input, Segmented } from '@/components/ui/form';
import { Avatar, Banner, Card, IconTile, PageHeader, QueryState, Table, Td } from '@/components/ui/misc';
import {
  fmtDate, fmtDateTime, sens, useAdminProjects, useAdminUsers, useAudit, useAuditActions, useCreateGroup, useCreateProject, useCreateUser,
  useDeleteGroup, useDirectory, useGroups, useResetPassword, useTeam, useTeamMutations, useUpdateUser,
  type AdminUserDto, type AuditFilter, type AuditRowDto, type SensitivityDto,
} from '@/data/live';
import { useSearchParams } from 'react-router-dom';
import { useSession } from '@/lib/auth';
import { NextStepGuide } from '@/components/layout/guide';
import { ApiError } from '@/lib/http';
import { cn } from '@/lib/utils';

// Shown in title case; sent to the API upper-case.
const LEVELS = ['Public', 'Internal', 'Confidential', 'Restricted'] as const;
type Level = (typeof LEVELS)[number];
const toDto = (l: Level) => l.toUpperCase() as SensitivityDto;
const fromDto = (d: SensitivityDto) => sens(d) as Level;
const initials = (n: string) => n.split(/\s+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
const toneOf = (id: string) => [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 0) % 6;
const errText = (e: unknown) => {
  if (!e) return null;
  const a = e instanceof ApiError ? e : null;
  const d = (a?.details ?? null) as Record<string, string> | null;
  return d && a?.code !== 'PLAN_LIMIT' ? Object.values(d).join(' ') : (e as Error).message;
};

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const k = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    ref.current?.focus();
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      <div className="absolute inset-0 bg-ink-900/50" onClick={onClose} />
      <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title} className="relative flex max-h-[90dvh] w-full max-w-[520px] flex-col overflow-hidden rounded-xl bg-card shadow-pop outline-none">
        <div className="flex items-center justify-between border-b border-line px-6 py-4">
          <h2 className="text-h3 font-semibold">{title}</h2>
          <button type="button" aria-label="Close" onClick={onClose} className="p-1 text-ink-500 cursor-pointer"><X className="size-[18px]" /></button>
        </div>
        <div className="overflow-y-auto p-6">{children}</div>
      </div>
    </div>
  );
}

/** A one-time secret: shown once, with copy, never shown again. */
function TempPassword({ who, value, onDone, emailed }: { who: string; value: string; onDone: () => void; emailed?: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex flex-col gap-4">
      {emailed && <Banner tone="verified" icon={Check}>We emailed {who} a link to choose their own password (valid for 3 days). The temporary password below also works.</Banner>}
      <Banner tone="inferred" icon={KeyRound}>{emailed ? 'If the email doesn’t arrive, give' : 'Give'} {who} this temporary password privately. It is shown only now; they must change it when they first sign in.</Banner>
      <div className="flex items-center gap-2 rounded-lg border border-line-strong bg-canvas px-3 py-2.5">
        <code className="flex-1 font-mono text-body tracking-wide">{value}</code>
        <Button onClick={async () => { try { await navigator.clipboard.writeText(value); setCopied(true); } catch { /* clipboard blocked */ } }}>
          <Copy className="size-3.5" aria-hidden />{copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
      <Button kind="primary" onClick={onDone}>Done</Button>
    </div>
  );
}

function GroupPicker({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const { data: groups = [] } = useGroups();
  if (!groups.length) return <p className="text-caption text-ink-500">No groups yet. Create them in the Groups tab.</p>;
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-2">
      {groups.map(g => (
        <Checkbox key={g.id} checked={value.includes(g.name)} onChange={on => onChange(on ? [...value, g.name] : value.filter(x => x !== g.name))} label={g.name} />
      ))}
    </div>
  );
}

function AddPersonModal({ onClose }: { onClose: () => void }) {
  const create = useCreateUser();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<'Member' | 'Admin'>('Member');
  const [level, setLevel] = useState<Level>('Internal');
  const [groups, setGroups] = useState<string[]>([]);
  if (create.data) return <Modal title="Person added" onClose={onClose}><TempPassword who={create.data.user.displayName} value={create.data.temporaryPassword} emailed={create.data.invitedByEmail} onDone={onClose} /></Modal>;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ displayName: name.trim(), email: email.trim(), orgRole: role === 'Admin' ? 'ADMIN' : 'MEMBER', clearance: toDto(level), groups });
  };
  return (
    <Modal title="Add a person" onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Name">{id => <Input id={id} autoFocus value={name} onChange={e => setName(e.target.value)} />}</Field>
        <Field label="Email">{id => <Input id={id} type="email" value={email} onChange={e => setEmail(e.target.value)} />}</Field>
        <div className="flex flex-col gap-1.5"><span className="text-caption font-medium text-ink-700">Role in the organisation</span><Segmented label="Role" options={['Member', 'Admin'] as const} value={role} onChange={setRole} /></div>
        <div className="flex flex-col gap-1.5">
          <span className="text-caption font-medium text-ink-700">Clearance</span>
          <Segmented label="Clearance" options={LEVELS} value={level} onChange={setLevel} />
          <span className="text-caption text-ink-500">The highest sensitivity this person can ever see. Groups narrow it further.</span>
        </div>
        <div className="flex flex-col gap-1.5"><span className="text-caption font-medium text-ink-700">Groups</span><GroupPicker value={groups} onChange={setGroups} /></div>
        {create.error && <Banner tone="conflict" icon={AlertTriangle}>{errText(create.error)}</Banner>}
        <Button kind="primary" type="submit" disabled={!name.trim() || !email.trim() || create.isPending}>{create.isPending ? 'Adding…' : 'Add person'}</Button>
      </form>
    </Modal>
  );
}

function EditPersonModal({ user, onClose }: { user: AdminUserDto; onClose: () => void }) {
  const update = useUpdateUser();
  const reset = useResetPassword();
  const { user: me } = useSession();
  const self = me.id === user.id;
  const [role, setRole] = useState<'Member' | 'Admin'>(user.orgRole === 'ADMIN' ? 'Admin' : 'Member');
  const [level, setLevel] = useState<Level>(fromDto(user.clearance));
  const [groups, setGroups] = useState<string[]>(user.groups);
  if (reset.data) return <Modal title="Password reset" onClose={onClose}><TempPassword who={user.displayName} value={reset.data.temporaryPassword} onDone={onClose} /></Modal>;
  const save = () => update.mutate({ id: user.id, orgRole: role === 'Admin' ? 'ADMIN' : 'MEMBER', clearance: toDto(level), groups }, { onSuccess: onClose });
  return (
    <Modal title={user.displayName} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <p className="text-small text-ink-500">{user.email} · added {fmtDate(user.createdAt)}</p>
        <div className="flex flex-col gap-1.5">
          <span className="text-caption font-medium text-ink-700">Role in the organisation</span>
          <Segmented label="Role" options={['Member', 'Admin'] as const} value={role} onChange={v => !self && setRole(v)} />
          {self && <span className="text-caption text-ink-500">You can’t change your own role.</span>}
        </div>
        <div className="flex flex-col gap-1.5"><span className="text-caption font-medium text-ink-700">Clearance</span><Segmented label="Clearance" options={LEVELS} value={level} onChange={setLevel} /></div>
        <div className="flex flex-col gap-1.5"><span className="text-caption font-medium text-ink-700">Groups</span><GroupPicker value={groups} onChange={setGroups} /></div>
        {(update.error || reset.error) && <Banner tone="conflict" icon={AlertTriangle}>{errText(update.error ?? reset.error)}</Banner>}
        <div className="flex flex-wrap gap-2">
          <Button kind="primary" onClick={save} disabled={update.isPending}>Save changes</Button>
          <Button onClick={() => reset.mutate(user.id)} disabled={reset.isPending}><KeyRound className="size-3.5" aria-hidden />Reset password</Button>
          {!self && (
            <Button kind="ghost" className={user.active ? 'text-conflict' : ''} onClick={() => update.mutate({ id: user.id, active: !user.active }, { onSuccess: onClose })}>
              {user.active ? 'Deactivate' : 'Reactivate'}
            </Button>
          )}
        </div>
      </div>
    </Modal>
  );
}

function GroupsTab() {
  const { data: groups = [], isLoading, error } = useGroups();
  const create = useCreateGroup();
  const del = useDeleteGroup();
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  return (
    <div className="grid items-start gap-6 xl:grid-cols-[1fr_360px]">
      <Card className="flex flex-col gap-2 p-0">
        <QueryState loading={isLoading} error={error} empty={!isLoading && groups.length === 0} emptyText="No groups yet. Groups decide which sources each person can see." />
        <ul className="divide-y divide-line">
          {groups.map(g => (
            <li key={g.id} className="flex items-center gap-3 px-5 py-3">
              <IconTile icon={Users} size={32} />
              <span className="flex-1"><span className="block text-small font-medium">{g.name}</span><span className="block text-caption text-ink-500">{g.members} member{g.members === 1 ? '' : 's'}{g.description ? ` · ${g.description}` : ''}</span></span>
              <Button kind="ghost" aria-label={`Delete ${g.name}`} onClick={() => del.mutate(g.id)} disabled={del.isPending}><Trash2 className="size-4" /></Button>
            </li>
          ))}
        </ul>
        {del.error && <Banner tone="conflict" icon={AlertTriangle} className="m-4">{errText(del.error)}</Banner>}
      </Card>
      <Card className="flex flex-col gap-3">
        <h2 className="text-h3 font-semibold">New group</h2>
        <form className="flex flex-col gap-3" onSubmit={e => { e.preventDefault(); create.mutate({ name, description: desc || undefined }, { onSuccess: () => { setName(''); setDesc(''); } }); }}>
          <Field label="Name">{id => <Input id={id} value={name} onChange={e => setName(e.target.value)} placeholder="engineering" />}</Field>
          <Field label="Description (optional)">{id => <Input id={id} value={desc} onChange={e => setDesc(e.target.value)} />}</Field>
          {create.error && <Banner tone="conflict" icon={AlertTriangle}>{errText(create.error)}</Banner>}
          <Button kind="primary" type="submit" disabled={!name.trim() || create.isPending}><Plus className="size-4" aria-hidden />Create group</Button>
        </form>
        <Banner icon={Layers}>Sensitivity order: Public &lt; Internal &lt; Confidential &lt; Restricted. A person sees a claim only if their clearance and groups cover all of its evidence.</Banner>
      </Card>
    </div>
  );
}

export function UsersGroups() {
  const [tab, setTab] = useState<'People' | 'Groups'>('People');
  // Admins who also lead a project see the guide on their dashboard instead.
  const pureAdmin = useSession().user.appRole === 'admin';
  const { data: users = [], isLoading, error } = useAdminUsers();
  const { data: groups = [] } = useGroups();
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<AdminUserDto | null>(null);
  const [params, setParams] = useSearchParams();
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const shown = q ? users.filter(u => u.displayName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q)) : users;
  return (
    <>
      <PageHeader title="People & groups" sub="Clearance and groups decide what each person can see. Every change is recorded in the audit log." right={tab === 'People' && <Button kind="primary" onClick={() => setAdding(true)}><UserPlus className="size-4" aria-hidden />Add person</Button>} />
      {pureAdmin && <NextStepGuide here={{ people: () => setAdding(true) }} />}
      <div role="tablist" className="flex gap-1 border-b border-line">
        {([['People', users.length], ['Groups', groups.length]] as const).map(([t, n]) => (
          <button key={t} role="tab" type="button" aria-selected={tab === t} onClick={() => setTab(t)} className={cn('-mb-px border-b-2 px-3 py-2.5 text-small font-medium cursor-pointer', tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}>
            {t} · {n}
          </button>
        ))}
      </div>
      {tab === 'Groups' ? <GroupsTab /> : (
        <>
          <QueryState loading={isLoading} error={error} />
          {q && <button type="button" onClick={() => setParams({})} className="flex items-center gap-1 self-start rounded-full bg-brand-50 px-3 py-1 text-small font-medium text-brand-700 cursor-pointer">Matching “{params.get('q')}” · {shown.length}<X className="size-3.5" aria-label="Clear search" /></button>}
          <Table label="People" columns={[{ label: 'Name' }, { label: 'Role' }, { label: 'Clearance' }, { label: 'Groups' }, { label: 'Status' }]}>
            {shown.map(u => (
              <tr key={u.id} className="cursor-pointer hover:bg-canvas" onClick={() => setEditing(u)}>
                <Td><span className="flex items-center gap-2.5"><Avatar initials={initials(u.displayName)} tone={toneOf(u.id)} size="sm" /><span><span className="block font-medium text-ink-900">{u.displayName}</span><span className="block text-caption text-ink-500">{u.email}</span></span></span></Td>
                <Td>{u.orgRole === 'ADMIN' ? 'Admin' : 'Member'}</Td>
                <Td><SensitivityChip level={sens(u.clearance)} /></Td>
                <Td><span className="flex flex-wrap gap-1">{u.groups.length ? u.groups.map(g => <Chip key={g}>{g}</Chip>) : <span className="text-caption text-ink-400">—</span>}</span></Td>
                <Td className="whitespace-nowrap">{!u.active ? <span className="text-ink-400">Deactivated</span> : u.mustChangePassword ? <span className="text-inferred">Awaiting first sign-in</span> : 'Active'}</Td>
              </tr>
            ))}
          </Table>
        </>
      )}
      {adding && <AddPersonModal onClose={() => setAdding(false)} />}
      {editing && <EditPersonModal user={editing} onClose={() => setEditing(null)} />}
    </>
  );
}

/** Add, re-role and remove project members. Used by admins (any project) and leads (their own). */
function TeamEditor({ projectId }: { projectId: string }) {
  const { data: team = [], isLoading, error } = useTeam(projectId);
  const { data: people = [] } = useDirectory();
  const { add, role, remove } = useTeamMutations(projectId);
  const [pick, setPick] = useState('');
  const available = people.filter(p => !team.some(t => t.userId === p.id));
  const err = add.error ?? role.error ?? remove.error;
  return (
    <div className="flex flex-col gap-3">
      <QueryState loading={isLoading} error={error} />
      <ul className="divide-y divide-line rounded-xl border border-line">
        {team.map(m => (
          <li key={m.userId} className="flex flex-wrap items-center gap-3 px-4 py-3">
            <Avatar initials={initials(m.displayName)} tone={toneOf(m.userId)} size="sm" />
            <span className="min-w-0 flex-1"><span className="block text-small font-medium">{m.displayName}</span><span className="block text-caption text-ink-500">{m.email}</span></span>
            <select aria-label={`Role of ${m.displayName}`} value={m.role} onChange={e => role.mutate({ userId: m.userId, role: e.target.value as 'PROJECT_LEAD' | 'MEMBER' })} className="rounded-lg border border-line-strong bg-card px-2 py-1.5 text-small">
              <option value="PROJECT_LEAD">Lead</option>
              <option value="MEMBER">Member</option>
            </select>
            <Button kind="ghost" aria-label={`Remove ${m.displayName}`} onClick={() => remove.mutate(m.userId)}><X className="size-4" /></Button>
          </li>
        ))}
      </ul>
      <form className="flex gap-2" onSubmit={e => { e.preventDefault(); if (pick) add.mutate({ userId: pick, role: 'MEMBER' }, { onSuccess: () => setPick('') }); }}>
        <select aria-label="Person to add" value={pick} onChange={e => setPick(e.target.value)} className="flex-1 rounded-lg border border-line-strong bg-card px-3 py-2 text-small">
          <option value="">Add someone to the team…</option>
          {available.map(p => <option key={p.id} value={p.id}>{p.displayName} · {p.email}</option>)}
        </select>
        <Button kind="primary" type="submit" disabled={!pick || add.isPending}>Add</Button>
      </form>
      {err && <Banner tone="conflict" icon={AlertTriangle}>{errText(err)}</Banner>}
    </div>
  );
}

function NewProjectModal({ onClose }: { onClose: () => void }) {
  const create = useCreateProject();
  const { data: people = [] } = useDirectory();
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [lead, setLead] = useState('');
  return (
    <Modal title="New project" onClose={onClose}>
      <form className="flex flex-col gap-4" onSubmit={e => { e.preventDefault(); create.mutate({ name: name.trim(), description: desc.trim() || undefined, leadUserId: lead }, { onSuccess: onClose }); }}>
        <Field label="Project name">{id => <Input id={id} autoFocus value={name} onChange={e => setName(e.target.value)} />}</Field>
        <Field label="Description (optional)">{id => <Input id={id} value={desc} onChange={e => setDesc(e.target.value)} />}</Field>
        <Field label="Project lead" hint={<p className="text-caption text-ink-500">Leads connect sources, run reconstruction, review and approve handovers.</p>}>
          {id => (
            <select id={id} value={lead} onChange={e => setLead(e.target.value)} className="w-full rounded-lg border border-line-strong bg-card px-3 py-2.5 text-body">
              <option value="">Choose a person…</option>
              {people.map(p => <option key={p.id} value={p.id}>{p.displayName} · {p.email}</option>)}
            </select>
          )}
        </Field>
        {create.error && <Banner tone="conflict" icon={AlertTriangle}>{errText(create.error)}</Banner>}
        <Button kind="primary" type="submit" disabled={!name.trim() || !lead || create.isPending}>Create project</Button>
      </form>
    </Modal>
  );
}

export function ProjectsPage() {
  const { data: projects = [], isLoading, error } = useAdminProjects();
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const openProject = useMemo(() => projects.find(p => p.id === open), [projects, open]);
  return (
    <>
      <PageHeader title="Projects" sub="Each project has a lead and a team. Handovers happen inside a project." right={<Button kind="primary" onClick={() => setCreating(true)}><Plus className="size-4" aria-hidden />New project</Button>} />
      {useSession().user.appRole === 'admin' && <NextStepGuide here={{ project: () => setCreating(true) }} />}
      <QueryState loading={isLoading} error={error} empty={!isLoading && projects.length === 0} emptyText="No projects yet. Create one and choose its lead." />
      {projects.length > 0 && (
        <Table label="Projects" columns={[{ label: 'Project' }, { label: 'Leads' }, { label: 'Team' }, { label: 'Handovers' }, { label: 'Created' }]}>
          {projects.map(p => (
            <tr key={p.id} className="cursor-pointer hover:bg-canvas" onClick={() => setOpen(p.id)}>
              <Td><span className="block font-medium text-ink-900">{p.name}</span>{p.description && <span className="block text-caption text-ink-500">{p.description}</span>}</Td>
              <Td>{p.leads.join(', ') || <span className="text-conflict">No lead</span>}</Td>
              <Td>{p.members}</Td>
              <Td>{p.handovers}</Td>
              <Td className="whitespace-nowrap">{fmtDate(p.createdAt)}</Td>
            </tr>
          ))}
        </Table>
      )}
      {creating && <NewProjectModal onClose={() => setCreating(false)} />}
      {openProject && <Modal title={`${openProject.name} · team`} onClose={() => setOpen(null)}><TeamEditor projectId={openProject.id} /></Modal>}
    </>
  );
}

/** A project lead's own team. */
export function TeamPage() {
  const { project } = useSession();
  if (!project) return <QueryState empty emptyText="You don’t lead a project." />;
  return (
    <>
      <PageHeader title="Team" sub={`Who is on ${project.name}. Members can be the person leaving or the people taking over.`} />
      <Card className="max-w-[720px]"><TeamEditor projectId={project.id} /></Card>
    </>
  );
}

/** Every event the server records, grouped the way an admin looks for them. */
const AUDIT_GROUPS: { label: string; actions: Record<string, string> }[] = [
  { label: 'Sign-in and accounts', actions: { LOGIN: 'Signed in', PASSWORD_CHANGED: 'Changed password', PASSWORD_RESET: 'Password reset by admin', PASSWORD_RESET_REQUESTED: 'Asked for a reset link' } },
  { label: 'People and groups', actions: { USER_CREATED: 'Person added', USER_UPDATED: 'Person changed', GROUP_CREATED: 'Group created', GROUP_DELETED: 'Group deleted' } },
  { label: 'Projects and teams', actions: { PROJECT_CREATED: 'Project created', MEMBER_ADDED: 'Team member added', MEMBER_REMOVED: 'Team member removed', MEMBER_ROLE_CHANGED: 'Team role changed' } },
  { label: 'Sources', actions: { SOURCE_CREATED: 'Source added', SOURCE_INGESTED: 'Source read', SOURCE_SYNC_REQUESTED: 'Source synced again' } },
  { label: 'Handover and review', actions: { HANDOVER_CREATED: 'Handover started', RECONSTRUCT_REQUESTED: 'Reconstruction started', RECONSTRUCTED: 'Reconstruction finished', CLAIM_ADDED: 'Claim added', CLAIM_REVIEWED: 'Claim reviewed', CONFLICT_RESOLVED: 'Conflict resolved', GAP_ANSWERED: 'Exit question answered', GAP_SKIPPED: 'Exit question skipped', PREVIEW_AS: 'Viewed as a recipient', HANDOVER_APPROVED: 'Version approved', HANDOVER_RENAMED: 'Handover renamed', HANDOVER_REOPENED: 'Handover reopened', VERSION_EXPORTED: 'Version exported' } },
  { label: 'Sharing and successors', actions: { SHARE_CREATED: 'Shared', SHARE_VIEWED: 'Share opened', SHARE_ACKNOWLEDGED: 'Share acknowledged', SHARE_ASKED: 'Question asked', SHARE_QUESTION_SENT: 'Question sent to the person leaving', SHARE_REVOKED: 'Share revoked', SHARES_MOVED: 'Recipients moved to a new version' } },
  { label: 'Workspace and settings', actions: { WORKSPACE_CREATED: 'Workspace created', AI_SETTINGS_CHANGED: 'AI provider changed', AI_SETTINGS_REMOVED: 'AI provider removed', INTEGRATION_CONNECTED: 'Tool connected', INTEGRATION_REMOVED: 'Tool disconnected', BILLING_CHECKOUT_STARTED: 'Upgrade started', PLAN_PAID: 'Plan paid', WORKSPACE_DISCONNECTED: 'Workspace disconnected' } },
];
const ACTION_LABEL: Record<string, string> = Object.assign({}, ...AUDIT_GROUPS.map(g => g.actions));
const actionLabel = (a: string) => ACTION_LABEL[a] ?? a.charAt(0) + a.slice(1).toLowerCase().replace(/_/g, ' ');

const NO_FILTER: AuditFilter = { actions: [], actor: '', from: '', to: '', q: '' };
const selectClass = 'w-full rounded-lg border border-line-strong bg-card px-3 py-2.5 text-body text-ink-900 outline-none transition-colors focus:border-brand-600 focus:ring-3 focus:ring-brand-100 cursor-pointer';

/** {"as":"…","claims":3} → "as: … · claims: 3" */
function readableDetails(details: string) {
  if (!details || details === '{}') return '—';
  try {
    const d = JSON.parse(details) as Record<string, unknown>;
    return Object.entries(d).map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`).join(' · ');
  } catch {
    return details;
  }
}

function downloadCsv(rows: AuditRowDto[]) {
  const cell = (v: string) => `"${v.replace(/"/g, '""')}"`;
  const lines = [['Time (UTC)', 'Who', 'Event', 'Code', 'Details'].map(cell).join(',')];
  for (const r of rows) lines.push([r.at, r.actorName ?? 'System', actionLabel(r.action), r.action, readableDetails(r.details)].map(cell).join(','));
  const url = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/csv;charset=utf-8' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: `audit-log-${new Date().toISOString().slice(0, 10)}.csv` });
  a.click();
  URL.revokeObjectURL(url);
}

export function AuditLog() {
  const [filter, setFilter] = useState<AuditFilter>(NO_FILTER);
  const [search, setSearch] = useState('');
  // Search as you type, without a request per keystroke.
  useEffect(() => {
    const t = setTimeout(() => setFilter(f => (f.q === search ? f : { ...f, q: search })), 300);
    return () => clearTimeout(t);
  }, [search]);
  const audit = useAudit(filter);
  const counts = useAuditActions().data ?? [];
  const people = useAdminUsers().data ?? [];
  const rows = useMemo(() => audit.data?.pages.flat() ?? [], [audit.data]);
  const countOf = (a: string) => counts.find(c => c.action === a)?.count ?? 0;
  const known = new Set(Object.keys(ACTION_LABEL));
  const others = counts.filter(c => !known.has(c.action));
  const active = filter.actions.length > 0 || !!filter.actor || !!filter.from || !!filter.to || !!filter.q;
  const set = (patch: Partial<AuditFilter>) => setFilter(f => ({ ...f, ...patch }));

  // The event picker holds one event ("LOGIN") or a whole category ("group:2").
  const eventValue = filter.actions.length === 0 ? ''
    : filter.actions.length === 1 ? filter.actions[0]
    : `group:${AUDIT_GROUPS.findIndex(g => Object.keys(g.actions).join() === filter.actions.join())}`;
  const pickEvent = (v: string) => set({ actions: !v ? [] : v.startsWith('group:') ? Object.keys(AUDIT_GROUPS[Number(v.slice(6))].actions) : [v] });

  return (
    <>
      <PageHeader title="Audit log" sub="Every sign-in, change, AI run and view, newest first. Stored in your company’s own database."
        right={<Button kind="secondary" onClick={() => downloadCsv(rows)} disabled={rows.length === 0}><Download className="size-3.5" aria-hidden />Download CSV</Button>} />

      <Card className="p-4">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-[1.4fr_1fr_0.8fr_0.8fr]">
          <Field label="Event">
            {id => (
              <select id={id} className={selectClass} value={eventValue} onChange={e => pickEvent(e.target.value)}>
                <option value="">All events</option>
                {AUDIT_GROUPS.map((g, i) => (
                  <optgroup key={g.label} label={g.label}>
                    <option value={`group:${i}`}>All {g.label.toLowerCase()}</option>
                    {Object.entries(g.actions).map(([a, label]) => (
                      <option key={a} value={a}>{label}{countOf(a) ? ` (${countOf(a)})` : ''}</option>
                    ))}
                  </optgroup>
                ))}
                {others.length > 0 && (
                  <optgroup label="Other">
                    {others.map(c => <option key={c.action} value={c.action}>{actionLabel(c.action)} ({c.count})</option>)}
                  </optgroup>
                )}
              </select>
            )}
          </Field>
          <Field label="Person">
            {id => (
              <select id={id} className={selectClass} value={filter.actor} onChange={e => set({ actor: e.target.value })}>
                <option value="">Everyone</option>
                {people.map(p => <option key={p.id} value={p.id}>{p.displayName}</option>)}
              </select>
            )}
          </Field>
          <Field label="From">{id => <Input id={id} type="date" value={filter.from} max={filter.to || undefined} onChange={e => set({ from: e.target.value })} />}</Field>
          <Field label="To">{id => <Input id={id} type="date" value={filter.to} min={filter.from || undefined} onChange={e => set({ to: e.target.value })} />}</Field>
        </div>
        <div className="mt-3 flex flex-wrap items-end gap-3">
          <div className="relative min-w-[240px] flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-ink-400" aria-hidden />
            <Input aria-label="Search the audit log" placeholder="Search names, events and details" value={search} onChange={e => setSearch(e.target.value)} className="pl-9" />
          </div>
          {active && (
            <Button kind="ghost" onClick={() => { setFilter(NO_FILTER); setSearch(''); }}><X className="size-3.5" aria-hidden />Clear filters</Button>
          )}
        </div>
      </Card>

      <p className="text-small text-ink-500" aria-live="polite">
        {audit.isLoading ? 'Loading…' : `${rows.length}${audit.hasNextPage ? '+' : ''} event${rows.length === 1 ? '' : 's'}${active ? ' match these filters' : ''}`}
      </p>
      <QueryState loading={audit.isLoading} error={audit.error} empty={!audit.isLoading && rows.length === 0}
        emptyText={active ? 'No events match these filters.' : 'No events yet.'} />
      {rows.length > 0 && (
        <Table label="Audit events" columns={[{ label: 'Time' }, { label: 'Who' }, { label: 'Event' }, { label: 'Details' }]}>
          {rows.map(r => (
            <tr key={r.id}>
              <Td className="whitespace-nowrap font-mono text-caption text-ink-500">{fmtDateTime(r.at)}</Td>
              <Td className="whitespace-nowrap text-ink-900">{r.actorName ?? 'System'}</Td>
              <Td className="whitespace-nowrap"><Chip>{actionLabel(r.action)}</Chip></Td>
              <Td className="max-w-[460px] truncate font-mono text-caption"><span title={r.details === '{}' ? undefined : r.details}>{readableDetails(r.details)}</span></Td>
            </tr>
          ))}
        </Table>
      )}
      {audit.hasNextPage && (
        <div className="flex justify-center">
          <Button kind="secondary" onClick={() => audit.fetchNextPage()} disabled={audit.isFetchingNextPage}>
            {audit.isFetchingNextPage ? 'Loading…' : 'Load older events'}
          </Button>
        </div>
      )}
    </>
  );
}

