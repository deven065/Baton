// Baton UI Generator — local Figma plugin (no AI, no network).
// Builds the Baton design system (colour variables, text and effect styles,
// components) and every product screen as plain editable layers.
// Re-running replaces the pages it created before; other pages are untouched.

const PLUGIN_TAG = 'baton-ui';
const PENDING = [];
const V = {};
const STYLE_IDS = {};
const EFFECT_IDS = {};
const K = {};
const SETS = {};
const ICONC = {};
const COMP = {};
let MONO = { family: 'Inter', style: 'Regular' };

// ---------------------------------------------------------------- tokens
const TOK = {
  'ink/900': '#0E1726', 'ink/700': '#2A3547', 'ink/500': '#5B6576', 'ink/400': '#8A93A3', 'ink/300': '#B4BBC6',
  'line/default': '#E3E6EC', 'line/strong': '#CDD2DB',
  'surface/canvas': '#F5F6F8', 'surface/card': '#FFFFFF', 'surface/subtle': '#EEF0F4',
  'surface/paper': '#FBF7EE', 'surface/inverse': '#0E1726', 'surface/inverse-raised': '#1B2638',
  'brand/700': '#2635A8', 'brand/600': '#3446D4', 'brand/100': '#DDE2FB', 'brand/50': '#EEF0FD',
  'accent/baton': '#E8A33D',
  'text/inverse': '#FFFFFF', 'text/inverse-muted': '#9AA4B5',
  'status/verified': '#127A4B', 'status/verified-bg': '#E3F4EB',
  'status/single': '#1F6FB2', 'status/single-bg': '#E4EFFA',
  'status/inferred': '#9A5800', 'status/inferred-bg': '#FDF0D9',
  'status/conflict': '#C0322B', 'status/conflict-bg': '#FCE7E5',
  'status/missing': '#6B4FBB', 'status/missing-bg': '#EFEAFB',
  'status/stale': '#6B7280', 'status/stale-bg': '#EEF0F3',
  'sens/confidential': '#9A3412', 'sens/confidential-bg': '#FDEBE1',
};

const TS = {
  display: { name: 'Display', s: 44, lh: 52, w: 'Bold', ls: -2 },
  h1: { name: 'Heading 1', s: 28, lh: 36, w: 'Semi Bold', ls: -1 },
  h2: { name: 'Heading 2', s: 20, lh: 28, w: 'Semi Bold', ls: -0.5 },
  h3: { name: 'Heading 3', s: 16, lh: 24, w: 'Semi Bold' },
  bodyL: { name: 'Body large', s: 16, lh: 24, w: 'Regular' },
  body: { name: 'Body', s: 14, lh: 20, w: 'Regular' },
  bodyM: { name: 'Body medium', s: 14, lh: 20, w: 'Medium' },
  small: { name: 'Small', s: 13, lh: 18, w: 'Regular' },
  smallM: { name: 'Small medium', s: 13, lh: 18, w: 'Medium' },
  caption: { name: 'Caption', s: 12, lh: 16, w: 'Medium' },
  captionR: { name: 'Caption regular', s: 12, lh: 16, w: 'Regular' },
  overline: { name: 'Overline', s: 11, lh: 16, w: 'Semi Bold', ls: 6, upper: true },
  stat: { name: 'Stat', s: 30, lh: 36, w: 'Semi Bold', ls: -1 },
  mono: { name: 'Mono', s: 13, lh: 20, mono: true },
  monoS: { name: 'Mono small', s: 12, lh: 16, mono: true },
};

const SHADOWS = {
  card: [{ type: 'DROP_SHADOW', color: { r: 0.055, g: 0.09, b: 0.15, a: 0.05 }, offset: { x: 0, y: 1 }, radius: 3, spread: 0, visible: true, blendMode: 'NORMAL' }],
  pop: [
    { type: 'DROP_SHADOW', color: { r: 0.055, g: 0.09, b: 0.15, a: 0.18 }, offset: { x: 0, y: 16 }, radius: 40, spread: 0, visible: true, blendMode: 'NORMAL' },
    { type: 'DROP_SHADOW', color: { r: 0.055, g: 0.09, b: 0.15, a: 0.06 }, offset: { x: 0, y: 2 }, radius: 6, spread: 0, visible: true, blendMode: 'NORMAL' },
  ],
};

