import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppShell, RecipientShell } from '@/components/layout/shells';
import { AuthProvider, HOME, useAuth } from '@/lib/auth';
import type { Role } from '@/data/types';
import { InvitePage, LoginPage } from '@/pages/auth/AuthPages';
import { ChangePasswordPage, SignupWizard } from '@/pages/auth/Onboarding';
import Dashboard from '@/pages/lead/Dashboard';
import Sources from '@/pages/lead/Sources';
import Reconstruct from '@/pages/lead/Reconstruct';
import Review from '@/pages/lead/Review';
import Conflicts from '@/pages/lead/Conflicts';
import Timeline from '@/pages/lead/Timeline';
import ViewAs from '@/pages/lead/ViewAs';
import { Approval, Share } from '@/pages/lead/ApprovalShare';
import ExitInterview from '@/pages/leaver/ExitInterview';
import { ChecklistPage, Ownership, RecipientTimeline, StartHere } from '@/pages/recipient/RecipientPages';
import { AuditLog, ProjectsPage, TeamPage, UsersGroups } from '@/pages/admin/AdminPages';
import { DisconnectedPage, WorkspaceSettings } from '@/pages/admin/WorkspaceSettings';
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

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupWizard />} />
            <Route path="/connect-database" element={<DatabaseGuide />} />
            <Route path="/disconnected" element={<DisconnectedPage />} />
            <Route path="/change-password" element={<ChangePasswordPage />} />
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
              <Route path="admin/users" element={<RequireAdmin><UsersGroups /></RequireAdmin>} />
              <Route path="admin/projects" element={<RequireAdmin><ProjectsPage /></RequireAdmin>} />
              <Route path="admin/audit" element={<RequireAdmin><AuditLog /></RequireAdmin>} />
              <Route path="admin/workspace" element={<RequireAdmin><WorkspaceSettings /></RequireAdmin>} />
            </Route>

            <Route path="/interview" element={<RequireRole roles={['leaver']}><ExitInterview /></RequireRole>} />

            <Route path="/handover" element={<RequireRole roles={['recipient']}><RecipientShell /></RequireRole>}>
              <Route index element={<StartHere />} />
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
