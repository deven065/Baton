import { AlertTriangle, Info, KeyRound, MessageCircle, Plug, type LucideIcon } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { StartHandoverModal } from './StartHandover';
import { StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Avatar, Card, CardHeader, PageHeader, QueryState, Segments, StatCard, Table, Td } from '@/components/ui/misc';
import { HANDOVER_STAGE, fmtDate, useGapList, useHandoverList, useSummary, type HandoverDto } from '@/data/live';
import type { Status } from '@/data/types';
import { useSession } from '@/lib/auth';
import { cn } from '@/lib/utils';

const HANDOVER_BADGE: Record<HandoverDto['status'], { s: Status; l: string }> = {
  DRAFT: { s: 'stale', l: 'Draft' }, RECONSTRUCTING: { s: 'single', l: 'Reconstructing' }, IN_REVIEW: { s: 'inferred', l: 'In review' },
  APPROVED: { s: 'verified', l: 'Approved' }, SHARED: { s: 'verified', l: 'Shared' }, ARCHIVED: { s: 'stale', l: 'Archived' },
};

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 17 ? 'Good afternoon' : 'Good evening';
}
const initials = (n: string) => n.split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();

export default function Dashboard() {
  const { user, project, handover } = useSession();
  const [starting, setStarting] = useState(false);
  const handovers = useHandoverList(project?.id);
  const { data: summary } = useSummary(handover?.id);
  const { data: gaps = [] } = useGapList(handover?.id);

  const toReview = summary ? (summary.byReviewState.PENDING ?? 0) + (summary.byReviewState.NEEDS_INFO ?? 0) : 0;
  const conflicts = summary?.byStatus.CONFLICTING ?? 0;
  const inferred = summary?.byStatus.INFERRED ?? 0;
  const missing = summary?.byStatus.MISSING_EVIDENCE ?? 0;
  const answered = gaps.filter(g => g.status === 'ANSWERED' || g.status === 'SKIPPED');
  const access = gaps.filter(g => g.kind === 'MISSING_OWNER' && g.status === 'OPEN').length;

  const attention: { icon: LucideIcon; tone: string; title: string; desc: string; action: string; to: string }[] = [];
  if (conflicts) attention.push({ icon: AlertTriangle, tone: 'bg-conflict-bg text-conflict', title: `${conflicts} conflicting claim${conflicts > 1 ? 's' : ''}`, desc: 'Sources disagree. Approval is blocked until they are resolved.', action: 'Resolve', to: '/app/conflicts' });
  if (toReview) attention.push({ icon: Info, tone: 'bg-inferred-bg text-inferred', title: `${toReview} claims need a decision`, desc: `${inferred} inferred · ${missing} missing evidence · ${summary?.critical ?? 0} marked critical`, action: 'Review', to: '/app/review' });
  if (access) attention.push({ icon: KeyRound, tone: 'bg-missing-bg text-missing', title: `${access} item${access > 1 ? 's have' : ' has'} no owner`, desc: 'Accounts or work with nobody named. These are exit-interview questions.', action: 'See questions', to: '/app/review' });
  if (gaps.length) attention.push({ icon: MessageCircle, tone: 'bg-single-bg text-single', title: `Exit interview: ${answered.length} of ${gaps.length} answered`, desc: handover ? `${handover.departingName} answers these in their exit interview.` : '', action: 'Review', to: '/app/review' });
  if (!handover) attention.push({ icon: Plug, tone: 'bg-brand-50 text-brand-700', title: 'No handover yet', desc: 'Add the person leaving to your team, connect sources, then start their handover.', action: 'Connect sources', to: '/app/sources' });

  return (
    <>
      <PageHeader
        title={`${greeting()}, ${user.name.split(' ')[0]}`}
        sub={`${handovers.data?.length ?? 0} handover${handovers.data?.length === 1 ? '' : 's'} in ${project?.name ?? 'your organisation'}`}
        right={<><Link to="/app/sources"><Button>Add a source</Button></Link><Button kind="primary" onClick={() => setStarting(true)}>Start a handover</Button></>}
      />
      {starting && <StartHandoverModal onClose={() => setStarting(false)} />}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Claims" value={String(summary?.total ?? '–')} note={summary ? `${summary.byStatus.VERIFIED ?? 0} verified · ${summary.byStatus.SINGLE_SOURCE ?? 0} single source` : 'No handover yet'} />
        <StatCard label="Claims to review" value={String(summary ? toReview : '–')} note={`${inferred} inferred · ${missing} missing evidence`} />
        <StatCard label="Open conflicts" value={String(summary ? conflicts : '–')} note={conflicts ? 'Blocking approval' : 'Nothing blocking'} valueClass={conflicts ? 'text-conflict' : undefined} />
        <StatCard label="Exit questions open" value={String(summary?.openGaps ?? '–')} note={`${answered.length} answered so far`} />
      </div>

      <section className="flex flex-col gap-3">
        <CardHeader title="Handovers" sub={`Everyone leaving ${project?.name ?? 'a project'}`} />
        <Table label="Handovers" columns={[{ label: 'Person leaving' }, { label: 'Handover' }, { label: 'Scenario' }, { label: 'Stage' }, { label: 'Started' }, { label: 'Status' }]}>
          {handovers.data?.map(h => {
            const st = HANDOVER_STAGE[h.status];
            return (
              <tr key={h.id} className="hover:bg-canvas">
                <Td><Link to="/app/review" className="flex items-center gap-2.5"><Avatar initials={initials(h.departingName)} tone={2} size="sm" /><span className="font-medium text-ink-900">{h.departingName}</span></Link></Td>
                <Td>{h.title}</Td>
                <Td className="capitalize">{h.scenario.toLowerCase()}</Td>
                <Td><span className="flex flex-col gap-1.5"><span className="text-caption font-medium">{st.label}</span><Segments n={st.n} total={5} tone={st.n === 5 ? 'bg-verified' : 'bg-brand-600'} /></span></Td>
                <Td className="whitespace-nowrap">{fmtDate(h.createdAt)}</Td>
                <Td><StatusBadge status={HANDOVER_BADGE[h.status].s} label={HANDOVER_BADGE[h.status].l} /></Td>
              </tr>
            );
          })}
        </Table>
        <QueryState loading={handovers.isLoading} error={handovers.error} empty={handovers.data?.length === 0} emptyText="No handovers yet." />
      </section>

      <div className="grid gap-6 xl:grid-cols-[1fr_420px]">
        <Card className="flex flex-col">
          <CardHeader title="Needs your attention" sub="Things that block approval come first" />
          <ul className="mt-2 divide-y divide-line">
            {attention.map(a => (
              <li key={a.title} className="flex items-center gap-3 py-3">
                <span className={cn('grid size-[34px] shrink-0 place-items-center rounded-lg', a.tone)}><a.icon className="size-4" aria-hidden /></span>
                <span className="flex-1"><span className="block text-small font-medium">{a.title}</span><span className="block text-caption text-ink-500">{a.desc}</span></span>
                <Link to={a.to}><Button>{a.action}</Button></Link>
              </li>
            ))}
            {attention.length === 0 && <li className="py-3 text-small text-ink-500">Nothing needs you right now.</li>}
          </ul>
        </Card>
        <Card className="flex flex-col gap-4">
          <CardHeader title="Exit interview answers" sub={handover ? `From ${handover.departingName}` : undefined} />
          <ul className="flex flex-col gap-4">
            {answered.slice(0, 4).map(g => (
              <li key={g.id} className="flex flex-col gap-1">
                <span className="text-small font-medium">{g.question.length > 110 ? g.question.slice(0, 110) + '…' : g.question}</span>
                <span className="text-small text-ink-700">{g.status === 'SKIPPED' ? 'Skipped — routed to you' : g.answer}</span>
                {g.answeredAt && <span className="text-caption text-ink-400">{fmtDate(g.answeredAt)}</span>}
              </li>
            ))}
            {answered.length === 0 && <li className="text-small text-ink-500">No answers yet.</li>}
          </ul>
        </Card>
      </div>
    </>
  );
}
