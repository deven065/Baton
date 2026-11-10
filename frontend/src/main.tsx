import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode, useEffect, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppShell, RecipientShell } from '@/components/layout/shells';
import { AuthProvider, HOME, useAuth } from '@/lib/auth';
import type { Role } from '@/data/types';
import { ForgotPasswordPage, InvitePage, LoginPage, ResetPasswordPage } from '@/pages/auth/AuthPages';
import { ChangePasswordPage, SignupWizard } from '@/pages/auth/Onboarding';
import { AccountPage } from '@/pages/auth/Account';
import Dashboard from '@/pages/lead/Dashboard';
import Sources from '@/pages/lead/Sources';
import Reconstruct from '@/pages/lead/Reconstruct';
import Review from '@/pages/lead/Review';
import Conflicts from '@/pages/lead/Conflicts';
import Timeline from '@/pages/lead/Timeline';
import ViewAs from '@/pages/lead/ViewAs';
import { Approval, Share } from '@/pages/lead/ApprovalShare';
import { RiskRadar, Successors } from '@/pages/lead/Insights';
import ExitInterview from '@/pages/leaver/ExitInterview';
import { ChecklistPage, Ownership, RecipientTimeline, StartHere } from '@/pages/recipient/RecipientPages';
import { AskPage } from '@/pages/recipient/AskPage';
import { AuditLog, ProjectsPage, TeamPage, UsersGroups } from '@/pages/admin/AdminPages';
import { DisconnectedPage, WorkspaceSettings } from '@/pages/admin/WorkspaceSettings';
import { AiSettings } from '@/pages/admin/AiSettings';
import { Billing, Connections } from '@/pages/admin/ConnectionsBilling';
import { DatabaseGuide } from '@/pages/public/DatabaseGuide';
import './index.css';

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false } } });

/** Lets a role in, or sends the user to their own home (UI convenience; the API enforces access). */
function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user, session, loading } = useAuth();
  if (loading) return <p className="grid min-h-dvh place-items-center text-small text-ink-500" role="status">Loading your workspace…</p>;
  if (!user) return <Navigate to="/login" replace />;
  if (session?.mustChangePassword) return <Navigate to="/change-password" replace />;
  // Organisation admins may use the workspace shell whatever their project role.
  if (session?.isAdmin && roles.includes('admin')) return <>{children}</>;
  if (!roles.includes(user.appRole)) return <Navigate to={HOME[user.appRole]} replace />;
  return <>{children}</>;
}

/** Organisation admins only (by their org role, not their project role). */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  if (!session?.isAdmin) return <Navigate to={session ? HOME[session.role] : '/login'} replace />;
  return <>{children}</>;
}

function Home() {
  const { user, loading } = useAuth();
  if (loading) return null;
  return <Navigate to={user ? HOME[user.appRole] : '/login'} replace />;
}

/**
 * Fades out the first-load splash from index.html once the session is known. The first visit in a browser
 * session keeps it up long enough for the logo animation to finish; later loads just fade.
 */
// Read once per page load (not inside an effect, which React may run twice).
const SPLASH_SEEN = (() => {
  try {
    const seen = sessionStorage.getItem('baton.splash') === '1';
    sessionStorage.setItem('baton.splash', '1');
    return seen;
  } catch {
    return false;
  }
})();

function SplashGate() {
  const { loading } = useAuth();
  useEffect(() => {
    if (loading) return;
    const splash = document.getElementById('splash');
    if (!splash) return;
    const wait = SPLASH_SEEN ? 0 : Math.max(0, 1800 - performance.now());
    const t = window.setTimeout(() => {
      splash.classList.add('out');
      window.setTimeout(() => splash.remove(), 600);
    }, wait);
    return () => window.clearTimeout(t);
  }, [loading]);
  return null;
}

/**
 * Slides list items in as they scroll into view (each container marked .reveal-list). Items already on screen
 * slide in straight away; the rest as they appear. Without IntersectionObserver everything simply shows.
 */
