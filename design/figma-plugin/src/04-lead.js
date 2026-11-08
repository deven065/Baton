// ---------------------------------------------------------------- lead workspace
const STEPS = ['Connect', 'Reconstruct', 'Verify', 'Review', 'Handover'];
function stepper(current) {
  const r = H({ name: 'Workflow stepper', gap: 10, align: 'CENTER' });
  STEPS.forEach((s, i) => {
    if (i) put(r, rect(28, 2, i <= current ? 'brand/600' : 'line/strong', 1));
    const done = i < current, on = i === current;
    const c = H({ fill: done ? 'status/verified' : on ? 'brand/600' : 'surface/card', stroke: done || on ? null : 'line/strong', r: 12, justify: 'CENTER', align: 'CENTER' },
      [done ? icon('check', 12, 'text/inverse') : tx(String(i + 1), 'caption', on ? 'text/inverse' : 'ink/500')]);
    c.resize(24, 24); c.primaryAxisSizingMode = 'FIXED'; c.counterAxisSizingMode = 'FIXED';
    put(r, H({ gap: 8, align: 'CENTER' }, [c, tx(s, on ? 'smallM' : 'small', on ? 'ink/900' : done ? 'ink/700' : 'ink/400')]));
  });
  return r;
}

function personCell(p, sub) {
  return H({ gap: 10, align: 'CENTER' }, [av(p, 'Sm'), Vt({ gap: 0 }, [tx(P[p] ? P[p].n : p, 'smallM'), tx(sub, 'caption', 'ink/500')])]);
}

function table(cols, rows, name = 'Table') {
  const t = Vt({ name, fill: 'surface/card', stroke: 'line/default', r: 12, clip: true, shadow: 'card' });
  const head = H({ name: 'Header row', fill: 'surface/canvas', stroke: 'line/default', sides: 'b', px: 16, py: 10, gap: 16, align: 'CENTER' });
  cols.forEach(c => put(head, tx(c.label, 'overline', 'ink/500'), c.w === 'fill' ? { w: 'fill' } : { w: c.w }));
  put(t, head, { w: 'fill' });
  rows.forEach((r, ri) => {
    const row = H({ name: 'Row', px: 16, py: 12, gap: 16, align: 'CENTER', stroke: ri < rows.length - 1 ? 'line/default' : null, sides: 'b' });
    r.forEach((cell, ci) => {
      const c = cols[ci];
      const sz = c.w === 'fill' ? { w: 'fill' } : { w: c.w };
      if (typeof cell === 'string') put(row, tx(cell, c.mono ? 'monoS' : 'small', c.tone || 'ink/700'), sz);
      else { const wrap = H({ name: 'Cell', gap: 8, align: 'CENTER' }, [cell]); put(row, wrap, sz); }
    });
    put(t, row, { w: 'fill' });
  });
  return t;
}

function attentionItem(ic, fg, bg, title, desc, action) {
  const r = H({ name: 'Attention item', gap: 12, align: 'CENTER', py: 12, stroke: 'line/default', sides: 'b' }, [tile(ic, fg, bg, 34)]);
  put(r, Vt({ gap: 2 }, [tx(title, 'smallM'), tx(desc, 'caption', 'ink/500')]), { w: 'fill' });
  r.children[1].children.forEach(t => { t.layoutSizingHorizontal = 'FILL'; t.textAutoResize = 'HEIGHT'; });
  if (action) put(r, btn('Secondary', action));
  return r;
}

function activityItem(p, text, when) {
  const r = H({ name: 'Activity', gap: 10, align: 'MIN' }, [av(p, 'Sm')]);
  const v = Vt({ gap: 2 });
  put(r, v, { w: 'fill' });
  put(v, tx(text, 'small', 'ink/900'), { w: 'fill' });
  put(v, tx(when, 'caption', 'ink/400'));
  return r;
}

