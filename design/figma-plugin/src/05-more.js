// ---------------------------------------------------------------- lead: view as, approval, share
function screenViewAs() {
  const { scr, content } = shell('Lead / View as preview', 'View as', ['Project Annapurna', 'Handover · Aarav Mehta', 'View as']);
  put(content, hdr('Preview what each recipient sees', 'Same approved handover, different views. Filtering runs in database queries, never only in the UI.'), { w: 'fill' });
  const personas = H({ name: 'Persona picker', gap: 12 });
  [['rohan', 'Confidential', 'engineering', '118 of 142 claims', true], ['sara', 'Internal', 'design', '41 of 142 claims', false], ['priya', 'Restricted', 'engineering · operations', '142 of 142 claims', false]].forEach(([p, lvl, g, n, on]) => {
    const c = H({ name: 'Persona', p: 14, gap: 12, r: 12, align: 'CENTER', fill: on ? 'brand/50' : 'surface/card', stroke: on ? 'brand/600' : 'line/default', sw: on ? 1.5 : 1 }, [av(p)]);
    const v = Vt({ gap: 4 });
    put(c, v, { w: 'fill' });
    put(v, tx(P[p].n, 'smallM'), { w: 'fill' });
    put(v, tx(P[p].role + ' · ' + g, 'caption', 'ink/500'), { w: 'fill' });
    put(v, H({ gap: 8, align: 'CENTER' }, [sens(lvl), tx(n, 'caption', 'ink/700')]));
    put(personas, c, { w: 'fill' });
  });
  put(content, personas, { w: 'fill' });
  put(content, infoBanner('Rohan will see 118 of 142 claims. 24 are hidden: 9 are Restricted (operations — vendor contracts, budget) and 15 belong to groups he is not in. A claim inherits the highest sensitivity of its evidence, so paraphrasing cannot leak it.', 'single', 'eyeOff'), { w: 'fill' });

  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const frame = Vt({ name: 'Recipient preview', r: 12, stroke: 'line/strong', fill: 'surface/canvas', clip: true });
  put(frame, H({ name: 'Preview chrome', px: 14, py: 10, gap: 6, fill: 'surface/subtle', align: 'CENTER' }, [dot(8, 'line/strong'), dot(8, 'line/strong'), dot(8, 'line/strong'), tx('   Recipient view · Rohan Kulkarni · read-only preview', 'caption', 'ink/500')]), { w: 'fill' });
  const pv = Vt({ name: 'Preview body', p: 20, gap: 14 });
  put(frame, pv, { w: 'fill' });
  put(pv, tx('Welcome, Rohan. Here’s what you’re picking up.', 'h2'), { w: 'fill' });
  const facts = H({ gap: 10 });
  [['Next deadline', '29 Nov · v1.0 launch'], ['Phase', 'Beta · closed testing'], ['Open work', '14 items, 5 yours']].forEach(([l, v]) => put(facts, Vt({ p: 12, gap: 2, r: 8, fill: 'surface/card', stroke: 'line/default' }, [tx(l, 'caption', 'ink/500'), tx(v, 'smallM')]), { w: 'fill' }));
  put(pv, facts, { w: 'fill' });
  [['Verified', 'Deploy: merge to main → GitHub Actions → Render (auto).'], ['Inferred', 'Database backups likely run only from Aarav’s laptop — not confirmed.'], ['Single source', 'SMS vendor contact: Meera at TextLocal.']].forEach(([s, t]) => {
    const r = H({ p: 12, gap: 10, r: 8, fill: 'surface/card', stroke: 'line/default', align: 'CENTER' }, [badge(s, s === 'Inferred' ? 'Likely, not confirmed' : s === 'Single source' ? 'One source' : 'Fact')]);
    put(r, tx(t, 'small'), { w: 'fill' });
    put(pv, r, { w: 'fill' });
  });
  const hidden = H({ name: 'Hidden placeholder', p: 12, gap: 10, r: 8, stroke: 'line/strong', dash: true, align: 'CENTER' }, [icon('eyeOff', 16, 'ink/400')]);
  put(hidden, tx('3 claims in this section are hidden for Rohan (Restricted · operations). He sees that something exists, not what it says.', 'small', 'ink/500'), { w: 'fill' });
  put(pv, hidden, { w: 'fill' });
  put(body, frame, { w: 'fill' });
  const cmp = Vt({ name: 'Comparison', gap: 12 }, [tx('What each recipient sees', 'h3')]);
  put(cmp, table([{ label: 'Section', w: 'fill' }, { label: 'Rohan', w: 50 }, { label: 'Sara', w: 44 }, { label: 'Priya', w: 44 }], [
    ['Start here', '5/5', '5/5', '5/5'], ['Open work', '14', '6', '14'], ['Risks', '7', '2', '9'], ['Contacts', '9', '3', '12'], ['Access to transfer', '4', '0', '6'], ['Design decisions', '6', '6', '6'],
  ], 'Visibility table'), { w: 'fill' });
  put(body, cmp, { w: 380 });
  put(content, body, { w: 'fill' });
  return scr;
}

