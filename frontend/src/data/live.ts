// Live data from the Spring Boot API. DTO shapes mirror the backend records (see root README "API").
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/http';
import type { Sensitivity, Status } from './types';

// ───────────── DTOs ─────────────
export type SensitivityDto = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
export type ClaimStatusDto = 'VERIFIED' | 'SINGLE_SOURCE' | 'INFERRED' | 'CONFLICTING' | 'MISSING_EVIDENCE' | 'STALE';
export type ReviewStateDto = 'PENDING' | 'ACCEPTED' | 'EDITED' | 'REJECTED' | 'NEEDS_INFO';
export type HandoverStatusDto = 'DRAFT' | 'RECONSTRUCTING' | 'IN_REVIEW' | 'APPROVED' | 'SHARED' | 'ARCHIVED';
export type SourceTypeDto = 'GITHUB' | 'SLACK' | 'GOOGLE_DRIVE' | 'TASK_CSV' | 'DOCUMENT' | 'MEETING_NOTES' | 'MEETING_RECORDING' | 'CHAT_EXPORT' | 'TESTIMONY';

export interface UserDto {
  id: string; email: string; displayName: string; orgRole: 'ADMIN' | 'MEMBER'; clearance: SensitivityDto; groups: string[];
  mustChangePassword: boolean;
  /** The company workspace this session belongs to (each has its own database). */
  workspace: { slug: string; name: string };
}
export interface ProjectDto { id: string; name: string; description: string; myRole: 'PROJECT_LEAD' | 'MEMBER' }
export interface MemberDto { userId: string; displayName: string; role: 'PROJECT_LEAD' | 'MEMBER'; clearance: SensitivityDto }
export interface ProjectDetailDto extends ProjectDto { members: MemberDto[] }
export interface HandoverDto {
  id: string; projectId: string; departingUserId: string; departingName: string; title: string;
  scenario: 'PLANNED' | 'SUDDEN' | 'SPLIT'; status: HandoverStatusDto; currentVersionNo: number | null; createdAt: string; updatedAt: string;
}
export interface SourceDto {
  id: string; type: SourceTypeDto; title: string; sensitivity: SensitivityDto; aclGroups: string[];
  status: 'PENDING' | 'SYNCING' | 'READY' | 'FAILED'; documentCount: number; chunkCount: number; redactionCount: number;
  lastError: string | null; lastSyncedAt: string | null; createdAt: string; config: Record<string, unknown>;
}
export interface EvidenceDto {
  id: string; chunkId: string; quote: string; matchScore: number; start: number | null; end: number | null;
  source: { type: SourceTypeDto; sourceTitle: string; title: string; author: string | null; date: string | null; url: string | null };
  /** Meeting recordings: the source to play and the second the quoted words were said. */
  sourceId?: string; atSeconds?: number | null;
}
export interface ClaimDto {
  id: string; type: string; version: number; title: string; body: string | null; attributes: Record<string, unknown>;
  subject: string | null; status: ClaimStatusDto; confidence: number; reasons: string[]; sensitivity: SensitivityDto;
  aclGroups: string[]; critical: boolean; reviewState: ReviewStateDto; origin: 'EXTRACTED' | 'REPO_SIGNAL' | 'TESTIMONY' | 'HUMAN';
  flags: string[]; evidence: EvidenceDto[]; links: { kind: string; claimId: string; resolved: boolean }[];
  unverifiedCitations: number; createdAt: string; updatedAt: string;
}
export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface GapDto {
  id: string; kind: 'MISSING_OWNER' | 'MISSING_EVIDENCE' | 'CONFLICT' | 'MISSING_CHECKLIST' | 'OTHER'; question: string; priority: number;
  status: 'OPEN' | 'ANSWERED' | 'SKIPPED' | 'UNANSWERED'; claimId: string | null; relatedClaimId: string | null; answer: string | null; answeredAt: string | null;
}
export interface TimelineDto { at: string; kind: 'DECISION' | 'CHANGE' | 'OPEN_DISCUSSION' | 'RISK'; title: string; claimId: string | null; status: ClaimStatusDto }
export interface SummaryDto {
  total: number; byStatus: Partial<Record<ClaimStatusDto, number>>; byType: Record<string, number>;
  byReviewState: Partial<Record<ReviewStateDto, number>>; critical: number; openGaps: number;
}
export interface RunDto {
  id: string; jobId: string; status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED'; stage: string;
  stats: Record<string, unknown>; warnings: string[]; promptVersion: string; model: string; error: string | null; startedAt: string; finishedAt: string | null;
}
export interface JobProgress { stage: string; percent: number; message: string; counts: Record<string, number> }
export interface JobDto { id: string; kind: string; status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'; progress: JobProgress | null; attempts: number; lastError: string | null }
export interface RevisionDto { version: number; actorId: string; actorName: string; before: Record<string, unknown>; after: Record<string, unknown>; reason: string | null; at: string }
export interface PreviewDto { as: { id: string; displayName: string; clearance: SensitivityDto; groups: string[] }; visibleCount: number; hiddenFromThemCount: number; claims: ClaimDto[] }

// ───────────── mapping to UI vocabulary ─────────────
export const STATUS: Record<ClaimStatusDto, Status> = {
  VERIFIED: 'verified', SINGLE_SOURCE: 'single', INFERRED: 'inferred', CONFLICTING: 'conflicting', MISSING_EVIDENCE: 'missing', STALE: 'stale',
};
export const sens = (s: SensitivityDto): Sensitivity => (s[0] + s.slice(1).toLowerCase()) as Sensitivity;
export const CLAIM_TYPE_LABEL: Record<string, string> = {
  DECISION: 'Decision', OPEN_WORK: 'Open work', OWNER: 'Owner', RISK: 'Risk', DEPENDENCY: 'Dependency', BLOCKER: 'Blocker',
  RECURRING_ISSUE: 'Recurring issue', CONTACT: 'Contact', OPEN_QUESTION: 'Open question', PROCESS: 'Process', ACCESS: 'Access to transfer',
};
export const typeLabel = (t: string) => CLAIM_TYPE_LABEL[t] ?? t;
export const SOURCE_TYPE_LABEL: Record<SourceTypeDto, string> = {
  GITHUB: 'GitHub', SLACK: 'Slack', GOOGLE_DRIVE: 'Google Drive', TASK_CSV: 'Task board', DOCUMENT: 'Document', MEETING_NOTES: 'Meeting notes', MEETING_RECORDING: 'Meeting recording', CHAT_EXPORT: 'Chat export', TESTIMONY: 'Testimony',
};
/** Recipient-facing wording for an evidence status. */
export const STATUS_LABEL: Record<ClaimStatusDto, string> = {
  VERIFIED: 'Verified', SINGLE_SOURCE: 'Single source', INFERRED: 'Likely, not confirmed', CONFLICTING: 'Conflicting', MISSING_EVIDENCE: 'Missing evidence', STALE: 'Stale',
};
export const HANDOVER_STAGE: Record<HandoverStatusDto, { n: number; label: string }> = {
  DRAFT: { n: 1, label: 'Connect · 1 of 5' }, RECONSTRUCTING: { n: 2, label: 'Reconstruct · 2 of 5' }, IN_REVIEW: { n: 4, label: 'Review · 4 of 5' },
  APPROVED: { n: 4, label: 'Approved · 4 of 5' }, SHARED: { n: 5, label: 'Handover · 5 of 5' }, ARCHIVED: { n: 5, label: 'Archived' },
};
export function evidenceLabel(e: EvidenceDto) {
  const d = e.source.date ? new Date(e.source.date).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
  return [e.source.sourceTitle, e.source.title !== e.source.sourceTitle ? e.source.title : null, d].filter(Boolean).join(' · ');
}
/** For a quote from a meeting recording with its audio kept: where to play it. */
export const playOf = (e: EvidenceDto) => e.sourceId && e.atSeconds != null && e.source.type === 'MEETING_RECORDING'
  ? { sourceId: e.sourceId, at: e.atSeconds } : null;
export const fmtDate = (iso: string) => new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
export const fmtDateTime = (iso: string) => new Date(iso).toLocaleString('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });

// ───────────── queries ─────────────
export const qk = {
  projects: ['projects'] as const,
  project: (id: string) => ['project', id] as const,
  handovers: (p: string) => ['handovers', p] as const,
  sources: (p: string) => ['sources', p] as const,
  summary: (h: string) => ['summary', h] as const,
  claims: (h: string) => ['claims', h] as const,
  claim: (id: string) => ['claim', id] as const,
  revisions: (id: string) => ['revisions', id] as const,
  gaps: (h: string) => ['gaps', h] as const,
  timeline: (h: string) => ['timeline', h] as const,
  run: (h: string) => ['run', h] as const,
  preview: (h: string, as: string) => ['preview', h, as] as const,
};

const on = (v: string | null | undefined) => !!v;

export const useProjects = () => useQuery({ queryKey: qk.projects, queryFn: () => api<ProjectDto[]>('/api/projects') });
export const useProject = (id?: string | null) => useQuery({ queryKey: qk.project(id ?? ''), queryFn: () => api<ProjectDetailDto>(`/api/projects/${id}`), enabled: on(id) });
export const useHandoverList = (projectId?: string | null) => useQuery({ queryKey: qk.handovers(projectId ?? ''), queryFn: () => api<HandoverDto[]>(`/api/projects/${projectId}/handovers`), enabled: on(projectId) });
export const useSourceList = (projectId?: string | null) => useQuery({
  queryKey: qk.sources(projectId ?? ''),
  queryFn: () => api<SourceDto[]>(`/api/projects/${projectId}/sources`),
  enabled: on(projectId),
  // Keep polling while anything is still ingesting.
  refetchInterval: q => (q.state.data?.some(s => s.status === 'PENDING' || s.status === 'SYNCING') ? 2000 : false),
});
export const useSummary = (h?: string | null) => useQuery({ queryKey: qk.summary(h ?? ''), queryFn: () => api<SummaryDto>(`/api/handovers/${h}/summary`), enabled: on(h) });
export const useClaimList = (h?: string | null) => useQuery({ queryKey: qk.claims(h ?? ''), queryFn: async () => (await api<Page<ClaimDto>>(`/api/handovers/${h}/claims?size=200`)).items, enabled: on(h) });
export const useClaim = (id?: string | null) => useQuery({ queryKey: qk.claim(id ?? ''), queryFn: () => api<ClaimDto>(`/api/claims/${id}`), enabled: on(id) });
export const useRevisions = (id?: string | null) => useQuery({ queryKey: qk.revisions(id ?? ''), queryFn: () => api<RevisionDto[]>(`/api/claims/${id}/revisions`), enabled: on(id) });
export const useGapList = (h?: string | null) => useQuery({ queryKey: qk.gaps(h ?? ''), queryFn: () => api<GapDto[]>(`/api/handovers/${h}/gaps`), enabled: on(h) });
export const useTimelineList = (h?: string | null) => useQuery({ queryKey: qk.timeline(h ?? ''), queryFn: () => api<TimelineDto[]>(`/api/handovers/${h}/timeline`), enabled: on(h) });
export const useLatestRun = (h?: string | null) => useQuery({
  queryKey: qk.run(h ?? ''),
  queryFn: async () => { try { return await api<RunDto>(`/api/handovers/${h}/runs/latest`); } catch (e) { if ((e as { status?: number }).status === 404) return null; throw e; } },
  enabled: on(h),
});
export const usePreview = (h?: string | null, as?: string | null) => useQuery({ queryKey: qk.preview(h ?? '', as ?? ''), queryFn: () => api<PreviewDto>(`/api/handovers/${h}/preview?as=${as}`), enabled: on(h) && on(as) });

// ───────────── mutations ─────────────
function useInvalidateHandover(handoverId?: string | null) {
  const qc = useQueryClient();
  return () => {
    if (!handoverId) return;
    for (const k of [qk.claims(handoverId), qk.summary(handoverId), qk.gaps(handoverId), qk.timeline(handoverId), ['claim'], ['revisions'], ['preview']]) {
      qc.invalidateQueries({ queryKey: k });
    }
  };
}

export function usePatchClaim(handoverId?: string | null) {
  const invalidate = useInvalidateHandover(handoverId);
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { id: string; version: number; reviewState?: ReviewStateDto; title?: string; reason?: string }) =>
      api<ClaimDto>(`/api/claims/${p.id}`, { method: 'PATCH', json: { version: p.version, reviewState: p.reviewState, title: p.title, reason: p.reason } }),
    onSuccess: c => { qc.setQueryData(qk.claim(c.id), c); invalidate(); },
    onError: invalidate, // e.g. CLAIM_STALE: reload so the next try uses the new version
  });
}

