// ---------------------------------------------------------------- setup
async function setupFonts() {
  const fonts = await figma.listAvailableFontsAsync();
  const has = (f, s) => fonts.some(x => x.fontName.family === f && x.fontName.style === s);
  for (const fam of ['JetBrains Mono', 'IBM Plex Mono', 'Roboto Mono', 'Source Code Pro', 'Courier New']) {
    if (has(fam, 'Regular')) { MONO = { family: fam, style: 'Regular' }; break; }
  }
  await Promise.all(['Regular', 'Medium', 'Semi Bold', 'Bold'].map(s => figma.loadFontAsync({ family: 'Inter', style: s })));
  await figma.loadFontAsync(MONO);
}

async function setupTokens() {
  try {
    const cols = await figma.variables.getLocalVariableCollectionsAsync();
    let col = cols.find(c => c.name === 'Baton tokens');
    if (!col) {
      col = figma.variables.createVariableCollection('Baton tokens');
      try { col.renameMode(col.modes[0].modeId, 'Light'); } catch (e) { /* ignore */ }
    }
    const mode = col.modes[0].modeId;
    const vars = await figma.variables.getLocalVariablesAsync('COLOR');
    for (const [name, h] of Object.entries(TOK)) {
      let v = vars.find(x => x.name === name && x.variableCollectionId === col.id);
      if (!v) v = figma.variables.createVariable(name, col, 'COLOR');
      const c = hex(h);
      v.setValueForMode(mode, { r: c.r, g: c.g, b: c.b, a: 1 });
      v.scopes = ['ALL_FILLS', 'STROKE_COLOR'];
      V[name] = v;
    }
  } catch (e) {
    // Variables unavailable on this plan/file: fall back to raw colours.
    for (const k of Object.keys(V)) delete V[k];
  }
}

async function setupStyles() {
  const existing = await figma.getLocalTextStylesAsync();
  for (const [k, s] of Object.entries(TS)) {
    const name = 'Baton/' + s.name;
    const st = existing.find(x => x.name === name) || figma.createTextStyle();
    st.name = name;
    st.fontName = s.mono ? MONO : { family: 'Inter', style: s.w };
    st.fontSize = s.s;
    st.lineHeight = { unit: 'PIXELS', value: s.lh };
    st.letterSpacing = { unit: 'PERCENT', value: s.ls || 0 };
    st.textCase = s.upper ? 'UPPER' : 'ORIGINAL';
    STYLE_IDS[k] = st.id;
  }
  const effs = await figma.getLocalEffectStylesAsync();
  for (const [k, e] of Object.entries(SHADOWS)) {
    const name = 'Baton/Shadow ' + k;
    const st = effs.find(x => x.name === name) || figma.createEffectStyle();
    st.name = name;
    st.effects = e;
    EFFECT_IDS[k] = st.id;
  }
}

// ---------------------------------------------------------------- components
function arrangeSet(set, w) {
  set.layoutMode = 'HORIZONTAL';
  set.layoutWrap = 'WRAP';
  set.itemSpacing = 16;
  set.counterAxisSpacing = 16;
  set.paddingTop = set.paddingBottom = set.paddingLeft = set.paddingRight = 20;
  set.counterAxisAlignItems = 'CENTER';
  set.resize(w, 100);
  set.primaryAxisSizingMode = 'FIXED';
  set.counterAxisSizingMode = 'AUTO';
  set.fills = [paint('surface/card')];
}

function variantOf(set, name) {
  const v = set.children.find(c => c.name === name);
  if (!v) throw new Error(`Variant "${name}" not found in ${set.name}`);
  return v;
}

