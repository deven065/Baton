import { CheckCircle2, GitCommitHorizontal } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { SensitivityChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Field, Input } from '@/components/ui/form';
import { Avatar, Banner, Card, CardHeader, DemoNotice, PageHeader, Progress, StatusCircle, Table, Td, WorkflowStepper, type StepState } from '@/components/ui/misc';
import { useAcknowledged } from '@/data/api';
import { useClaimList, useGapList, useSummary } from '@/data/live';
import { useSession } from '@/lib/auth';
import { PEOPLE } from '@/data/mock';
import { cn } from '@/lib/utils';

export function Approval() {
  const { handover } = useSession();
  const { data: summary, isLoading } = useSummary(handover?.id);
  const { data: gaps = [] } = useGapList(handover?.id);
  const { data: claims = [] } = useClaimList(handover?.id);
  const conflicts = summary?.byStatus.CONFLICTING ?? 0;
  const inferredOpen = claims.filter(c => c.status === 'INFERRED' && (c.reviewState === 'PENDING' || c.reviewState === 'NEEDS_INFO')).length;
  const criticalOpen = claims.filter(c => c.critical && (c.reviewState === 'PENDING' || c.reviewState === 'NEEDS_INFO')).length;
  const pending = (summary?.byReviewState.PENDING ?? 0) + (summary?.byReviewState.NEEDS_INFO ?? 0);
  const openGaps = gaps.filter(g => g.status === 'OPEN' || g.status === 'UNANSWERED').length;

  const gates: { state: StepState; title: string; desc: string; to?: string; action?: string }[] = [
    { state: 'done', title: 'Every shown quote is verified by code', desc: 'Claims without a matching quote are never marked Verified' },
    { state: conflicts ? 'fail' : 'done', title: conflicts ? `${conflicts} conflicting claim${conflicts > 1 ? 's' : ''}` : 'No conflicts', desc: 'Sources that disagree must be resolved', to: '/app/conflicts', action: conflicts ? 'Resolve' : undefined },
    { state: criticalOpen ? 'fail' : 'done', title: criticalOpen ? `${criticalOpen} critical claim${criticalOpen > 1 ? 's' : ''} not reviewed` : 'Critical claims reviewed', desc: 'Risks, access to transfer and blockers need a decision', to: '/app/review', action: criticalOpen ? 'Review' : undefined },
    { state: inferredOpen ? 'warn' : 'done', title: inferredOpen ? `${inferredOpen} inferred claim${inferredOpen > 1 ? 's' : ''} need a decision` : 'Inferred claims decided', desc: 'Inferred claims are never shown as facts', to: '/app/review', action: inferredOpen ? 'Review' : undefined },
    { state: openGaps ? 'warn' : 'done', title: openGaps ? `${openGaps} exit question${openGaps > 1 ? 's' : ''} unanswered` : 'Exit interview complete', desc: `${handover?.departingName ?? 'The departing member'} answers these, or they are routed to you` },
    { state: pending ? 'warn' : 'done', title: pending ? `${pending} claims still to review` : 'All claims reviewed', desc: 'Accept, edit or reject each claim', to: '/app/review', action: pending ? 'Review' : undefined },
  ];
  const blockers = gates.filter(g => g.state === 'fail').length;
  const warnings = gates.filter(g => g.state === 'warn').length;

  return (
    <>
      <WorkflowStepper current={3} />
      <PageHeader title="Approval gate" sub="Approval will freeze a version with a SHA-256 hash. Recipients only ever see approved versions." />
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_420px]">
        <Card className="flex flex-col p-6">
          <CardHeader title="Checks" sub={isLoading ? 'Loading…' : `Computed live from ${summary?.total ?? 0} claims and ${gaps.length} exit questions`} right={<StatusBadge status={blockers ? 'conflicting' : warnings ? 'inferred' : 'verified'} label={blockers ? `${blockers} blocking` : warnings ? `${warnings} to finish` : 'Ready'} />} />
          <ul className="mt-2 divide-y divide-line">
            {gates.map(g => (
              <li key={g.title} className="flex items-center gap-3 py-3">
                <StatusCircle state={g.state} size={24} />
                <span className="flex-1"><span className="block text-small font-medium">{g.title}</span><span className="block text-caption text-ink-500">{g.desc}</span></span>
                {g.action && g.to && <Link to={g.to} className="text-small font-medium text-brand-600 hover:underline">{g.action}</Link>}
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap items-center justify-between gap-3 pt-[18px]">
            <p className="text-small text-ink-500">Approving and freezing versions arrives with milestone M5 — there is no approval endpoint yet.</p>
            <Button kind="primary" size="lg" disabled title="Approval API arrives in M5">Approve and freeze</Button>
          </div>
        </Card>
        <div className="flex flex-col gap-4">
          <DemoNotice what="Versions and diffs below are an M5 preview, not this handover’s data." />
          <Card className="flex flex-col gap-2.5">
            <h2 className="text-h3 font-semibold">Versions</h2>
            <Version v="v2" badge={<StatusBadge status="inferred" label="Draft" />} meta="Edited today" hash="not frozen yet" current />
            <Version v="v1" badge={<StatusBadge status="verified" label="Approved" />} meta="Approved earlier · shared with 1" hash="sha256 3f9a1c…7be0c21e" />
          </Card>
          <Card className="flex flex-col gap-2">
            <CardHeader title="Changes since v1" sub="Example diff" />
            <Diff sign="+" text="Launch date: 29 November 2026" cls="bg-verified-bg text-verified" />
            <Diff sign="−" text="Launch date: 15 November 2026" cls="bg-conflict-bg text-conflict" strike />
          </Card>
        </div>
      </div>
    </>
  );
}

function Version({ v, badge, meta, hash, current }: { v: string; badge: React.ReactNode; meta: string; hash: string; current?: boolean }) {
  return (
    <div className={cn('flex flex-col gap-1.5 rounded-xl border p-3.5', current ? 'border-brand-600 bg-brand-50 ring-1 ring-brand-600' : 'border-line')}>
      <span className="flex items-center gap-2"><span className="text-h3 font-semibold">{v}</span>{badge}</span>
      <span className="text-caption text-ink-500">{meta}</span>
      <span className="flex items-center gap-1.5 font-mono text-caption text-ink-700"><GitCommitHorizontal className="size-3 text-ink-400" aria-hidden />{hash}</span>
    </div>
  );
}

function Diff({ sign, text, cls, strike }: { sign: string; text: string; cls: string; strike?: boolean }) {
  return (
    <p className={cn('flex gap-2.5 rounded-md px-3 py-1.5 font-mono text-small', cls)}>
      <span aria-hidden>{sign}</span><span className={cn('text-ink-900', strike && 'line-through')}>{text}</span>
    </p>
  );
}

const RECIPIENTS = [
  { p: 'rohan', email: 'rohan.k@northfield.club', view: 'Engineer view', level: 'Confidential' as const, expires: '20 Dec 2026' },
  { p: 'sara', email: 'sara.d@northfield.club', view: 'Design view', level: 'Internal' as const, expires: '20 Dec 2026' },
];

export function Share() {
  const { data: ack } = useAcknowledged();
  const [revoked, setRevoked] = useState<Set<string>>(new Set());
  const acked = 1 + (ack ? 1 : 0);
  return (
    <>
      <WorkflowStepper current={4} />
      <PageHeader title="Share approved handover v1" sub="Each recipient gets their own role-filtered view. Shares can expire or be revoked; every view is logged." right={<Button kind="primary">Add recipient</Button>} />
      <DemoNotice what="Shares and acknowledgements arrive with milestone M5. This screen shows the planned design with sample people." />
      <Table label="Recipients" columns={[{ label: 'Recipient' }, { label: 'View' }, { label: 'Clearance' }, { label: 'Expires' }, { label: 'Status' }, { label: 'Actions', className: 'text-right' }]}>
        {RECIPIENTS.map(r => {
          const person = PEOPLE[r.p];
          const isRevoked = revoked.has(r.p);
          const status = isRevoked ? <StatusBadge status="stale" label="Revoked" /> : r.p === 'sara' || ack ? <StatusBadge status="verified" label={r.p === 'sara' ? 'Acknowledged 7 Nov' : 'Acknowledged just now'} /> : <StatusBadge status="single" label="Opened · not acknowledged" />;
          return (
            <tr key={r.p}>
              <Td><span className="flex items-center gap-2.5"><Avatar initials={person.initials} tone={person.tone} size="sm" /><span><span className="block font-medium text-ink-900">{person.name}</span><span className="block text-caption text-ink-500">{r.email}</span></span></span></Td>
              <Td>{r.view}</Td>
              <Td><SensitivityChip level={r.level} /></Td>
              <Td className="whitespace-nowrap">{r.expires}</Td>
              <Td>{status}</Td>
              <Td className="text-right whitespace-nowrap">
                {!isRevoked && <><Button>{r.p === 'rohan' && !ack ? 'Remind' : 'Copy link'}</Button> <Button kind="ghost" onClick={() => setRevoked(s => new Set(s).add(r.p))}>Revoke</Button></>}
              </Td>
            </tr>
          );
        })}
        <tr>
          <Td><span className="flex items-center gap-2.5"><Avatar initials="MJ" tone={5} size="sm" /><span><span className="block font-medium text-ink-900">Meera Joshi</span><span className="block text-caption text-ink-500">external · NGO partner</span></span></span></Td>
          <Td>Partner summary</Td><Td><SensitivityChip level="Public" /></Td><Td>1 Dec 2026</Td><Td><StatusBadge status="stale" label="Expired" /></Td>
          <Td className="text-right"><Button>Renew</Button></Td>
        </tr>
      </Table>
      <div className="grid items-start gap-6 xl:grid-cols-[1fr_400px]">
        <Card className="flex flex-col gap-3.5">
          <h2 className="text-h3 font-semibold">Add a recipient</h2>
          <form className="grid gap-3 sm:grid-cols-[1fr_160px_140px]" onSubmit={e => e.preventDefault()}>
            <Field label="Email">{id => <Input id={id} type="email" placeholder="new.member@northfield.club" />}</Field>
            <Field label="View">{id => <Input id={id} defaultValue="Engineer view" />}</Field>
            <Field label="Expires">{id => <Input id={id} defaultValue="20 Dec 2026" />}</Field>
            <div className="sm:col-span-3 flex justify-end"><Button kind="primary" type="submit">Share v1</Button></div>
          </form>
        </Card>
        <Card className="flex flex-col gap-2.5">
          <h2 className="text-h3 font-semibold">Did the handover land?</h2>
          <p className="flex items-end gap-2"><span className="text-stat font-semibold">{acked} / 2</span><span className="pb-1 text-small text-ink-500">recipients acknowledged</span></p>
          <Progress value={acked / 2} tone="bg-verified" label="Acknowledged" />
          {ack
            ? <Banner tone="verified" icon={CheckCircle2}>Rohan acknowledged the handover. Both recipients have confirmed it landed.</Banner>
            : <p className="text-small text-ink-500">Rohan opened the handover 2 hours ago and read Start here. You’ll be notified when he acknowledges.</p>}
        </Card>
      </div>
    </>
  );
}
