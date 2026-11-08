import {
  AlertTriangle, FolderKanban, UsersRound, Bell, CheckSquare, ChevronDown, ChevronRight, Clock, Eye, FileText, Home, Layers, LogOut, Menu, Plug, Search, Share2, ShieldCheck, Users, X,
  type LucideIcon,
} from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { SensitivityChip } from '@/components/ui/badge';
import { useSummary } from '@/data/live';
import { Avatar, DemoNotice, Kbd, Logo } from '@/components/ui/misc';
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
  { to: '/app/share', label: 'Share', icon: Share2, demo: true },
];
const NAV_ADMIN: Nav[] = [
  { to: '/app/admin/users', label: 'People & groups', icon: Users },
  { to: '/app/admin/projects', label: 'Projects', icon: FolderKanban },
  { to: '/app/admin/audit', label: 'Audit log', icon: FileText },
];
const NAV_TEAM: Nav = { to: '/app/team', label: 'Team', icon: UsersRound };

const PAGE: Record<string, string> = {
  '/app': 'Dashboard', '/app/sources': 'Sources', '/app/reconstruct': 'Reconstruct', '/app/review': 'Review',
  '/app/conflicts': 'Conflicts', '/app/timeline': 'Timeline', '/app/view-as': 'View as', '/app/approval': 'Approval',
  '/app/share': 'Share', '/app/admin/users': 'People & groups', '/app/admin/projects': 'Projects',
  '/app/admin/audit': 'Audit log', '/app/team': 'Team',
};

function NavItem({ item, onClick }: { item: Nav; onClick?: () => void }) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onClick}
      className={({ isActive }) =>
        cn('flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-small font-medium transition-colors', isActive ? 'bg-brand-50 text-brand-700' : 'text-ink-700 hover:bg-canvas')
      }
    >
      <Icon className="size-[18px] text-ink-500" aria-hidden />
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
      <Link to="/app" className="px-2 pt-1 pb-4"><Logo /></Link>
      <button type="button" className="flex items-center gap-2.5 rounded-xl border border-line bg-canvas p-2.5 text-left">
        <span className="grid size-8 place-items-center rounded-lg bg-brand-100 text-brand-700"><Layers className="size-4" aria-hidden /></span>
        <span className="flex-1">
          <span className="block text-small font-medium">{session?.project?.name ?? 'No project'}</span>
          <span className="block text-caption text-ink-500">{session?.workspace.name ?? 'Workspace'}</span>
        </span>
        <ChevronDown className="size-3.5 text-ink-400" aria-hidden />
      </button>
      <nav aria-label="Main" className="flex flex-col gap-1">
        {!isAdmin && (
          <>
            <p className="overline px-2.5 pt-4 pb-1.5 text-ink-400">Handover · {session?.handover?.departingName ?? 'none yet'}</p>
            {NAV_MAIN.map(n => <NavItem key={n.to} item={{ ...n, count: counts[n.to] }} onClick={onNavigate} />)}
            {user?.appRole === 'lead' && <NavItem item={NAV_TEAM} onClick={onNavigate} />}
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

export function AppShell() {
  const { user, session } = useAuth();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const project = session?.project?.name ?? session?.workspace.name ?? 'Workspace';
  const inHandover = !['/app', '/app/team'].includes(pathname) && !pathname.startsWith('/app/admin');
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
            <label className="hidden w-[300px] items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 md:flex">
              <Search className="size-4 text-ink-400" aria-hidden />
              <input placeholder="Search claims, sources, people" aria-label="Search" className="w-full bg-transparent text-small outline-none placeholder:text-ink-400" />
              <Kbd>/</Kbd>
            </label>
            <button type="button" aria-label="Notifications" className="rounded-lg border border-line p-2 text-ink-700 hover:bg-canvas cursor-pointer"><Bell className="size-4" /></button>
            {user && <Avatar initials={user.initials} tone={user.tone} />}
          </div>
        </header>
        <main className="flex flex-col gap-6 p-4 sm:p-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}

const TABS = [
  { to: '/handover', label: 'Start here', end: true },
  { to: '/handover/checklist', label: 'Checklist' },
  { to: '/handover/ownership', label: 'Ownership map' },
  { to: '/handover/timeline', label: 'Timeline' },
];

export function RecipientShell() {
  const { user, signOut } = useAuth();
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
                  className={({ isActive }) => cn('border-b-2 px-3 py-5 text-small font-medium', isActive ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500 hover:text-ink-900')}
                >
                  {t.label}
                </NavLink>
              ))}
            </nav>
          </div>
          <div className="flex items-center gap-3">
            <span className="hidden items-center gap-1.5 text-caption text-ink-500 sm:flex"><ShieldCheck className="size-3.5 text-verified" aria-hidden />Approved v2 · 9 Nov 2026</span>
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
            <NavLink key={t.to} to={t.to} end={t.end} className={({ isActive }) => cn('border-b-2 px-3 py-2.5 text-small font-medium whitespace-nowrap', isActive ? 'border-brand-600 text-brand-700' : 'border-transparent text-ink-500')}>
              {t.label}
            </NavLink>
          ))}
        </nav>
      </header>
      <main className="mx-auto flex max-w-[1120px] flex-col gap-6 px-4 py-6 sm:py-8">
        <DemoNotice what="The recipient handover (shares of an approved version) arrives with milestone M5. These pages show sample content." />
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
      <main className="mx-auto max-w-[1120px] px-4 py-6 sm:py-8">{children}</main>
    </div>
  );
}