const BTN_KIND = {
  Primary: { fill: 'brand/600', fg: 'text/inverse' },
  Secondary: { fill: 'surface/card', fg: 'ink/900', stroke: 'line/strong' },
  Ghost: { fill: null, fg: 'ink/700' },
  Danger: { fill: 'status/conflict', fg: 'text/inverse' },
  Dark: { fill: 'ink/900', fg: 'text/inverse' },
};
const BTN_SIZE = { Md: { px: 14, py: 8, ts: 'smallM' }, Lg: { px: 20, py: 12, ts: 'bodyM' } };
const STATUS = {
  'Verified': ['status/verified', 'status/verified-bg'],
  'Single source': ['status/single', 'status/single-bg'],
  'Inferred': ['status/inferred', 'status/inferred-bg'],
  'Conflicting': ['status/conflict', 'status/conflict-bg'],
  'Missing evidence': ['status/missing', 'status/missing-bg'],
  'Stale': ['status/stale', 'status/stale-bg'],
};
const SENS = {
  Public: ['ink/500', 'surface/subtle'],
  Internal: ['status/single', 'status/single-bg'],
  Confidential: ['sens/confidential', 'sens/confidential-bg'],
  Restricted: ['text/inverse', 'ink/900'],
};
const NAV_ICONS = ['home', 'plug', 'layers', 'checkSquare', 'alert', 'clock', 'eye', 'shield', 'share', 'users', 'fileText', 'message', 'map', 'calendar'];

function compSection(host, title, desc) {
  const s = Vt({ name: title, gap: 12 });
  put(s, tx(title, 'h2'));
  if (desc) put(s, tx(desc, 'small', 'ink/500'), { w: 900 });
  put(host, s);
  return s;
}

