// ---------------------------------------------------------------- cover + design-system docs
function coverFrame() {
  const f = screenFrame('Cover', 1440, 900, 'surface/inverse', 'v', false);
  f.paddingLeft = f.paddingRight = 96; f.paddingTop = f.paddingBottom = 88;
  f.primaryAxisAlignItems = 'SPACE_BETWEEN';
  put(f, logo(true, 40));
  const mid = Vt({ gap: 18 });
  put(mid, tx('Complete product UI/UX · Round 2 build', 'overline', 'accent/baton'));
  put(mid, tx('Every handover sentence carries its receipt.', 'display', 'text/inverse'), { w: 860 });
  put(mid, tx('Whatever has no receipt becomes a question for the person leaving. The AI proposes, code verifies, humans approve.', 'bodyL', 'text/inverse-muted'), { w: 720 });
  put(f, mid);
  const pages = H({ gap: 12, wrap: true });
  fixW(pages, 1240);
  ['01 Design system', '02 Auth', '03 Lead workspace', '04 Leaver', '05 Recipient', '06 Admin'].forEach(p =>
    put(pages, H({ px: 14, py: 8, r: 999, fill: 'surface/inverse-raised' }, [tx(p, 'smallM', 'text/inverse')])));
  put(f, pages);
  put(f, tx('Team Bramhastra · Deven Rikame · Bhushan Shinde · Shawn Dmello · Synthetic demo data (Project Annapurna)', 'small', 'text/inverse-muted'));
  return f;
}

function docsFrame() {
  const f = Vt({ name: 'Foundations', p: 48, gap: 40, fill: 'surface/canvas' });
  fixW(f, 1440);
  put(f, Vt({ gap: 8 }, [tx('Baton design system', 'display'), tx('Tokens are local variables (collection “Baton tokens”); type and shadows are local styles prefixed “Baton/”.', 'bodyL', 'ink/500')]));

  // Colours
  const colSec = Vt({ name: 'Colour', gap: 16 }, [tx('Colour', 'h2')]);
  const groups = {};
  Object.keys(TOK).forEach(k => { const g = k.split('/')[0]; (groups[g] = groups[g] || []).push(k); });
  for (const [g, list] of Object.entries(groups)) {
    const row = H({ name: g, gap: 12, wrap: true });
    fixW(row, 1344);
    list.forEach(k => {
      const sw = Vt({ name: k, gap: 8, p: 8, r: 10, fill: 'surface/card', stroke: 'line/default' });
      const chipR = rect(140, 56, k, 6);
      if (TOK[k] === '#FFFFFF') { chipR.strokes = [paint('line/default')]; chipR.strokeWeight = 1; }
      put(sw, chipR);
      put(sw, Vt({ gap: 0 }, [tx(k, 'caption'), tx(TOK[k], 'monoS', 'ink/500')]));
      put(row, sw);
    });
    put(colSec, row);
  }
  put(f, colSec);

  // Type
  const ty = Vt({ name: 'Typography', gap: 14 }, [tx('Typography', 'h2'), tx('Inter for interface, ' + MONO.family + ' for source quotes, hashes and IDs.', 'small', 'ink/500')]);
  Object.entries(TS).forEach(([k, s]) => {
    const r = H({ gap: 24, align: 'CENTER', py: 6, stroke: 'line/default', sides: 'b' });
    put(r, tx('Baton/' + s.name, 'monoS', 'ink/500'), { w: 220 });
    put(r, tx(`${s.s}/${s.lh}`, 'monoS', 'ink/400'), { w: 70 });
    put(r, tx(k === 'mono' || k === 'monoS' ? '“we hit the FCM cap again during the drive”' : 'Every sentence carries its receipt', k), { w: 'fill' });
    put(ty, r, { w: 'fill' });
  });
  put(f, ty, { w: 'fill' });

  // Evidence model
  const ev = Vt({ name: 'Evidence model', gap: 14 }, [tx('Evidence model', 'h2'), tx('Status is computed by code. Colour always comes with a text label, so status never relies on colour alone.', 'small', 'ink/500')]);
  [['Verified', 'Two or more independent sources, or one system of record; all quotes match', 'Fact'],
    ['Single source', 'One matching quote from notes or chat', 'Fact, marked “one source”'],
    ['Inferred', 'AI reasoning with no direct quote', '“Likely, not confirmed”'],
    ['Conflicting', 'Sources disagree and the conflict is unresolved', 'Blocks approval until resolved'],
    ['Missing evidence', 'An expected fact has no support, e.g. a task with no owner', '“Unknown”, becomes an exit question'],
    ['Stale', 'Superseded by newer evidence', 'Hidden by default, kept in history']].forEach(([s, rule, shown]) => {
    const r = H({ gap: 24, align: 'CENTER', py: 10, stroke: 'line/default', sides: 'b' });
    put(r, H({}, [badge(s)]), { w: 180 });
    put(r, tx(rule, 'small', 'ink/900'), { w: 'fill' });
    put(r, tx(shown, 'small', 'ink/500'), { w: 320 });
    put(ev, r, { w: 'fill' });
  });
  put(f, ev, { w: 'fill' });
  return f;
}

