import { useQueryClient } from '@tanstack/react-query';
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { sens, type HandoverDto, type ProjectDto, type UserDto } from '@/data/live';
import type { Person, Role } from '@/data/types';
import { api, getToken, onUnauthenticated, setToken } from './http';

export interface Session {
  user: Person;
  role: Role;
  project: ProjectDto | null;
  workspace: { slug: string; name: string };
  /** The handover this person works on: the lead's latest one, or the leaver's own. */
  handover: HandoverDto | null;
  /** Organisation admin (manages people, groups and projects), whatever their project role. */
  isAdmin: boolean;
  /** Set after an admin created or reset the account: the person must pick their own password first. */
  mustChangePassword: boolean;
}

export interface WorkspaceRegistration {
  slug: string; name: string;
  database: { url: string; username?: string; password?: string };
  admin: { displayName: string; email: string; password: string };
}

interface Auth {
  session: Session | null;
  /** Shortcut used by most screens. */
  user: Person | null;
  loading: boolean;
  signIn: (workspace: string, email: string, password: string) => Promise<Session>;
  /** Creates a company workspace on its own database and signs its first admin in. */
  register: (r: WorkspaceRegistration) => Promise<Session>;
  /** Re-reads the session (after a password change, a new handover or a membership change). */
  refresh: () => Promise<Session | null>;
  signOut: () => void;
}

const Ctx = createContext<Auth | null>(null);

function initials(name: string) {
  return name.split(/\s+/).filter(Boolean).map(w => w[0]).join('').slice(0, 2).toUpperCase();
}
function tone(id: string) {
  let h = 0;
  for (const ch of id) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return h % 6;
}

/** Works out what this user does in Baton from what the API lets them see. */
async function resolveSession(me: UserDto): Promise<Session> {
  const projects = await api<ProjectDto[]>('/api/projects');
  const project = projects.find(p => p.myRole === 'PROJECT_LEAD') ?? projects[0] ?? null;
  let role: Role = me.orgRole === 'ADMIN' && !project ? 'admin' : 'recipient';
  let handover: HandoverDto | null = null;
  if (project) {
    // Leads see every handover; members only the ones where they are the person leaving.
    const list = await api<HandoverDto[]>(`/api/projects/${project.id}/handovers`);
    const latest = [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0] ?? null;
    if (project.myRole === 'PROJECT_LEAD') { role = 'lead'; handover = latest; }
    else if (latest) { role = 'leaver'; handover = latest; }
  }
  const user: Person = {
    id: me.id, name: me.displayName, initials: initials(me.displayName), tone: tone(me.id), email: me.email,
    clearance: sens(me.clearance), groups: me.groups, appRole: role,
    role: { lead: 'Project lead', leaver: 'Departing member', recipient: 'Team member', admin: 'Organisation admin' }[role],
  };
  return { user, role, project, handover, workspace: me.workspace, isAdmin: me.orgRole === 'ADMIN', mustChangePassword: me.mustChangePassword };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(!!getToken());

  const signOut = useCallback(() => {
    setToken(null);
    setSession(null);
    qc.clear();
  }, [qc]);

  useEffect(() => onUnauthenticated(signOut), [signOut]);

  // Restore a saved session on reload.
  useEffect(() => {
    if (!getToken()) return;
    api<UserDto>('/api/me')
      .then(resolveSession)
      .then(setSession)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  const signIn = useCallback(async (workspace: string, email: string, password: string) => {
    const res = await api<{ token: string; user: UserDto }>('/api/auth/login', { method: 'POST', json: { workspace, email, password } });
    try { localStorage.setItem(WORKSPACE_KEY, workspace); } catch { /* private mode */ }
    setToken(res.token);
    qc.clear();
    const s = await resolveSession(res.user);
    setSession(s);
    return s;
  }, [qc]);

  const register = useCallback(async (r: WorkspaceRegistration) => {
    const res = await api<{ token: string; user: UserDto }>('/api/workspaces', { method: 'POST', json: r });
    try { localStorage.setItem(WORKSPACE_KEY, r.slug); } catch { /* private mode */ }
    setToken(res.token);
    qc.clear();
    const s = await resolveSession(res.user);
    setSession(s);
    return s;
  }, [qc]);

  const refresh = useCallback(async () => {
    if (!getToken()) return null;
    const s = await resolveSession(await api<UserDto>('/api/me'));
    setSession(s);
    return s;
  }, []);

  return <Ctx.Provider value={{ session, user: session?.user ?? null, loading, signIn, register, refresh, signOut }}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAuth outside AuthProvider');
  return v;
}

/** Session fields most screens need; only rendered inside guarded routes. */
export function useSession() {
  const { session } = useAuth();
  if (!session) throw new Error('No session');
  return session;
}

export const HOME: Record<Role, string> = {
  lead: '/app',
  leaver: '/interview',
  recipient: '/handover',
  admin: '/app/admin/users',
};

/** Seeded demo accounts (README "Run locally"). Shown on the sign-in page only when VITE_DEMO_PASSWORD is set. */
export const DEMO_ACCOUNTS = [
  { email: 'neha@annapurna.test', name: 'Neha Kulkarni', role: 'Project lead', clearance: 'Restricted' as const },
  { email: 'priya@annapurna.test', name: 'Priya Sharma', role: 'Departing member', clearance: 'Confidential' as const },
  { email: 'arjun@annapurna.test', name: 'Arjun Mehta', role: 'Incoming engineer', clearance: 'Confidential' as const },
  { email: 'rahul@annapurna.test', name: 'Rahul Desai', role: 'Incoming design', clearance: 'Internal' as const },
  { email: 'admin@annapurna.test', name: 'Org Admin', role: 'Organisation admin', clearance: 'Internal' as const },
];
export const DEMO_PASSWORD = import.meta.env.VITE_DEMO_PASSWORD as string | undefined;
export const DEMO_WORKSPACE = 'demo';

const WORKSPACE_KEY = 'baton.workspace';
/** The workspace this browser last signed in to, to pre-fill the sign-in form. */
export function lastWorkspace(): string {
  try { return localStorage.getItem(WORKSPACE_KEY) ?? ''; } catch { return ''; }
}
