import { AlertTriangle, CheckCircle2, Clock, KeyRound, MessageCircleQuestion, Radar, UserRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Chip, FilterChip, StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Avatar, Banner, Card, CardHeader, PageHeader, Progress, QueryState, StatCard, Table, Td } from '@/components/ui/misc';
import { fmtDate, useProgress, useRadar, type RiskPersonDto } from '@/data/live';
import type { Status } from '@/data/types';
import { useSession } from '@/lib/auth';
import { cn } from '@/lib/utils';

const initials = (n: string) => n.split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
const LEVEL: Record<RiskPersonDto['level'], { s: Status; l: string }> = {
  HIGH: { s: 'conflicting', l: 'High risk' }, MEDIUM: { s: 'inferred', l: 'Medium' }, LOW: { s: 'verified', l: 'Low' },
};

/** Knowledge-risk radar: what the project would lose if someone left tomorrow. */
export function RiskRadar() {
  const { project } = useSession();
  const { data, isLoading, error } = useRadar(project?.id);
  const [topicFilter, setTopicFilter] = useState<'sole' | 'all' | 'quiet'>('sole');
  const topics = (data?.topics ?? []).filter(t => topicFilter === 'all' || (topicFilter === 'sole' ? t.soleSource : t.quiet));

  return (
    <>
      <PageHeader title="Knowledge risk" sub={`What ${project?.name ?? 'this project'} would lose if someone left tomorrow, from its own sources. Start handovers before anyone resigns.`} />
      <QueryState loading={isLoading} error={error} />
      {data && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Bus factor" value={String(data.busFactor)} note={data.busFactor <= 1 ? 'One person wrote half of what the project knows' : `${data.busFactor} people wrote half of what the project knows`} valueClass={data.busFactor <= 1 ? 'text-conflict' : data.busFactor === 2 ? 'text-inferred' : undefined} />
            <StatCard label="Single-source topics" value={String(data.topics.filter(t => t.soleSource).length)} note={`of ${data.topics.length} topics in the claims`} />
            <StatCard label="Access with one person" value={String(data.access.filter(a => a.sources.length === 1).length)} note="Accounts or keys only one person mentions" />
            <StatCard label="Quiet topics" value={String(data.topics.filter(t => t.quiet).length)} note="Nothing written in 60 days" />
          </div>
          {!data.hasClaims && <Banner icon={Radar}>Topics and access appear once a handover in this project has been reconstructed. Authorship below comes straight from your sources.</Banner>}

          <section className="flex flex-col gap-3">
            <CardHeader title="People" sub="Share of the project’s written knowledge, and what only they have written about" />
            <Table label="People at risk" columns={[{ label: 'Person' }, { label: 'Share of sources' }, { label: 'Only source for' }, { label: 'Access' }, { label: 'Risk' }, { label: '', className: 'text-right' }]}>
              {data.team.map(p => (
                <tr key={p.userId}>
                  <Td><span className="flex items-center gap-2.5"><Avatar initials={initials(p.name)} tone={2} size="sm" /><span><span className="block font-medium text-ink-900">{p.name}</span><span className="block text-caption text-ink-500">{p.role === 'PROJECT_LEAD' ? 'Lead' : 'Member'}{p.leaving && ' · handover in progress'}</span></span></span></Td>
                  <Td className="w-[180px]"><span className="flex flex-col gap-1"><span className="text-small">{Math.round(p.share * 100)}% · {p.chunks} passages</span><Progress value={p.share} tone={p.share >= 0.4 ? 'bg-conflict' : 'bg-brand-600'} label="Share" /></span></Td>
                  <Td><span className="flex max-w-[280px] flex-wrap gap-1">{p.soleTopics.slice(0, 4).map(t => <Chip key={t}>{t}</Chip>)}{p.soleTopics.length > 4 && <span className="text-caption text-ink-500">+{p.soleTopics.length - 4}</span>}{p.soleTopics.length === 0 && <span className="text-caption text-ink-400">—</span>}</span></Td>
                  <Td>{p.access.length ? <span className="flex items-center gap-1 text-small text-missing"><KeyRound className="size-3.5" aria-hidden />{p.access.length}</span> : <span className="text-caption text-ink-400">—</span>}</Td>
                  <Td><StatusBadge status={LEVEL[p.level].s} label={LEVEL[p.level].l} /></Td>
                  <Td className="text-right">{p.level !== 'LOW' && !p.leaving && <Link to="/app" className="text-small font-medium text-brand-600 hover:underline">Plan a handover</Link>}</Td>
                </tr>
              ))}
            </Table>
            {data.outsideAuthors.length > 0 && <p className="text-caption text-ink-500">Also in the sources, not on the team: {data.outsideAuthors.join(', ')}</p>}
          </section>

          <div className="grid items-start gap-6 xl:grid-cols-[1fr_380px]">
            <section className="flex flex-col gap-3">
              <CardHeader title="Topics" sub="From reconstructed claims: who has written about each" right={<span className="flex gap-1.5"><FilterChip active={topicFilter === 'sole'} onClick={() => setTopicFilter('sole')}>One source</FilterChip><FilterChip active={topicFilter === 'quiet'} onClick={() => setTopicFilter('quiet')}>Quiet</FilterChip><FilterChip active={topicFilter === 'all'} onClick={() => setTopicFilter('all')}>All</FilterChip></span>} />
              <ul className="grid gap-3 sm:grid-cols-2">
                {topics.map(t => (
                  <li key={t.label}>
                    <Card as="article" className={cn('flex h-full flex-col gap-2', t.soleSource && 'border-inferred/50')}>
                      <span className="flex items-start justify-between gap-2"><span className="font-medium">{t.label}</span>{t.soleSource ? <StatusBadge status="single" label="One source" /> : <StatusBadge status="verified" label={`${t.sources.length} sources`} />}</span>
                      <span className="flex items-center gap-1.5 text-small text-ink-700"><UserRound className="size-3.5 text-ink-400" aria-hidden />{t.sources.join(', ') || 'Unknown'}</span>
                      <span className="flex items-center gap-1.5 text-caption text-ink-500"><Clock className="size-3" aria-hidden />{t.claims} claim{t.claims === 1 ? '' : 's'}{t.lastActivity && ` · last written ${fmtDate(t.lastActivity)}`}{t.quiet && ' · quiet'}</span>
                    </Card>
                  </li>
                ))}
              </ul>
              {topics.length === 0 && <p className="text-small text-ink-500">Nothing here.</p>}
            </section>
            <Card className="flex flex-col gap-2">
              <h2 className="flex items-center gap-2 text-h3 font-semibold"><KeyRound className="size-5 text-missing" aria-hidden />Access and accounts</h2>
              <ul className="flex flex-col divide-y divide-line">
                {data.access.map(a => (
                  <li key={a.title} className="flex flex-col gap-1 py-2.5">
                    <span className="text-small">{a.title}</span>
                    <span className="text-caption text-ink-500">{a.subject ?? 'unspecified'} · {a.sources.length === 1 ? `only ${a.sources[0]}` : a.sources.join(', ') || 'no named source'}</span>
                  </li>
                ))}
                {data.access.length === 0 && <li className="py-2 text-small text-ink-500">No access claims yet.</li>}
              </ul>
            </Card>
          </div>
        </>
      )}
    </>
  );
}