function screenDashboard() {
  const { scr, content } = shell('Lead / Dashboard', 'Dashboard', ['Northfield Tech Club', 'Dashboard']);
  put(content, hdr('Good afternoon, Priya', '3 handovers in progress · next departure in 12 days',
    H({ gap: 8 }, [btn('Secondary', 'Import export file'), btn('Primary', 'Start a handover')])), { w: 'fill' });
  const stats = H({ name: 'Stats', gap: 16 });
  put(stats, stat('Active handovers', '3', '1 departure this month'), { w: 'fill' });
  put(stats, stat('Claims to review', '47', '12 inferred · 6 missing evidence'), { w: 'fill' });
  put(stats, stat('Open conflicts', '2', 'Blocking approval of Annapurna v2', 'status/conflict'), { w: 'fill' });
  put(stats, stat('Awaiting acknowledgement', '1', 'Rohan opened v2 two hours ago'), { w: 'fill' });
  put(content, stats, { w: 'fill' });

  const cols = [{ label: 'Person leaving', w: 'fill' }, { label: 'Project', w: 170 }, { label: 'Successors', w: 110 }, { label: 'Stage', w: 190 }, { label: 'Departure', w: 130 }, { label: 'Status', w: 170 }];
  const stage = (label, n) => Vt({ gap: 6 }, [tx(label, 'caption', 'ink/700'), segs(n, 5, n === 5 ? 'status/verified' : 'brand/600')]);
  const rows = [
    [personCell('aarav', 'Tech lead · graduating'), 'Project Annapurna', avatarStack([['RK', 1], ['SD', 3]]), stage('Review · 4 of 5', 4), 'Fri 20 Nov · 12 days', badge('Conflicting', '2 conflicts')],
    [personCell('Ishita Rao', 'Events coordinator'), 'Hackfest 2027', avatarStack([['MJ', 4]]), stage('Reconstruct · 2 of 5', 2), 'Mon 30 Nov · 22 days', badge('Single source', 'Analysing · 64%')],
    [personCell('Dev Patel', 'Sponsorship lead'), 'Sponsor CRM', avatarStack([['AK', 5]]), stage('Handover · 5 of 5', 5), 'Mon 2 Nov · left', badge('Verified', 'Acknowledged')],
    [personCell('Neha Joshi', 'Design lead · sudden exit'), 'Club website', avatarStack([['SD', 3]]), stage('Connect · 1 of 5', 1), 'Unavailable', badge('Missing evidence', 'No exit interview')],
  ];
  const tcard = Vt({ name: 'Handovers', gap: 12 }, [cardHead('Handovers', 'Everyone currently leaving a project', link('View all'))]);
  put(tcard, table(cols, rows, 'Handover table'), { w: 'fill' });
  tcard.children[0].layoutSizingHorizontal = 'FILL';
  put(content, tcard, { w: 'fill' });

  const lower = H({ name: 'Lower row', gap: 24, align: 'MIN' });
  const att = card({ name: 'Needs your attention', gap: 0 }, [cardHead('Needs your attention', 'Things that block approval come first')]);
  att.children[0].layoutSizingHorizontal = 'FILL';
  put(att, attentionItem('alert', 'status/conflict', 'status/conflict-bg', 'Launch date conflict: 15 Nov vs 29 Nov', 'Meeting notes and a later Slack message disagree. Aarav has answered.', 'Resolve'), { w: 'fill' });
  put(att, attentionItem('info', 'status/inferred', 'status/inferred-bg', '12 inferred claims need a decision', 'AI reasoning with no direct quote — accept, edit or reject each one.', 'Review'), { w: 'fill' });
  put(att, attentionItem('key', 'status/missing', 'status/missing-bg', 'Play Console account is under a personal Gmail', 'Access to transfer · no owner in any source. Ask before 18 Nov.', 'Ask Aarav'), { w: 'fill' });
  put(att, attentionItem('message', 'status/single', 'status/single-bg', 'Exit interview: 5 of 9 answered', 'Aarav last answered 10 minutes ago.', 'Open'), { w: 'fill' });
  put(lower, att, { w: 'fill' });
  const act = card({ name: 'Recent activity', gap: 16 }, [cardHead('Recent activity', null, link('Audit log'))]);
  act.children[0].layoutSizingHorizontal = 'FILL';
  put(act, activityItem('aarav', 'Aarav answered “How do we deploy to production?”', '10 min ago · now cited testimony'), { w: 'fill' });
  put(act, activityItem('rohan', 'Rohan opened handover v2 (engineer view)', '2 h ago'), { w: 'fill' });
  put(act, activityItem('priya', 'You rejected “Backend uses MongoDB” — marked stale', 'Yesterday'), { w: 'fill' });
  put(act, activityItem('kabir', 'Slack export re-synced · 41 new chunks, 3 items redacted', 'Yesterday'), { w: 'fill' });
  put(lower, act, { w: 420 });
  put(content, lower, { w: 'fill' });
  return scr;
}

function sourceRow(ic, name, meta, level, groups, status, statusNote, extra) {
  // Two lines: identity + status on top, sensitivity + groups underneath.
  const card = Vt({ name: 'Source · ' + name, p: 16, gap: 12, fill: 'surface/card', stroke: 'line/default', r: 12, shadow: 'card' });
  const r = H({ name: 'Top', gap: 14, align: 'CENTER' }, [tile(ic, 'ink/900', 'surface/subtle', 40)]);
  const v = Vt({ gap: 2 });
  put(r, v, { w: 'fill' });
  put(v, tx(name, 'bodyM'), { w: 'fill' });
  put(v, tx(meta, 'caption', 'ink/500'), { w: 'fill' });
  if (extra) put(v, extra);
  put(r, Vt({ gap: 4, align: 'MAX' }, [status, tx(statusNote, 'caption', 'ink/500')]));
  put(r, icon('more', 18, 'ink/400'));
  put(card, r, { w: 'fill' });
  const b = H({ name: 'Access', gap: 8, align: 'CENTER', pl: 54 }, [tx('Sensitivity', 'caption', 'ink/400'), sens(level), icon('chevronDown', 12, 'ink/400'), rect(1, 16, 'line/default'), tx('Access groups', 'caption', 'ink/400')]);
  groups.forEach(x => put(b, chip(x)));
  put(card, b, { w: 'fill' });
  return card;
}

function stageRow(kind, title, detail, value) {
  const r = H({ name: 'Stage · ' + title, gap: 12, align: 'CENTER', py: 8 }, [circleStatus(kind)]);
  const v = Vt({ gap: 1 });
  put(r, v, { w: 'fill' });
  put(v, tx(title, 'smallM', kind === 'todo' ? 'ink/400' : 'ink/900'), { w: 'fill' });
  if (detail) put(v, tx(detail, 'caption', 'ink/500'), { w: 'fill' });
  if (value) put(r, tx(value, 'monoS', 'ink/700'));
  return r;
}

