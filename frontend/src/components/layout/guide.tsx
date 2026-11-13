import { ArrowRight, Check, ChevronDown, PartyPopper, Sparkles } from 'lucide-react';
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

function readPref(): boolean | null {
  try { const v = localStorage.getItem(HIDE_KEY); return v === '1' ? true : v === '0' ? false : null; } catch { return null; }
}

/** The "Your next step" card: one clear action, a progress bar, and the whole path as a stepper underneath. */
/** {@code here} turns a step's button into an action on the current page (for example opening a form). */
export function NextStepGuide({ onStart, here = {} }: { onStart?: () => void; here?: Record<string, () => void> }) {
  const { steps, next, loading, allDone } = useGuide();
  const [pref, setPref] = useState<boolean | null>(readPref);
  if (loading || steps.length === 0) return null;
  // Until the person chooses, the steps are open while there is work to do and folded away once it is all done.
  const collapsed = pref ?? allDone;
  const toggle = () => {
    const v = !collapsed;
    setPref(v);
    try { localStorage.setItem(HIDE_KEY, v ? '1' : '0'); } catch { /* private mode */ }
  };
  const doneCount = steps.filter(s => s.done).length;
  const pct = Math.round((doneCount / steps.length) * 100);
  const nextIndex = next ? steps.indexOf(next) + 1 : steps.length;
  const onHere = next ? (next.to === 'start' ? onStart : here[next.key]) : undefined;
  const arrow = <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" aria-hidden />;
  const button = allDone
    ? <Link to="/app/successors" className="group"><Button kind="primary">See successors{arrow}</Button></Link>
    : onHere
      ? <span className="group"><Button kind="primary" onClick={onHere}>{next!.action}{arrow}</Button></span>
      : <Link to={next!.to} className="group"><Button kind="primary">{next!.action}{arrow}</Button></Link>;

  return (
    <Card className={cn('relative overflow-hidden p-0', allDone ? 'border-verified/20' : 'border-brand-100')}>
      {/* A soft wash of colour behind the header; purely decorative. */}
      <div aria-hidden className={cn('pointer-events-none absolute -top-24 -right-16 size-72 rounded-full blur-3xl',
        allDone ? 'bg-verified-bg' : 'bg-brand-100/70')} />
      <div className="relative flex flex-wrap items-center gap-x-5 gap-y-4 p-5 sm:p-6">
        <span className={cn('hidden size-12 shrink-0 sm:grid place-items-center rounded-2xl text-white shadow-sm',
          allDone ? 'bg-verified' : 'bg-gradient-to-br from-brand-600 to-brand-700')}>
          {allDone ? <PartyPopper className="size-6" aria-hidden /> : <Sparkles className="size-6" aria-hidden />}
        </span>
        <div className="min-w-[240px] flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className={cn('overline', allDone ? 'text-verified' : 'text-brand-700')}>{allDone ? 'All done' : 'Your next step'}</span>
            <span className={cn('rounded-full px-2 py-0.5 text-[11px] font-semibold tabular-nums',
              allDone ? 'bg-verified-bg text-verified' : 'bg-brand-50 text-brand-700 ring-1 ring-brand-100')}>
              {allDone ? `${steps.length} of ${steps.length}` : `Step ${nextIndex} of ${steps.length}`}
            </span>
          </div>
          <h2 className="mt-1 text-h2 font-semibold tracking-tight text-ink-900">{allDone ? 'Your handover is shared' : next!.title}</h2>
          <p className="mt-0.5 max-w-2xl text-small text-ink-700">
            {allDone ? 'Follow how your successors are doing, and start the next version when something changes.' : next!.why}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {button}
          <button type="button" onClick={toggle} aria-expanded={!collapsed}
            className="flex items-center gap-1 rounded-md px-2 py-1.5 text-caption font-medium text-ink-500 hover:bg-subtle hover:text-ink-700 cursor-pointer">
            {collapsed ? 'Show steps' : 'Hide steps'}<ChevronDown className={cn('size-3.5 transition-transform', !collapsed && 'rotate-180')} aria-hidden />
          </button>
        </div>
      </div>

      <div className="relative px-5 sm:px-6" role="progressbar" aria-label="Handover progress"
        aria-valuemin={0} aria-valuemax={steps.length} aria-valuenow={doneCount}>
        <div className="h-1.5 overflow-hidden rounded-full bg-ink-900/5">
          <div className={cn('h-full rounded-full transition-[width] duration-700 ease-out',
            allDone ? 'bg-verified' : 'bg-gradient-to-r from-brand-600 to-brand-700')} style={{ width: `${Math.max(pct, 3)}%` }} />
        </div>
        <p className="mt-1.5 text-caption text-ink-500 tabular-nums">{doneCount} of {steps.length} steps done</p>
      </div>

      {collapsed ? <div className="h-4" /> : (
        <ol className="relative flex flex-col gap-1 px-5 pt-3 pb-5 sm:px-6 lg:flex-row lg:gap-0 lg:pt-5" aria-label="Steps">
          {steps.map((s, i) => {
            const isNext = next?.key === s.key;
            const last = i === steps.length - 1;
            const body = (
              <>
                <span className={cn('relative z-10 grid size-7 shrink-0 place-items-center rounded-full text-caption font-semibold',
                  s.done ? 'bg-verified text-white'
                    : isNext ? 'bg-brand-600 text-white ring-4 ring-brand-100'
                      : 'border border-line-strong bg-card text-ink-500')}>
                  {s.done ? <Check className="size-3.5" strokeWidth={3} aria-hidden /> : i + 1}
                </span>
                <span className={cn('text-small leading-snug lg:text-caption',
                  s.done ? 'text-ink-500' : isNext ? 'font-semibold text-brand-700' : 'text-ink-700')}>
                  {s.title}<span className="sr-only">{s.done ? ' (done)' : isNext ? ' (next)' : ''}</span>
                </span>
              </>
            );
            const cls = 'relative flex w-full items-center gap-3 rounded-lg p-1.5 text-left hover:bg-canvas lg:flex-col lg:gap-2 lg:px-1 lg:text-center cursor-pointer';
            return (
              <li key={s.key} className="relative flex-1" aria-current={isNext ? 'step' : undefined}>
                {!last && (
                  <>
                    {/* The line to the next step: across on wide screens, down on narrow ones; green once this step is done. */}
                    <span aria-hidden className={cn('absolute hidden h-0.5 lg:block top-[19px] left-[calc(50%+18px)] right-[calc(-50%+18px)]',
                      s.done ? 'bg-verified' : 'bg-line')} />
                    <span aria-hidden className={cn('absolute w-0.5 lg:hidden left-[19px] top-[36px] -bottom-[6px]',
                      s.done ? 'bg-verified' : 'bg-line')} />
                  </>
                )}
                {s.to === 'start'
                  ? <button type="button" onClick={onStart} className={cls}>{body}</button>
                  : <Link to={s.to} className={cls}>{body}</Link>}
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}

/** A small "Next step" card for the sidebar, so the next step is one click away from any page. */
export function NextStepLink({ onNavigate }: { onNavigate?: () => void }) {
  const { steps, next, loading } = useGuide();
  if (loading || !next) return null;
  const to = next.to === 'start' ? '/app?start=1' : next.to;
  const done = steps.filter(s => s.done).length;
  return (
    <Link to={to} onClick={onNavigate}
      className="group mx-0.5 mt-2 flex flex-col gap-1.5 rounded-xl border border-brand-100 bg-gradient-to-br from-brand-50 to-card px-3 py-2.5 hover:border-brand-600/40 hover:shadow-sm">
      <span className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-brand-700">
        <Sparkles className="size-3 shrink-0" aria-hidden />Next step
        <span className="ml-auto tabular-nums normal-case tracking-normal text-brand-700/70">{done}/{steps.length}</span>
      </span>
      <span className="flex items-center gap-2 text-small font-medium text-ink-900">
        <span className="min-w-0 flex-1 truncate">{next.title}</span>
        <ArrowRight className="size-3.5 shrink-0 text-brand-700 transition-transform group-hover:translate-x-0.5" aria-hidden />
      </span>
      <span className="h-1 overflow-hidden rounded-full bg-brand-100" aria-hidden>
        <span className="block h-full rounded-full bg-brand-600" style={{ width: `${Math.max((done / steps.length) * 100, 4)}%` }} />
      </span>
    </Link>
  );
}
