import { ArrowRight, Check, ChevronDown, Sparkles } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/misc';
import { useAdminProjects, useAdminUsers, useGate, useShares, useSourceList, useSummary, useTeam } from '@/data/live';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/utils';

export interface GuideStep {
  key: string;
  title: string;
  /** What to do and why, in one sentence. */
  why: string;
  done: boolean;
  /** Where the button goes; "start" opens the start-a-handover dialog on the dashboard. */
  to: string | 'start';
  action: string;
}

/**
 * The whole path from an empty workspace to a shared handover, each step ticked from real data. Admins get the
 * setup steps; project leads get the handover steps. The first step not yet done is "next".
 */
export function useGuide() {
  const { session, user } = useAuth();
  const isAdmin = !!session?.isAdmin;
  const isLead = user?.appRole === 'lead';
  const project = isLead ? session?.project : null;
  const handover = isLead ? session?.handover : null;

  const people = useAdminUsers({ enabled: isAdmin });
  const projects = useAdminProjects({ enabled: isAdmin });
  const team = useTeam(project?.id);
  const sources = useSourceList(project?.id);
  const summary = useSummary(handover?.id);
  const gate = useGate(handover?.id);
  const shares = useShares(handover?.id);

  const steps: GuideStep[] = [];
  if (isAdmin) {
    steps.push({ key: 'people', title: 'Add your team', why: 'Give an account to the person leaving and the people taking over. Each gets a temporary password to pass on.',
      done: (people.data?.length ?? 0) > 1, to: '/app/admin/users', action: 'Add people' });
    steps.push({ key: 'project', title: 'Create a project', why: 'A project groups the sources and handovers for one piece of work, and names its lead.',
      done: (projects.data?.length ?? 0) > 0, to: '/app/admin/projects', action: 'Create a project' });
  }
  if (isLead && project) {
    steps.push({ key: 'team', title: 'Add people to the project', why: 'The person leaving and their successors must be members of this project.',
      done: (team.data?.length ?? 0) > 1, to: '/app/team', action: 'Open team' });
    steps.push({ key: 'sources', title: 'Add sources', why: 'Upload meeting notes, documents, a task-board CSV or a WhatsApp, Slack or Discord export. Secrets are removed automatically.',
      done: (sources.data ?? []).some(s => s.status === 'READY'), to: '/app/sources', action: 'Add sources' });
    steps.push({ key: 'start', title: 'Start a handover', why: 'Choose the person who is leaving. Baton builds their handover from the sources.',
      done: !!handover, to: 'start', action: 'Start a handover' });
    steps.push({ key: 'reconstruct', title: 'Reconstruct', why: 'Baton reads every source and lists what the next person needs to know, each with its source quote.',
      done: (summary.data?.total ?? 0) > 0, to: '/app/reconstruct', action: 'Reconstruct' });
    const conflicts = summary.data?.byStatus.CONFLICTING ?? 0;
    const approvedBefore = gate.data?.currentVersionNo != null;
    steps.push({ key: 'review', title: conflicts ? 'Resolve conflicts' : 'Review',
      why: conflicts
        ? `${conflicts} claims disagree with each other. Pick which one is correct; approval waits until you do.`
        : `Accept, edit or reject what Baton found. ${handover?.departingName ?? 'The person leaving'} can answer the open questions in their exit interview.`,
      done: approvedBefore || !!gate.data?.canApprove, to: conflicts ? '/app/conflicts' : '/app/review', action: conflicts ? 'Resolve conflicts' : 'Review claims' });
    steps.push({ key: 'approve', title: 'Approve', why: 'Freeze this version. It gets a fingerprint, so nobody can change what was handed over.',
      done: approvedBefore, to: '/app/approval', action: 'Approve' });
    steps.push({ key: 'share', title: 'Share with successors', why: 'Each successor sees only what their clearance allows, with a checklist and answers to their questions.',
      done: (shares.data ?? []).some(s => !s.revokedAt), to: '/app/share', action: 'Share' });
  }
  const loading = [people, projects, team, sources, summary, gate, shares].some(q => q.isLoading && q.fetchStatus !== 'idle');
  const next = steps.find(s => !s.done) ?? null;
  return { steps, next, loading, allDone: steps.length > 0 && !next };
}

const HIDE_KEY = 'baton.guideCollapsed';