export function useResolveConflictLive(handoverId?: string | null) {
  const invalidate = useInvalidateHandover(handoverId);
  return useMutation({
    mutationFn: (p: { winnerId: string; loserId: string; reason: string }) =>
      api<ClaimDto>(`/api/claims/${p.winnerId}/resolve-conflict`, { method: 'POST', json: { otherClaimId: p.loserId, reason: p.reason } }),
    onSuccess: invalidate,
  });
}

export function useAnswerGapLive(handoverId?: string | null) {
  const invalidate = useInvalidateHandover(handoverId);
  return useMutation({
    mutationFn: (p: { id: string; answer?: string; skip?: boolean; reason?: string; sensitivity?: SensitivityDto }) =>
      p.skip
        ? api<GapDto>(`/api/gaps/${p.id}/skip`, { method: 'POST', json: { reason: p.reason ?? null } })
        : api<GapDto>(`/api/gaps/${p.id}/answer`, { method: 'POST', json: { answer: p.answer, sensitivity: p.sensitivity } }),
    onSuccess: invalidate,
  });
}

export function useStartReconstruct(handoverId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<{ handoverId: string; jobId: string }>(`/api/handovers/${handoverId}/reconstruct`, { method: 'POST' }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['handovers'] }); },
  });
}

/** Deletes a recording's audio; the transcript and every quote stay. */
export function useDeleteRecording(projectId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => api<void>(`/api/sources/${sourceId}/media`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.sources(projectId ?? '') }),
  });
}

