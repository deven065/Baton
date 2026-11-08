// Synthetic demo data: "Project Annapurna" (Northfield Tech Club). No real people or messages.
import type { AuditEvent, Claim, Gap, Handover, Person, Source, TimelineEntry } from './types';

export const PEOPLE: Record<string, Person> = {
  priya: { id: 'priya', name: 'Priya Nair', initials: 'PN', tone: 0, role: 'Project lead', email: 'priya.nair@northfield.club', appRole: 'lead', clearance: 'Restricted', groups: ['engineering', 'operations'] },
  aarav: { id: 'aarav', name: 'Aarav Mehta', initials: 'AM', tone: 2, role: 'Tech lead · leaving 20 Nov', email: 'aarav.m@northfield.club', appRole: 'leaver', clearance: 'Confidential', groups: ['engineering'] },
  rohan: { id: 'rohan', name: 'Rohan Kulkarni', initials: 'RK', tone: 1, role: 'Incoming engineer', email: 'rohan.k@northfield.club', appRole: 'recipient', clearance: 'Confidential', groups: ['engineering'] },
  sara: { id: 'sara', name: 'Sara D’Souza', initials: 'SD', tone: 3, role: 'Incoming design volunteer', email: 'sara.d@northfield.club', appRole: 'recipient', clearance: 'Internal', groups: ['design'] },
  kabir: { id: 'kabir', name: 'Kabir Shah', initials: 'KS', tone: 4, role: 'Organisation admin', email: 'kabir.s@northfield.club', appRole: 'admin', clearance: 'Restricted', groups: ['all members'] },
};
export const PERSONA_ORDER = ['priya', 'aarav', 'rohan', 'sara', 'kabir'] as const;

export const HANDOVERS: Handover[] = [
  { id: 'h1', person: 'Aarav Mehta', personRole: 'Tech lead · graduating', project: 'Project Annapurna', initials: 'AM', tone: 2, successors: [{ initials: 'RK', tone: 1 }, { initials: 'SD', tone: 3 }], stage: 4, stageLabel: 'Review · 4 of 5', departure: 'Fri 20 Nov · 12 days', status: 'conflicting', statusLabel: '2 conflicts' },
  { id: 'h2', person: 'Ishita Rao', personRole: 'Events coordinator', project: 'Hackfest 2027', initials: 'IR', tone: 4, successors: [{ initials: 'MJ', tone: 4 }], stage: 2, stageLabel: 'Reconstruct · 2 of 5', departure: 'Mon 30 Nov · 22 days', status: 'single', statusLabel: 'Analysing · 64%' },
  { id: 'h3', person: 'Dev Patel', personRole: 'Sponsorship lead', project: 'Sponsor CRM', initials: 'DP', tone: 3, successors: [{ initials: 'AK', tone: 5 }], stage: 5, stageLabel: 'Handover · 5 of 5', departure: 'Mon 2 Nov · left', status: 'verified', statusLabel: 'Acknowledged' },
  { id: 'h4', person: 'Neha Joshi', personRole: 'Design lead · sudden exit', project: 'Club website', initials: 'NJ', tone: 4, successors: [{ initials: 'SD', tone: 3 }], stage: 1, stageLabel: 'Connect · 1 of 5', departure: 'Unavailable', status: 'missing', statusLabel: 'No exit interview' },
];

export const SOURCES: Source[] = [
  { id: 's1', kind: 'github', name: 'GitHub · northfield-club/annapurna', meta: 'Read-only REST API · README, docs, 7 open PRs, 11 issues, 1,920 commits', sensitivity: 'Confidential', groups: ['engineering'], status: 'verified', statusLabel: 'Synced', note: '412 chunks · 5 min ago' },
  { id: 's2', kind: 'tasks', name: 'Task board · annapurna-board.csv', meta: 'Jira CSV export · 86 tasks, 23 assigned to Aarav', sensitivity: 'Internal', groups: ['engineering', 'design'], status: 'verified', statusLabel: 'Synced', note: '86 tasks · 1 h ago' },
  { id: 's3', kind: 'notes', name: 'Meeting notes · 14 files', meta: 'Markdown and PDF · Aug–Nov 2026', sensitivity: 'Internal', groups: ['all members'], status: 'single', statusLabel: 'Indexing 9 / 14', note: 'Embedding with MiniLM', progress: 0.64 },
  { id: 's4', kind: 'chat', name: 'Slack export · #dev, #annapurna-ops', meta: 'JSON export · 3,412 messages · DMs excluded', sensitivity: 'Confidential', groups: ['engineering', 'operations'], status: 'inferred', statusLabel: 'Redacting…', note: '23 items replaced so far' },
  { id: 's5', kind: 'docs', name: 'Documents · Runbook.docx, Vendor contracts.pdf', meta: 'Word and PDF · 2 files · 31 pages', sensitivity: 'Restricted', groups: ['operations'], status: 'missing', statusLabel: 'Needs authorisation', note: 'Confirm before indexing' },
];

