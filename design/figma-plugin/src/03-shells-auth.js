// ---------------------------------------------------------------- demo data (synthetic: Project Annapurna)
const P = {
  aarav: { n: 'Aarav Mehta', i: 'AM', t: 2, role: 'Tech lead · leaving 20 Nov' },
  priya: { n: 'Priya Nair', i: 'PN', t: 0, role: 'Project lead' },
  rohan: { n: 'Rohan Kulkarni', i: 'RK', t: 1, role: 'Incoming engineer' },
  sara: { n: 'Sara D’Souza', i: 'SD', t: 3, role: 'Incoming design volunteer' },
  kabir: { n: 'Kabir Shah', i: 'KS', t: 4, role: 'Organisation admin' },
};
function av(p, size = 'Md') {
  if (P[p]) return avatar(P[p].i, size, P[p].t);
  const ini = p.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
  return avatar(ini, size, p.length);
}

function screenFrame(name, w = 1440, h = 900, fill = 'surface/canvas', dir = 'h', hug = true) {
  const s = box({ name, dir, fill, clip: true });
  s.resize(w, h);
  if (dir === 'h') { s.primaryAxisSizingMode = 'FIXED'; s.counterAxisSizingMode = hug ? 'AUTO' : 'FIXED'; }
  else { s.counterAxisSizingMode = 'FIXED'; s.primaryAxisSizingMode = hug ? 'AUTO' : 'FIXED'; }
  if (hug) s.minHeight = h;
  return s;
}

// Lead / admin workspace shell -----------------------------------------
const NAV_MAIN = [
  ['Dashboard', 'home'], ['Sources', 'plug'], ['Reconstruct', 'layers'], ['Review', 'checkSquare', 47],
  ['Conflicts', 'alert', 2], ['Timeline', 'clock'], ['View as', 'eye'], ['Approval', 'shield'], ['Share', 'share'],
];
const NAV_ADMIN = [['Users & groups', 'users'], ['Audit log', 'fileText']];

function shell(name, active, crumbs) {
  const scr = screenFrame(name);
  const side = Vt({ name: 'Sidebar', fill: 'surface/card', stroke: 'line/default', sides: 'r', p: 16, gap: 4 });
  put(scr, side, { w: 248, h: 'fill' });
  put(side, H({ name: 'Logo row', px: 8, pt: 4, pb: 16 }, [logo(false)]), { w: 'fill' });

  const ws = H({ name: 'Project switcher', p: 10, gap: 10, r: 10, stroke: 'line/default', align: 'CENTER', fill: 'surface/canvas' }, [
    tile('layers', 'brand/700', 'brand/100', 32),
  ]);
  put(ws, Vt({ gap: 0 }, [tx('Project Annapurna', 'smallM'), tx('Northfield Tech Club', 'caption', 'ink/500')]), { w: 'fill' });
  put(ws, icon('chevronDown', 14, 'ink/400'));
  put(side, ws, { w: 'fill' });

  put(side, H({ px: 10, pt: 18, pb: 6 }, [tx('Handover · Aarav Mehta', 'overline', 'ink/400')]));
  for (const [l, ic, c] of NAV_MAIN) put(side, navItem(l, ic, l === active, c), { w: 'fill' });
  put(side, H({ px: 10, pt: 18, pb: 6 }, [tx('Organisation', 'overline', 'ink/400')]));
  for (const [l, ic, c] of NAV_ADMIN) put(side, navItem(l, ic, l === active, c), { w: 'fill' });

  const sp = spacer(); put(side, sp, { w: 'fill', grow: true });
  const me = H({ name: 'Current user', p: 10, gap: 10, r: 10, align: 'CENTER', fill: 'surface/canvas' }, [av('priya')]);
  put(me, Vt({ gap: 4 }, [tx('Priya Nair', 'smallM'), tx('Project lead', 'caption', 'ink/500'), sens('Restricted')]), { w: 'fill' });
  put(me, icon('logout', 16, 'ink/400'));
  put(side, me, { w: 'fill' });

  const main = Vt({ name: 'Main' });
  put(scr, main, { w: 'fill' });
  const top = H({ name: 'Top bar', fill: 'surface/card', stroke: 'line/default', sides: 'b', px: 32, py: 12, justify: 'SPACE_BETWEEN', align: 'CENTER' });
  const cr = H({ name: 'Breadcrumb', gap: 8, align: 'CENTER' });
  crumbs.forEach((c, i) => {
    if (i) put(cr, icon('chevronRight', 14, 'ink/300'));
    put(cr, tx(c, i === crumbs.length - 1 ? 'smallM' : 'small', i === crumbs.length - 1 ? 'ink/900' : 'ink/500'));
  });
  put(top, cr);
  const search = H({ name: 'Search', px: 12, py: 8, gap: 8, r: 8, fill: 'surface/canvas', stroke: 'line/default', align: 'CENTER' }, [icon('search', 16, 'ink/400')]);
  put(search, tx('Search claims, sources, people', 'small', 'ink/400'), { w: 'fill' });
  put(search, kbd('/'));
  const right = H({ gap: 12, align: 'CENTER' });
  put(right, search, { w: 300 });
  const bell = H({ name: 'Notifications', p: 8, r: 8, stroke: 'line/default' }, [icon('bell', 16, 'ink/700')]);
  put(right, bell);
  put(right, av('priya'));
  put(top, right);
  put(main, top, { w: 'fill' });
  const content = Vt({ name: 'Content', p: 32, gap: 24 });
  put(main, content, { w: 'fill' });
  return { scr, content };
}