export function useCreateSource(projectId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { type: SourceTypeDto; title: string; sensitivity: SensitivityDto; aclGroups: string[]; file?: File; repo?: string; branch?: string; channel?: string; folder?: string; meetingDate?: string }) => {
      const f = new FormData();
      f.set('type', p.type);
      f.set('title', p.title);
      f.set('sensitivity', p.sensitivity);
      p.aclGroups.forEach(g => f.append('aclGroups', g));
      f.set('authorisationAttested', 'true');
      if (p.file) f.set('file', p.file);
      if (p.repo) f.set('repo', p.repo);
      if (p.branch) f.set('branch', p.branch);
      if (p.channel) f.set('channel', p.channel);
      if (p.folder) f.set('folder', p.folder);
      if (p.meetingDate) f.set('meetingDate', p.meetingDate);
      return api<{ source: SourceDto; jobId: string }>(`/api/projects/${projectId}/sources`, { method: 'POST', body: f });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.sources(projectId ?? '') }),
  });
}

export function useSyncSource(projectId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api<unknown>(`/api/sources/${id}/sync`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.sources(projectId ?? '') }),
  });
}

// ───────────── phase 2: company administration ─────────────
export interface AdminUserDto {
  id: string; email: string; displayName: string; orgRole: 'ADMIN' | 'MEMBER'; clearance: SensitivityDto;
  groups: string[]; active: boolean; mustChangePassword: boolean; createdAt: string;
}
export interface GroupDto { id: string; name: string; description: string | null; members: number }
export interface AuditRowDto {
  id: number; at: string; actorId: string | null; actorName: string | null; action: string; entityType: string | null;
  entityId: string | null; projectId: string | null; details: string;
}
export interface AdminProjectDto { id: string; name: string; description: string | null; leads: string[]; members: number; handovers: number; createdAt: string }
export interface TeamMemberDto { userId: string; displayName: string; email: string; role: 'PROJECT_LEAD' | 'MEMBER'; joinedAt: string }
export interface PersonDto { id: string; displayName: string; email: string }