const F = (trust: number, indep: number, match: number, rec: number, pen = 0) => [
  { label: 'Source trust', value: trust },
  { label: 'Independent sources', value: indep },
  { label: 'Quote match', value: match },
  { label: 'Recency', value: rec },
  { label: 'Conflict penalty', value: -pen },
];

export const CLAIMS: Claim[] = [
  {
    id: 'C-0142', type: 'Decision', status: 'verified', statusLabel: 'Verified · 2 sources', sensitivity: 'Confidential', confidence: 0.92, review: 'PENDING',
    text: 'Push notifications moved from Firebase to OneSignal after the free-tier quota ran out during the Diwali drive.',
    meta: 'Slack #dev · PR #131 review · confidence 0.92',
    evidence: [
      { source: 'slack/#dev · 12 Oct 2026 · chunk 214', quote: '“we hit the FCM cap again during the drive — switching to OneSignal tonight”' },
      { source: 'github PR #131 review · 13 Oct 2026', quote: '“OneSignal SDK in, FCM removed. Free tier covers 10k subscribers.”' },
    ],
    factors: F(0.35, 0.25, 0.25, 0.07),
    history: [{ who: 'priya', what: 'Priya edited the wording (“Diwali drive”)', when: '8 Nov, 10:15' }, { who: 'system', what: 'Extracted by pipeline run #7 · prompt v3', when: '7 Nov, 14:02' }],
  },
  {
    id: 'C-0137', type: 'Decision', status: 'conflicting', sensitivity: 'Internal', confidence: 0.41, review: 'PENDING',
    text: 'v1.0 launch is on 15 November 2026.', meta: 'Meeting notes 2 Oct · contradicted by Slack 21 Oct',
    evidence: [{ source: 'meeting-notes/2026-10-02.md · chunk 14', quote: '“Launch locked for 15 November, right after mid-sem exams.”' }],
    factors: F(0.3, 0.1, 0.25, 0.06, 0.3),
    history: [{ who: 'system', what: 'Conflict detected with C-0140', when: '7 Nov, 14:05' }],
  },
  {
    id: 'C-0129', type: 'Decision', status: 'verified', statusLabel: 'Verified · system of record', sensitivity: 'Confidential', confidence: 0.95, review: 'ACCEPTED',
    text: 'Backend moved from Firebase Functions to Spring Boot on Render in September.', meta: 'GitHub commits · README · confidence 0.95',
    evidence: [{ source: 'github README.md · 9 Sep 2026', quote: '“Backend: Spring Boot 3 on Render (migrated from Firebase Functions).”' }],
    factors: F(0.4, 0.25, 0.25, 0.05),
    history: [{ who: 'priya', what: 'Priya accepted', when: '8 Nov, 09:40' }],
  },
  {
    id: 'C-0118', type: 'Decision', status: 'inferred', sensitivity: 'Internal', confidence: 0.48, review: 'PENDING',
    text: 'The team prefers the Expo managed workflow to avoid native build setup.', meta: 'No direct quote · AI reasoning from 3 chunks',
    evidence: [], factors: F(0.2, 0, 0, 0.08),
    history: [{ who: 'system', what: 'Extracted by pipeline run #7 · prompt v3', when: '7 Nov, 14:02' }],
  },
  {
    id: 'C-0102', type: 'Decision', status: 'single', sensitivity: 'Internal', confidence: 0.71, review: 'ACCEPTED',
    text: 'Chose React Native + Expo over Flutter because two members already knew React.', meta: 'Meeting notes 14 Aug · confidence 0.71',
    evidence: [{ source: 'meeting-notes/2026-08-14.md · chunk 3', quote: '“going with RN + Expo, Aarav and Ishita already know React”' }],
    factors: F(0.3, 0.1, 0.25, 0.06),
    history: [{ who: 'priya', what: 'Priya accepted', when: '8 Nov, 09:41' }],
  },
  {
    id: 'C-0097', type: 'Decision', status: 'stale', sensitivity: 'Internal', confidence: 0.2, review: 'REJECTED',
    text: 'Backend uses MongoDB Atlas free tier.', meta: 'Superseded by C-0129 · hidden from recipients',
    evidence: [{ source: 'meeting-notes/2026-08-14.md · chunk 5', quote: '“Mongo Atlas free tier for now”' }],
    factors: F(0.3, 0.1, 0.25, 0.01, 0.2),
    history: [{ who: 'priya', what: 'Priya rejected — superseded by C-0129', when: 'Yesterday' }],
  },
  {
    id: 'C-0088', type: 'Open work', status: 'verified', statusLabel: 'System of record', sensitivity: 'Confidential', confidence: 0.9, review: 'PENDING',
    text: 'PR #142 “Volunteer shift swap” is half done; timezone tests fail on IST → UTC conversion.', meta: 'GitHub PR #142 · confidence 0.90',
    evidence: [{ source: 'github PR #142 · 4 Nov 2026', quote: '“WIP: swap flow done, tz tests red (IST->UTC off by 5:30)”' }],
    factors: F(0.4, 0.2, 0.25, 0.09), history: [],
  },
  {
    id: 'C-0163', type: 'Risk', status: 'inferred', statusLabel: 'Likely, not confirmed', sensitivity: 'Confidential', confidence: 0.44, review: 'PENDING',
    text: 'Production database backups appear to run only from a cron job on Aarav’s laptop.', meta: 'No direct quote · AI reasoning from 2 chunks',
    evidence: [], factors: F(0.2, 0, 0, 0.08), history: [],
  },
  {
    id: 'C-0170', type: 'Contact', status: 'single', statusLabel: 'One source', sensitivity: 'Confidential', confidence: 0.68, review: 'PENDING',
    text: 'SMS gateway vendor contact is Meera at TextLocal ([PHONE_2]).', meta: 'Slack #annapurna-ops · confidence 0.68',
    evidence: [{ source: 'slack/#annapurna-ops · 2 Oct 2026', quote: '“Meera from TextLocal is our contact, [PHONE_2]”' }],
    factors: F(0.25, 0.1, 0.25, 0.08), history: [],
  },
  {
    id: 'C-0175', type: 'Access to transfer', status: 'missing', statusLabel: 'No owner found', sensitivity: 'Restricted', confidence: 0.3, review: 'NEEDS_INFO',
    text: 'Google Play Console is registered to a personal Gmail.', meta: 'Asked in exit interview Q6',
    evidence: [{ source: 'slack/#annapurna-ops · 19 Oct 2026', quote: '“play console login is on my gmail, I’ll sort it later”' }],
    factors: F(0.25, 0.1, 0.25, 0.08), history: [],
  },
];

