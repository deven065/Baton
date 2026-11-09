import { AlertTriangle, CheckCircle2, Download, FileCheck2, GitCommitHorizontal, GitCompare, Lock, Users } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { Avatar, Banner, Card, CardHeader, PageHeader, Progress, QueryState, StatusCircle, Table, Td, WorkflowStepper } from '@/components/ui/misc';
import type { Status } from '@/data/types';
import {
  fmtDate, fmtDateTime, sens, typeLabel, useApprove, useDiff, useDrift, useGate, useLiving, useProject, useShareMutations, useShares, useVersions,
  type VerificationDto,
  type ShareRowDto,
} from '@/data/live';
import { useAuth, useSession } from '@/lib/auth';
import { ApiError, api, download } from '@/lib/http';
import { cn } from '@/lib/utils';

const FIX: Record<string, { to: string; action: string }> = {
  conflicts: { to: '/app/conflicts', action: 'Resolve' },
  critical: { to: '/app/review', action: 'Review' },
  inferred: { to: '/app/review', action: 'Review' },
  pending: { to: '/app/review', action: 'Review' },
  claims: { to: '/app/reconstruct', action: 'Reconstruct' },
};
const initials = (n: string) => n.split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();

export function Approval() {
  const { handover } = useSession();
  const { refresh } = useAuth();
  const gate = useGate(handover?.id);
  const versions = useVersions(handover?.id);
  const latest = versions.data?.[0];
  const diff = useDiff(handover?.id, latest?.versionNo);
  const approve = useApprove(handover?.id);
  const drift = useDrift(handover?.id);
  const { reopen } = useLiving(handover?.id);
  const checks = gate.data?.checks ?? [];
  const blockers = checks.filter(c => c.blocking && !c.ok).length;
  const warnings = checks.filter(c => !c.blocking && !c.ok).length;
  const status = gate.data?.status;
  const err = approve.error instanceof ApiError ? approve.error : null;

  if (!handover) {
    return (<><PageHeader title="Approval gate" /><p className="text-small text-ink-500">Start a handover first.</p></>);
  }
  return (
    <>
      <WorkflowStepper current={3} />
      <PageHeader title="Approval gate" sub="Approving freezes a version with a SHA-256 hash. Recipients only ever see approved versions." />
      <DriftBanner
        versionNo={drift.data?.versionNo ?? null}
        status={drift.data?.status}
        changes={drift.data?.changes.length ?? 0}
        answers={drift.data?.answersSince ?? 0}
        sources={drift.data?.sourcesUpdated ?? 0}
        busy={reopen.isPending}
        onReopen={() => reopen.mutate(undefined, { onSuccess: () => refresh() })}
      />
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_420px]">
        <Card className="flex flex-col p-6">
          <CardHeader
            title="Checks"
            sub={gate.isLoading ? 'Loading…' : 'Computed by the server from the handover as it is now'}
            right={<StatusBadge status={blockers ? 'conflicting' : warnings ? 'inferred' : 'verified'} label={blockers ? `${blockers} blocking` : warnings ? `${warnings} to finish` : 'Ready'} />}
          />
          <ul className="mt-2 divide-y divide-line">
            {checks.map(c => (
              <li key={c.key} className="flex items-center gap-3 py-3">
                <StatusCircle state={c.ok ? 'done' : c.blocking ? 'fail' : 'warn'} size={24} />
                <span className="flex-1"><span className="block text-small font-medium">{c.title}</span><span className="block text-caption text-ink-500">{c.detail}{!c.blocking && ' · not blocking'}</span></span>
                {!c.ok && FIX[c.key] && <Link to={FIX[c.key].to} className="text-small font-medium text-brand-600 hover:underline">{FIX[c.key].action}</Link>}
              </li>
            ))}
          </ul>
          <QueryState loading={gate.isLoading} error={gate.error} />
          {err && <Banner tone="conflict" icon={AlertTriangle} className="mt-3">{Array.isArray(err.details) ? (err.details as string[]).join(' · ') : err.message}</Banner>}
          {approve.isSuccess && <Banner tone="verified" icon={CheckCircle2} className="mt-3">Version {approve.data.versionNo} frozen with {approve.data.checklistItems} checklist items. <Link to="/app/share" className="font-medium underline">Share it</Link></Banner>}
          <div className="flex flex-wrap items-center justify-between gap-3 pt-[18px]">
            <p className="text-small text-ink-500">
              {status === 'APPROVED' || status === 'SHARED' ? `Version ${gate.data?.currentVersionNo} is approved. Editing a claim reopens review.` : status === 'IN_REVIEW' ? (gate.data?.canApprove ? 'Ready to freeze. Pending claims are included as they are.' : 'Fix the blocking checks to approve.') : 'Reconstruct the handover before approving.'}
            </p>
            <Button kind="primary" size="lg" disabled={!gate.data?.canApprove || approve.isPending} onClick={() => approve.mutate(undefined, { onSuccess: () => refresh() })}>
              <Lock className="size-4" aria-hidden />{approve.isPending ? 'Freezing…' : 'Approve and freeze'}
            </Button>
          </div>
        </Card>
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-2.5">
            <h2 className="text-h3 font-semibold">Versions</h2>
            {versions.data?.map((v, i) => (
              <div key={v.versionNo} className={cn('flex flex-col gap-1.5 rounded-xl border p-3.5', i === 0 ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-line')}>
                <span className="flex items-center gap-2"><span className="text-h3 font-semibold">v{v.versionNo}</span><StatusBadge status="verified" label="Approved" /></span>
                <span className="text-caption text-ink-500">{v.approvedByName} · {fmtDateTime(v.approvedAt)} · {v.claims} claims · {v.checklistItems} checklist items</span>
                <span className="flex items-center gap-1.5 font-mono text-caption text-ink-700" title={v.sha256}><GitCommitHorizontal className="size-3 text-ink-400" aria-hidden />sha256 {v.sha256.slice(0, 8)}…{v.sha256.slice(-8)}</span>
              </div>
            ))}
            {versions.data?.length === 0 && <p className="text-small text-ink-500">Nothing approved yet.</p>}
            {!!versions.data?.length && <ExportTools handoverId={handover.id} versions={versions.data.map(v => v.versionNo)} />}
          </Card>
          {latest && (
            <Card className="flex flex-col gap-2">
              <CardHeader title={`Changes since v${latest.versionNo}`} sub="The handover as it is now, compared with the last frozen version" />
              {diff.data?.map(c => (
                <p key={c.claimId + c.kind} className={cn('flex gap-2.5 rounded-md px-3 py-1.5 text-small', c.kind === 'ADDED' ? 'bg-verified-bg' : c.kind === 'REMOVED' ? 'bg-conflict-bg' : 'bg-inferred-bg')}>
                  <span aria-hidden className="font-mono">{c.kind === 'ADDED' ? '+' : c.kind === 'REMOVED' ? '−' : '~'}</span>
                  <span className="flex flex-col"><span className="text-caption text-ink-500">{typeLabel(c.type)}</span><span className={cn('text-ink-900', c.kind === 'REMOVED' && 'line-through')}>{c.after ?? c.before}</span></span>
                </p>
              ))}
              {diff.data?.length === 0 && <p className="text-small text-ink-500">No changes.</p>}
            </Card>
          )}
        </div>
      </div>
    </>
  );
}

const STATE: Record<ShareRowDto['state'], { s: Status; l: (r: ShareRowDto) => string }> = {
  SENT: { s: 'single', l: () => 'Not opened yet' },
  OPENED: { s: 'inferred', l: r => `Opened ${r.lastViewedAt ? fmtDate(r.lastViewedAt) : ''} · not acknowledged` },
  ACKNOWLEDGED: { s: 'verified', l: r => `Acknowledged ${r.acknowledgedAt ? fmtDate(r.acknowledgedAt) : ''}` },
  EXPIRED: { s: 'stale', l: () => 'Expired' },
  REVOKED: { s: 'stale', l: () => 'Revoked' },
};
const inDays = (d: number) => new Date(Date.now() + d * 86_400_000).toISOString().slice(0, 10);

export function Share() {
  const { handover, project, user } = useSession();
  const shares = useShares(handover?.id);
  const { data: detail } = useProject(project?.id);
  const { create, revoke } = useShareMutations(handover?.id);
  const drift = useDrift(handover?.id);
  const { move } = useLiving(handover?.id);
  const [recipient, setRecipient] = useState('');
  const [label, setLabel] = useState('Successor');
  const [expires, setExpires] = useState(inDays(30));
  const approved = handover?.currentVersionNo != null && (handover.status === 'APPROVED' || handover.status === 'SHARED');
  const rows = shares.data ?? [];
  const active = rows.filter(r => r.state !== 'REVOKED' && r.state !== 'EXPIRED');
  const acked = active.filter(r => r.state === 'ACKNOWLEDGED').length;
  const candidates = (detail?.members ?? []).filter(m => m.userId !== user.id && m.userId !== handover?.departingUserId && !active.some(r => r.recipientId === m.userId && r.versionNo === handover?.currentVersionNo));
  const err = create.error instanceof ApiError ? create.error : null;
  const details = (err?.details ?? null) as Record<string, string> | null;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ recipientId: recipient, roleLabel: label.trim(), expiresAt: new Date(expires + 'T23:59:59').toISOString() },
      { onSuccess: () => setRecipient('') });
  };

  return (
    <>
      <WorkflowStepper current={4} />
      <PageHeader title={approved ? `Share approved handover v${handover?.currentVersionNo}` : 'Share the handover'} sub="Each recipient sees the frozen version filtered by their own clearance and groups. Shares expire or can be revoked; every view is logged." />
      {approved && (drift.data?.recipientsOnOlderVersion ?? 0) > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-brand-600/40 bg-brand-50 p-4">
          <Users className="size-5 text-brand-700" aria-hidden />
          <span className="flex-1 text-small text-ink-900">{drift.data!.recipientsOnOlderVersion} recipient{drift.data!.recipientsOnOlderVersion > 1 ? 's are' : ' is'} still on an older version. Moving them keeps their view name and expiry, and shows them what changed.</span>
          {move.isSuccess ? <span className="text-small font-medium text-verified">Moved {move.data.moved}</span>
            : <Button kind="primary" disabled={move.isPending} onClick={() => move.mutate()}>{move.isPending ? 'Moving…' : `Move them to v${handover?.currentVersionNo}`}</Button>}
        </div>
      )}
      {!approved && <Banner tone="inferred" icon={Lock}>Approve the handover first. <Link to="/app/approval" className="font-medium underline">Go to the approval gate</Link></Banner>}
      <Table label="Recipients" columns={[{ label: 'Recipient' }, { label: 'View' }, { label: 'Clearance' }, { label: 'Version' }, { label: 'Expires' }, { label: 'Status' }, { label: 'Actions', className: 'text-right' }]}>
        {rows.map(r => (
          <tr key={r.id}>
            <Td><span className="flex items-center gap-2.5"><Avatar initials={initials(r.recipientName)} tone={3} size="sm" /><span><span className="block font-medium text-ink-900">{r.recipientName}</span><span className="block text-caption text-ink-500">{r.recipientEmail}</span></span></span></Td>
            <Td>{r.roleLabel}</Td>
            <Td><SensitivityChip level={sens(r.clearance)} /></Td>
            <Td>v{r.versionNo}</Td>
            <Td className="whitespace-nowrap">{fmtDate(r.expiresAt)}</Td>
            <Td><StatusBadge status={STATE[r.state].s} label={STATE[r.state].l(r)} /></Td>
            <Td className="text-right whitespace-nowrap">{r.state !== 'REVOKED' && r.state !== 'EXPIRED' && <Button kind="ghost" disabled={revoke.isPending} onClick={() => revoke.mutate(r.id)}>Revoke</Button>}</Td>
          </tr>
        ))}
      </Table>
      <QueryState loading={shares.isLoading} error={shares.error} empty={rows.length === 0} emptyText="Not shared with anyone yet." />
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_400px]">
        <Card className="flex flex-col gap-3.5">
          <h2 className="text-h3 font-semibold">Add a recipient</h2>
          <form className="grid gap-3 sm:grid-cols-[1fr_160px_160px]" onSubmit={submit}>
            <Field label="Person" hint={candidates.length === 0 ? <p className="text-caption text-ink-500">Add people to the project team to share with them.</p> : undefined}>
              {id => (
                <select id={id} value={recipient} onChange={e => setRecipient(e.target.value)} disabled={!approved} className="w-full rounded-lg border border-line-strong bg-card px-3 py-2.5 text-body">
                  <option value="">Choose a team member…</option>
                  {candidates.map(m => <option key={m.userId} value={m.userId}>{m.displayName} · {sens(m.clearance)}</option>)}
                </select>
              )}
            </Field>
            <Field label="View name">{id => <Input id={id} value={label} onChange={e => setLabel(e.target.value)} maxLength={60} disabled={!approved} />}</Field>
            <Field label="Expires">{id => <Input id={id} type="date" value={expires} min={inDays(1)} max={inDays(90)} onChange={e => setExpires(e.target.value)} disabled={!approved} />}</Field>
            {err && <div className="sm:col-span-3"><Banner tone="conflict" icon={AlertTriangle}>{details && !Array.isArray(details) ? Object.values(details).join(' ') : err.message}</Banner></div>}
            <div className="sm:col-span-3 flex justify-end"><Button kind="primary" type="submit" disabled={!approved || !recipient || create.isPending}>{create.isPending ? 'Sharing…' : `Share v${handover?.currentVersionNo ?? ''}`}</Button></div>
          </form>
        </Card>
        <Card className="flex flex-col gap-2.5">
          <h2 className="text-h3 font-semibold">Did the handover land?</h2>
          <p className="flex items-end gap-2"><span className="text-stat font-semibold">{acked} / {active.length}</span><span className="pb-1 text-small text-ink-500">recipients acknowledged</span></p>
          <Progress value={active.length ? acked / active.length : 0} tone="bg-verified" label="Acknowledged" />
          {active.length > 0 && acked === active.length
            ? <Banner tone="verified" icon={CheckCircle2}>Every recipient has confirmed the handover landed.</Banner>
            : <p className="text-small text-ink-500">{active.length ? 'Recipients acknowledge once they have read Start here and their Day 1 list.' : 'Share the approved version to start.'}</p>}
        </Card>
      </div>
    </>
  );
}