export const qa = {
  users: ['admin', 'users'] as const,
  groups: ['admin', 'groups'] as const,
  audit: (f: AuditFilter) => ['admin', 'audit', f] as const,
  auditActions: ['admin', 'audit-actions'] as const,
  projects: ['admin', 'projects'] as const,
  team: (p: string) => ['team', p] as const,
  directory: ['directory'] as const,
};

export const useAdminUsers = (opts: { enabled?: boolean } = {}) => useQuery({ queryKey: qa.users, queryFn: () => api<AdminUserDto[]>('/api/admin/users'), enabled: opts.enabled ?? true });
export const useGroups = () => useQuery({ queryKey: qa.groups, queryFn: () => api<GroupDto[]>('/api/admin/groups') });
export interface AuditFilter { actions: string[]; actor: string; from: string; to: string; q: string }
export interface AuditActionCountDto { action: string; count: number }

/** Audit events, newest first, 100 at a time; fetchNextPage loads older ones. */
export const useAudit = (f: AuditFilter) => useInfiniteQuery({
  queryKey: qa.audit(f),
  initialPageParam: undefined as number | undefined,
  queryFn: ({ pageParam }) => {
    const p = new URLSearchParams({ limit: '100' });
    if (f.actions.length) p.set('action', f.actions.join(','));
    if (f.actor) p.set('actor', f.actor);
    if (f.from) p.set('from', f.from);
    if (f.to) p.set('to', f.to);
    if (f.q.trim()) p.set('q', f.q.trim());
    if (pageParam) p.set('before', String(pageParam));
    return api<AuditRowDto[]>(`/api/admin/audit?${p}`);
  },
  getNextPageParam: last => (last.length === 100 ? last[last.length - 1].id : undefined),
});
export const useAuditActions = () => useQuery({ queryKey: qa.auditActions, queryFn: () => api<AuditActionCountDto[]>('/api/admin/audit/actions') });
export const useAdminProjects = (opts: { enabled?: boolean } = {}) => useQuery({ queryKey: qa.projects, queryFn: () => api<AdminProjectDto[]>('/api/admin/projects'), enabled: opts.enabled ?? true });
export const useTeam = (projectId?: string | null) => useQuery({ queryKey: qa.team(projectId ?? ''), queryFn: () => api<TeamMemberDto[]>(`/api/projects/${projectId}/team`), enabled: !!projectId });
export const useDirectory = (enabled = true) => useQuery({ queryKey: qa.directory, queryFn: () => api<PersonDto[]>('/api/directory'), enabled });