export const CLAIM_TYPE_COUNTS: Record<string, number> = {
  Decision: 18, 'Open work': 14, Owner: 22, Risk: 9, Dependency: 11, Blocker: 4, 'Recurring issue': 7, Contact: 12, 'Open question': 8, Process: 10, 'Access to transfer': 6,
};

export const GAPS: Gap[] = [
  { id: 'q1', question: 'How do we deploy to production?', kind: 'Process', state: 'answered', sensitivity: 'Internal', answer: 'Merge to main. GitHub Actions builds and Render deploys automatically.' },
  { id: 'q2', question: 'Who owns the SMS gateway budget?', kind: 'Owner', state: 'answered', sensitivity: 'Confidential', answer: 'Priya, from the club operations budget.' },
  { id: 'q3', question: 'Which launch date is current?', kind: 'Conflict', state: 'answered', sensitivity: 'Internal', answer: '29 November is current. We moved it after the Play Store rejected build 0.9 on 19 October.' },
  { id: 'q4', question: 'Why was Firebase dropped?', kind: 'Decision', state: 'answered', sensitivity: 'Internal', answer: 'FCM quota during the Diwali drive, and Functions cold starts.' },
  { id: 'q5', question: 'Where do DB backups run?', kind: 'Risk', state: 'answered', sensitivity: 'Confidential', answer: 'A cron job on my laptop. It needs to move to the server.' },
  {
    id: 'q6', question: 'Who owns the Google Play Console account, and how do we transfer it before you leave?', kind: 'Access to transfer', state: 'current', sensitivity: 'Restricted',
    why: 'No source names an owner for this account, and one chat message suggests it is under a personal Gmail. Losing it would block the 29 Nov launch.',
    evidence: { source: 'slack/#annapurna-ops · 19 Oct 2026', quote: '“play console login is on my gmail, I’ll sort it later”' },
  },
  { id: 'q7', question: 'What blocks PR #142?', kind: 'Open work', state: 'open', sensitivity: 'Confidential', why: 'The PR is half done and its timezone tests fail. Nothing says what is left.', evidence: { source: 'github PR #142 · 4 Nov 2026', quote: '“WIP: swap flow done, tz tests red (IST->UTC off by 5:30)”' } },
  { id: 'q8', question: 'Who is the contact at the NGO?', kind: 'Contact', state: 'open', sensitivity: 'Confidential', why: 'Three messages mention “the NGO person” but never a name.' },
  { id: 'q9', question: 'Should we keep donor SMS reminders after December?', kind: 'Open question', state: 'open', sensitivity: 'Internal', why: 'Discussed on 3 Nov with no decision recorded. Credits cost ₹1,800 / month.' },
];