function buildComponents(host) {
  // Icons --------------------------------------------------------
  const iconSec = compSection(host, 'Icons', '24px grid, 2px rounded stroke. Instance-swappable inside Nav item.');
  const iconRow = H({ name: 'Icon set', gap: 16, p: 20, fill: 'surface/card', r: 12, stroke: 'line/default' });
  put(iconSec, iconRow);
  for (const name of NAV_ICONS) {
    const n = icon(name, 18, 'ink/500');
    const c = figma.createComponentFromNode(n);
    c.name = 'Icon/' + name;
    put(iconRow, c);
    ICONC[name] = c;
  }

  // Button -------------------------------------------------------
  const btnSec = compSection(host, 'Button', 'Kind × Size. Text property "Label".');
  const bHold = H({ name: 'holder' });
  put(btnSec, bHold);
  const bComps = [], bTexts = [];
  for (const size of Object.keys(BTN_SIZE)) {
    for (const kind of Object.keys(BTN_KIND)) {
      const k = BTN_KIND[kind], sz = BTN_SIZE[size];
      const c = style(figma.createComponent(), { dir: 'h', px: sz.px, py: sz.py, gap: 8, r: 8, fill: k.fill, stroke: k.stroke, justify: 'CENTER', align: 'CENTER' });
      c.name = `Kind=${kind}, Size=${size}`;
      const t = tx('Button', sz.ts, k.fg, { name: 'Label' });
      put(c, t);
      bHold.appendChild(c);
      bComps.push(c); bTexts.push(t);
    }
  }
  const bSet = figma.combineAsVariants(bComps, bHold);
  bSet.name = 'Button';
  arrangeSet(bSet, 820);
  K.btn = bSet.addComponentProperty('Label', 'TEXT', 'Button');
  bTexts.forEach(t => { t.componentPropertyReferences = { characters: K.btn }; });
  bSet.description = 'Primary for the one main action per view; Danger only for destructive actions (reject, revoke).';
  SETS.button = bSet;

  // Status badge -------------------------------------------------
  const stSec = compSection(host, 'Status badge', 'Evidence status is computed by code, never by the model. Colour is always paired with a text label.');
  const sHold = H({ name: 'holder' });
  put(stSec, sHold);
  const sComps = [];
  for (const [name, [fg, bg]] of Object.entries(STATUS)) {
    const c = style(figma.createComponent(), { dir: 'h', px: 8, py: 2, gap: 6, r: 999, fill: bg, align: 'CENTER' });
    c.name = `Status=${name}`;
    put(c, dot(6, fg));
    put(c, tx(name, 'caption', fg, { name: 'Label' }));
    sHold.appendChild(c);
    sComps.push(c);
  }
  const sSet = figma.combineAsVariants(sComps, sHold);
  sSet.name = 'Status badge';
  arrangeSet(sSet, 820);
  sSet.description = 'Verified · Single source · Inferred · Conflicting · Missing evidence · Stale.';
  SETS.badge = sSet;

  // Sensitivity chip --------------------------------------------
  const seSec = compSection(host, 'Sensitivity chip', 'A claim inherits the highest sensitivity of its evidence.');
  const seHold = H({ name: 'holder' });
  put(seSec, seHold);
  const seComps = [];
  for (const [name, [fg, bg]] of Object.entries(SENS)) {
    const c = style(figma.createComponent(), { dir: 'h', px: 6, py: 2, gap: 4, r: 4, fill: bg, align: 'CENTER' });
    c.name = `Level=${name}`;
    put(c, icon('lock', 11, fg));
    put(c, tx(name, 'caption', fg, { name: 'Label' }));
    seHold.appendChild(c);
    seComps.push(c);
  }
  const seSet = figma.combineAsVariants(seComps, seHold);
  seSet.name = 'Sensitivity chip';
  arrangeSet(seSet, 820);
  SETS.sens = seSet;

  // Input --------------------------------------------------------
  const inSec = compSection(host, 'Input', 'Text properties "Label" and "Value".');
  const iHold = H({ name: 'holder' });
  put(inSec, iHold);
  const iComps = [], iLabels = [], iValues = [];
  for (const state of ['Default', 'Focused', 'Error']) {
    const c = style(figma.createComponent(), { dir: 'v', gap: 6 });
    c.name = `State=${state}`;
    fixW(c, 360);
    const l = tx('Label', 'caption', 'ink/700', { name: 'Label' });
    put(c, l);
    const field = H({
      name: 'Field', px: 12, py: 10, r: 8, fill: 'surface/card', align: 'CENTER',
      stroke: state === 'Focused' ? 'brand/600' : state === 'Error' ? 'status/conflict' : 'line/strong', sw: state === 'Default' ? 1 : 1.5,
    });
    const v = tx('Placeholder', 'body', 'ink/400', { name: 'Value' });
    put(field, v, { w: 'fill' });
    put(c, field, { w: 'fill' });
    iHold.appendChild(c);
    iComps.push(c); iLabels.push(l); iValues.push(v);
  }
  const iSet = figma.combineAsVariants(iComps, iHold);
  iSet.name = 'Input';
  arrangeSet(iSet, 1200);
  K.inLabel = iSet.addComponentProperty('Label', 'TEXT', 'Label');
  K.inValue = iSet.addComponentProperty('Value', 'TEXT', 'Placeholder');
  iLabels.forEach(t => { t.componentPropertyReferences = { characters: K.inLabel }; });
  iValues.forEach(t => { t.componentPropertyReferences = { characters: K.inValue }; });
  SETS.input = iSet;

  // Checkbox -----------------------------------------------------
  const cbSec = compSection(host, 'Checkbox');
  const cbHold = H({ name: 'holder' });
  put(cbSec, cbHold);
  const cbComps = [];
  for (const state of ['Off', 'On']) {
    const c = style(figma.createComponent(), { dir: 'h', r: 4, justify: 'CENTER', align: 'CENTER', fill: state === 'On' ? 'brand/600' : 'surface/card', stroke: state === 'On' ? null : 'line/strong', sw: 1.5 });
    c.name = `State=${state}`;
    if (state === 'On') put(c, icon('check', 12, 'text/inverse'));
    c.resize(16, 16);
    c.primaryAxisSizingMode = 'FIXED'; c.counterAxisSizingMode = 'FIXED';
    cbHold.appendChild(c);
    cbComps.push(c);
  }
  const cbSet = figma.combineAsVariants(cbComps, cbHold);
  cbSet.name = 'Checkbox';
  arrangeSet(cbSet, 200);
  SETS.checkbox = cbSet;

  // Avatar -------------------------------------------------------
  const avSec = compSection(host, 'Avatar', 'Initials only — no photos of people in the demo.');
  const avHold = H({ name: 'holder' });
  put(avSec, avHold);
  const avComps = [];
  for (const [size, px, st] of [['Sm', 24, 'caption'], ['Md', 32, 'caption'], ['Lg', 44, 'h3']]) {
    const c = style(figma.createComponent(), { dir: 'h', r: px / 2, justify: 'CENTER', align: 'CENTER', fill: 'brand/100' });
    c.name = `Size=${size}`;
    put(c, tx('AM', st, 'brand/700', { name: 'Initials' }));
    c.resize(px, px);
    c.primaryAxisSizingMode = 'FIXED'; c.counterAxisSizingMode = 'FIXED';
    avHold.appendChild(c);
    avComps.push(c);
  }
  const avSet = figma.combineAsVariants(avComps, avHold);
  avSet.name = 'Avatar';
  arrangeSet(avSet, 300);
  SETS.avatar = avSet;

  // Nav item -----------------------------------------------------
  const nvSec = compSection(host, 'Nav item', 'Properties: Label, Icon (instance swap), Count, Show count.');
  const nvHold = H({ name: 'holder' });
  put(nvSec, nvHold);
  const nvComps = [], nvLabels = [], nvCounts = [], nvIcons = [];
  for (const state of ['Default', 'Active']) {
    const act = state === 'Active';
    const c = style(figma.createComponent(), { dir: 'h', px: 10, py: 8, gap: 10, r: 8, align: 'CENTER', fill: act ? 'brand/50' : null });
    c.name = `State=${state}`;
    fixW(c, 216);
    const ic = ICONC.home.createInstance();
    put(c, ic);
    const l = tx('Dashboard', 'smallM', act ? 'brand/700' : 'ink/700', { name: 'Label' });
    put(c, l, { w: 'fill' });
    const n = tx('0', 'caption', act ? 'brand/700' : 'ink/500', { name: 'Count' });
    put(c, n);
    nvHold.appendChild(c);
    nvComps.push(c); nvLabels.push(l); nvCounts.push(n); nvIcons.push(ic);
  }
  const nvSet = figma.combineAsVariants(nvComps, nvHold);
  nvSet.name = 'Nav item';
  arrangeSet(nvSet, 520);
  K.navLabel = nvSet.addComponentProperty('Label', 'TEXT', 'Dashboard');
  K.navCount = nvSet.addComponentProperty('Count', 'TEXT', '0');
  K.navShow = nvSet.addComponentProperty('Show count', 'BOOLEAN', false);
  K.navIcon = nvSet.addComponentProperty('Icon', 'INSTANCE_SWAP', ICONC.home.id);
  nvLabels.forEach(t => { t.componentPropertyReferences = { characters: K.navLabel }; });
  nvCounts.forEach(t => { t.componentPropertyReferences = { characters: K.navCount, visible: K.navShow }; });
  nvIcons.forEach(i => { i.componentPropertyReferences = { mainComponent: K.navIcon }; });
  SETS.nav = nvSet;

  // Evidence quote ----------------------------------------------
  const qSec = compSection(host, 'Evidence quote', 'The verbatim source quote ("receipt") behind a claim. Matched against stored text by the citation verifier.');
  const q = style(figma.createComponent(), { dir: 'v', p: 12, gap: 6, r: 6, fill: 'surface/paper', stroke: 'accent/baton', sw: 3, sides: 'l' });
  q.name = 'Evidence quote';
  fixW(q, 420);
  const qHead = H({ name: 'Source row', gap: 6, align: 'CENTER' }, [icon('file', 12, 'ink/500')]);
  const qSrc = tx('slack/#dev · 12 Oct 2026', 'monoS', 'ink/500', { name: 'Source' });
  put(qHead, qSrc);
  put(q, qHead, { w: 'fill' });
  const qText = tx('“Quoted source text”', 'mono', 'ink/900', { name: 'Quote' });
  put(q, qText, { w: 'fill' });
  const qMatch = H({ name: 'Match', gap: 4, align: 'CENTER' }, [icon('check', 12, 'status/verified'), tx('Quote matched by verifier · 100%', 'caption', 'status/verified')]);
  put(q, qMatch);
  put(qSec, q);
  K.qSource = q.addComponentProperty('Source', 'TEXT', 'slack/#dev · 12 Oct 2026');
  K.qQuote = q.addComponentProperty('Quote', 'TEXT', '“Quoted source text”');
  K.qMatch = q.addComponentProperty('Show match', 'BOOLEAN', true);
  qSrc.componentPropertyReferences = { characters: K.qSource };
  qText.componentPropertyReferences = { characters: K.qQuote };
  qMatch.componentPropertyReferences = { visible: K.qMatch };
  COMP.quote = q;

  // Stat card ----------------------------------------------------
  const scSec = compSection(host, 'Stat card');
  const sc = style(figma.createComponent(), { dir: 'v', p: 20, gap: 4, r: 12, fill: 'surface/card', stroke: 'line/default', shadow: 'card' });
  sc.name = 'Stat card';
  fixW(sc, 270);
  const scL = tx('Label', 'smallM', 'ink/500', { name: 'Label' });
  const scV = tx('0', 'stat', 'ink/900', { name: 'Value' });
  const scN = tx('Note', 'captionR', 'ink/500', { name: 'Note' });
  put(sc, scL, { w: 'fill' }); put(sc, scV, { w: 'fill' }); put(sc, scN, { w: 'fill' });
  put(scSec, sc);
  K.scLabel = sc.addComponentProperty('Label', 'TEXT', 'Label');
  K.scValue = sc.addComponentProperty('Value', 'TEXT', '0');
  K.scNote = sc.addComponentProperty('Note', 'TEXT', 'Note');
  scL.componentPropertyReferences = { characters: K.scLabel };
  scV.componentPropertyReferences = { characters: K.scValue };
  scN.componentPropertyReferences = { characters: K.scNote };
  COMP.stat = sc;
}

