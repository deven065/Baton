import {
  Database, Sparkles, Cable, CreditCard, Radar, TrendingUp,
  AlertTriangle, FolderKanban, UsersRound, Bell, CheckSquare, ChevronDown, ChevronRight, Clock, Eye, FileText, Home, Layers, LogOut, Menu, Plug, Search, Share2, ShieldCheck, Users, X,
  type LucideIcon,
} from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SensitivityChip } from '@/components/ui/badge';
import { fmtDate, fmtDateTime, useGapList, useShares, useSummary } from '@/data/live';
import { useCurrentShare } from '@/pages/recipient/RecipientPages';
import { Avatar, Kbd, Logo } from '@/components/ui/misc';
import { useAuth } from '@/lib/auth';
import { cn } from '@/lib/utils';

type Nav = { to: string; label: string; icon: LucideIcon; count?: number; end?: boolean; demo?: boolean };
const NAV_MAIN: Nav[] = [
  { to: '/app', label: 'Dashboard', icon: Home, end: true },
  { to: '/app/sources', label: 'Sources', icon: Plug },
  { to: '/app/reconstruct', label: 'Reconstruct', icon: Layers },
  { to: '/app/review', label: 'Review', icon: CheckSquare },
  { to: '/app/conflicts', label: 'Conflicts', icon: AlertTriangle },
  { to: '/app/timeline', label: 'Timeline', icon: Clock },
  { to: '/app/view-as', label: 'View as', icon: Eye },
  { to: '/app/approval', label: 'Approval', icon: ShieldCheck },
  { to: '/app/share', label: 'Share', icon: Share2 },
  { to: '/app/successors', label: 'Successors', icon: TrendingUp },
];
const NAV_ADMIN: Nav[] = [
  { to: '/app/admin/users', label: 'People & groups', icon: Users },
  { to: '/app/admin/projects', label: 'Projects', icon: FolderKanban },
  { to: '/app/admin/audit', label: 'Audit log', icon: FileText },
  { to: '/app/admin/connections', label: 'Connections', icon: Cable },
  { to: '/app/admin/ai', label: 'AI', icon: Sparkles },
  { to: '/app/admin/billing', label: 'Billing', icon: CreditCard },
  { to: '/app/admin/workspace', label: 'Workspace', icon: Database },
];
const NAV_TEAM: Nav = { to: '/app/team', label: 'Team', icon: UsersRound };
const NAV_RISK: Nav = { to: '/app/risk', label: 'Knowledge risk', icon: Radar };

const PAGE: Record<string, string> = {
  '/app': 'Dashboard', '/app/sources': 'Sources', '/app/reconstruct': 'Reconstruct', '/app/review': 'Review',
  '/app/conflicts': 'Conflicts', '/app/timeline': 'Timeline', '/app/view-as': 'View as', '/app/approval': 'Approval',
  '/app/share': 'Share', '/app/admin/users': 'People & groups', '/app/admin/projects': 'Projects',
  '/app/admin/audit': 'Audit log', '/app/admin/workspace': 'Workspace', '/app/admin/ai': 'AI', '/app/admin/connections': 'Connections', '/app/admin/billing': 'Billing', '/app/team': 'Team', '/app/risk': 'Knowledge risk', '/app/successors': 'Successors',
};