function setupReveal() {
  const show = (el: Element) => el.classList.add('revealed');
  if (!('IntersectionObserver' in window)) {
    new MutationObserver(() => document.querySelectorAll('.reveal-list > *:not(.revealed)').forEach(show))
      .observe(document.body, { childList: true, subtree: true });
    return;
  }
  const io = new IntersectionObserver(entries => {
    for (const e of entries) {
      if (e.isIntersecting) { show(e.target); io.unobserve(e.target); }
    }
  }, { rootMargin: '0px 0px -6% 0px', threshold: 0.06 });
  const watched = new WeakSet<Element>();
  const scan = () => document.querySelectorAll('.reveal-list > *:not(.revealed)').forEach(el => {
    if (!watched.has(el)) { watched.add(el); io.observe(el); }
  });
  let queued = false;
  new MutationObserver(() => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => { queued = false; scan(); });
  }).observe(document.body, { childList: true, subtree: true });
  scan();
}
setupReveal();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <SplashGate />
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupWizard />} />
            <Route path="/connect-database" element={<DatabaseGuide />} />
            <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            <Route path="/reset-password" element={<ResetPasswordPage />} />
            <Route path="/disconnected" element={<DisconnectedPage />} />
            <Route path="/change-password" element={<ChangePasswordPage />} />
            <Route path="/account" element={<AccountPage />} />
            <Route path="/invite" element={<InvitePage />} />

            <Route path="/app" element={<RequireRole roles={['lead', 'admin']}><AppShell /></RequireRole>}>
              <Route index element={<RequireRole roles={['lead']}><Dashboard /></RequireRole>} />
              <Route path="sources" element={<RequireRole roles={['lead']}><Sources /></RequireRole>} />
              <Route path="reconstruct" element={<RequireRole roles={['lead']}><Reconstruct /></RequireRole>} />
              <Route path="review" element={<RequireRole roles={['lead']}><Review /></RequireRole>} />
              <Route path="conflicts" element={<RequireRole roles={['lead']}><Conflicts /></RequireRole>} />
              <Route path="timeline" element={<RequireRole roles={['lead']}><Timeline /></RequireRole>} />
              <Route path="view-as" element={<RequireRole roles={['lead']}><ViewAs /></RequireRole>} />
              <Route path="approval" element={<RequireRole roles={['lead']}><Approval /></RequireRole>} />
              <Route path="share" element={<RequireRole roles={['lead']}><Share /></RequireRole>} />
              <Route path="team" element={<RequireRole roles={['lead']}><TeamPage /></RequireRole>} />
              <Route path="risk" element={<RequireRole roles={['lead']}><RiskRadar /></RequireRole>} />
              <Route path="successors" element={<RequireRole roles={['lead']}><Successors /></RequireRole>} />
              <Route path="admin/users" element={<RequireAdmin><UsersGroups /></RequireAdmin>} />
              <Route path="admin/projects" element={<RequireAdmin><ProjectsPage /></RequireAdmin>} />
              <Route path="admin/audit" element={<RequireAdmin><AuditLog /></RequireAdmin>} />
              <Route path="admin/workspace" element={<RequireAdmin><WorkspaceSettings /></RequireAdmin>} />
              <Route path="admin/ai" element={<RequireAdmin><AiSettings /></RequireAdmin>} />
              <Route path="admin/connections" element={<RequireAdmin><Connections /></RequireAdmin>} />
              <Route path="admin/billing" element={<RequireAdmin><Billing /></RequireAdmin>} />
            </Route>

            <Route path="/interview" element={<RequireRole roles={['leaver']}><ExitInterview /></RequireRole>} />

            <Route path="/handover" element={<RequireRole roles={['recipient']}><RecipientShell /></RequireRole>}>
              <Route index element={<StartHere />} />
              <Route path="ask" element={<AskPage />} />
              <Route path="checklist" element={<ChecklistPage />} />
              <Route path="ownership" element={<Ownership />} />
              <Route path="timeline" element={<RecipientTimeline />} />
            </Route>

            <Route path="*" element={<Home />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