// ---------------------------------------------------------------- instance helpers
function btn(kind, label, size = 'Md') {
  const i = variantOf(SETS.button, `Kind=${kind}, Size=${size}`).createInstance();
  i.setProperties({ [K.btn]: label });
  i.name = 'Button · ' + label;
  return i;
}
function setText(inst, name, value) {
  const t = inst.findOne(n => n.type === 'TEXT' && (!name || n.name === name));
  if (t) t.characters = value;
  return t;
}
function badge(status, label) {
  const i = variantOf(SETS.badge, `Status=${status}`).createInstance();
  if (label) setText(i, 'Label', label);
  i.name = 'Badge · ' + (label || status);
  return i;
}
function sens(level) {
  const i = variantOf(SETS.sens, `Level=${level}`).createInstance();
  i.name = 'Sensitivity · ' + level;
  return i;
}
function input(label, value, state = 'Default', filled = true) {
  const i = variantOf(SETS.input, `State=${state}`).createInstance();
  i.setProperties({ [K.inLabel]: label, [K.inValue]: value });
  if (filled) { const t = i.findOne(n => n.type === 'TEXT' && n.name === 'Value'); if (t) t.fills = [paint('ink/900')]; }
  i.name = 'Input · ' + label;
  return i;
}
function checkbox(on) { return variantOf(SETS.checkbox, `State=${on ? 'On' : 'Off'}`).createInstance(); }
const AV_TONES = ['brand/100', 'status/verified-bg', 'status/inferred-bg', 'status/missing-bg', 'status/single-bg', 'sens/confidential-bg'];
const AV_FG = ['brand/700', 'status/verified', 'status/inferred', 'status/missing', 'status/single', 'sens/confidential'];
function avatar(initials, size = 'Md', tone = 0) {
  const i = variantOf(SETS.avatar, `Size=${size}`).createInstance();
  i.fills = [paint(AV_TONES[tone % AV_TONES.length])];
  const t = setText(i, 'Initials', initials);
  if (t) t.fills = [paint(AV_FG[tone % AV_FG.length])];
  i.name = 'Avatar · ' + initials;
  return i;
}
function avatarStack(list, size = 'Sm') {
  const r = H({ name: 'Avatar stack', gap: -6 });
  list.forEach(([ini, tone]) => {
    const a = avatar(ini, size, tone);
    a.strokes = [paint('surface/card')]; a.strokeWeight = 2; a.strokeAlign = 'OUTSIDE';
    put(r, a);
  });
  return r;
}
function navItem(label, ic, active, count) {
  const i = variantOf(SETS.nav, `State=${active ? 'Active' : 'Default'}`).createInstance();
  const props = { [K.navLabel]: label, [K.navIcon]: ICONC[ic].id, [K.navShow]: !!count };
  if (count) props[K.navCount] = String(count);
  i.setProperties(props);
  i.name = 'Nav · ' + label;
  return i;
}
function quote(source, text, match = true) {
  const i = COMP.quote.createInstance();
  i.setProperties({ [K.qSource]: source, [K.qQuote]: text, [K.qMatch]: match });
  i.name = 'Quote · ' + source;
  return i;
}
function stat(label, value, note, valueTok) {
  const i = COMP.stat.createInstance();
  i.setProperties({ [K.scLabel]: label, [K.scValue]: value, [K.scNote]: note });
  if (valueTok) { const t = i.findOne(n => n.type === 'TEXT' && n.name === 'Value'); if (t) t.fills = [paint(valueTok)]; }
  i.name = 'Stat · ' + label;
  return i;
}