function NavItem({ item, onClick }: { item: Nav; onClick?: () => void }) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onClick}
      viewTransition
      className={({ isActive }) =>
        cn('nav-item group relative flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-small font-medium transition-[background-color,color] duration-200',
          isActive ? 'is-active bg-brand-50 text-brand-700' : 'text-ink-700 hover:bg-canvas')
      }
    >
      <Icon className="size-[18px] text-ink-500 transition-transform duration-300 ease-out group-hover:-translate-y-px group-[.is-active]:text-brand-600" aria-hidden />
      <span className="flex-1">{item.label}</span>
      {item.demo && <span className="rounded bg-subtle px-1 text-[10px] font-medium text-ink-400" title="Shows demo data until this API exists">demo</span>}
      {!!item.count && <span className="text-caption text-ink-500">{item.count}</span>}
    </NavLink>
  );
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { user, session, signOut } = useAuth();
  const navigate = useNavigate();
  // A pure admin (not leading a project) sees only the organisation section.
  const isAdmin = user?.appRole === 'admin';
  const canAdmin = !!session?.isAdmin;
  const { data: summary } = useSummary(session?.handover?.id);
  const counts: Record<string, number | undefined> = {
    '/app/review': summary ? (summary.byReviewState.PENDING ?? 0) + (summary.byReviewState.NEEDS_INFO ?? 0) : undefined,
    '/app/conflicts': summary?.byStatus.CONFLICTING,
  };
  return (
    <div className="flex h-full flex-col gap-1 p-4">
      <Link to="/app" className="px-2 pt-1 pb-4"><Logo animate /></Link>
      <button type="button" className="flex items-center gap-2.5 rounded-xl border border-line bg-canvas p-2.5 text-left">
        <span className="grid size-8 place-items-center rounded-lg bg-brand-100 text-brand-700"><Layers className="size-4" aria-hidden /></span>
        <span className="flex-1">
          <span className="block text-small font-medium">{session?.project?.name ?? 'No project'}</span>
          <span className="block text-caption text-ink-500">{session?.workspace.name ?? 'Workspace'}</span>
        </span>
        <ChevronDown className="size-3.5 text-ink-400" aria-hidden />
      </button>
      <nav aria-label="Main" className="stagger-side flex flex-col gap-1">
        {!isAdmin && (
          <>
            <p className="overline px-2.5 pt-4 pb-1.5 text-ink-400">Handover · {session?.handover?.departingName ?? 'none yet'}</p>
            {NAV_MAIN.map(n => <NavItem key={n.to} item={{ ...n, count: counts[n.to] }} onClick={onNavigate} />)}
            {user?.appRole === 'lead' && <NavItem item={NAV_TEAM} onClick={onNavigate} />}
            {user?.appRole === 'lead' && <NavItem item={NAV_RISK} onClick={onNavigate} />}
          </>
        )}
        {canAdmin && (
          <>
            <p className="overline px-2.5 pt-4 pb-1.5 text-ink-400">Organisation</p>
            {NAV_ADMIN.map(n => <NavItem key={n.to} item={n} onClick={onNavigate} />)}
          </>
        )}
      </nav>
      <div className="flex-1" />
      {user && (
        <div className="flex items-center gap-2.5 rounded-xl bg-canvas p-2.5">
          <Avatar initials={user.initials} tone={user.tone} />
          <div className="flex-1">
            <p className="text-small font-medium">{user.name}</p>
            <p className="text-caption text-ink-500">{user.role}</p>
            <SensitivityChip level={user.clearance} className="mt-1" />
          </div>
          <button type="button" aria-label="Sign out" onClick={() => { signOut(); navigate('/login'); }} className="rounded p-1 text-ink-400 hover:text-ink-900 cursor-pointer">
            <LogOut className="size-4" />
          </button>
        </div>
      )}
    </div>
  );
}

/** Header search: claims for a lead (opens Review filtered), people for an admin. Press / to focus. */
function HeaderSearch() {
  const { session } = useAuth();
  const navigate = useNavigate();
  const ref = useRef<HTMLInputElement>(null);
  const [q, setQ] = useState('');
  const target = session?.role === 'lead' ? '/app/review' : session?.isAdmin ? '/app/admin/users' : null;
  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (e.key === '/' && !(t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable))) {
        e.preventDefault();
        ref.current?.focus();
      }
    };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);
  if (!target) return null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    navigate(q.trim() ? `${target}?q=${encodeURIComponent(q.trim())}` : target);
  };
  return (
    <form role="search" onSubmit={submit} className="hidden w-[300px] items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 md:flex">
      <Search className="size-4 text-ink-400" aria-hidden />
      <input ref={ref} value={q} onChange={e => setQ(e.target.value)} placeholder={target === '/app/review' ? 'Search claims' : 'Search people'} aria-label="Search" className="w-full bg-transparent text-small outline-none placeholder:text-ink-400" />
      <Kbd>/</Kbd>
    </form>
  );
}