// Recipient shell (newcomer) -------------------------------------------
function recipientShell(name, activeTab, person = 'rohan') {
  const scr = screenFrame(name, 1440, 900, 'surface/canvas', 'v');
  const top = H({ name: 'Top bar', fill: 'surface/card', stroke: 'line/default', sides: 'b', px: 40, py: 0, justify: 'SPACE_BETWEEN', align: 'CENTER' });
  const left = H({ gap: 28, align: 'CENTER' }, [logo(false)]);
  const tabs = H({ name: 'Tabs', gap: 4, align: 'CENTER' });
  for (const t of ['Start here', 'Checklist', 'Ownership map', 'Timeline', 'Evidence']) {
    const on = t === activeTab;
    put(tabs, H({ name: 'Tab', px: 12, py: 20, stroke: on ? 'brand/600' : null, sw: 2, sides: 'b' }, [tx(t, 'smallM', on ? 'brand/700' : 'ink/500')]));
  }
  put(left, tabs);
  put(top, left);
  const r = H({ gap: 12, align: 'CENTER' }, [
    H({ gap: 6, align: 'CENTER' }, [icon('shield', 14, 'status/verified'), tx('Approved v2 · 9 Nov 2026', 'caption', 'ink/500')]),
    sens(person === 'sara' ? 'Internal' : 'Confidential'),
    av(person),
  ]);
  put(top, r);
  put(scr, top, { w: 'fill' });
  const content = Vt({ name: 'Content', px: 160, py: 32, gap: 24 });
  put(scr, content, { w: 'fill' });
  return { scr, content };
}

// ---------------------------------------------------------------- auth
function brandPanel(over, title, sub, extra) {
  const L = Vt({ name: 'Brand panel', fill: 'surface/inverse', p: 56, justify: 'SPACE_BETWEEN' });
  put(L, logo(true));
  const mid = Vt({ name: 'Pitch', gap: 20 });
  put(mid, tx(over, 'overline', 'accent/baton'));
  put(mid, tx(title, 'display', 'text/inverse'), { w: 520 });
  put(mid, tx(sub, 'bodyL', 'text/inverse-muted'), { w: 470 });
  if (extra) put(mid, extra, { w: 528 });
  put(L, mid, { w: 'fill' });
  put(L, tx('Synthetic demo data · Project Annapurna · Team Bramhastra', 'captionR', 'text/inverse-muted'));
  return L;
}

function sampleClaimDark() {
  const c = Vt({ name: 'Sample claim', fill: 'surface/inverse-raised', r: 12, p: 20, gap: 12 });
  put(c, H({ gap: 8, align: 'CENTER' }, [badge('Verified', 'Verified · 2 sources'), tx('Decision', 'overline', 'text/inverse-muted')]));
  put(c, tx('Push notifications moved from Firebase to OneSignal after the free-tier quota ran out during the Diwali food drive.', 'bodyM', 'text/inverse'), { w: 'fill' });
  put(c, quote('slack/#dev · 12 Oct 2026 · chunk 214', '“we hit the FCM cap again during the drive — switching to OneSignal tonight”'), { w: 'fill' });
  return c;
}