function gateRow(kind, title, desc, action) {
  const r = H({ name: 'Gate · ' + title, gap: 12, align: 'CENTER', py: 12, stroke: 'line/default', sides: 'b' }, [circleStatus(kind, 24)]);
  const v = Vt({ gap: 2 });
  put(r, v, { w: 'fill' });
  put(v, tx(title, 'smallM'), { w: 'fill' });
  put(v, tx(desc, 'caption', 'ink/500'), { w: 'fill' });
  if (action) put(r, link(action));
  return r;
}

function diffLine(sign, text, tok, bg) {
  const r = H({ name: 'Diff line', px: 12, py: 6, gap: 10, r: 6, fill: bg, align: 'MIN' }, [tx(sign, 'mono', tok)]);
  put(r, tx(text, 'mono', 'ink/900', { strike: sign === '−' }), { w: 'fill' });
  return r;
}

function screenApproval() {
  const { scr, content } = shell('Lead / Approval & versions', 'Approval', ['Project Annapurna', 'Handover · Aarav Mehta', 'Approval']);
  put(content, stepper(3));
  put(content, hdr('Approve handover v2', 'Approval freezes a version with a SHA-256 hash. Recipients only ever see approved versions.'), { w: 'fill' });
  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const gate = card({ name: 'Approval gate', gap: 0, p: 24 }, [cardHead('Approval gate', '2 blockers left', badge('Conflicting', 'Blocked'))]);
  gate.children[0].layoutSizingHorizontal = 'FILL';
  put(gate, gateRow('done', 'Every verified claim has a matching quote', '212 of 212 quotes matched by the citation verifier'), { w: 'fill' });
  put(gate, gateRow('fail', '2 conflicts unresolved', 'Launch date · SMS gateway budget owner', 'Resolve'), { w: 'fill' });
  put(gate, gateRow('warn', '12 inferred claims need a decision', 'Accept, edit or reject — inferred claims are never shown as facts', 'Review'), { w: 'fill' });
  put(gate, gateRow('done', 'Critical items reviewed', '9 of 9 risks · 6 of 6 access-to-transfer items'), { w: 'fill' });
  put(gate, gateRow('done', 'Exit interview complete', '9 of 9 questions answered or routed to the lead'), { w: 'fill' });
  put(gate, gateRow('done', 'Permission check passed', 'No claim visible above a recipient’s clearance (automated test)'), { w: 'fill' });
  const foot = H({ name: 'Approve row', pt: 18, gap: 12, align: 'CENTER', justify: 'SPACE_BETWEEN' });
  put(foot, tx('Resolve 2 blockers to approve. You can still preview with “View as”.', 'small', 'ink/500'));
  const ab = btn('Primary', 'Approve and freeze v2', 'Lg'); ab.opacity = 0.4;
  put(foot, ab);
  put(gate, foot, { w: 'fill' });
  put(body, gate, { w: 'fill' });

  const side = Vt({ name: 'Versions', gap: 16 });
  const vers = card({ name: 'Version history', gap: 10 }, [tx('Versions', 'h3')]);
  const ver = (v, b, meta, hash, cur) => {
    const c = Vt({ name: 'Version ' + v, p: 14, gap: 6, r: 10, stroke: cur ? 'brand/600' : 'line/default', sw: cur ? 1.5 : 1, fill: cur ? 'brand/50' : 'surface/card' });
    put(c, H({ gap: 8, align: 'CENTER' }, [tx(v, 'h3'), b]));
    put(c, tx(meta, 'caption', 'ink/500'));
    put(c, H({ gap: 6, align: 'CENTER' }, [icon('commit', 12, 'ink/400'), tx(hash, 'monoS', 'ink/700')]));
    return c;
  };
  put(vers, ver('v2', badge('Inferred', 'Draft'), '142 claims · edited today by Priya', 'not frozen yet', true), { w: 'fill' });
  put(vers, ver('v1', badge('Verified', 'Approved'), '131 claims · approved 1 Nov by Priya · shared with 1', 'sha256 3f9a1c…7be0c21e', false), { w: 'fill' });
  put(side, vers, { w: 'fill' });
  const diff = card({ name: 'Diff v1 → v2', gap: 8 }, [cardHead('Changes since v1', '+11 added · 4 changed · 2 now stale')]);
  diff.children[0].layoutSizingHorizontal = 'FILL';
  put(diff, diffLine('+', 'Launch date: 29 November 2026', 'status/verified', 'status/verified-bg'), { w: 'fill' });
  put(diff, diffLine('−', 'Launch date: 15 November 2026', 'status/conflict', 'status/conflict-bg'), { w: 'fill' });
  put(diff, diffLine('~', 'Owner of donor onboarding: unknown → Rohan Kulkarni', 'status/inferred', 'status/inferred-bg'), { w: 'fill' });
  put(diff, diffLine('+', 'Access to transfer: Play Console → Priya by 18 Nov', 'status/verified', 'status/verified-bg'), { w: 'fill' });
  put(side, diff, { w: 'fill' });
  put(body, side, { w: 420 });
  put(content, body, { w: 'fill' });
  return scr;
}