export const TIMELINE: TimelineEntry[] = [
  { date: '14 Aug', kind: 'Decision', text: 'Chose React Native + Expo over Flutter — two members already knew React.', status: 'single', statusLabel: 'Single source', source: 'Meeting notes' },
  { date: '9 Sep', kind: 'Decision', text: 'Backend moved from Firebase Functions to Spring Boot on Render.', status: 'verified', statusLabel: 'Verified', source: 'GitHub · README' },
  { date: '12 Oct', kind: 'Decision', text: 'Push notifications moved to OneSignal after the FCM quota ran out.', status: 'verified', statusLabel: 'Verified', source: 'Slack · PR #131' },
  { date: '19 Oct', kind: 'Event', text: 'Play Store rejected build 0.9 (background location permission).', status: 'verified', statusLabel: 'Verified', source: 'GitHub issue #118' },
  { date: '21 Oct', kind: 'Change', text: 'Launch moved from 15 Nov to 29 Nov.', status: 'verified', statusLabel: 'Resolved conflict', source: 'Slack · exit interview' },
  { date: '3 Nov', kind: 'Unresolved', text: 'Keep donor SMS reminders after December? Credits cost ₹1,800 / month.', status: 'missing', statusLabel: 'No decision recorded', source: 'Meeting notes' },
];

export const AUDIT: AuditEvent[] = [
  { at: '09 Nov 14:22', actor: 'rohan', event: 'Viewed share', tone: 'single', target: 'Handover v1 · engineer view', detail: '118 claims shown · 24 hidden by policy' },
  { at: '09 Nov 11:05', actor: 'priya', event: 'Approved', tone: 'verified', target: 'Handover v1', detail: 'sha256 3f9a1c…7be0c21e · 131 claims' },
  { at: '08 Nov 18:40', actor: null, event: 'Redacted', tone: 'missing', target: 'Slack export', detail: '5 API keys · 9 emails · 6 phone numbers' },
  { at: '08 Nov 10:15', actor: 'priya', event: 'Edited claim', tone: 'inferred', target: 'C-0142 · Decision', detail: 'Wording changed · revision 2' },
  { at: '07 Nov 16:02', actor: 'aarav', event: 'Answered', tone: 'single', target: 'Exit interview · Q6', detail: 'Stored as testimony · re-scored 4 claims' },
  { at: '07 Nov 14:02', actor: null, event: 'AI request', tone: 'stale', target: 'Extraction batch 26 / 38', detail: 'gpt-oss-120b · redacted text only · 0 secrets' },
  { at: '06 Nov 09:30', actor: 'priya', event: 'Connected', tone: 'verified', target: 'GitHub · annapurna', detail: 'Read-only · authorisation confirmed' },
  { at: '05 Nov 17:48', actor: 'kabir', event: 'Access change', tone: 'conflicting', target: 'Rohan Kulkarni', detail: 'Clearance Internal → Confidential' },
];