/** The "Your next step" card: one clear action, and the whole path underneath. */
/** {@code here} turns a step's button into an action on the current page (for example opening a form). */
export function NextStepGuide({ onStart, here = {} }: { onStart?: () => void; here?: Record<string, () => void> }) {
  const { steps, next, loading, allDone } = useGuide();
  const [collapsed, setCollapsed] = useState(() => { try { return localStorage.getItem(HIDE_KEY) === '1'; } catch { return false; } });
  if (loading || steps.length === 0) return null;
  const toggle = () => setCollapsed(c => { try { localStorage.setItem(HIDE_KEY, c ? '0' : '1'); } catch { /* private mode */ } return !c; });
  const doneCount = steps.filter(s => s.done).length;
  const onHere = next ? (next.to === 'start' ? onStart : here[next.key]) : undefined;
  const button = next && (onHere
    ? <Button kind="primary" onClick={onHere}>{next.action}<ArrowRight className="size-4" aria-hidden /></Button>
    : <Link to={next.to}><Button kind="primary">{next.action}<ArrowRight className="size-4" aria-hidden /></Button></Link>);

  return (
    <Card className="border-brand-100 bg-gradient-to-br from-brand-50/60 to-card p-0">
      <div className="flex flex-wrap items-center gap-4 p-5">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-brand-600 text-white"><Sparkles className="size-5" aria-hidden /></span>
        <div className="min-w-[220px] flex-1">
          <p className="overline text-brand-700">{allDone ? 'All done' : `Your next step · ${doneCount} of ${steps.length} done`}</p>
          <h2 className="text-h3 font-semibold text-ink-900">{allDone ? 'Your handover is shared' : next!.title}</h2>
          <p className="text-small text-ink-700">{allDone ? 'Follow how your successors are doing, and start the next version when something changes.' : next!.why}</p>
        </div>
        {allDone ? <Link to="/app/successors"><Button kind="primary">See successors<ArrowRight className="size-4" aria-hidden /></Button></Link> : button}
        <button type="button" onClick={toggle} aria-expanded={!collapsed} className="flex items-center gap-1 rounded-md px-2 py-1 text-caption text-ink-500 hover:bg-subtle cursor-pointer">
          {collapsed ? 'Show all steps' : 'Hide steps'}<ChevronDown className={cn('size-3.5 transition-transform', !collapsed && 'rotate-180')} aria-hidden />
        </button>
      </div>
      {!collapsed && (
        <ol className="grid gap-px border-t border-brand-100 bg-brand-100/60 sm:grid-cols-2 xl:grid-cols-4" aria-label="Steps">
          {steps.map((s, i) => {
            const isNext = next?.key === s.key;
            const label = (
              <span className="flex items-start gap-2.5 px-4 py-3">
                <span className={cn('mt-0.5 grid size-5 shrink-0 place-items-center rounded-full text-[11px] font-semibold',
                  s.done ? 'bg-verified text-white' : isNext ? 'bg-brand-600 text-white' : 'border border-line-strong bg-card text-ink-500')}>
                  {s.done ? <Check className="size-3" strokeWidth={3} aria-hidden /> : i + 1}
                </span>
                <span className={cn('text-small', s.done ? 'text-ink-500 line-through decoration-ink-300' : isNext ? 'font-medium text-ink-900' : 'text-ink-700')}>
                  {s.title}<span className="sr-only">{s.done ? ' (done)' : isNext ? ' (next)' : ''}</span>
                </span>
              </span>
            );
            return (
              <li key={s.key} className={cn('bg-card', isNext && 'bg-brand-50')} aria-current={isNext ? 'step' : undefined}>
                {s.to === 'start' ? <button type="button" onClick={onStart} className="w-full text-left hover:bg-canvas cursor-pointer">{label}</button>
                  : <Link to={s.to} className="block hover:bg-canvas">{label}</Link>}
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}

/** A small "Next: …" link for the sidebar, so the next step is one click away from any page. */
export function NextStepLink({ onNavigate }: { onNavigate?: () => void }) {
  const { next, loading } = useGuide();
  if (loading || !next) return null;
  const to = next.to === 'start' ? '/app?start=1' : next.to;
  return (
    <Link to={to} onClick={onNavigate} className="mx-0.5 mt-2 flex items-center gap-2 rounded-lg border border-brand-100 bg-brand-50 px-2.5 py-2 text-small text-brand-700 hover:bg-brand-100">
      <Sparkles className="size-3.5 shrink-0" aria-hidden />
      <span className="min-w-0 flex-1 truncate"><span className="font-medium">Next:</span> {next.title}</span>
      <ArrowRight className="size-3.5 shrink-0" aria-hidden />
    </Link>
  );
}