function useInvalidate(...keys: readonly (readonly unknown[])[]) {
  const qc = useQueryClient();
  return () => keys.forEach(k => qc.invalidateQueries({ queryKey: k as unknown[] }));
}

export function useCreateUser() {
  const inv = useInvalidate(qa.users, ['admin', 'audit'], qa.directory);
  return useMutation({
    mutationFn: (p: { displayName: string; email: string; orgRole: 'ADMIN' | 'MEMBER'; clearance: SensitivityDto; groups: string[] }) =>
      api<{ user: AdminUserDto; temporaryPassword: string; invitedByEmail: boolean }>('/api/admin/users', { method: 'POST', json: p }),
    onSuccess: inv,
  });
}
export function useUpdateUser() {
  const inv = useInvalidate(qa.users, qa.groups, ['admin', 'audit'], qa.directory);
  return useMutation({
    mutationFn: ({ id, ...p }: { id: string; displayName?: string; orgRole?: 'ADMIN' | 'MEMBER'; clearance?: SensitivityDto; groups?: string[]; active?: boolean }) =>
      api<AdminUserDto>(`/api/admin/users/${id}`, { method: 'PATCH', json: p }),
    onSuccess: inv,
  });
}
export function useResetPassword() {
  const inv = useInvalidate(qa.users, ['admin', 'audit']);
  return useMutation({
    mutationFn: (id: string) => api<{ temporaryPassword: string }>(`/api/admin/users/${id}/reset-password`, { method: 'POST' }),
    onSuccess: inv,
  });
}
export function useCreateGroup() {
  const inv = useInvalidate(qa.groups, ['admin', 'audit']);
  return useMutation({ mutationFn: (p: { name: string; description?: string }) => api<GroupDto>('/api/admin/groups', { method: 'POST', json: p }), onSuccess: inv });
}
export function useDeleteGroup() {
  const inv = useInvalidate(qa.groups, ['admin', 'audit']);
  return useMutation({ mutationFn: (id: string) => api<void>(`/api/admin/groups/${id}`, { method: 'DELETE' }), onSuccess: inv });
}
export function useCreateProject() {
  const inv = useInvalidate(qa.projects, ['admin', 'audit'], qk.projects);
  return useMutation({
    mutationFn: (p: { name: string; description?: string; leadUserId: string }) => api<{ id: string; name: string }>('/api/projects', { method: 'POST', json: p }),
    onSuccess: inv,
  });
}
export function useTeamMutations(projectId?: string | null) {
  const inv = useInvalidate(qa.team(projectId ?? ''), qa.projects, ['admin', 'audit'], ['project']);
  const add = useMutation({ mutationFn: (p: { userId: string; role: 'PROJECT_LEAD' | 'MEMBER' }) => api<void>(`/api/projects/${projectId}/members`, { method: 'POST', json: p }), onSuccess: inv });
  const role = useMutation({ mutationFn: (p: { userId: string; role: 'PROJECT_LEAD' | 'MEMBER' }) => api<void>(`/api/projects/${projectId}/members/${p.userId}`, { method: 'PATCH', json: { role: p.role } }), onSuccess: inv });
  const remove = useMutation({ mutationFn: (userId: string) => api<void>(`/api/projects/${projectId}/members/${userId}`, { method: 'DELETE' }), onSuccess: inv });
  return { add, role, remove };
}
export function useCreateHandover(projectId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { departingUserId: string; title: string; scenario: 'PLANNED' | 'SUDDEN' | 'SPLIT' }) =>
      api<HandoverDto>(`/api/projects/${projectId}/handovers`, { method: 'POST', json: p }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['handovers'] }),
  });
}

export function useRenameHandover() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { id: string; title: string }) => api<HandoverDto>(`/api/handovers/${p.id}`, { method: 'PATCH', json: { title: p.title } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['handovers'] }),
  });
}

// ───────────── phase 3: approval, versions, shares, recipient view ─────────────