function screenLogin() {
  const scr = screenFrame('Auth / Sign in', 1440, 900, 'surface/card', 'h', false);
  put(scr, brandPanel('Knowledge handover', 'Every handover sentence carries its receipt.', 'Whatever has no receipt becomes a question for the person leaving.', sampleClaimDark()), { w: 640, h: 'fill' });
  const R = Vt({ name: 'Form side', justify: 'CENTER', align: 'CENTER', fill: 'surface/card' });
  put(scr, R, { w: 'fill', h: 'fill' });
  const f = Vt({ name: 'Sign-in form', gap: 18 });
  put(R, f, { w: 400 });
  put(f, Vt({ gap: 6 }, [tx('Sign in to Baton', 'h1'), tx('Use your club or team account.', 'body', 'ink/500')]), { w: 'fill' });
  put(f, input('Email', 'priya.nair@northfield.club'), { w: 'fill' });
  put(f, input('Password', '••••••••••••', 'Focused'), { w: 'fill' });
  const row = H({ justify: 'SPACE_BETWEEN', align: 'CENTER' }, [
    H({ gap: 8, align: 'CENTER' }, [checkbox(true), tx('Keep me signed in', 'small', 'ink/700')]), link('Forgot password?'),
  ]);
  put(f, row, { w: 'fill' });
  const b = btn('Primary', 'Sign in', 'Lg'); put(f, b, { w: 'fill' });
  const or = H({ gap: 12, align: 'CENTER' });
  put(or, divider(), { w: 'fill' }); put(or, tx('or explore with a demo persona', 'caption', 'ink/400')); put(or, divider(), { w: 'fill' });
  put(f, or, { w: 'fill' });
  const list = Vt({ name: 'Demo personas', gap: 8 });
  for (const [k, lvl] of [['priya', 'Restricted'], ['aarav', 'Confidential'], ['rohan', 'Confidential'], ['sara', 'Internal'], ['kabir', 'Restricted']]) {
    const r = H({ name: 'Persona', px: 12, py: 10, gap: 12, r: 10, stroke: 'line/default', align: 'CENTER' }, [av(k, 'Sm')]);
    put(r, Vt({ gap: 0 }, [tx(P[k].n, 'smallM'), tx(P[k].role, 'caption', 'ink/500')]), { w: 'fill' });
    put(r, sens(lvl));
    put(r, icon('chevronRight', 14, 'ink/400'));
    put(list, r, { w: 'fill' });
  }
  put(f, list, { w: 'fill' });
  put(f, H({ gap: 4, justify: 'CENTER' }, [tx('New to Baton?', 'small', 'ink/500'), link('Create an account')]), { w: 'fill' });
  return scr;
}