function screenShare() {
  const { scr, content } = shell('Lead / Share & acknowledgement', 'Share', ['Project Annapurna', 'Handover · Aarav Mehta', 'Share']);
  put(content, stepper(4));
  put(content, hdr('Share approved handover v1', 'Each recipient gets their own role-filtered view. Shares can expire or be revoked; every view is logged.', btn('Primary', 'Add recipient')), { w: 'fill' });
  const rows = [
    [personCell('rohan', 'rohan.k@northfield.club'), 'Engineer view', sens('Confidential'), '20 Dec 2026', H({ gap: 6, align: 'CENTER' }, [badge('Single source', 'Opened · not acknowledged')]), H({ gap: 8 }, [btn('Secondary', 'Remind'), btn('Ghost', 'Revoke')])],
    [personCell('sara', 'sara.d@northfield.club'), 'Design view', sens('Internal'), '20 Dec 2026', badge('Verified', 'Acknowledged 7 Nov'), H({ gap: 8 }, [btn('Secondary', 'Copy link'), btn('Ghost', 'Revoke')])],
    [personCell('Meera Joshi', 'external · NGO partner'), 'Partner summary', sens('Public'), '1 Dec 2026', badge('Stale', 'Expired'), H({ gap: 8 }, [btn('Secondary', 'Renew')])],
  ];
  put(content, table([{ label: 'Recipient', w: 'fill' }, { label: 'View', w: 130 }, { label: 'Clearance', w: 120 }, { label: 'Expires', w: 110 }, { label: 'Status', w: 210 }, { label: 'Actions', w: 170 }], rows, 'Recipients'), { w: 'fill' });
  const lower = H({ name: 'Lower', gap: 24, align: 'MIN' });
  const add = card({ name: 'Add recipient', gap: 14 }, [tx('Add a recipient', 'h3')]);
  const fr = H({ gap: 12 });
  put(fr, input('Email', 'new.member@northfield.club', 'Default', false), { w: 'fill' });
  put(fr, input('View', 'Engineer view'), { w: 150 });
  put(fr, input('Expires', '20 Dec 2026'), { w: 130 });
  put(add, fr, { w: 'fill' });
  put(add, H({ gap: 8, justify: 'MAX' }, [btn('Primary', 'Share v1')]), { w: 'fill' });
  put(lower, add, { w: 'fill' });
  const ack = card({ name: 'Acknowledgement', gap: 10 }, [tx('Did the handover land?', 'h3')]);
  put(ack, H({ gap: 8, align: 'MAX' }, [tx('1 / 2', 'stat'), tx('recipients acknowledged', 'small', 'ink/500')]));
  put(ack, bar(0.5, 340, 'status/verified'));
  put(ack, tx('Rohan opened the handover 2 hours ago and read Start here. You’ll be notified when he acknowledges.', 'small', 'ink/500'), { w: 'fill' });
  put(lower, ack, { w: 400 });
  put(content, lower, { w: 'fill' });
  return scr;
}