export interface GateCheckDto { key: string; ok: boolean; blocking: boolean; title: string; detail: string; count: number }
export interface GateDto { canApprove: boolean; checks: GateCheckDto[]; status: HandoverStatusDto; currentVersionNo: number | null }
export interface VersionDto { versionNo: number; sha256: string; approvedBy: string; approvedByName: string; approvedAt: string; claims: number; checklistItems: number }
export interface ChangeDto { kind: 'ADDED' | 'CHANGED' | 'REMOVED'; claimId: string; type: string; before: string | null; after: string | null }
export interface ShareRowDto {
  id: string; recipientId: string; recipientName: string; recipientEmail: string; clearance: SensitivityDto; roleLabel: string;
  versionNo: number; createdAt: string; expiresAt: string; revokedAt: string | null; acknowledgedAt: string | null; lastViewedAt: string | null;
  state: 'SENT' | 'OPENED' | 'ACKNOWLEDGED' | 'EXPIRED' | 'REVOKED';
}
export interface MyShareDto { id: string; handoverTitle: string; projectName: string; departingName: string; versionNo: number; roleLabel: string; expiresAt: string; acknowledgedAt: string | null }
export interface SnapshotEvidence { quote: string; sourceType: SourceTypeDto; source: string; document: string; date: string | null }
export interface SnapshotClaim {
  id: string; type: string; title: string; body: string | null; subject: string | null; status: ClaimStatusDto; confidence: string;
  sensitivity: number; aclGroups: string[]; critical: boolean; reviewState: ReviewStateDto; evidence: SnapshotEvidence[];
}
export interface ChecklistItemDto { id: string; phase: 'DAY1' | 'WEEK1' | 'MONTH1'; position: number; text: string; claimId: string | null; done: boolean }
export interface RecipientViewDto {
  shareId: string; roleLabel: string; expiresAt: string; acknowledgedAt: string | null; versionNo: number; sha256: string;
  approvedAt: string; approvedBy: string; handover: { title: string; scenario: string; departingName: string; project: { name: string; description: string } };
  claims: SnapshotClaim[]; hiddenCount: number; timeline: { at: string; kind: TimelineDto['kind']; title: string; claimId: string }[];
  checklist: ChecklistItemDto[]; previousVersionNo: number | null; changes: ChangeDto[];
}
export const LEVELS: SensitivityDto[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];

export const ql = {
  gate: (h: string) => ['gate', h] as const,
  versions: (h: string) => ['versions', h] as const,
  diff: (h: string, from: number) => ['diff', h, from] as const,
  shares: (h: string) => ['shares', h] as const,
  mine: ['myShares'] as const,
  share: (id: string) => ['share', id] as const,
};

export const useGate = (h?: string | null) => useQuery({ queryKey: ql.gate(h ?? ''), queryFn: () => api<GateDto>(`/api/handovers/${h}/approval`), enabled: on(h) });
export const useVersions = (h?: string | null) => useQuery({ queryKey: ql.versions(h ?? ''), queryFn: () => api<VersionDto[]>(`/api/handovers/${h}/versions`), enabled: on(h) });
export const useDiff = (h?: string | null, from?: number | null) => useQuery({ queryKey: ql.diff(h ?? '', from ?? 0), queryFn: () => api<ChangeDto[]>(`/api/handovers/${h}/diff?from=${from}&to=0`), enabled: on(h) && !!from });
export const useShares = (h?: string | null) => useQuery({ queryKey: ql.shares(h ?? ''), queryFn: () => api<ShareRowDto[]>(`/api/handovers/${h}/shares`), enabled: on(h) });
export const useMyShares = () => useQuery({ queryKey: ql.mine, queryFn: () => api<MyShareDto[]>('/api/shares') });
export const useShareView = (id?: string | null) => useQuery({ queryKey: ql.share(id ?? ''), queryFn: () => api<RecipientViewDto>(`/api/shares/${id}`), enabled: on(id), retry: false });

export function useApprove(h?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<VersionDto>(`/api/handovers/${h}/approve`, { method: 'POST' }),
    onSuccess: () => { for (const k of [ql.gate(h ?? ''), ql.versions(h ?? ''), ['diff', h ?? ''], ['handovers']]) qc.invalidateQueries({ queryKey: k }); },
  });
}
export function useShareMutations(h?: string | null) {
  const qc = useQueryClient();
  const inv = () => { for (const k of [ql.shares(h ?? ''), ql.gate(h ?? ''), ['handovers']]) qc.invalidateQueries({ queryKey: k }); };
  const create = useMutation({
    mutationFn: (p: { recipientId: string; roleLabel: string; expiresAt?: string }) => api<ShareRowDto>(`/api/handovers/${h}/shares`, { method: 'POST', json: p }),
    onSuccess: inv,
  });
  const revoke = useMutation({ mutationFn: (id: string) => api<void>(`/api/shares/${id}/revoke`, { method: 'POST' }), onSuccess: inv });
  return { create, revoke };
}
export function useRecipientMutations(shareId?: string | null) {
  const qc = useQueryClient();
  const inv = () => { qc.invalidateQueries({ queryKey: ql.share(shareId ?? '') }); qc.invalidateQueries({ queryKey: ql.mine }); };
  const check = useMutation({ mutationFn: (p: { itemId: string; done: boolean }) => api<void>(`/api/shares/${shareId}/checklist/${p.itemId}`, { method: 'PUT', json: { done: p.done } }), onSuccess: inv });
  const acknowledge = useMutation({ mutationFn: () => api<{ acknowledgedAt: string }>(`/api/shares/${shareId}/acknowledge`, { method: 'POST' }), onSuccess: inv });
  return { check, acknowledge };
}