const PHASE = { DAY1: 'Day 1', WEEK1: 'Week 1', MONTH1: 'Month 1' } as const;

/** Successor progress: did the handover land, person by person. */
export function Successors() {
  const { handover } = useSession();
  const { data = [], isLoading, error } = useProgress(handover?.id);
  return (
    <>
      <PageHeader title="Successors" sub="How far each person taking over has got through their checklist, and what they’re still waiting on." right={<Link to="/app/share"><Button>Manage shares</Button></Link>} />
      <QueryState loading={isLoading} error={error} empty={data.length === 0} emptyText="Not shared with anyone yet." />
      <ul className="grid gap-4 lg:grid-cols-2">
        {data.map(s => (
          <li key={s.shareId}>
            <Card as="article" className="flex h-full flex-col gap-4">
              <div className="flex items-start justify-between gap-3">
                <span className="flex items-center gap-3"><Avatar initials={initials(s.name)} tone={3} /><span><span className="block font-semibold">{s.name}</span><span className="block text-caption text-ink-500">{s.roleLabel} · v{s.versionNo} · shared {fmtDate(s.sharedAt)}</span></span></span>
                {s.acknowledgedAt ? <StatusBadge status="verified" label={`Acknowledged ${fmtDate(s.acknowledgedAt)}`} /> : s.lastViewedAt ? <StatusBadge status="inferred" label={`Opened ${fmtDate(s.lastViewedAt)}`} /> : <StatusBadge status="single" label="Not opened" />}
              </div>
              <div className="flex items-end gap-2"><span className="text-stat font-semibold">{s.percent}%</span><span className="pb-1 text-small text-ink-500">of their checklist done</span></div>
              <div className="grid grid-cols-3 gap-3">
                {s.phases.map(p => (
                  <div key={p.phase} className="flex flex-col gap-1">
                    <span className="flex items-center justify-between text-caption"><span className="font-medium">{PHASE[p.phase]}</span><span className={p.overdue ? 'text-conflict' : 'text-ink-500'}>{p.done}/{p.total}</span></span>
                    <Progress value={p.total ? p.done / p.total : 0} tone={p.overdue ? 'bg-conflict' : p.done === p.total && p.total > 0 ? 'bg-verified' : 'bg-brand-600'} label={PHASE[p.phase]} />
                    <span className="text-caption text-ink-400">{p.overdue ? 'Overdue' : `Due ${fmtDate(p.dueAt)}`}</span>
                  </div>
                ))}
              </div>
              {s.overdueItems.length > 0 && (
                <div className="flex flex-col gap-1 rounded-lg bg-conflict-bg p-3">
                  <span className="flex items-center gap-1.5 text-caption font-semibold text-conflict"><AlertTriangle className="size-3.5" aria-hidden />Overdue</span>
                  {s.overdueItems.map(t => <span key={t} className="text-small text-ink-900">{t}</span>)}
                </div>
              )}
              <div className="mt-auto flex flex-wrap gap-3 text-caption text-ink-500">
                <span className="flex items-center gap-1"><MessageCircleQuestion className="size-3.5" aria-hidden />{s.openQuestions} waiting for an answer</span>
                <span className="flex items-center gap-1"><CheckCircle2 className="size-3.5" aria-hidden />{s.answeredQuestions} answered</span>
              </div>
            </Card>
          </li>
        ))}
      </ul>
    </>
  );
}