const SEEN_KEY = 'baton.notificationsSeen';

/** What happened on the lead's handover lately: exit answers, and recipients opening or acknowledging it. */
function Notifications() {
  const { session } = useAuth();
  const h = session?.role === 'lead' ? session.handover : null;
  const { data: gaps = [] } = useGapList(h?.id);
  const { data: shares = [] } = useShares(h?.id);
  const [open, setOpen] = useState(false);
  const [seen, setSeen] = useState(() => { try { return Number(localStorage.getItem(SEEN_KEY) ?? 0); } catch { return 0; } });
  const items = [
    ...gaps.filter(g => g.answeredAt && g.status === 'ANSWERED').map(g => ({ at: g.answeredAt!, text: `${h?.departingName ?? 'The person leaving'} answered: ${g.question.length > 90 ? g.question.slice(0, 90) + '…' : g.question}`, to: '/app/review' })),
    ...shares.filter(s => s.acknowledgedAt).map(s => ({ at: s.acknowledgedAt!, text: `${s.recipientName} acknowledged handover v${s.versionNo}`, to: '/app/share' })),
    ...shares.filter(s => s.lastViewedAt && !s.acknowledgedAt).map(s => ({ at: s.lastViewedAt!, text: `${s.recipientName} opened handover v${s.versionNo}`, to: '/app/share' })),
  ].sort((a, b) => b.at.localeCompare(a.at)).slice(0, 8);
  const unread = items.filter(i => new Date(i.at).getTime() > seen).length;
  const toggle = () => {
    setOpen(o => !o);
    const now = Date.now();
    setSeen(now);
    try { localStorage.setItem(SEEN_KEY, String(now)); } catch { /* private mode */ }
  };
  return (
    <div className="relative">
      <button type="button" aria-label={unread ? `Notifications, ${unread} new` : 'Notifications'} aria-expanded={open} onClick={toggle} className="relative rounded-lg border border-line p-2 text-ink-700 hover:bg-canvas cursor-pointer">
        <Bell className="size-4" />
        {unread > 0 && <span className="absolute -top-1 -right-1 grid min-w-4 place-items-center rounded-full bg-conflict px-1 text-[10px] font-semibold text-white">{unread}</span>}
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
          <div className="absolute right-0 z-50 mt-2 w-[340px] overflow-hidden rounded-xl border border-line bg-card shadow-pop">
            <p className="border-b border-line px-4 py-2.5 text-small font-semibold">Notifications</p>
            <ul className="max-h-[360px] overflow-y-auto">
              {items.map(i => (
                <li key={i.at + i.text}>
                  <Link to={i.to} onClick={() => setOpen(false)} className="flex flex-col gap-0.5 px-4 py-2.5 hover:bg-canvas">
                    <span className="text-small text-ink-900">{i.text}</span>
                    <span className="text-caption text-ink-400">{fmtDateTime(i.at)}</span>
                  </Link>
                </li>
              ))}
              {items.length === 0 && <li className="px-4 py-4 text-small text-ink-500">{h ? 'Nothing new yet. Exit answers and recipients opening the handover show up here.' : 'Nothing here yet.'}</li>}
            </ul>
          </div>
        </>
      )}
    </div>
  );
}