// ---------------------------------------------------------------- leaver: exit interview
function screenExitInterview() {
  const scr = screenFrame('Leaver / Exit interview', 1440, 900, 'surface/canvas', 'v');
  const top = H({ name: 'Top bar', fill: 'surface/card', stroke: 'line/default', sides: 'b', px: 40, py: 14, justify: 'SPACE_BETWEEN', align: 'CENTER' });
  put(top, H({ gap: 16, align: 'CENTER' }, [logo(false), rect(1, 24, 'line/default'), tx('Exit interview · Project Annapurna', 'smallM', 'ink/700')]));
  put(top, H({ gap: 16, align: 'CENTER' }, [tx('5 of 9 answered · about 12 minutes left', 'small', 'ink/500'), bar(5 / 9, 160, 'status/verified'), av('aarav')]));
  put(scr, top, { w: 'fill' });
  const body = H({ name: 'Body', px: 160, py: 32, gap: 32, align: 'MIN' });
  put(scr, body, { w: 'fill' });

  const ql = Vt({ name: 'Question list', gap: 4 });
  put(ql, tx('Only what sources can’t answer', 'overline', 'ink/400'));
  put(ql, tx('9 questions instead of a 20-page document.', 'small', 'ink/500'), { w: 'fill' });
  put(ql, rect(10, 8, null));
  [['done', 'How do we deploy to production?', 'Process'], ['done', 'Who owns the SMS gateway budget?', 'Owner'], ['done', 'Which launch date is current?', 'Conflict'], ['done', 'Why was Firebase dropped?', 'Decision'], ['done', 'Where do DB backups run?', 'Risk'], ['run', 'Who owns the Play Console account?', 'Access to transfer'], ['todo', 'What blocks PR #142?', 'Open work'], ['todo', 'Who is the contact at the NGO?', 'Contact'], ['todo', 'Keep donor SMS reminders?', 'Open question']].forEach(([k, q, t], i) => {
    const on = k === 'run';
    const r = H({ name: 'Q' + (i + 1), p: 10, gap: 10, r: 8, align: 'CENTER', fill: on ? 'surface/card' : null, stroke: on ? 'brand/600' : null, sw: 1.5 }, [circleStatus(k === 'run' ? 'todo' : k, 20)]);
    const v = Vt({ gap: 0 });
    put(r, v, { w: 'fill' });
    put(v, tx(q, 'smallM', k === 'todo' ? 'ink/500' : 'ink/900'), { w: 'fill' });
    put(v, tx(t, 'caption', 'ink/400'));
    put(ql, r, { w: 'fill' });
  });
  put(body, ql, { w: 320 });

  const qc = card({ name: 'Question card', p: 32, gap: 20, shadow: 'pop' });
  put(qc, H({ gap: 8, align: 'CENTER' }, [tx('Question 6 of 9 · Access to transfer', 'overline', 'brand/600'), sens('Restricted')]));
  put(qc, tx('Who owns the Google Play Console account, and how do we transfer it before you leave?', 'h1'), { w: 'fill' });
  const why = Vt({ name: 'Why Baton is asking', p: 16, gap: 10, r: 10, fill: 'status/missing-bg' });
  put(why, H({ gap: 8, align: 'CENTER' }, [icon('info', 14, 'status/missing'), tx('Why Baton is asking', 'smallM', 'status/missing')]));
  put(why, tx('No source names an owner for this account, and one chat message suggests it is under a personal Gmail. Losing it would block the 29 Nov launch.', 'small', 'ink/900'), { w: 'fill' });
  put(why, quote('slack/#annapurna-ops · 19 Oct 2026', '“play console login is on my gmail, I’ll sort it later”'), { w: 'fill' });
  put(qc, why, { w: 'fill' });
  put(qc, tx('Your answer', 'caption', 'ink/700'));
  const ta = Vt({ name: 'Answer', p: 14, gap: 8, r: 10, stroke: 'brand/600', sw: 1.5, fill: 'surface/card' });
  put(ta, tx('It’s registered to my personal Gmail ([EMAIL_1]). I’ll add Priya as an admin this week and transfer ownership to the club account before 18 Nov. Recovery codes are in the club password manager under “Play Console”.', 'body'), { w: 'fill' });
  put(ta, rect(10, 30, null));
  put(ta, H({ gap: 6, align: 'CENTER' }, [icon('lock', 12, 'status/verified'), tx('Emails and phone numbers you type are redacted before AI processing.', 'caption', 'ink/500')]));
  put(qc, ta, { w: 'fill' });
  const acts = H({ name: 'Actions', gap: 8, align: 'CENTER' });
  put(acts, btn('Ghost', 'I don’t know'));
  put(acts, btn('Secondary', 'Someone else knows →'));
  put(acts, spacer(), { grow: true });
  put(acts, btn('Primary', 'Save and next', 'Lg'));
  put(qc, acts, { w: 'fill' });
  put(qc, divider(), { w: 'fill' });
  put(qc, tx('Your answers become cited testimony. Priya reviews them before anything is approved or shared.', 'caption', 'ink/500'), { w: 'fill' });
  put(body, qc, { w: 'fill' });
  return scr;
}