/** Shown on the approval gate when the handover has moved on since its approved version. */
function DriftBanner({ versionNo, status, changes, answers, sources, busy, onReopen }: {
  versionNo: number | null; status?: string; changes: number; answers: number; sources: number; busy: boolean; onReopen: () => void;
}) {
  if (!versionNo) return null;
  if (status === 'IN_REVIEW') {
    return <Banner icon={GitCompare}>Working on version {versionNo + 1}. Recipients keep version {versionNo} until you approve this one and move them.</Banner>;
  }
  if (changes + answers + sources === 0) return null;
  const parts = [changes && `${changes} claim change${changes > 1 ? 's' : ''}`, answers && `${answers} new exit answer${answers > 1 ? 's' : ''}`,
    sources && `${sources} source${sources > 1 ? 's' : ''} re-synced`].filter(Boolean).join(', ');
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border border-inferred/40 bg-inferred-bg p-4">
      <GitCompare className="size-5 text-inferred" aria-hidden />
      <span className="flex-1 text-small text-ink-900"><strong>Version {versionNo} is out of date:</strong> {parts} since it was approved.</span>
      <Button kind="primary" disabled={busy} onClick={onReopen}>{busy ? 'Opening…' : `Start version ${versionNo + 1}`}</Button>
    </div>
  );
}