// ───────────── phase 4: workspace settings ─────────────

export interface WorkspaceSettingsDto {
  slug: string; name: string; createdAt: string; demo: boolean;
  database: { host: string; port: number; database: string } | null; people: number; projects: number;
}
export const useWorkspaceSettings = () => useQuery({ queryKey: ['workspaceSettings'], queryFn: () => api<WorkspaceSettingsDto>('/api/admin/workspace') });
export const useDisconnectWorkspace = () => useMutation({
  mutationFn: (p: { confirm: string; password: string }) =>
    api<{ recoveryKey: string; disconnectedAt: string }>('/api/admin/workspace/disconnect', { method: 'POST', json: p }),
});

// ───────────── phase 5: bring-your-own AI ─────────────

export type AiProvider = 'GROQ' | 'OPENAI' | 'OPENROUTER' | 'CUSTOM';
export interface AiOverviewDto {
  settings: { configured: boolean; enabled: boolean; provider: AiProvider | null; baseUrl: string | null; model: string | null; keyHint: string | null; updatedAt: string | null; updatedByName: string | null };
  usage: { calls: number; failed: number; tokensIn: number; tokensOut: number; byDay: { day: string; calls: number; tokens: number }[] };
}
export interface AiSaveDto { provider: AiProvider; baseUrl?: string; model?: string; apiKey?: string; enabled?: boolean }
export const useAiSettings = () => useQuery({ queryKey: ['aiSettings'], queryFn: () => api<AiOverviewDto>('/api/admin/ai') });
export function useAiMutations() {
  const qc = useQueryClient();
  const inv = () => qc.invalidateQueries({ queryKey: ['aiSettings'] });
  const save = useMutation({ mutationFn: (p: AiSaveDto) => api('/api/admin/ai', { method: 'PUT', json: p }), onSuccess: inv });
  const remove = useMutation({ mutationFn: () => api<void>('/api/admin/ai', { method: 'DELETE' }), onSuccess: inv });
  const test = useMutation({ mutationFn: (p: AiSaveDto) => api<{ ok: boolean; model: string; latencyMs?: number; message?: string }>('/api/admin/ai/test', { method: 'POST', json: p }) });
  return { save, remove, test };
}

// ───────────── ask the handover, living handovers, progress, export, risk radar ─────────────

export interface CitationDto { claimId: string; type: string; title: string; status: ClaimStatusDto; quote: string | null; source: string | null }
export interface AskAnswerDto { found: boolean; answer: string; mode: 'ai' | 'search'; citations: CitationDto[] }
export interface MyQuestionDto { id: string; question: string; status: GapDto['status']; answer: string | null; askedAt: string; answeredAt: string | null; answeredByLeaver: boolean }
export const useMyQuestions = (shareId?: string | null) => useQuery({ queryKey: ['myQuestions', shareId ?? ''], queryFn: () => api<MyQuestionDto[]>(`/api/shares/${shareId}/questions`), enabled: on(shareId) });
export function useAsk(shareId?: string | null) {
  const qc = useQueryClient();
  const ask = useMutation({ mutationFn: (question: string) => api<AskAnswerDto>(`/api/shares/${shareId}/ask`, { method: 'POST', json: { question } }) });
  const send = useMutation({
    mutationFn: (question: string) => api<MyQuestionDto>(`/api/shares/${shareId}/questions`, { method: 'POST', json: { question } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['myQuestions', shareId ?? ''] }),
  });
  return { ask, send };
}

