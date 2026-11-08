export type Status = 'verified' | 'single' | 'inferred' | 'conflicting' | 'missing' | 'stale';
export type Sensitivity = 'Public' | 'Internal' | 'Confidential' | 'Restricted';
export type Role = 'lead' | 'leaver' | 'recipient' | 'admin';
export type ReviewState = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'NEEDS_INFO' | 'EDITED';

export const SENSITIVITY_ORDER: Sensitivity[] = ['Public', 'Internal', 'Confidential', 'Restricted'];

export interface Person {
  id: string;
  name: string;
  initials: string;
  tone: number;
  role: string;
  email: string;
  appRole: Role;
  clearance: Sensitivity;
  groups: string[];
}

export const CLAIM_TYPES = [
  'Decision', 'Open work', 'Owner', 'Risk', 'Dependency', 'Blocker', 'Recurring issue',
  'Contact', 'Open question', 'Process', 'Access to transfer',
] as const;
export type ClaimType = (typeof CLAIM_TYPES)[number];

export interface Evidence { source: string; quote: string }

export interface Claim {
  id: string;
  type: ClaimType;
  text: string;
  status: Status;
  statusLabel?: string;
  sensitivity: Sensitivity;
  confidence: number;
  meta: string;
  review: ReviewState;
  evidence: Evidence[];
  factors: { label: string; value: number }[];
  history: { who: string; what: string; when: string }[];
}

export interface Source {
  id: string;
  kind: 'github' | 'tasks' | 'notes' | 'chat' | 'docs';
  name: string;
  meta: string;
  sensitivity: Sensitivity;
  groups: string[];
  status: Status;
  statusLabel: string;
  note: string;
  progress?: number;
}

export interface Handover {
  id: string;
  person: string;
  personRole: string;
  project: string;
  successors: { initials: string; tone: number }[];
  stage: number;
  stageLabel: string;
  departure: string;
  status: Status;
  statusLabel: string;
  initials: string;
  tone: number;
}

export interface Gap {
  id: string;
  question: string;
  kind: string;
  state: 'answered' | 'open' | 'current';
  why?: string;
  evidence?: Evidence;
  sensitivity: Sensitivity;
  answer?: string;
}

export interface TimelineEntry {
  date: string;
  kind: 'Decision' | 'Event' | 'Change' | 'Unresolved';
  text: string;
  status: Status;
  statusLabel: string;
  source: string;
}

export interface AuditEvent {
  at: string;
  actor: string | null;
  event: string;
  tone: Status;
  target: string;
  detail: string;
}