/** Download a signed export of a version, or check one. */
function ExportTools({ handoverId, versions }: { handoverId: string; versions: number[] }) {
  const [busy, setBusy] = useState<number | 'verify' | null>(null);
  const [error, setError] = useState('');
  const [result, setResult] = useState<VerificationDto | null>(null);
  const exportVersion = async (n: number) => {
    setBusy(n); setError('');
    try { await download(`/api/handovers/${handoverId}/versions/${n}/export`, `baton-v${n}.zip`); } catch (e) { setError((e as Error).message); } finally { setBusy(null); }
  };
  const verify = async (file: File) => {
    setBusy('verify'); setError(''); setResult(null);
    const form = new FormData();
    form.append('file', file);
    try { setResult(await api<VerificationDto>('/api/exports/verify', { method: 'POST', body: form })); } catch (e) { setError((e as Error).message); } finally { setBusy(null); }
  };
  return (
    <div className="flex flex-col gap-2 border-t border-line pt-3">
      <p className="overline text-ink-400">Signed export</p>
      <div className="flex flex-wrap gap-2">
        {versions.map(n => <Button key={n} onClick={() => exportVersion(n)} disabled={busy !== null}><Download className="size-4" aria-hidden />{busy === n ? 'Preparing…' : `v${n} (.zip)`}</Button>)}
      </div>
      <label className="flex cursor-pointer items-center gap-2 text-small font-medium text-brand-600 hover:underline">
        <FileCheck2 className="size-4" aria-hidden />{busy === 'verify' ? 'Checking…' : 'Verify an export…'}
        <input type="file" accept=".zip,application/zip" className="sr-only" onChange={e => { const f = e.target.files?.[0]; if (f) verify(f); e.target.value = ''; }} />
      </label>
      {error && <p className="text-caption text-conflict">{error}</p>}
      {result && (result.valid
        ? <Banner tone="verified" icon={CheckCircle2}>Genuine: “{result.title}” v{result.versionNo}, unchanged since export and matching Baton’s record.</Banner>
        : <Banner tone="conflict" icon={AlertTriangle}>Not valid: {result.problems.join('; ')}</Banner>)}
      <p className="text-caption text-ink-500">The zip holds the frozen version exactly as hashed, its checklist, shares, audit trail and a printable report, with a signed manifest.</p>
    </div>
  );
}