export interface DriftDto { versionNo: number | null; approvedAt: string | null; status: HandoverStatusDto; changes: ChangeDto[]; sourcesUpdated: number; answersSince: number; recipientsOnOlderVersion: number }
export const useDrift = (h?: string | null) => useQuery({ queryKey: ['drift', h ?? ''], queryFn: () => api<DriftDto>(`/api/handovers/${h}/drift`), enabled: on(h) });
export function useLiving(h?: string | null) {
  const qc = useQueryClient();
  const inv = () => { for (const k of [['drift', h ?? ''], ql.gate(h ?? ''), ql.shares(h ?? ''), ql.versions(h ?? ''), ['handovers'], ['progress', h ?? '']]) qc.invalidateQueries({ queryKey: k }); };
  const reopen = useMutation({ mutationFn: () => api<void>(`/api/handovers/${h}/reopen`, { method: 'POST' }), onSuccess: inv });
  const move = useMutation({ mutationFn: () => api<{ moved: number }>(`/api/handovers/${h}/shares/move-to-latest`, { method: 'POST' }), onSuccess: inv });
  return { reopen, move };
}

export interface PhaseDto { phase: 'DAY1' | 'WEEK1' | 'MONTH1'; done: number; total: number; dueAt: string; overdue: boolean }
export interface SuccessorDto {
  shareId: string; recipientId: string; name: string; roleLabel: string; versionNo: number; sharedAt: string; lastViewedAt: string | null;
  acknowledgedAt: string | null; percent: number; phases: PhaseDto[]; overdueItems: string[]; openQuestions: number; answeredQuestions: number;
}
export const useProgress = (h?: string | null) => useQuery({ queryKey: ['progress', h ?? ''], queryFn: () => api<SuccessorDto[]>(`/api/handovers/${h}/progress`), enabled: on(h) });

export interface VerificationDto { valid: boolean; signatureOk: boolean; filesOk: boolean; matchesRecord: boolean; title: string | null; versionNo: number | null; approvedAt: string | null; problems: string[] }

export interface RiskPersonDto { userId: string; name: string; role: 'PROJECT_LEAD' | 'MEMBER'; share: number; chunks: number; soleTopics: string[]; access: string[]; leaving: boolean; level: 'HIGH' | 'MEDIUM' | 'LOW' }
export interface RiskTopicDto { label: string; sources: string[]; soleSource: string | null; claims: number; lastActivity: string | null; quiet: boolean }
export interface RadarDto { busFactor: number; totalChunks: number; people: number; team: RiskPersonDto[]; topics: RiskTopicDto[]; access: { title: string; subject: string | null; sources: string[] }[]; outsideAuthors: string[]; hasClaims: boolean }
export const useRadar = (projectId?: string | null) => useQuery({ queryKey: ['radar', projectId ?? ''], queryFn: () => api<RadarDto>(`/api/projects/${projectId}/risk`), enabled: on(projectId) });

// ───────────── phases 6 and 7: connections, plans and billing ─────────────

export type IntegrationKind = 'GITHUB' | 'SLACK' | 'GOOGLE_DRIVE';
export interface IntegrationDto { kind: IntegrationKind; connected: boolean; hint: string | null; updatedAt: string | null; updatedByName: string | null }
export const useIntegrations = () => useQuery({ queryKey: ['integrations'], queryFn: () => api<IntegrationDto[]>('/api/integrations') });
export function useIntegrationMutations() {
  const qc = useQueryClient();
  const inv = () => qc.invalidateQueries({ queryKey: ['integrations'] });
  const save = useMutation({ mutationFn: (p: { kind: IntegrationKind; secret: string }) => api<IntegrationDto>(`/api/admin/integrations/${p.kind}`, { method: 'PUT', json: { secret: p.secret } }), onSuccess: inv });
  const remove = useMutation({ mutationFn: (kind: IntegrationKind) => api<void>(`/api/admin/integrations/${kind}`, { method: 'DELETE' }), onSuccess: inv });
  const test = useMutation({ mutationFn: (p: { kind: IntegrationKind; secret?: string }) => api<{ ok: boolean; message: string }>(`/api/admin/integrations/${p.kind}/test`, { method: 'POST', json: { secret: p.secret ?? '' } }) });
  return { save, remove, test };
}

export interface PlanLimits { people: number; projects: number; sources: number }
export interface BillingDto {
  plan: 'FREE' | 'TEAM'; paidUntil: string | null; unlimited: boolean; limits: PlanLimits; usage: { PEOPLE: number; PROJECTS: number; SOURCES: number };
  freeLimits: PlanLimits; teamLimits: PlanLimits; teamPriceMinor: number; currency: string; paymentsEnabled: boolean;
}
export const useBilling = () => useQuery({ queryKey: ['billing'], queryFn: () => api<BillingDto>('/api/admin/billing') });
export const useCheckout = () => useMutation({ mutationFn: () => api<{ url: string }>('/api/admin/billing/checkout', { method: 'POST' }) });