function screenSources() {
  const { scr, content } = shell('Lead / Connect sources', 'Sources', ['Project Annapurna', 'Handover · Aarav Mehta', 'Sources']);
  put(content, stepper(0));
  put(content, hdr('Connect authorised sources', 'Add what the team already has. Each source needs an authorisation confirmation, a sensitivity level and access groups.',
    H({ gap: 8 }, [btn('Secondary', 'Connect GitHub'), btn('Primary', 'Add source')])), { w: 'fill' });
  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const list = Vt({ name: 'Sources', gap: 12 });
  put(list, sourceRow('branch', 'GitHub · northfield-club/annapurna', 'Read-only REST API · README, docs, 7 open PRs, 11 issues, 1,920 commits', 'Confidential', ['engineering'], badge('Verified', 'Synced'), '412 chunks · 5 min ago'), { w: 'fill' });
  put(list, sourceRow('board', 'Task board · annapurna-board.csv', 'Jira CSV export · 86 tasks, 23 assigned to Aarav', 'Internal', ['engineering', 'design'], badge('Verified', 'Synced'), '86 tasks · 1 h ago'), { w: 'fill' });
  put(list, sourceRow('fileText', 'Meeting notes · 14 files', 'Markdown and PDF · Aug–Nov 2026', 'Internal', ['all members'], badge('Single source', 'Indexing 9 / 14'), 'Embedding with MiniLM', bar(0.64, 260)), { w: 'fill' });
  put(list, sourceRow('hash', 'Slack export · #dev, #annapurna-ops', 'JSON export · 3,412 messages · DMs excluded', 'Confidential', ['engineering', 'operations'], badge('Inferred', 'Redacting…'), '23 items replaced so far'), { w: 'fill' });
  put(list, sourceRow('file', 'Documents · Runbook.docx, Vendor contracts.pdf', 'Word and PDF · 2 files · 31 pages', 'Restricted', ['operations'], badge('Missing evidence', 'Needs authorisation'), 'Confirm before indexing'), { w: 'fill' });
  const drop = Vt({ name: 'Drop zone', p: 28, gap: 8, r: 12, stroke: 'line/strong', dash: true, align: 'CENTER', fill: 'surface/card' }, [
    tile('upload', 'brand/700', 'brand/50', 40),
    tx('Drop export files here', 'bodyM'),
    tx('Markdown, text, PDF, Word, task CSV (Jira, Trello, GitHub Projects), Slack JSON · WhatsApp and Discord exports coming soon', 'caption', 'ink/500', { align: 'CENTER' }),
  ]);
  drop.children[2].resize(520, 16); drop.children[2].textAutoResize = 'HEIGHT';
  put(list, drop, { w: 'fill' });
  put(body, list, { w: 'fill' });

  const side = Vt({ name: 'Side', gap: 16 });
  const prog = card({ name: 'Ingestion progress', gap: 6 }, [cardHead('Ingestion', 'Live · server-sent events', badge('Single source', 'Running'))]);
  prog.children[0].layoutSizingHorizontal = 'FILL';
  put(prog, H({ gap: 8, align: 'MAX', pt: 6 }, [tx('72%', 'stat'), tx('of 1,284 chunks ready', 'small', 'ink/500')]));
  put(prog, bar(0.72, 340), {});
  put(prog, stageRow('done', 'Chunking', 'Split into 1,284 chunks with source offsets', '1,284'), { w: 'fill' });
  put(prog, stageRow('done', 'Redaction', '9 emails · 6 phone numbers · 5 API keys · 3 passwords', '23'), { w: 'fill' });
  put(prog, stageRow('run', 'Embedding', 'all-MiniLM-L6-v2, in-process — text stays local', '71%'), { w: 'fill' });
  put(prog, stageRow('todo', 'Full-text and vector index', 'PostgreSQL + pgvector', null), { w: 'fill' });
  put(side, prog, { w: 'fill' });
  put(side, infoBanner('Secrets and personal data are replaced with tokens such as [EMAIL_3] and [API_KEY_1] before any AI call. Originals are stored encrypted (AES-GCM).', 'verified', 'shield'), { w: 'fill' });
  const ready = card({ name: 'Next step', gap: 10 }, [tx('Ready to reconstruct', 'h3'), tx('4 of 5 sources are indexed. You can start now and add the documents later.', 'small', 'ink/500')]);
  ready.children[1].layoutSizingHorizontal = 'FILL'; ready.children[1].textAutoResize = 'HEIGHT';
  put(ready, btn('Primary', 'Start reconstruction'), { w: 'fill' });
  put(side, ready, { w: 'fill' });
  put(body, side, { w: 380 });
  put(content, body, { w: 'fill' });
  return scr;
}