export const GROUPS = [
  { name: 'engineering', detail: '6 members · Confidential sources' },
  { name: 'design', detail: '3 members · Internal sources' },
  { name: 'operations', detail: '2 members · vendor contracts, budget' },
  { name: 'all members', detail: '24 members · Public and Internal' },
];

export const RISKS = [
  { text: 'Database backups run only from a cron job on Aarav’s laptop.', owner: 'rohan', status: 'inferred' as const, label: 'Likely, not confirmed', claim: 'C-0163' },
  { text: 'Play Console account is on a personal Gmail — transfer by 18 Nov.', owner: 'priya', status: 'verified' as const, label: 'Fact · testimony', claim: 'C-0175' },
  { text: 'PR #142 timezone tests fail (IST → UTC).', owner: 'rohan', status: 'verified' as const, label: 'Fact', claim: 'C-0088' },
  { text: 'SMS gateway credits run out in mid-December.', owner: 'priya', status: 'single' as const, label: 'One source', claim: 'C-0170' },
  { text: 'Build breaks on every Expo SDK upgrade — keep the version pinned.', owner: 'rohan', status: 'single' as const, label: 'One source', claim: 'C-0102' },
];

export const CHECKLIST = {
  'Day 1': [
    { id: 'd1', text: 'Get added to the GitHub org and the Render team', claim: 'C-0129', who: 'Ask Priya', done: true },
    { id: 'd2', text: 'Read the deploy runbook: merge to main → GitHub Actions → Render', claim: 'C-0151', who: '', done: false },
    { id: 'd3', text: 'Pick up PR #142 “Volunteer shift swap” and fix the timezone tests', claim: 'C-0088', who: 'Aarav until 20 Nov', done: false },
    { id: 'd4', text: 'Move the backup cron job to the server and confirm it runs', claim: 'C-0163', who: 'Inferred — check first', done: false },
    { id: 'd5', text: 'Introduce yourself to Meera (SMS vendor)', claim: 'C-0170', who: 'See ownership map', done: false },
  ],
  'Week 1': [
    { id: 'w1', text: 'Ship the timezone fix and merge PR #142', claim: 'C-0088', who: '', done: false },
    { id: 'w2', text: 'Confirm Play Console ownership moved to the club account', claim: 'C-0175', who: 'Priya', done: false },
    { id: 'w3', text: 'Pin the Expo SDK version in package.json', claim: 'C-0102', who: '', done: false },
  ],
  'Month 1': [
    { id: 'm1', text: 'Launch v1.0 on the Play Store (29 Nov)', claim: 'C-0140', who: 'Priya', done: false },
    { id: 'm2', text: 'Decide on donor SMS reminders with Priya before credits run out', claim: 'C-0181', who: 'Priya', done: false },
  ],
};

export const OWNERSHIP = [
  { area: 'Mobile app', owner: 'rohan', note: 'You · previously Aarav', status: 'verified' as const, label: 'Verified', extra: 'React Native + Expo · 41 files' },
  { area: 'Backend & deploy', owner: 'rohan', note: 'You · Spring Boot on Render', status: 'verified' as const, label: 'Verified', extra: 'Runbook C-0151 · GitHub Actions' },
  { area: 'Design system & UI', owner: 'sara', note: 'Design volunteer', status: 'single' as const, label: 'One source', extra: 'Figma file linked in README' },
  { area: 'Play Console & store listing', owner: 'priya', note: 'Transferring from Aarav by 18 Nov', status: 'verified' as const, label: 'Testimony', extra: 'Access to transfer' },
  { area: 'SMS gateway vendor', owner: 'priya', note: 'Budget owner · contact: Meera, TextLocal', status: 'single' as const, label: 'One source', extra: '[PHONE_2] · visible at Confidential' },
  { area: 'NGO partnerships', owner: 'Ishita Rao', note: 'Events team · weekly call on Fridays', status: 'single' as const, label: 'One source', extra: '' },
  { area: 'Database backups', owner: null, note: 'No owner in any source — asked Priya', status: 'missing' as const, label: 'Unknown', extra: '' },
  { area: 'Donor onboarding epic', owner: null, note: 'Open question from the exit interview', status: 'missing' as const, label: 'Unknown', extra: '' },
];