// ---------------------------------------------------------------- recipient
function riskRow(n, text, owner, status, label) {
  const r = H({ name: 'Risk ' + n, py: 12, gap: 12, align: 'CENTER', stroke: 'line/default', sides: 'b' }, [tx(String(n), 'h3', 'ink/300')]);
  const v = Vt({ gap: 4 });
  put(r, v, { w: 'fill' });
  put(v, tx(text, 'smallM'), { w: 'fill' });
  put(v, badge(status, label));
  put(r, H({ gap: 8, align: 'CENTER' }, [av(owner, 'Sm'), tx(P[owner].n.split(' ')[0], 'caption', 'ink/700')]));
  return r;
}
function checkItem(on, text, claim, who) {
  const r = H({ name: 'Checklist item', p: 12, gap: 12, r: 10, align: 'MIN', stroke: 'line/default', fill: on ? 'surface/canvas' : 'surface/card' }, [checkbox(on)]);
  const v = Vt({ gap: 6 });
  put(r, v, { w: 'fill' });
  put(v, tx(text, 'small', on ? 'ink/500' : 'ink/900', { strike: on }), { w: 'fill' });
  put(v, H({ gap: 8, align: 'CENTER' }, [chip(claim, { fill: 'brand/50', fg: 'brand/700' }), who ? tx(who, 'caption', 'ink/500') : null]));
  return r;
}

function screenStartHere() {
  const { scr, content } = recipientShell('Recipient / Start here', 'Start here');
  const hero = Vt({ name: 'Hero', p: 32, gap: 20, r: 16, fill: 'surface/inverse' });
  put(hero, tx('Start here', 'overline', 'accent/baton'));
  put(hero, tx('Welcome, Rohan. Here’s what you’re picking up from Aarav.', 'h1', 'text/inverse'), { w: 'fill' });
  const facts = H({ name: 'Facts', gap: 12 });
  [['Project goal', 'Campus food-rescue app linking mess halls with NGOs', 'Verified'], ['Current phase', 'Beta · v0.9 in closed testing with 40 volunteers', 'Verified'], ['Next deadline', '29 Nov 2026 · v1.0 on the Play Store', 'Verified']].forEach(([l, v, s]) => {
    put(facts, Vt({ name: l, p: 16, gap: 6, r: 12, fill: 'surface/inverse-raised' }, [tx(l, 'caption', 'text/inverse-muted'), tx(v, 'bodyM', 'text/inverse'), badge(s, 'Fact · linked')]), { w: 'fill' });
  });
  facts.children.forEach(f => { f.children[1].layoutSizingHorizontal = 'FILL'; f.children[1].textAutoResize = 'HEIGHT'; });
  put(hero, facts, { w: 'fill' });
  put(content, hero, { w: 'fill' });

  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const risks = card({ name: 'Top risks', gap: 0 }, [cardHead('Top 5 risks', 'Each with an owner', link('All 7 risks'))]);
  risks.children[0].layoutSizingHorizontal = 'FILL';
  put(risks, riskRow(1, 'Database backups run only from a cron job on Aarav’s laptop.', 'rohan', 'Inferred', 'Likely, not confirmed'), { w: 'fill' });
  put(risks, riskRow(2, 'Play Console account is on a personal Gmail — transfer by 18 Nov.', 'priya', 'Verified', 'Fact · testimony'), { w: 'fill' });
  put(risks, riskRow(3, 'PR #142 timezone tests fail (IST → UTC).', 'rohan', 'Verified', 'Fact'), { w: 'fill' });
  put(risks, riskRow(4, 'SMS gateway credits run out in mid-December.', 'priya', 'Single source', 'One source'), { w: 'fill' });
  put(risks, riskRow(5, 'Build breaks on every Expo SDK upgrade — keep the version pinned.', 'rohan', 'Single source', 'One source'), { w: 'fill' });
  put(body, risks, { w: 'fill' });

  const cl = card({ name: 'Continuation checklist', gap: 12 });
  put(cl, H({ justify: 'SPACE_BETWEEN', align: 'CENTER' }, [tx('Your checklist', 'h3'), tx('1 of 5 done', 'caption', 'ink/500')]), { w: 'fill' });
  const tabs = H({ name: 'Segmented', p: 3, gap: 2, r: 8, fill: 'surface/subtle' });
  ['Day 1', 'Week 1', 'Month 1'].forEach((l, i) => put(tabs, H({ px: 12, py: 6, r: 6, fill: i === 0 ? 'surface/card' : null, shadow: i === 0 ? 'card' : null, justify: 'CENTER' }, [tx(l, 'smallM', i === 0 ? 'ink/900' : 'ink/500')]), { w: 'fill' }));
  put(cl, tabs, { w: 'fill' });
  put(cl, checkItem(true, 'Get added to the GitHub org and the Render team', 'C-0129', 'Ask Priya'), { w: 'fill' });
  put(cl, checkItem(false, 'Read the deploy runbook: merge to main → GitHub Actions → Render', 'C-0151', null), { w: 'fill' });
  put(cl, checkItem(false, 'Pick up PR #142 “Volunteer shift swap” and fix the timezone tests', 'C-0088', 'Aarav until 20 Nov'), { w: 'fill' });
  put(cl, checkItem(false, 'Move the backup cron job to the server and confirm it runs', 'C-0163', 'Inferred — check first'), { w: 'fill' });
  put(cl, checkItem(false, 'Introduce yourself to Meera (SMS vendor)', 'C-0170', 'See ownership map'), { w: 'fill' });
  put(body, cl, { w: 480 });
  put(content, body, { w: 'fill' });

  const ack = H({ name: 'Acknowledge', p: 20, gap: 16, r: 12, fill: 'surface/card', stroke: 'line/default', align: 'CENTER' }, [tile('check', 'status/verified', 'status/verified-bg', 40)]);
  put(ack, Vt({ gap: 2 }, [tx('Read through Start here and your Day 1 list?', 'bodyM'), tx('Acknowledging tells Priya the handover landed. You can still ask questions afterwards.', 'small', 'ink/500')]), { w: 'fill' });
  put(ack, btn('Secondary', 'Ask a question'));
  put(ack, btn('Primary', 'I’ve read this handover', 'Lg'));
  put(content, ack, { w: 'fill' });
  return scr;
}