function modalAddSource() {
  const m = card({ name: 'Modal / Add source — authorisation', p: 0, gap: 0, shadow: 'pop' });
  fixW(m, 600);
  const head = H({ px: 24, py: 18, justify: 'SPACE_BETWEEN', align: 'CENTER', stroke: 'line/default', sides: 'b' }, [
    H({ gap: 12, align: 'CENTER' }, [tile('hash', 'ink/900', 'surface/subtle', 36), Vt({ gap: 0 }, [tx('Add Slack export', 'h3'), tx('northfield-slack-export-2026-11.zip · 18.4 MB', 'caption', 'ink/500')])]),
    icon('x', 18, 'ink/500'),
  ]);
  put(m, head, { w: 'fill' });
  const b = Vt({ name: 'Body', p: 24, gap: 18 });
  put(m, b, { w: 'fill' });
  put(b, tx('Channels to include', 'caption', 'ink/700'));
  const ch = Vt({ gap: 8 });
  [['#dev', '2,180 messages', true], ['#annapurna-ops', '1,232 messages', true], ['#random', '4,880 messages', false], ['Direct messages', 'Excluded by default — private by nature', false]].forEach(([n, d, on]) => {
    const r = H({ gap: 10, align: 'CENTER' }, [checkbox(on), tx(n, 'smallM')]);
    put(r, tx(d, 'caption', 'ink/500'));
    put(ch, r);
  });
  put(b, ch);
  put(b, tx('Sensitivity', 'caption', 'ink/700'));
  const seg = H({ name: 'Segmented control', p: 3, gap: 2, r: 8, fill: 'surface/subtle' });
  ['Public', 'Internal', 'Confidential', 'Restricted'].forEach(l => {
    const on = l === 'Confidential';
    put(seg, H({ px: 12, py: 6, r: 6, fill: on ? 'surface/card' : null, shadow: on ? 'card' : null, justify: 'CENTER' }, [tx(l, 'smallM', on ? 'ink/900' : 'ink/500')]), { w: 'fill' });
  });
  put(b, seg, { w: 'fill' });
  put(b, tx('Access groups', 'caption', 'ink/700'));
  put(b, H({ gap: 6, align: 'CENTER', p: 8, r: 8, stroke: 'line/strong' }, [chip('engineering', { x: true }), chip('operations', { x: true }), tx('Add group…', 'small', 'ink/400')]), { w: 'fill' });
  const auth = H({ name: 'Authorisation', p: 14, gap: 10, r: 10, fill: 'surface/paper', stroke: 'accent/baton' }, [checkbox(true)]);
  put(auth, Vt({ gap: 4 }, [
    tx('I confirm I’m authorised to share this export with Baton for this handover.', 'smallM'),
    tx('Baton redacts emails, phone numbers, API keys and passwords before any AI call. Only redacted text reaches the model.', 'caption', 'ink/500'),
  ]), { w: 'fill' });
  auth.children[1].children.forEach(t => { t.layoutSizingHorizontal = 'FILL'; t.textAutoResize = 'HEIGHT'; });
  put(b, auth, { w: 'fill' });
  put(m, H({ px: 24, py: 16, gap: 8, justify: 'MAX', fill: 'surface/canvas', stroke: 'line/default', sides: 't' }, [btn('Ghost', 'Cancel'), btn('Primary', 'Connect source')]), { w: 'fill' });
  return m;
}

function claimFeedItem(type, text, status, label) {
  const r = Vt({ name: 'Feed item', gap: 6, py: 12, stroke: 'line/default', sides: 'b' });
  put(r, H({ gap: 8, align: 'CENTER' }, [tx(type, 'overline', 'ink/400'), badge(status, label)]));
  put(r, tx(text, 'small', 'ink/900'), { w: 'fill' });
  return r;
}