// ---------------------------------------------------------------- run
async function onPage(page, builders) {
  await figma.setCurrentPageAsync(page);
  let x = 0;
  const made = [];
  for (const b of builders) {
    const n = b();
    page.appendChild(n);
    n.x = x; n.y = 0;
    x += n.width + 120;
    made.push(n);
  }
  return made;
}

async function main() {
  await setupFonts();
  await setupTokens();
  await setupStyles();

  const old = figma.root.children.filter(p => { try { return p.getPluginData(PLUGIN_TAG) === '1'; } catch (e) { return false; } });
  const mk = (name) => { const p = figma.createPage(); p.name = name; p.setPluginData(PLUGIN_TAG, '1'); return p; };
  const pCover = mk('Baton · 00 Cover');
  const pDS = mk('Baton · 01 Design system');
  const pAuth = mk('Baton · 02 Auth');
  const pLead = mk('Baton · 03 Lead workspace');
  const pLeaver = mk('Baton · 04 Leaver');
  const pRec = mk('Baton · 05 Recipient');
  const pAdmin = mk('Baton · 06 Admin');

  // Design system first: every other page instantiates these components.
  await figma.setCurrentPageAsync(pDS);
  const docs = docsFrameShell();
  pDS.appendChild(docs.root);
  buildComponents(docs.components);
  const found = docsFrame();
  pDS.appendChild(found);
  found.x = 0; found.y = 0;
  docs.root.x = found.width + 120; docs.root.y = 0;

  await onPage(pAuth, [screenLogin, screenSignup, screenInvite]);
  const lead = await onPage(pLead, [screenDashboard, screenSources, screenReconstruct, screenReview, screenConflicts, screenTimeline, screenViewAs, screenApproval, screenShare]);
  // Modal sits under the Sources screen.
  const modal = modalAddSource();
  pLead.appendChild(modal);
  modal.x = lead[1].x + (lead[1].width - modal.width) / 2;
  modal.y = lead[1].height + 120;
  await onPage(pLeaver, [screenExitInterview]);
  await onPage(pRec, [screenStartHere, screenOwnership, screenMobileStart]);
  await onPage(pAdmin, [screenUsers, screenAudit]);
  await onPage(pCover, [coverFrame]);

  await Promise.all(PENDING);
  for (const p of old) { try { p.remove(); } catch (e) { /* page may be current */ } }
  figma.closePlugin('Baton UI generated: 7 pages · 18 screens + 1 modal · components and tokens.');
}

function docsFrameShell() {
  const root = Vt({ name: 'Components', p: 48, gap: 40, fill: 'surface/canvas' });
  put(root, Vt({ gap: 8 }, [tx('Components', 'display'), tx('Local components used by every screen. Edit here and instances update.', 'bodyL', 'ink/500')]));
  return { root, components: root };
}

main().catch(e => {
  figma.closePlugin('Baton UI generator failed: ' + (e && e.message ? e.message : String(e)));
});