function ownerCard(area, owner, note, status, label, extra) {
  const c = card({ name: 'Area · ' + area, gap: 12 });
  put(c, H({ justify: 'SPACE_BETWEEN', align: 'CENTER' }, [tx(area, 'h3'), badge(status, label)]), { w: 'fill' });
  const o = H({ gap: 10, align: 'CENTER' }, [owner ? av(owner) : H({ r: 16, fill: 'status/missing-bg', justify: 'CENTER', align: 'CENTER' }, [tx('?', 'smallM', 'status/missing')])]);
  if (!owner) { const q = o.children[0]; q.resize(32, 32); q.primaryAxisSizingMode = 'FIXED'; q.counterAxisSizingMode = 'FIXED'; }
  put(o, Vt({ gap: 0 }, [tx(owner ? (P[owner] ? P[owner].n : owner) : 'Unknown owner', 'smallM', owner ? 'ink/900' : 'status/missing'), tx(note, 'caption', 'ink/500')]), { w: 'fill' });
  put(c, o, { w: 'fill' });
  if (extra) put(c, extra, { w: 'fill' });
  return c;
}

function screenOwnership() {
  const { scr, content } = recipientShell('Recipient / Ownership map', 'Ownership map');
  put(content, hdr('Who to ask about what', 'Owners after the handover, with the evidence behind each one.', H({ gap: 6 }, [chip('All areas', { fill: 'ink/900', fg: 'text/inverse' }), chip('Mine', { fill: 'surface/card', stroke: 'line/default' }), chip('Unknown', { fill: 'surface/card', stroke: 'line/default' })])), { w: 'fill' });
  const grid = H({ name: 'Area grid', gap: 16, wrap: true });
  put(content, grid, { w: 'fill' });
  const cards = [
    ownerCard('Mobile app', 'rohan', 'You · previously Aarav', 'Verified', 'Verified', tx('React Native + Expo · 41 files', 'caption', 'ink/500')),
    ownerCard('Backend & deploy', 'rohan', 'You · Spring Boot on Render', 'Verified', 'Verified', tx('Runbook C-0151 · GitHub Actions', 'caption', 'ink/500')),
    ownerCard('Design system & UI', 'sara', 'Design volunteer', 'Single source', 'One source', tx('Figma file linked in README', 'caption', 'ink/500')),
    ownerCard('Play Console & store listing', 'priya', 'Transferring from Aarav by 18 Nov', 'Verified', 'Testimony', H({ gap: 6, align: 'CENTER' }, [icon('key', 12, 'status/missing'), tx('Access to transfer', 'caption', 'status/missing')])),
    ownerCard('SMS gateway vendor', 'priya', 'Budget owner · contact: Meera, TextLocal', 'Single source', 'One source', H({ gap: 6, align: 'CENTER' }, [icon('phone', 12, 'ink/500'), tx('[PHONE_2] · visible at Confidential', 'monoS', 'ink/500')])),
    ownerCard('NGO partnerships', 'Ishita Rao', 'Events team · weekly call on Fridays', 'Single source', 'One source', null),
    ownerCard('Database backups', null, 'No owner in any source — asked Priya', 'Missing evidence', 'Unknown', btn('Secondary', 'Ask Priya')),
    ownerCard('Donor onboarding epic', null, 'Open question from the exit interview', 'Missing evidence', 'Unknown', btn('Secondary', 'Ask Priya')),
  ];
  cards.forEach(c => put(grid, c, { w: 361 }));
  return scr;
}

