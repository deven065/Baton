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