// ---------------------------------------------------------------- icons (24px grid, 2px stroke)
const C = (cx, cy, r) => `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0`;
const ICONS = {
  home: ['M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z'],
  plug: ['M9 2v6', 'M15 2v6', 'M6 8h12v4a6 6 0 0 1-12 0z', 'M12 18v4'],
  layers: ['M12 2 2 7l10 5 10-5z', 'M2 17l10 5 10-5', 'M2 12l10 5 10-5'],
  checkSquare: ['M9 11l3 3L22 4', 'M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11'],
  alert: ['M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z', 'M12 9v4', 'M12 17h.01'],
  clock: [C(12, 12, 9), 'M12 7v5l3 2'],
  eye: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z', C(12, 12, 3)],
  eyeOff: ['M17.9 17.9A10 10 0 0 1 12 20C5 20 1 12 1 12a18.5 18.5 0 0 1 5.1-5.9', 'M9.9 4.2A9 9 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.2 3.2', 'M1 1l22 22'],
  shield: ['M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z', 'M9 12l2 2 4-4'],
  share: ['M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7', 'M16 6l-4-4-4 4', 'M12 2v13'],
  users: ['M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2', C(9, 7, 4), 'M22 21v-2a4 4 0 0 0-3-3.9', 'M16 3.1a4 4 0 0 1 0 7.8'],
  fileText: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6', 'M16 13H8', 'M16 17H8'],
  file: ['M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z', 'M14 2v6h6'],
  search: [C(11, 11, 8), 'M21 21l-4.3-4.3'],
  bell: ['M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9', 'M13.7 21a2 2 0 0 1-3.4 0'],
  chevronRight: ['M9 18l6-6-6-6'],
  chevronDown: ['M6 9l6 6 6-6'],
  check: ['M20 6 9 17l-5-5'],
  x: ['M18 6 6 18', 'M6 6l12 12'],
  message: ['M21 11.5a8.4 8.4 0 0 1-9 8.4 8.5 8.5 0 0 1-3.8-.9L3 21l1.9-5.2A8.4 8.4 0 1 1 21 11.5z'],
  branch: ['M6 3v12', C(6, 18, 3), C(18, 6, 3), 'M18 9a9 9 0 0 1-9 9'],
  board: ['M3 3h18v18H3z', 'M9 3v18', 'M15 3v12'],
  calendar: ['M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z', 'M16 2v4', 'M8 2v4', 'M3 10h18'],
  hash: ['M4 9h16', 'M4 15h16', 'M10 3 8 21', 'M16 3l-2 18'],
  lock: ['M5 11h14v10H5z', 'M8 11V7a4 4 0 0 1 8 0v4'],
  arrowRight: ['M5 12h14', 'M12 5l7 7-7 7'],
  upload: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'M17 8l-5-5-5 5', 'M12 3v12'],
  edit: ['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'],
  link: ['M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7', 'M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7'],
  plus: ['M12 5v14', 'M5 12h14'],
  filter: ['M22 3H2l8 9.5V19l4 2v-8.5z'],
  user: ['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', C(12, 7, 4)],
  key: [C(7.5, 15.5, 4.5), 'M10.7 12.3 21 2', 'M16 7l3 3', 'M19 4l2 2'],
  logout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
  commit: [C(12, 12, 4), 'M3 12h5', 'M16 12h5'],
  info: [C(12, 12, 9), 'M12 16v-4', 'M12 8h.01'],
  refresh: ['M21 12a9 9 0 1 1-3-6.7L21 8', 'M21 3v5h-5'],
  map: ['M1 6v16l7-4 8 4 7-4V2l-7 4-8-4z', 'M8 2v16', 'M16 6v16'],
  keyboard: ['M2 6h20v12H2z', 'M6 10h.01', 'M10 10h.01', 'M14 10h.01', 'M18 10h.01', 'M7 14h10'],
  more: ['M12 12h.01', 'M19 12h.01', 'M5 12h.01'],
  copy: ['M9 9h11v11H9z', 'M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1'],
  download: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'M7 10l5 5 5-5', 'M12 15V3'],
  flag: ['M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z', 'M4 22v-7'],
  phone: ['M7 2h10v20H7z', 'M11 18h2'],
};