function screenMobileStart() {
  const scr = screenFrame('Recipient / Start here — mobile', 390, 844, 'surface/canvas', 'v');
  const top = H({ name: 'Top bar', fill: 'surface/card', stroke: 'line/default', sides: 'b', px: 16, py: 12, justify: 'SPACE_BETWEEN', align: 'CENTER' }, [logo(false), av('rohan', 'Sm')]);
  put(scr, top, { w: 'fill' });
  const c = Vt({ name: 'Content', p: 16, gap: 16 });
  put(scr, c, { w: 'fill' });
  const hero = Vt({ name: 'Hero', p: 20, gap: 12, r: 14, fill: 'surface/inverse' });
  put(hero, tx('Start here', 'overline', 'accent/baton'));
  put(hero, tx('Welcome, Rohan.', 'h2', 'text/inverse'));
  const dl = Vt({ p: 12, gap: 4, r: 10, fill: 'surface/inverse-raised' }, [tx('Next deadline', 'caption', 'text/inverse-muted'), tx('29 Nov · v1.0 on the Play Store', 'bodyM', 'text/inverse'), badge('Verified', 'Fact · linked')]);
  put(hero, dl, { w: 'fill' });
  put(c, hero, { w: 'fill' });
  const risks = card({ name: 'Risks', gap: 0, p: 16 }, [tx('Top risks', 'h3')]);
  [['Backups only on Aarav’s laptop', 'Inferred', 'Likely, not confirmed'], ['Play Console on personal Gmail', 'Verified', 'Fact'], ['PR #142 tests failing', 'Verified', 'Fact']].forEach(([t, s, l]) => {
    const r = Vt({ py: 10, gap: 4, stroke: 'line/default', sides: 'b' }, [tx(t, 'smallM'), badge(s, l)]);
    put(risks, r, { w: 'fill' });
  });
  put(c, risks, { w: 'fill' });
  const cl = card({ name: 'Day 1', gap: 10, p: 16 }, [tx('Day 1', 'h3')]);
  put(cl, checkItem(true, 'Get added to GitHub and Render', 'C-0129', null), { w: 'fill' });
  put(cl, checkItem(false, 'Read the deploy runbook', 'C-0151', null), { w: 'fill' });
  put(cl, checkItem(false, 'Pick up PR #142', 'C-0088', null), { w: 'fill' });
  put(c, cl, { w: 'fill' });
  put(c, btn('Primary', 'I’ve read this handover', 'Lg'), { w: 'fill' });
  return scr;
}