function screenReconstruct() {
  const { scr, content } = shell('Lead / Reconstruct (live)', 'Reconstruct', ['Project Annapurna', 'Handover · Aarav Mehta', 'Reconstruct']);
  put(content, stepper(1));
  put(content, hdr('Reconstructing project state', 'Repository signals run first, without AI. Then the model reads redacted chunks in batches and proposes claims — code checks every quote.',
    btn('Secondary', 'Pause')), { w: 'fill' });
  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const pipe = card({ name: 'Pipeline', gap: 4 }, [cardHead('Pipeline · 9 stages', 'Started 14:02 · about 3 minutes left', tx('62%', 'h2', 'brand/600'))]);
  pipe.children[0].layoutSizingHorizontal = 'FILL';
  [
    ['done', '1 · Repository signals (no AI)', '7 open PRs and 11 issues by Aarav · 23 tasks assigned · 41 files mostly written by Aarav', '0.8 s'],
    ['done', '2 · Batch planning', '1,284 chunks grouped into 38 batches by source and date', '38'],
    ['run', '3 · Claim extraction', 'gpt-oss-120b on Groq · batch 26 of 38 · redacted text only', '26/38'],
    ['run', '4 · Citation verification', 'Every quote matched against stored chunk text · 9 claims rejected (quote not found)', '212 ✓'],
    ['todo', '5 · Merge similar claims', null, null],
    ['todo', '6 · Conflict detection', null, null],
    ['todo', '7 · Status and confidence scoring', null, null],
    ['todo', '8 · Gap questions for the exit interview', null, null],
    ['todo', '9 · Decision timeline', null, null],
  ].forEach(s => put(pipe, stageRow(...s), { w: 'fill' }));
  put(pipe, infoBanner('3 chunks were not analysed: the model returned invalid output twice (one repair attempt). They are listed in the report — nothing is silently dropped.', 'inferred', 'alert'), { w: 'fill' });
  put(body, pipe, { w: 'fill' });

  const right = Vt({ name: 'Right', gap: 16 });
  const counts = card({ name: 'Claims by type', gap: 12 }, [cardHead('Claims so far', '212 verified quotes')]);
  const grid = H({ name: 'Type grid', gap: 8, wrap: true });
  fixW(grid, 420);
  [['Decisions', 18], ['Open work', 14], ['Owners', 22], ['Risks', 9], ['Dependencies', 11], ['Blockers', 4], ['Recurring issues', 7], ['Contacts', 12], ['Open questions', 8], ['Processes', 10], ['Access to transfer', 6]].forEach(([l, n]) => {
    put(grid, Vt({ name: 'Type count', px: 12, py: 10, gap: 0, r: 8, fill: 'surface/canvas' }, [tx(String(n), 'h3'), tx(l, 'caption', 'ink/500')]), { w: 132 });
  });
  put(counts, grid, { w: 'fill' });
  put(right, counts, { w: 'fill' });
  const feed = card({ name: 'Live feed', gap: 0 }, [cardHead('Arriving now', 'Newest first')]);
  feed.children[0].layoutSizingHorizontal = 'FILL';
  put(feed, claimFeedItem('Open work', 'PR #142 “Volunteer shift swap” is half done; timezone tests fail on IST → UTC conversion.', 'Verified', 'System of record'), { w: 'fill' });
  put(feed, claimFeedItem('Risk', 'Production database backups appear to run only from a cron job on Aarav’s laptop.', 'Inferred', 'Likely, not confirmed'), { w: 'fill' });
  put(feed, claimFeedItem('Contact', 'SMS gateway vendor contact is Meera at TextLocal ([PHONE_2]).', 'Single source', 'One source'), { w: 'fill' });
  put(feed, claimFeedItem('Access', 'Google Play Console is registered to a personal Gmail.', 'Missing evidence', 'No owner found'), { w: 'fill' });
  put(right, feed, { w: 'fill' });
  put(body, right, { w: 460 });
  put(content, body, { w: 'fill' });
  const lock = H({ name: 'AI boundary', gap: 10, align: 'CENTER', p: 14, r: 10, fill: 'surface/card', stroke: 'line/default' }, [icon('lock', 16, 'status/verified')]);
  put(lock, tx('AI receives redacted text only · 0 raw secrets sent (planted fake key detected and replaced) · responses cached; offline replay available for demos', 'small', 'ink/700'), { w: 'fill' });
  put(content, lock, { w: 'fill' });
  return scr;
}

function confRow(label, value, pct, tok = 'brand/600') {
  return H({ name: 'Confidence factor', gap: 12, align: 'CENTER' }, [
    (() => { const t = tx(label, 'small', 'ink/700'); return t; })(),
    bar(pct, 120, tok, 6),
    tx(value, 'monoS', 'ink/900'),
  ]);
}

function claimRow(o) {
  const r = H({ name: 'Claim · ' + o.id, px: 16, py: 14, gap: 12, align: 'MIN', fill: o.sel ? 'brand/50' : 'surface/card', stroke: o.sel ? 'brand/600' : 'line/default', sw: o.sel ? 2 : 1, sides: o.sel ? 'l' : 'b' }, [checkbox(!!o.checked)]);
  const v = Vt({ gap: 6 });
  put(r, v, { w: 'fill' });
  put(v, H({ gap: 6, align: 'CENTER' }, [badge(o.status, o.label), sens(o.sens)]));
  put(v, tx(o.text, 'small', 'ink/900'), { w: 'fill' });
  put(v, tx(o.id + ' · ' + o.meta, 'caption', 'ink/500'), { w: 'fill' });
  put(r, o.state === 'Accepted' ? H({ gap: 4, align: 'CENTER' }, [icon('check', 12, 'status/verified'), tx('Accepted', 'caption', 'status/verified')]) : tx(o.state, 'caption', 'ink/400'));
  return r;
}