function screenSignup() {
  const scr = screenFrame('Auth / Create account', 1440, 900, 'surface/card', 'h', false);
  const steps = Vt({ name: 'Setup steps', gap: 14 });
  [['1', 'Create your organisation', 'Your club, startup or NGO.'], ['2', 'Invite people and set access groups', 'engineering · design · operations'], ['3', 'Connect a project’s sources', 'GitHub, task CSV, notes, chat export']].forEach(([n, t, d]) => {
    const r = H({ gap: 14, align: 'CENTER' });
    const c = H({ fill: 'surface/inverse-raised', r: 16, justify: 'CENTER', align: 'CENTER' }, [tx(n, 'smallM', 'accent/baton')]);
    c.resize(32, 32); c.primaryAxisSizingMode = 'FIXED'; c.counterAxisSizingMode = 'FIXED';
    put(r, c);
    put(r, Vt({ gap: 2 }, [tx(t, 'bodyM', 'text/inverse'), tx(d, 'small', 'text/inverse-muted')]));
    put(steps, r);
  });
  put(scr, brandPanel('Get started', 'Set up a handover workspace in two minutes.', 'Baton turns records your team already has into a verified continuation plan — before anyone leaves.', steps), { w: 640, h: 'fill' });
  const R = Vt({ name: 'Form side', justify: 'CENTER', align: 'CENTER' });
  put(scr, R, { w: 'fill', h: 'fill' });
  const f = Vt({ name: 'Sign-up form', gap: 16 });
  put(R, f, { w: 440 });
  put(f, Vt({ gap: 6 }, [tx('Step 1 of 2', 'overline', 'brand/600'), tx('Create your account', 'h1'), tx('You’ll invite your team on the next step.', 'body', 'ink/500')]), { w: 'fill' });
  put(f, input('Full name', 'Priya Nair'), { w: 'fill' });
  put(f, input('Club or work email', 'priya.nair@northfield.club'), { w: 'fill' });
  put(f, input('Password', '••••••••••••••'), { w: 'fill' });
  const meter = H({ name: 'Strength', gap: 4 });
  for (let i = 0; i < 4; i++) put(meter, rect(10, 4, i < 3 ? 'status/verified' : 'line/default', 2), { w: 'fill' });
  put(f, Vt({ gap: 6 }, [meter, tx('Strong · 14 characters, stored with BCrypt', 'caption', 'status/verified')]), { w: 'fill' });
  meter.layoutSizingHorizontal = 'FILL';
  put(f, input('Organisation name', 'Northfield Tech Club'), { w: 'fill' });
  put(f, tx('Your role', 'caption', 'ink/700'));
  const roles = H({ name: 'Role picker', gap: 8 });
  [['Project lead', 'Reviews and approves', true], ['Team member', 'Leaves or receives', false], ['Org admin', 'Users and groups', false]].forEach(([t, d, on]) => {
    const opt = Vt({ name: 'Role option', p: 12, gap: 2, r: 10, fill: on ? 'brand/50' : 'surface/card', stroke: on ? 'brand/600' : 'line/default', sw: on ? 1.5 : 1 });
    put(roles, opt, { w: 'fill' });
    put(opt, tx(t, 'smallM', on ? 'brand/700' : 'ink/900'), { w: 'fill' });
    put(opt, tx(d, 'caption', 'ink/500'), { w: 'fill' });
  });
  put(f, roles, { w: 'fill' });
  const agree = H({ gap: 10, align: 'MIN' }, [checkbox(true)]);
  put(agree, tx('I agree to the terms. I will only connect sources I’m authorised to share, and I understand Baton redacts secrets before any AI call.', 'small', 'ink/700'), { w: 'fill' });
  put(f, agree, { w: 'fill' });
  put(f, btn('Primary', 'Create account', 'Lg'), { w: 'fill' });
  put(f, H({ gap: 4, justify: 'CENTER' }, [tx('Already have an account?', 'small', 'ink/500'), link('Sign in')]), { w: 'fill' });
  return scr;
}

function screenInvite() {
  const scr = screenFrame('Auth / Handover invite (recipient)', 1440, 900, 'surface/canvas', 'v', false);
  scr.primaryAxisAlignItems = 'CENTER'; scr.counterAxisAlignItems = 'CENTER';
  const c = card({ name: 'Invite card', p: 32, gap: 20, shadow: 'pop' });
  put(scr, c, { w: 500 });
  put(c, logo(false));
  put(c, H({ gap: 12, align: 'CENTER' }, [av('priya', 'Lg'), Vt({ gap: 2 }, [tx('Priya Nair shared a handover with you', 'h2'), tx('Project Annapurna · Engineer view · link expires 20 Dec 2026', 'small', 'ink/500')])]), { w: 'fill' });
  put(c, divider(), { w: 'fill' });
  put(c, tx('What you’ll get', 'overline', 'ink/400'));
  for (const s of ['Start here: goal, current phase, next deadline, top risks', 'A Day 1 / Week 1 / Month 1 checklist', 'Who to ask about what — the ownership map', 'Every statement linked to its source']) {
    const r = H({ gap: 10, align: 'CENTER' }, [circleStatus('done', 18)]);
    put(r, tx(s, 'body', 'ink/700'), { w: 'fill' });
    put(c, r, { w: 'fill' });
  }
  put(c, infoBanner('You’ll see claims up to Confidential in the engineering group. Anything above that stays hidden — filtering happens in the database.', 'single', 'lock'), { w: 'fill' });
  put(c, input('Signed in as', 'rohan.k@northfield.club'), { w: 'fill' });
  put(c, btn('Primary', 'Open my handover', 'Lg'), { w: 'fill' });
  put(c, tx('This link is personal. Every view is recorded in the audit log for the project lead.', 'caption', 'ink/500', { align: 'CENTER' }), { w: 'fill' });
  return scr;
}
