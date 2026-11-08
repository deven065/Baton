// Live data from the Spring Boot API. DTO shapes mirror the backend records (see root README "API").
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/http';
import type { Sensitivity, Status } from './types';

// ───────────── DTOs ─────────────
export type SensitivityDto = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'RESTRICTED';
export type ClaimStatusDto = 'VERIFIED' | 'SINGLE_SOURCE' | 'INFERRED' | 'CONFLICTING' | 'MISSING_EVIDENCE' | 'STALE';
export type ReviewStateDto = 'PENDING' | 'ACCEPTED' | 'EDITED' | 'REJECTED' | 'NEEDS_INFO';
export type HandoverStatusDto = 'DRAFT' | 'RECONSTRUCTING' | 'IN_REVIEW' | 'APPROVED' | 'SHARED' | 'ARCHIVED';
export type SourceTypeDto = 'GITHUB' | 'TASK_CSV' | 'DOCUMENT' | 'MEETING_NOTES' | 'CHAT_EXPORT' | 'TESTIMONY';

export interface UserDto {
  id: string; email: string; displayName: string; orgRole: 'ADMIN' | 'MEMBER'; clearance: SensitivityDto; groups: string[];
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
  GITHUB: 'GitHub', TASK_CSV: 'Task board', DOCUMENT: 'Document', MEETING_NOTES: 'Meeting notes', CHAT_EXPORT: 'Chat export', TESTIMONY: 'Testimony',
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

export function useCreateSource(projectId?: string | null) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (p: { type: SourceTypeDto; title: string; sensitivity: SensitivityDto; aclGroups: string[]; file?: File; repo?: string; branch?: string }) => {
      const f = new FormData();
      f.set('type', p.type);
      f.set('title', p.title);
      f.set('sensitivity', p.sensitivity);
      p.aclGroups.forEach(g => f.append('aclGroups', g));
      f.set('authorisationAttested', 'true');
      if (p.file) f.set('file', p.file);
      if (p.repo) f.set('repo', p.repo);
      if (p.branch) f.set('branch', p.branch);
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