function screenReview() {
  const { scr, content } = shell('Lead / Review workspace', 'Review', ['Project Annapurna', 'Handover · Aarav Mehta', 'Review']);
  content.itemSpacing = 16;
  const top = H({ name: 'Toolbar', justify: 'SPACE_BETWEEN', align: 'CENTER' });
  put(top, Vt({ gap: 2 }, [tx('Review claims', 'h1'), tx('142 claims · 47 to review · every claim links to its receipt', 'body', 'ink/500')]));
  put(top, H({ gap: 8 }, [btn('Secondary', 'View as…'), btn('Primary', 'Go to approval')]));
  put(content, top, { w: 'fill' });
  const filters = H({ name: 'Filters', gap: 8, align: 'CENTER' });
  [['All 142', true], ['To review 47'], ['Inferred 12'], ['Conflicting 2'], ['Missing evidence 6'], ['Stale 4']].forEach(([l, on]) => put(filters, chip(l, on ? { fill: 'ink/900', fg: 'text/inverse' } : { fill: 'surface/card', stroke: 'line/default' })));
  put(filters, spacer(), { grow: true });
  put(filters, H({ gap: 6, align: 'CENTER' }, [icon('filter', 14, 'ink/500'), tx('Sensitivity: any', 'small', 'ink/700'), icon('chevronDown', 12, 'ink/400')]));
  put(content, filters, { w: 'fill' });

  const panes = H({ name: 'Panes', gap: 16, align: 'MIN' });
  // left: types
  const left = Vt({ name: 'Claim types', p: 8, gap: 2, fill: 'surface/card', stroke: 'line/default', r: 12 });
  put(left, H({ px: 8, py: 6 }, [tx('Claim type', 'overline', 'ink/400')]));
  [['Decisions', 18, true], ['Open work', 14], ['Owners', 22], ['Risks', 9], ['Dependencies', 11], ['Blockers', 4], ['Recurring issues', 7], ['Contacts', 12], ['Open questions', 8], ['Processes', 10], ['Access to transfer', 6]].forEach(([l, n, on]) => {
    put(left, H({ px: 8, py: 7, r: 6, justify: 'SPACE_BETWEEN', fill: on ? 'brand/50' : null }, [tx(l, 'smallM', on ? 'brand/700' : 'ink/700'), tx(String(n), 'caption', on ? 'brand/700' : 'ink/400')]), { w: 'fill' });
  });
  put(left, H({ px: 8, pt: 14, pb: 6 }, [tx('Status', 'overline', 'ink/400')]));
  [['Verified', 61], ['Single source', 38], ['Inferred', 12], ['Conflicting', 4], ['Missing evidence', 6], ['Stale', 4]].forEach(([s, n]) => {
    put(left, H({ px: 8, py: 6, justify: 'SPACE_BETWEEN', align: 'CENTER' }, [H({ gap: 8, align: 'CENTER' }, [dot(8, STATUS[s][0]), tx(s, 'small', 'ink/700')]), tx(String(n), 'caption', 'ink/400')]), { w: 'fill' });
  });
  put(panes, left, { w: 200 });

  // center: list
  const center = Vt({ name: 'Claim list', fill: 'surface/card', stroke: 'line/default', r: 12, clip: true });
  put(center, H({ px: 16, py: 10, gap: 10, align: 'CENTER', fill: 'surface/canvas', stroke: 'line/default', sides: 'b', justify: 'SPACE_BETWEEN' }, [
    H({ gap: 10, align: 'CENTER' }, [checkbox(false), tx('18 decisions', 'smallM')]), tx('Sort: newest evidence', 'caption', 'ink/500'),
  ]), { w: 'fill' });
  [
    { id: 'C-0142', status: 'Verified', label: 'Verified · 2 sources', sens: 'Confidential', text: 'Push notifications moved from Firebase to OneSignal after the free-tier quota ran out during the Diwali drive.', meta: 'Slack #dev · PR #131 review · confidence 0.92', state: 'To review', sel: true },
    { id: 'C-0137', status: 'Conflicting', label: 'Conflicting', sens: 'Internal', text: 'v1.0 launch is on 15 November 2026.', meta: 'Meeting notes 2 Oct · contradicted by Slack 21 Oct', state: 'Blocked' },
    { id: 'C-0129', status: 'Verified', label: 'Verified · system of record', sens: 'Confidential', text: 'Backend moved from Firebase Functions to Spring Boot on Render in September.', meta: 'GitHub commits · README · confidence 0.95', state: 'Accepted', checked: true },
    { id: 'C-0118', status: 'Inferred', label: 'Inferred', sens: 'Internal', text: 'The team prefers Expo managed workflow to avoid native build setup.', meta: 'No direct quote · AI reasoning from 3 chunks', state: 'To review' },
    { id: 'C-0102', status: 'Single source', label: 'Single source', sens: 'Internal', text: 'Chose React Native + Expo over Flutter because two members already knew React.', meta: 'Meeting notes 14 Aug · confidence 0.71', state: 'Accepted', checked: true },
    { id: 'C-0097', status: 'Stale', label: 'Stale', sens: 'Internal', text: 'Backend uses MongoDB Atlas free tier.', meta: 'Superseded by C-0129 · hidden from recipients', state: 'Rejected' },
  ].forEach(o => put(center, claimRow(o), { w: 'fill' }));
  put(panes, center, { w: 'fill' });

  // right: evidence drawer
  const d = card({ name: 'Evidence drawer', gap: 16, p: 20 });
  put(d, H({ justify: 'SPACE_BETWEEN', align: 'CENTER' }, [tx('C-0142 · Decision', 'monoS', 'ink/500'), H({ gap: 8, align: 'CENTER' }, [sens('Confidential'), icon('x', 16, 'ink/400')])]), { w: 'fill' });
  put(d, tx('Push notifications moved from Firebase to OneSignal after the free-tier quota ran out during the Diwali drive.', 'h3'), { w: 'fill' });
  put(d, H({ gap: 8, align: 'CENTER' }, [badge('Verified', 'Verified · 2 independent sources'), tx('Confidence 0.92', 'smallM', 'status/verified')]));
  const conf = Vt({ name: 'Confidence breakdown', gap: 8, p: 14, r: 10, fill: 'surface/canvas' }, [tx('How 0.92 is computed', 'overline', 'ink/400')]);
  [['Source trust', '+0.35', 0.88], ['Independent sources (2)', '+0.25', 1], ['Quote match 100%', '+0.25', 1], ['Recency · 12 Oct', '+0.07', 0.7], ['Conflict penalty', ' 0.00', 0.02]].forEach(([l, v, p]) => {
    const r = confRow(l, v, p, l.startsWith('Conflict') ? 'status/conflict' : 'brand/600');
    put(conf, r, { w: 'fill' });
    r.children[0].layoutSizingHorizontal = 'FILL';
  });
  put(d, conf, { w: 'fill' });
  put(d, tx('Evidence', 'overline', 'ink/400'));
  put(d, quote('slack/#dev · 12 Oct 2026 · chunk 214', '“we hit the FCM cap again during the drive — switching to OneSignal tonight”'), { w: 'fill' });
  put(d, quote('github PR #131 review · 13 Oct 2026', '“OneSignal SDK in, FCM removed. Free tier covers 10k subscribers.”'), { w: 'fill' });
  put(d, infoBanner('Sensitivity inherited: Confidential — the highest level of its evidence (Slack #dev). An edit cannot lower it.', 'single', 'lock'), { w: 'fill' });
  const acts = H({ name: 'Actions', gap: 8 });
  put(acts, btn('Primary', 'Accept'), { w: 'fill' });
  put(acts, btn('Secondary', 'Edit'), { w: 'fill' });
  put(acts, btn('Secondary', 'Needs info'), { w: 'fill' });
  put(acts, btn('Ghost', 'Reject'), { w: 'fill' });
  put(d, acts, { w: 'fill' });
  put(d, H({ name: 'Shortcuts', gap: 6, align: 'CENTER' }, [icon('keyboard', 14, 'ink/400'), kbd('J'), kbd('K'), tx('move', 'caption', 'ink/500'), kbd('A'), tx('accept', 'caption', 'ink/500'), kbd('E'), tx('edit', 'caption', 'ink/500'), kbd('R'), tx('reject', 'caption', 'ink/500'), kbd('N'), tx('needs info', 'caption', 'ink/500')]));
  put(d, divider(), { w: 'fill' });
  put(d, tx('Revision history', 'overline', 'ink/400'));
  put(d, activityItem('priya', 'Priya edited the wording (“Diwali drive”)', '8 Nov, 10:15'), { w: 'fill' });
  put(d, H({ gap: 10, align: 'CENTER' }, [tile('layers', 'ink/500', 'surface/subtle', 24, 12), Vt({ gap: 2 }, [tx('Extracted by pipeline run #7 · prompt v3', 'small'), tx('7 Nov, 14:02', 'caption', 'ink/400')])]));
  put(panes, d, { w: 440 });
  put(content, panes, { w: 'fill' });
  return scr;
}