// ---------------------------------------------------------------- admin
function screenUsers() {
  const { scr, content } = shell('Admin / Users & groups', 'Users & groups', ['Northfield Tech Club', 'Users & groups']);
  put(content, hdr('Users & groups', 'Clearance and groups decide what each person can see. Changes are recorded in the audit log.', H({ gap: 8 }, [btn('Secondary', 'New group'), btn('Primary', 'Invite people')])), { w: 'fill' });
  const tabs = H({ name: 'Tabs', gap: 4, stroke: 'line/default', sides: 'b' });
  [['Users · 24', true], ['Groups · 4'], ['Pending invites · 2']].forEach(([t, on]) => put(tabs, H({ px: 12, py: 10, stroke: on ? 'brand/600' : null, sw: 2, sides: 'b' }, [tx(t, 'smallM', on ? 'brand/700' : 'ink/500')])));
  put(content, tabs, { w: 'fill' });
  const body = H({ name: 'Body', gap: 24, align: 'MIN' });
  const g = (l) => { const r = H({ gap: 4 }); l.forEach(x => put(r, chip(x))); return r; };
  put(body, table([{ label: 'Name', w: 'fill' }, { label: 'Role', w: 120 }, { label: 'Clearance', w: 110 }, { label: 'Groups', w: 190 }], [
    [personCell('priya', 'priya.nair@northfield.club'), 'Project lead', sens('Restricted'), g(['engineering', 'operations'])],
    [personCell('aarav', 'aarav.m@northfield.club'), 'Member · leaving', sens('Confidential'), g(['engineering'])],
    [personCell('rohan', 'rohan.k@northfield.club'), 'Member', sens('Confidential'), g(['engineering'])],
    [personCell('sara', 'sara.d@northfield.club'), 'Volunteer', sens('Internal'), g(['design'])],
    [personCell('kabir', 'kabir.s@northfield.club'), 'Org admin', sens('Restricted'), g(['all members'])],
    [personCell('Ishita Rao', 'ishita.r@northfield.club'), 'Member · leaving', sens('Internal'), g(['events'])],
  ], 'Users table'), { w: 'fill' });
  const groups = card({ name: 'Groups', gap: 10 }, [tx('Access groups', 'h3')]);
  [['engineering', '6 members · Confidential sources'], ['design', '3 members · Internal sources'], ['operations', '2 members · vendor contracts, budget'], ['all members', '24 members · Public and Internal']].forEach(([n, d]) => {
    const r = H({ p: 12, gap: 10, r: 10, stroke: 'line/default', align: 'CENTER' }, [tile('users', 'ink/700', 'surface/subtle', 32)]);
    put(r, Vt({ gap: 0 }, [tx(n, 'smallM'), tx(d, 'caption', 'ink/500')]), { w: 'fill' });
    put(r, icon('chevronRight', 14, 'ink/400'));
    put(groups, r, { w: 'fill' });
  });
  put(groups, infoBanner('Sensitivity order: Public < Internal < Confidential < Restricted. A person sees a claim only if their clearance and groups cover all of its evidence.', 'single', 'info'), { w: 'fill' });
  put(body, groups, { w: 360 });
  put(content, body, { w: 'fill' });
  return scr;
}

function screenAudit() {
  const { scr, content } = shell('Admin / Audit log', 'Audit log', ['Northfield Tech Club', 'Audit log']);
  put(content, hdr('Audit log', 'Every change, every AI request and every view of a share. Append-only.', H({ gap: 8 }, [btn('Secondary', 'Filters'), btn('Secondary', 'Export CSV')])), { w: 'fill' });
  const f = H({ gap: 8, align: 'CENTER' });
  [['All events', true], ['Views'], ['Edits'], ['Approvals'], ['AI requests'], ['Redaction'], ['Access changes']].forEach(([l, on]) => put(f, chip(l, on ? { fill: 'ink/900', fg: 'text/inverse' } : { fill: 'surface/card', stroke: 'line/default' })));
  put(content, f);
  const actor = (p) => P[p] ? H({ gap: 8, align: 'CENTER' }, [av(p, 'Sm'), tx(P[p].n, 'small', 'ink/900')]) : H({ gap: 8, align: 'CENTER' }, [tile('layers', 'ink/500', 'surface/subtle', 24, 12), tx('System', 'small', 'ink/700')]);
  const ev = (k, l) => chip(l, { fill: k + '-bg', fg: k });
  put(content, table([{ label: 'Time', w: 120, mono: true, tone: 'ink/500' }, { label: 'Actor', w: 180 }, { label: 'Event', w: 150 }, { label: 'Target', w: 210 }, { label: 'Detail', w: 'fill' }], [
    ['09 Nov 14:22', actor('rohan'), ev('status/single', 'Viewed share'), 'Handover v1 · engineer view', '118 claims shown · 24 hidden by policy'],
    ['09 Nov 11:05', actor('priya'), ev('status/verified', 'Approved'), 'Handover v1', 'sha256 3f9a1c…7be0c21e · 131 claims'],
    ['08 Nov 18:40', actor('system'), ev('status/missing', 'Redacted'), 'Slack export', '5 API keys · 9 emails · 6 phone numbers'],
    ['08 Nov 10:15', actor('priya'), ev('status/inferred', 'Edited claim'), 'C-0142 · Decision', 'Wording changed · revision 2'],
    ['07 Nov 16:02', actor('aarav'), ev('status/single', 'Answered'), 'Exit interview · Q6', 'Stored as testimony · re-scored 4 claims'],
    ['07 Nov 14:02', actor('system'), ev('status/stale', 'AI request'), 'Extraction batch 26 / 38', 'gpt-oss-120b · redacted text only · 0 secrets'],
    ['06 Nov 09:30', actor('priya'), ev('status/verified', 'Connected'), 'GitHub · annapurna', 'Read-only · authorisation confirmed'],
    ['05 Nov 17:48', actor('kabir'), ev('status/conflict', 'Access change'), 'Rohan Kulkarni', 'Clearance Internal → Confidential'],
  ], 'Audit table'), { w: 'fill' });
  return scr;
}