// ---------------------------------------------------------------- primitives
function hex(h) {
  const n = parseInt(h.slice(1), 16);
  return { r: ((n >> 16) & 255) / 255, g: ((n >> 8) & 255) / 255, b: (n & 255) / 255 };
}

function paint(tok, opacity) {
  const h = TOK[tok];
  if (!h) throw new Error('Unknown colour token ' + tok);
  let p = { type: 'SOLID', color: hex(h) };
  if (opacity != null) p.opacity = opacity;
  if (V[tok]) {
    try { p = figma.variables.setBoundVariableForPaint(p, 'color', V[tok]); } catch (e) { /* raw paint */ }
  }
  return p;
}

// Configure an auto-layout frame-like node (frame or component).
function style(f, o = {}) {
  if (o.name) f.name = o.name;
  f.fills = o.fill ? [paint(o.fill, o.fillOpacity)] : [];
  if ('clipsContent' in f) f.clipsContent = !!o.clip;
  if (o.dir !== 'none') {
    f.layoutMode = o.dir === 'h' ? 'HORIZONTAL' : 'VERTICAL';
    f.primaryAxisSizingMode = 'AUTO';
    f.counterAxisSizingMode = 'AUTO';
    f.itemSpacing = o.gap || 0;
    const p = o.p != null ? o.p : 0;
    f.paddingTop = o.pt != null ? o.pt : (o.py != null ? o.py : p);
    f.paddingBottom = o.pb != null ? o.pb : (o.py != null ? o.py : p);
    f.paddingLeft = o.pl != null ? o.pl : (o.px != null ? o.px : p);
    f.paddingRight = o.pr != null ? o.pr : (o.px != null ? o.px : p);
    f.primaryAxisAlignItems = o.justify || 'MIN';
    f.counterAxisAlignItems = o.align || 'MIN';
    if (o.wrap && o.dir === 'h') {
      f.layoutWrap = 'WRAP';
      f.counterAxisSpacing = o.rowGap != null ? o.rowGap : (o.gap || 0);
    }
  }
  if (o.r != null) f.cornerRadius = o.r;
  if (o.stroke) {
    f.strokes = [paint(o.stroke)];
    const w = o.sw || 1;
    f.strokeWeight = w;
    f.strokeAlign = 'INSIDE';
    if (o.sides) {
      f.strokeTopWeight = o.sides.includes('t') ? w : 0;
      f.strokeBottomWeight = o.sides.includes('b') ? w : 0;
      f.strokeLeftWeight = o.sides.includes('l') ? w : 0;
      f.strokeRightWeight = o.sides.includes('r') ? w : 0;
    }
    if (o.dash) f.dashPattern = [6, 4];
  }
  if (o.shadow) {
    if (EFFECT_IDS[o.shadow]) PENDING.push(f.setEffectStyleIdAsync(EFFECT_IDS[o.shadow]));
    else f.effects = SHADOWS[o.shadow];
  }
  if (o.opacity != null) f.opacity = o.opacity;
  return f;
}

function box(o = {}) { return style(figma.createFrame(), Object.assign({ name: 'Frame' }, o)); }

function isAL(n) {
  return (n.type === 'FRAME' || n.type === 'COMPONENT' || n.type === 'INSTANCE' || n.type === 'COMPONENT_SET') &&
    n.layoutMode && n.layoutMode !== 'NONE';
}

// Append child to parent and apply sizing: w/h = number | 'fill' | undefined (hug).
function put(parent, child, o = {}) {
  parent.appendChild(child);
  const al = isAL(parent);
  const nw = typeof o.w === 'number', nh = typeof o.h === 'number';
  if (nw || nh) child.resize(nw ? o.w : child.width, nh ? o.h : child.height);
  if (child.type === 'TEXT' && nw) child.textAutoResize = nh ? 'NONE' : 'HEIGHT';
  if (al) {
    if (o.w === 'fill') child.layoutSizingHorizontal = 'FILL';
    if (o.h === 'fill') child.layoutSizingVertical = 'FILL';
    if (isAL(child)) {
      if (nw && o.h === undefined) child.layoutSizingVertical = 'HUG';
      if (nh && o.w === undefined) child.layoutSizingHorizontal = 'HUG';
    }
    if (child.type === 'TEXT' && o.w === 'fill') child.textAutoResize = 'HEIGHT';
    if (o.grow) child.layoutGrow = 1;
  }
  if (o.x != null) child.x = o.x;
  if (o.y != null) child.y = o.y;
  return child;
}

