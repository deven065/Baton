import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Lock, Play } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { StatusBadge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Banner, Card, CardHeader, PageHeader, Progress, QueryState, StatusCircle, WorkflowStepper, type StepState } from '@/components/ui/misc';
import { fmtDateTime, typeLabel, useLatestRun, useSourceList, useStartReconstruct, type JobProgress } from '@/data/live';
import { useSession } from '@/lib/auth';
import { ApiError, streamEvents } from '@/lib/http';

const STAGES: [string, string, string][] = [
  ['SIGNAL', 'Repository signals (no AI)', 'Open PRs, issues, tasks and files tied to the person leaving'],
  ['EXTRACT', 'Claim extraction', 'Redacted chunks in batches · the model only proposes'],
  ['VERIFY', 'Citation verification', 'Every quote is located in its stored chunk by code'],
  ['CONSOLIDATE', 'Merge similar claims', 'Duplicates folded together, evidence kept'],
  ['CHECK', 'Model check', 'A second model pass asks: does the quote really support the claim?'],
  ['CONFLICT', 'Conflict detection', 'Claims that disagree are linked'],
  ['SCORE', 'Status and confidence', 'Computed by code from sources, quotes and recency'],
  ['GAP', 'Exit-interview questions', 'What sources cannot answer becomes a question'],
  ['TIMELINE', 'Decision timeline', 'Dated decisions, changes and open discussions'],
];
const n = (v: unknown) => (typeof v === 'number' ? v : 0);