function screenConflicts() {
  const { scr, content } = shell('Lead / Resolve conflict', 'Conflicts', ['Project Annapurna', 'Handover · Aarav Mehta', 'Conflicts']);
  put(content, hdr('Resolve conflicts', '2 open · approval stays blocked until both are resolved', H({ gap: 8 }, [chip('1 of 2'), btn('Secondary', 'Next conflict')])), { w: 'fill' });
  const main = card({ name: 'Conflict', gap: 18, p: 24 });
  put(main, H({ justify: 'SPACE_BETWEEN', align: 'CENTER' }, [
    Vt({ gap: 4 }, [H({ gap: 8, align: 'CENTER' }, [badge('Conflicting'), tx('Deadline · C-0137 ↔ C-0140', 'monoS', 'ink/400')]), tx('When is the v1.0 launch?', 'h2')]),
    sens('Internal'),
  ]), { w: 'fill' });
  const cols = H({ name: 'Sides', gap: 16, align: 'CENTER' });
  const side = (lab, b, src, q, meta) => {
    const c = Vt({ name: lab, p: 16, gap: 10, r: 10, stroke: 'line/default' });
    put(c, H({ gap: 8, align: 'CENTER' }, [tx(lab, 'overline', 'ink/500'), b]));
    put(c, quote(src, q), { w: 'fill' });
    put(c, tx(meta, 'caption', 'ink/500'), { w: 'fill' });
    return c;
  };
  put(cols, side('Source A', badge('Single source', 'Older · 2 Oct'), 'meeting-notes/2026-10-02.md · chunk 14', '“Launch locked for 15 November, right after mid-sem exams.”', 'Meeting notes · written by Aarav · 37 days ago'), { w: 'fill' });
  const vs = H({ fill: 'surface/subtle', r: 18, justify: 'CENTER', align: 'CENTER' }, [tx('vs', 'smallM', 'ink/500')]);
  vs.resize(36, 36); vs.primaryAxisSizingMode = 'FIXED'; vs.counterAxisSizingMode = 'FIXED';
  put(cols, vs);
  put(cols, side('Source B', badge('Single source', 'Newer · 21 Oct'), 'slack/#annapurna-ops · 21 Oct 2026 · chunk 902', '“ok moving launch to 29 Nov, Play Store review is slow after the rejection”', 'Slack · Aarav · 18 days ago'), { w: 'fill' });
  put(main, cols, { w: 'fill' });
  const test = Vt({ name: 'Testimony', p: 16, gap: 10, r: 10, fill: 'status/verified-bg' });
  put(test, H({ gap: 8, align: 'CENTER' }, [av('aarav', 'Sm'), tx('Aarav’s answer · exit interview Q3 · 7 Nov', 'smallM'), badge('Verified', 'Cited testimony')]));
  put(test, quote('exit-interview/aarav/q3 · 7 Nov 2026', '“29 November is current. We moved it after the Play Store rejected build 0.9 on 19 October.”'), { w: 'fill' });
  put(main, test, { w: 'fill' });
  put(main, tx('Resolution', 'overline', 'ink/400'));
  [[true, 'Keep B — 29 November 2026', 'Newer source and confirmed by the leaver. A becomes Stale: hidden by default, kept in history.'], [false, 'Keep A — 15 November 2026', 'B becomes Stale.'], [false, 'Both are wrong — write the correct value', 'Your edit is stored with your name as the source.']].forEach(([on, t, d]) => {
    const r = H({ name: 'Option', p: 14, gap: 12, r: 10, align: 'MIN', stroke: on ? 'brand/600' : 'line/default', sw: on ? 1.5 : 1, fill: on ? 'brand/50' : 'surface/card' }, [radio(on)]);
    put(r, Vt({ gap: 2 }, [tx(t, 'smallM'), tx(d, 'caption', 'ink/500')]), { w: 'fill' });
    put(main, r, { w: 'fill' });
  });
  put(main, H({ gap: 8, justify: 'MAX' }, [btn('Ghost', 'Ask Aarav again'), btn('Primary', 'Resolve conflict')]), { w: 'fill' });
  put(content, main, { w: 'fill' });
  const next = H({ name: 'Next conflict', p: 16, gap: 12, r: 12, fill: 'surface/card', stroke: 'line/default', align: 'CENTER' }, [badge('Conflicting'), tx('Owner · Who pays for the SMS gateway credits: Aarav or Priya?', 'smallM')]);
  put(next, spacer(), { grow: true });
  put(next, tx('2 sources · no exit-interview answer yet', 'caption', 'ink/500'));
  put(next, icon('chevronRight', 16, 'ink/400'));
  put(content, next, { w: 'fill' });
  return scr;
}