function kids(f, list) {
  for (const k of list) {
    if (!k) continue;
    if (Array.isArray(k)) put(f, k[0], k[1] || {}); else put(f, k);
  }
  return f;
}
function H(o, list = []) { return kids(box(Object.assign({ name: 'Row' }, o, { dir: 'h' })), list); }
function Vt(o, list = []) { return kids(box(Object.assign({ name: 'Stack' }, o, { dir: 'v' })), list); }

// Fix width of an auto-layout node while it keeps hugging its height.
function fixW(n, w) {
  n.resize(w, Math.max(1, n.height));
  if (n.layoutMode === 'VERTICAL') { n.counterAxisSizingMode = 'FIXED'; n.primaryAxisSizingMode = 'AUTO'; }
  else { n.primaryAxisSizingMode = 'FIXED'; n.counterAxisSizingMode = 'AUTO'; }
  return n;
}

function tx(str, st = 'body', color = 'ink/900', o = {}) {
  const s = TS[st];
  if (!s) throw new Error('Unknown text style ' + st);
  const t = figma.createText();
  t.fontName = s.mono ? MONO : { family: 'Inter', style: s.w };
  t.fontSize = s.s;
  t.lineHeight = { unit: 'PIXELS', value: s.lh };
  t.letterSpacing = { unit: 'PERCENT', value: s.ls || 0 };
  if (s.upper) t.textCase = 'UPPER';
  t.characters = String(str);
  t.fills = [paint(color)];
  if (o.align) t.textAlignHorizontal = o.align;
  if (o.strike) t.textDecoration = 'STRIKETHROUGH';
  t.name = o.name || String(str).slice(0, 40);
  if (STYLE_IDS[st]) PENDING.push(t.setTextStyleIdAsync(STYLE_IDS[st]));
  return t;
}

function rect(w, h, tok, r) {
  const n = figma.createRectangle();
  n.resize(w, h);
  n.fills = tok ? [paint(tok)] : [];
  if (r != null) n.cornerRadius = r;
  return n;
}
function dot(size, tok) {
  const e = figma.createEllipse();
  e.resize(size, size);
  e.fills = [paint(tok)];
  e.name = 'Dot';
  return e;
}
function divider(tok = 'line/default') { const r = rect(100, 1, tok); r.name = 'Divider'; return r; }
function spacer() {
  // Fixed 1×1 so it never adds height; layoutGrow stretches it along the main axis.
  const s = box({ name: 'Spacer', dir: 'none' });
  s.resize(1, 1);
  return s;
}