export default function Reconstruct() {
  const { project, handover } = useSession();
  const qc = useQueryClient();
  const run = useLatestRun(handover?.id);
  const sources = useSourceList(project?.id);
  const start = useStartReconstruct(handover?.id);
  const [jobId, setJobId] = useState<string | null>(null);
  const [live, setLive] = useState<JobProgress | null>(null);
  const [finished, setFinished] = useState<string | null>(null);

  // Attach to a run that is still going (e.g. after a page reload).
  const latest = run.data;
  useEffect(() => { if (latest?.status === 'RUNNING' && !jobId) setJobId(latest.jobId); }, [latest, jobId]);

  // Stream progress for the active job.
  useEffect(() => {
    if (!jobId) return;
    const ctl = new AbortController();
    setFinished(null);
    streamEvents(`/api/jobs/${jobId}/events`, (event, data) => {
      if (event === 'progress') setLive(data as JobProgress);
      if (event === 'done') {
        setFinished((data as { status?: string }).status ?? 'SUCCEEDED');
        for (const k of [['run'], ['summary'], ['claims'], ['gaps'], ['timeline'], ['handovers']]) qc.invalidateQueries({ queryKey: k });
      }
    }, ctl.signal).catch(() => { /* aborted or closed */ });
    return () => ctl.abort();
  }, [jobId, qc]);

  const running = !!jobId && !finished;
  const busySources = sources.data?.filter(s => s.status === 'PENDING' || s.status === 'SYNCING').length ?? 0;
  const stageIdx = (s?: string) => STAGES.findIndex(x => x[0] === s);
  const curIdx = running ? stageIdx(live?.stage) : -1;
  const done = !running && latest && latest.status !== 'RUNNING';

  const state = (i: number): StepState => {
    if (running) return i < curIdx ? 'done' : i === curIdx ? 'run' : 'todo';
    if (!latest) return 'todo';
    if (latest.status === 'FAILED') return 'fail';
    return 'done';
  };
  const stats = latest?.stats ?? {};
  const statFor = (k: string) => {
    switch (k) {
      case 'SIGNAL': return `${n(stats.signals)} signals`;
      case 'EXTRACT': return `${n(stats.extracted)} claims · ${n(stats.batches)} batches${n(stats.batchesFailed) ? ` · ${n(stats.batchesFailed)} failed` : ''}`;
      case 'VERIFY': return `${n(stats.quotesVerified)} quotes verified · ${n(stats.quotesRejected)} rejected`;
      case 'CONSOLIDATE': return `${n(stats.merged)} merged`;
      case 'CHECK': return n(stats.modelChecked) ? `${n(stats.modelChecked)} checked · ${n(stats.modelDisputed)} disputed`
        : n(stats.modelCheckSkipped) ? 'skipped: offline mode' : '–';
      case 'CONFLICT': return `${n(stats.conflicts)} conflicts`;
      case 'GAP': return `${n(stats.gapQuestions)} questions`;
      case 'TIMELINE': return `${n(stats.timelineEvents)} events`;
      default: return '';
    }
  };
  const byType = (stats.byType ?? {}) as Record<string, number>;
  const startErr = start.error instanceof ApiError ? start.error.message : null;

  if (!handover) {
    return (
      <>
        <PageHeader title="Reconstruct project state" />
        <QueryState empty emptyText="There is no handover in this project yet." />
      </>
    );
  }

  return (
    <>
      <WorkflowStepper current={1} />
      <PageHeader
        title={running ? 'Reconstructing project state' : done ? 'Project state reconstructed' : 'Reconstruct project state'}
        sub="Repository signals run first, without AI. Then the model reads redacted chunks in batches and proposes claims — code checks every quote."
        right={
          <Button kind="primary" disabled={running || start.isPending || busySources > 0} onClick={() => start.mutate(undefined, { onSuccess: r => { setLive(null); setJobId(r.jobId); } })}>
            <Play className="size-3.5" aria-hidden />{latest ? 'Run again' : 'Start reconstruction'}
          </Button>
        }
      />
      {busySources > 0 && <Banner tone="inferred" icon={AlertTriangle}>{busySources} source{busySources > 1 ? 's are' : ' is'} still syncing. Reconstruction can start when ingestion finishes.</Banner>}
      {startErr && <Banner tone="conflict" icon={AlertTriangle}>{startErr}</Banner>}
      <div className="grid gap-6 xl:grid-cols-[1fr_460px]">
        <Card className="flex flex-col gap-1">
          <CardHeader
            title={`Pipeline · ${STAGES.length} stages`}
            sub={running ? live?.message || 'Starting…' : latest ? `Last run ${fmtDateTime(latest.startedAt)} · ${latest.model} · prompt ${latest.promptVersion}${n(stats.tokensIn) ? ` · ${(n(stats.tokensIn) + n(stats.tokensOut)).toLocaleString()} tokens` : ''}` : 'Not run yet'}
            right={running
              ? <span className="text-h2 font-semibold text-brand-600" aria-live="polite">{live?.percent ?? 0}%</span>
              : latest && <StatusBadge status={latest.status === 'SUCCEEDED' ? 'verified' : latest.status === 'PARTIAL' ? 'inferred' : latest.status === 'FAILED' ? 'conflicting' : 'single'} label={latest.status.toLowerCase()} />}
          />
          {running && <Progress value={(live?.percent ?? 0) / 100} className="mt-2" label="Reconstruction progress" />}
          <QueryState loading={run.isLoading} error={run.error} />
          <ul className="mt-2">
            {STAGES.map(([k, t, d], i) => (
              <li key={k} className="flex items-center gap-3 py-2">
                <StatusCircle state={state(i)} />
                <span className="flex-1">
                  <span className={state(i) === 'todo' ? 'block text-small font-medium text-ink-400' : 'block text-small font-medium'}>{i + 1} · {t}</span>
                  <span className="block text-caption text-ink-500">{running && i === curIdx && live?.message ? live.message : d}</span>
                </span>
                {!running && latest && <span className="font-mono text-caption text-ink-700">{statFor(k)}</span>}
              </li>
            ))}
          </ul>
          {latest?.warnings?.map(w => <Banner key={w} tone="inferred" icon={AlertTriangle} className="mt-2">{w}</Banner>)}
          {latest?.error && <Banner tone="conflict" icon={AlertTriangle} className="mt-2">{latest.error}</Banner>}
        </Card>
        <div className="flex flex-col gap-4">
          <Card className="flex flex-col gap-3">
            <CardHeader title="Claims found" sub={latest ? `${n(stats.quotesVerified)} verified quotes` : 'Run reconstruction to see claims'} />
            <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {Object.entries(byType).map(([l, c]) => (
                <li key={l} className="rounded-lg bg-canvas px-3 py-2.5"><span className="block text-h3 font-semibold">{c}</span><span className="block text-caption text-ink-500">{typeLabel(l)}</span></li>
              ))}
            </ul>
            {done && <Link to="/app/review"><Button kind="primary" className="w-full">Review claims</Button></Link>}
          </Card>
          {running && live?.counts && Object.keys(live.counts).length > 0 && (
            <Card className="flex flex-col gap-2">
              <CardHeader title="Live counters" />
              {Object.entries(live.counts).map(([k, v]) => <p key={k} className="flex justify-between text-small"><span className="text-ink-500">{k}</span><span className="font-mono">{v}</span></p>)}
            </Card>
          )}
        </div>
      </div>
      <div className="flex items-center gap-2.5 rounded-xl border border-line bg-card p-3.5 text-small text-ink-700">
        <Lock className="size-4 shrink-0 text-verified" aria-hidden />
        AI receives redacted text only · responses are cached and replayed offline when AI_MOCK=true
      </div>
    </>
  );
}