export function AppShell() {
  const { user, session } = useAuth();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const project = session?.project?.name ?? session?.workspace.name ?? 'Workspace';
  const inHandover = !['/app', '/app/team', '/app/risk'].includes(pathname) && !pathname.startsWith('/app/admin');
  const crumbs = [project, ...(inHandover && session?.handover ? ['Handover · ' + session.handover.departingName] : []), PAGE[pathname] ?? 'Baton'];
  return (
    <div className="flex min-h-dvh">
      <aside className="sticky top-0 hidden h-dvh w-[248px] shrink-0 overflow-y-auto border-r border-line bg-card lg:block">
        <Sidebar />
      </aside>
      {open && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
          <div className="absolute inset-0 bg-ink-900/40" onClick={() => setOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-[264px] overflow-y-auto bg-card shadow-pop">
            <button type="button" aria-label="Close menu" onClick={() => setOpen(false)} className="absolute top-4 right-3 p-1 text-ink-500 cursor-pointer"><X className="size-5" /></button>
            <Sidebar onNavigate={() => setOpen(false)} />
          </aside>
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center justify-between gap-4 border-b border-line bg-card px-4 py-3 sm:px-8">
          <div className="flex min-w-0 items-center gap-3">
            <button type="button" aria-label="Open menu" onClick={() => setOpen(true)} className="rounded p-1 text-ink-700 lg:hidden cursor-pointer"><Menu className="size-5" /></button>
            <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-2 text-small">
              {crumbs.map((c, i) => (
                <span key={c} className={cn('flex items-center gap-2 truncate', i < crumbs.length - 1 && 'hidden sm:flex')}>
                  {i > 0 && <ChevronRight className="size-3.5 text-ink-300" aria-hidden />}
                  <span className={i === crumbs.length - 1 ? 'font-medium text-ink-900' : 'text-ink-500'}>{c}</span>
                </span>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <HeaderSearch />
            <Notifications />
            {user && <Avatar initials={user.initials} tone={user.tone} />}
          </div>
        </header>
        <main key={pathname} className="page stagger flex flex-col gap-6 p-4 sm:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

const TABS = [
  { to: '/handover', label: 'Start here', end: true },
  { to: '/handover/ask', label: 'Ask' },
  { to: '/handover/checklist', label: 'Checklist' },
  { to: '/handover/ownership', label: 'Ownership map' },
  { to: '/handover/timeline', label: 'Timeline' },
];

export function RecipientShell() {
  const location = useLocation();
  const { user, signOut } = useAuth();
  const { view } = useCurrentShare();
  const navigate = useNavigate();
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-line bg-card">
        <div className="flex items-center justify-between gap-4 px-4 sm:px-10">
          <div className="flex min-w-0 items-center gap-7">
            <Link to="/handover" className="py-3"><Logo /></Link>
            <nav aria-label="Handover sections" className="hidden gap-1 md:flex">
              {TABS.map(t => (
                <NavLink
                  key={t.to}
                  to={t.to}
                  end={t.end}
                  viewTransition
                  className={({ isActive }) => cn('border-b-2 px-3 py-5 text-small font-medium', isActive ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}
                >
                  {t.label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            {view.data && <span className="hidden items-center gap-1.5 text-caption text-ink-500 sm:flex" title={`sha256 ${view.data.sha256}`}><ShieldCheck className="size-3.5 text-verified" aria-hidden />Approved v{view.data.versionNo} · {fmtDate(view.data.approvedAt)}</span>}
            {user && <SensitivityChip level={user.clearance} />}
            {user && (
              <button type="button" aria-label="Sign out" title="Sign out" onClick={() => { signOut(); navigate('/login'); }} className="cursor-pointer">
                <Avatar initials={user.initials} tone={user.tone} />
              </button>
            )}
          </div>
        </div>
        <nav aria-label="Handover sections" className="flex gap-1 overflow-x-auto px-2 md:hidden">
          {TABS.map(t => (
            <NavLink key={t.to} to={t.to} end={t.end} viewTransition className={({ isActive }) => cn('border-b-2 px-3 py-2.5 text-small font-medium whitespace-nowrap', isActive ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500')}>
              {t.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main key={location.pathname} className="page stagger mx-auto flex max-w-[1120px] flex-col gap-6 px-4 py-6 sm:py-8">
        <Outlet />
      </main>
    </div>
  );
}

export function FocusShell({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 flex flex-wrap items-center justify-between gap-3 border-b border-line bg-card px-4 py-3.5 sm:px-10">
        <div className="flex items-center gap-4">
          <Logo />
          <span className="hidden h-6 w-px bg-line sm:block" aria-hidden />
          <span className="hidden text-small font-medium text-ink-700 sm:block">{title}</span>
        </div>
        {right}
      </header>
      <main className="animate-rise mx-auto max-w-[1120px] px-4 py-6 sm:py-8">{children}</main>
    </div>
  );
}