function svgPaths(name, size) {
  const d = ICONS[name];
  if (!d) throw new Error('Unknown icon ' + name);
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">` +
    d.map(p => `<path d="${p}" stroke="#5B6576" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`).join('') + '</svg>';
}
function icon(name, size = 16, tok = 'ink/500') {
  const n = figma.createNodeFromSvg(svgPaths(name, size));
  n.name = 'icon/' + name;
  n.fills = [];
  for (const v of n.findAll(x => x.type === 'VECTOR')) v.strokes = [paint(tok)];
  return n;
}
function tile(name, fg = 'ink/700', bg = 'surface/subtle', size = 36, r = 8) {
  const t = H({ name: 'Icon tile', fill: bg, r, justify: 'CENTER', align: 'CENTER' }, [icon(name, Math.round(size * 0.5), fg)]);
  t.resize(size, size);
  t.primaryAxisSizingMode = 'FIXED'; t.counterAxisSizingMode = 'FIXED';
  return t;
}
function logo(dark = false, size = 28) {
  const fg = dark ? '#FFFFFF' : '#0E1726', mk = dark ? '#0E1726' : '#FFFFFF';
  const m = figma.createNodeFromSvg(`<svg width="${size}" height="${size}" viewBox="0 0 28 28" fill="none" xmlns="http://www.w3.org/2000/svg"><rect width="28" height="28" rx="8" fill="${fg}"/><path d="M8.5 19.5 L19.5 8.5" stroke="${mk}" stroke-width="4" stroke-linecap="round"/><circle cx="20.5" cy="19.5" r="2.5" fill="#E8A33D"/></svg>`);
  m.name = 'Baton mark';
  m.fills = [];
  return H({ name: 'Logo', gap: 10, align: 'CENTER' }, [m, tx('Baton', size > 30 ? 'h1' : 'h2', dark ? 'text/inverse' : 'ink/900')]);
}
function link(s, st = 'smallM') { return tx(s, st, 'brand/600'); }
function chip(label, o = {}) {
  return H({ name: 'Chip', px: 8, py: 3, r: 999, gap: 4, align: 'CENTER', fill: o.fill || 'surface/subtle', stroke: o.stroke },
    [tx(label, 'caption', o.fg || 'ink/700'), o.x ? icon('x', 12, 'ink/400') : null]);
}
function kbd(k) { return H({ name: 'Kbd', px: 6, py: 1, r: 4, fill: 'surface/card', stroke: 'line/strong' }, [tx(k, 'monoS', 'ink/700')]); }
function segs(n, total, tok = 'brand/600', w = 26) {
  const r = H({ name: 'Stage progress', gap: 3 });
  for (let i = 0; i < total; i++) put(r, rect(w, 4, i < n ? tok : 'line/default', 2));
  return r;
}
function bar(pct, w, tok = 'brand/600', h = 6) {
  const t = box({ name: 'Progress', dir: 'none', fill: 'surface/subtle', r: h / 2 });
  t.resize(w, h);
  const f = rect(Math.max(h, Math.round(w * pct)), h, tok, h / 2);
  t.appendChild(f);
  f.x = 0; f.y = 0;
  return t;
}
function circleStatus(kind, size = 22) {
  // kind: done | run | todo | fail | warn
  const map = {
    done: ['status/verified', 'check', 'text/inverse'],
    run: ['brand/600', 'refresh', 'text/inverse'],
    todo: ['surface/subtle', null, null],
    fail: ['status/conflict', 'x', 'text/inverse'],
    warn: ['status/inferred', 'alert', 'text/inverse'],
  }[kind];
  const c = H({ name: 'Status ' + kind, fill: map[0], r: size / 2, justify: 'CENTER', align: 'CENTER', stroke: kind === 'todo' ? 'line/strong' : null }, map[1] ? [icon(map[1], Math.round(size * 0.55), map[2])] : []);
  c.resize(size, size);
  c.primaryAxisSizingMode = 'FIXED'; c.counterAxisSizingMode = 'FIXED';
  return c;
}
function radio(on) {
  const r = H({ name: 'Radio', r: 9, justify: 'CENTER', align: 'CENTER', stroke: on ? 'brand/600' : 'line/strong', sw: on ? 5 : 1.5, fill: 'surface/card' });
  r.resize(18, 18); r.primaryAxisSizingMode = 'FIXED'; r.counterAxisSizingMode = 'FIXED';
  return r;
}
function card(o = {}, list = []) {
  return Vt(Object.assign({ name: 'Card', fill: 'surface/card', stroke: 'line/default', r: 12, p: 20, gap: 14, shadow: 'card' }, o), list);
}
function cardHead(title, sub, right) {
  return H({ name: 'Card header', justify: 'SPACE_BETWEEN', align: 'CENTER', gap: 12 }, [
    Vt({ gap: 2 }, [tx(title, 'h3'), sub ? tx(sub, 'small', 'ink/500') : null]),
    right || null,
  ]);
}
function hdr(title, sub, right) {
  const left = Vt({ name: 'Title', gap: 4 }, [tx(title, 'h1'), sub ? tx(sub, 'body', 'ink/500') : null]);
  return H({ name: 'Page header', justify: 'SPACE_BETWEEN', align: 'MAX', gap: 16 }, [left, right || null]);
}
function infoBanner(text, tone = 'single', ic = 'info', w) {
  const b = H({ name: 'Banner', p: 14, gap: 10, r: 10, fill: `status/${tone}-bg`, align: 'MIN' }, [icon(ic, 16, `status/${tone}`)]);
  put(b, tx(text, 'small', 'ink/900'), { w: 'fill' });
  return b;
}
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