function screenTimeline() {
  const { scr, content } = shell('Lead / Decision timeline', 'Timeline', ['Project Annapurna', 'Handover · Aarav Mehta', 'Timeline']);
  put(content, hdr('Decision timeline', 'Dated decisions, changes and unresolved discussions, rebuilt from sources.', H({ gap: 6 }, [chip('Decisions', { fill: 'ink/900', fg: 'text/inverse' }), chip('Changes', { fill: 'ink/900', fg: 'text/inverse' }), chip('Unresolved', { fill: 'ink/900', fg: 'text/inverse' }), chip('Stale', { fill: 'surface/card', stroke: 'line/default' })])), { w: 'fill' });
  const tl = card({ name: 'Timeline', gap: 0, p: 24 });
  const items = [
    ['14 Aug', 'Decision', 'Chose React Native + Expo over Flutter — two members already knew React.', 'Single source', 'Single source', 'Meeting notes', 'brand/600'],
    ['9 Sep', 'Decision', 'Backend moved from Firebase Functions to Spring Boot on Render.', 'Verified', 'Verified', 'GitHub · README', 'brand/600'],
    ['12 Oct', 'Decision', 'Push notifications moved to OneSignal after the FCM quota ran out.', 'Verified', 'Verified', 'Slack · PR #131', 'brand/600'],
    ['19 Oct', 'Event', 'Play Store rejected build 0.9 (background location permission).', 'Verified', 'Verified', 'GitHub issue #118', 'ink/500'],
    ['21 Oct', 'Change', 'Launch moved from 15 Nov to 29 Nov.', 'Verified', 'Resolved conflict', 'Slack · exit interview', 'status/inferred'],
    ['3 Nov', 'Unresolved', 'Keep donor SMS reminders after December? Credits cost ₹1,800 / month.', 'Missing evidence', 'No decision recorded', 'Meeting notes', 'status/missing'],
  ];
  items.forEach(([date, type, text, st, lab, src, tok], i) => {
    const row = H({ name: 'Entry · ' + date, gap: 0, align: 'MIN' });
    put(row, Vt({ gap: 2, pt: 2 }, [tx(date, 'smallM'), tx('2026', 'caption', 'ink/400')]), { w: 90 });
    const rail = Vt({ name: 'Rail', align: 'CENTER', gap: 4, pt: 4 });
    put(rail, dot(12, tok));
    if (i < items.length - 1) put(rail, rect(2, 40, 'line/default'), { h: 'fill' });
    put(row, rail, { w: 40, h: 'fill' });
    const c = Vt({ name: 'Entry card', gap: 6, pb: 24 });
    put(c, H({ gap: 8, align: 'CENTER' }, [tx(type, 'overline', tok === 'ink/500' ? 'ink/500' : tok), badge(st, lab)]));
    put(c, tx(text, 'bodyM'), { w: 'fill' });
    put(c, H({ gap: 6, align: 'CENTER' }, [icon('link', 12, 'ink/400'), tx(src, 'caption', 'ink/500')]));
    put(row, c, { w: 'fill' });
    put(tl, row, { w: 'fill' });
  });
  const wrapRow = H({ name: 'Body', gap: 24, align: 'MIN' });
  put(wrapRow, tl, { w: 'fill' });
  const legend = card({ name: 'Legend', gap: 10 }, [tx('Reading the timeline', 'h3')]);
  [['brand/600', 'Decision — what was chosen and why'], ['status/inferred', 'Change — a later source replaced an earlier one'], ['status/missing', 'Unresolved — becomes an exit-interview question'], ['ink/500', 'Event — from a system of record']].forEach(([t, l]) => {
    const r = H({ gap: 10, align: 'CENTER' }, [dot(10, t)]);
    put(r, tx(l, 'small', 'ink/700'), { w: 'fill' });
    put(legend, r, { w: 'fill' });
  });
  put(wrapRow, legend, { w: 340 });
  put(content, wrapRow, { w: 'fill' });
  return scr;
}
