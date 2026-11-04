// Walker Studio page controller. State lives in the URL so any view can be shared or screenshotted.
import {
  normalizeSpec, runScenario, runMatrix, defaultTicks, formatValue, SCENARIOS, GAIT_PRESETS,
  LEG_REPRESENTATIONS, estimateCost, stress,
} from '../sim/index.js';
import { StudioScene } from './scene.js';
import { drawGait, Timeline, TIMELINE_METRICS, esc } from './charts.js';

const PALETTE = ['#3987e5', '#d95926', '#199e70']; // categorical slots 1–3 (dark-validated)
const MAX_COMPARE = 3;
const $ = (id) => document.getElementById(id);

const params = new URLSearchParams(location.search);
const state = {
  index: [],
  selected: (params.get('walkers') || params.get('walker') || '').split(',').filter(Boolean),
  focus: Number(params.get('focus') || 0),
  terrain: params.get('terrain') || 'flat',
  t: Number(params.get('t') || 0),
  playing: params.get('paused') !== '1',
  speed: Number(params.get('speed') || 1),
  cam: params.get('cam') || 'iso',
  tab: params.get('tab') || 'metrics',
  metric: params.get('metric') || 'margin',
  hud: params.get('hud') !== '0',
  tweaks: {},
  raw: {},
  injected: {},
  runs: [],
  matrices: {},
  ticks: 0,
  costModel: null,
  targets: {},
  targetsDoc: {},
};

const scene = new StudioScene($('viewport'));
const timeline = new Timeline($('timeline'), $('tooltip'));
timeline.onSeek = (t) => { state.t = t; state.playing = false; syncTransport(); };
timeline.metric = TIMELINE_METRICS[state.metric] ? state.metric : 'margin';
if (!state.hud) document.body.classList.add('nohud');

// ---------- loading ----------
async function getJson(url) {
  const r = await fetch(url, { cache: 'no-store' });
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return r.json();
}

async function loadConfig() {
  state.costModel = await getJson('config/cost-model.json');
  state.targetsDoc = await getJson('config/targets.json');
  state.targets = Object.fromEntries(Object.entries(state.targetsDoc).filter(([k]) => !k.startsWith('_')));
}

async function loadIndex() {
  try { state.index = await getJson('api/walkers'); }
  catch { state.index = await getJson('walkers/index.json').catch(() => []); }
}

async function loadRaw(ids) {
  await Promise.all(ids.map(async (id) => {
    if (state.injected[id]) { state.raw[id] = state.injected[id]; return; }
    try { state.raw[id] = await getJson(`walkers/${id}.json`); }
    catch (e) { state.raw[id] = { __error: `Could not load walkers/${id}.json — ${e.message}` }; }
  }));
}

// ---------- tweaks (dot-path patches over the raw JSON) ----------
const getPath = (o, p) => p.split('.').reduce((a, k) => (a == null ? undefined : a[k]), o);
function setPath(o, p, v) {
  const ks = p.split('.');
  let cur = o;
  ks.slice(0, -1).forEach((k) => { if (cur[k] == null || typeof cur[k] !== 'object') cur[k] = {}; cur = cur[k]; });
  cur[ks[ks.length - 1]] = v;
}
function applyTweaks(raw, patch = {}) {
  const out = structuredClone(raw);
  for (const [p, v] of Object.entries(patch)) {
    if (p === 'gait.footholdSearch') setPath(out, p, v > 0 ? { radius: v } : null);
    else setPath(out, p, v);
  }
  return out;
}

// ---------- simulation ----------
function rebuild({ keepTime = true } = {}) {
  const errors = [];
  const specs = [];
  state.selected.forEach((id, i) => {
    const raw0 = state.raw[id];
    if (!raw0 || raw0.__error) { errors.push(raw0?.__error ?? `Unknown walker ${id}`); return; }
    try {
      const spec = normalizeSpec(applyTweaks(raw0, state.tweaks[id]), { id, sharedTargets: state.targets });
      if (spec.errors.length) { errors.push(`${id}: ${spec.errors.join('\n  ')}`); return; }
      specs.push({ spec, color: spec.color ?? PALETTE[i % PALETTE.length] });
    } catch (e) { errors.push(`${id}: ${e.message}`); }
  });
  // Distinct colours per lane even if two specs ask for the same one.
  const used = new Set();
  specs.forEach((s, i) => { if (used.has(s.color)) s.color = PALETTE.find((c) => !used.has(c)) ?? s.color; used.add(s.color); });

  state.ticks = specs.length ? Math.min(4000, Math.max(...specs.map(({ spec }) => defaultTicks(spec, 60)))) : 0;
  state.runs = specs.map(({ spec, color }) => ({
    spec, color,
    result: runScenario(spec, state.terrain, state.costModel, { ticks: state.ticks }),
  }));
  for (const run of state.runs) {
    const key = JSON.stringify([run.spec.raw, state.targets, state.costModel]);
    if (state.matrices[run.spec.id]?.key !== key) state.matrices[run.spec.id] = { key, rows: runMatrix(run.spec, state.costModel) };
  }
  state.focus = Math.min(state.focus, Math.max(0, state.runs.length - 1));
  if (!keepTime) state.t = 0;
  state.t = Math.min(state.t, state.ticks);

  $('errors').innerHTML = errors.map((e) => `<div class="err">${esc(e)}</div>`).join('');
  scene.setRuns(state.runs);
  timeline.setRuns(state.runs, state.ticks);
  scene.update(state.t, state.focus);
  if (!rebuild.didCamera) { scene.setCamera(state.cam, state.focus); rebuild.didCamera = true; }
  renderChrome();
  renderPanel();
  writeUrl();
}

// ---------- URL ----------
function writeUrl() {
  const p = new URLSearchParams();
  p.set('walkers', state.selected.join(','));
  p.set('terrain', state.terrain);
  if (state.focus) p.set('focus', state.focus);
  if (state.tab !== 'metrics') p.set('tab', state.tab);
  if (state.metric !== 'margin') p.set('metric', state.metric);
  if (state.cam !== 'iso') p.set('cam', state.cam);
  if (state.speed !== 1) p.set('speed', state.speed);
  if (!state.playing) { p.set('paused', '1'); p.set('t', Math.round(state.t)); }
  if (!state.hud) p.set('hud', '0');
  history.replaceState(null, '', `?${p}`);
}

// ---------- header / transport ----------
function renderChrome() {
  $('chips').innerHTML = state.selected.map((id, i) => {
    const run = state.runs.find((r) => r.spec.id === id);
    const name = run?.spec.name ?? id;
    const mod = state.tweaks[id] && Object.keys(state.tweaks[id]).length ? '<span class="mod" title="Has unsaved tweaks">● tweaked</span>' : '';
    const idx = state.runs.indexOf(run);
    return `<span class="chip ${idx === state.focus ? 'focus' : ''}" data-id="${esc(id)}" title="Click to focus · ${esc(run?.spec.description ?? '')}">
      <i style="background:${run?.color ?? '#888'}"></i>${esc(name)}${mod}
      ${state.selected.length > 1 ? `<button class="x" data-rm="${esc(id)}" title="Remove from comparison">×</button>` : ''}</span>`;
  }).join('');
  const add = $('addWalker');
  const avail = state.index.filter((w) => !state.selected.includes(w.id));
  add.innerHTML = `<option value="">${state.selected.length >= MAX_COMPARE ? 'max 3 lanes' : '+ compare…'}</option>` +
    avail.map((w) => `<option value="${esc(w.id)}">${esc(w.name)}</option>`).join('');
  add.disabled = state.selected.length >= MAX_COMPARE;
  $('terrain').innerHTML = Object.entries(SCENARIOS).map(([k, s]) => `<option value="${k}" ${k === state.terrain ? 'selected' : ''}>${s.label}</option>`).join('');
  $('cams').innerHTML = '<label>Camera</label>' + ['iso', 'side', 'front', 'top', 'chase'].map((c) => `<button data-cam="${c}" class="${c === state.cam ? 'on' : ''}">${c}</button>`).join('') +
    `<button data-follow class="${scene.follow ? 'on' : ''}" title="Camera follows the focused walker">follow</button>`;
  $('toggles').innerHTML = [['support', 'support'], ['footprints', 'steps'], ['targets', 'targets']]
    .map(([k, l]) => `<button data-opt="${k}" class="${scene.options[k] ? 'on' : ''}">${l}</button>`).join('');
  $('tabs').innerHTML = [['metrics', 'Metrics'], ['design', 'Design'], ['tweak', 'Tweak'], ['events', 'Events'], ['model', 'Model']]
    .map(([k, l]) => `<button data-tab="${k}" class="${k === state.tab ? 'on' : ''}">${l}${k === 'events' ? ` (${state.runs.reduce((a, r) => a + r.result.sim.events.length, 0)})` : ''}</button>`).join('');
  $('metricTabs').innerHTML = Object.entries(TIMELINE_METRICS).map(([k, m]) => `<button data-metric="${k}" class="${k === timeline.metric ? 'on' : ''}">${m.label}</button>`).join('');
  $('speed').value = String(state.speed);
  syncTransport();
}

function syncTransport() {
  $('play').textContent = state.playing ? '⏸' : '▶';
  const run = state.runs[state.focus];
  const T = run?.spec.gait.cycleTicks ?? 1;
  $('readout').textContent = `tick ${String(Math.floor(state.t)).padStart(4)} / ${state.ticks} · ${(state.t / 20).toFixed(2)} s · cycle ${(state.t / T).toFixed(2)}`;
}

function renderHud() {
  const sc = SCENARIOS[state.terrain];
  const rows = state.runs.map((run, i) => {
    const f = scene.lanes[i]?.frame;
    if (!f) return '';
    const feet = f.legs.filter((l) => l.stance).length;
    return `<div class="row" style="${i === state.focus ? 'font-weight:600' : 'color:var(--text-2)'}"><i style="background:${run.color}"></i>${esc(run.spec.name)}
      <span class="mono">${run.spec.speed.toFixed(2)} b/s · ${feet}/${run.spec.legs.length} feet · margin ${f.margin.toFixed(2)} · x ${f.body.pos[0].toFixed(1)}</span></div>`;
  }).join('');
  $('hud').innerHTML = `<div class="row" style="margin-bottom:4px"><b>${esc(sc.label)}</b><span style="color:var(--text-3)">${esc(sc.description)}</span></div>${rows}`;
}

// ---------- side panels ----------
function statusCell(row) {
  return `<span class="st ${row.status}" title="${row.status.toUpperCase()}">${formatValue(row)}</span>`;
}

function renderPanel() {
  const fn = { metrics: renderMetrics, design: renderDesign, tweak: renderTweak, events: renderEvents, model: renderModel }[state.tab] ?? renderMetrics;
  $('panel').innerHTML = state.runs.length ? fn() : '<p>No valid walker loaded. See the error at the bottom-left of the viewport.</p>';
  if (state.tab === 'tweak') bindTweak();
}

function renderMetrics() {
  const runs = state.runs;
  const scen = Object.keys(SCENARIOS);
  const matrix = `<h2>All courses</h2><table class="matrix"><tr><th></th>${scen.map((s) => `<th title="${esc(SCENARIOS[s].description)}">${SCENARIOS[s].label}</th>`).join('')}</tr>
    ${runs.map((run, ri) => `<tr><td><span class="chip" style="cursor:default;padding:2px 8px"><i style="background:${run.color}"></i>${esc(run.spec.name)}</span></td>${state.matrices[run.spec.id].rows.map((m) => {
      const n = m.evaluation.fails, w = m.evaluation.warns;
      const label = n ? `${n} fail${n > 1 ? 's' : ''}` : w ? `${w} warn` : 'pass';
      return `<td class="${m.scenario === state.terrain && ri === state.focus ? 'cur' : ''}" data-scn="${m.scenario}" data-focus="${ri}"><span class="st ${m.evaluation.verdict}">${label}</span></td>`;
    }).join('')}</tr>`).join('')}</table>
    <p style="font-size:12px">Click a cell to watch that walker on that course. Server checks only warn until <code>config/cost-model.json</code> is calibrated.</p>`;

  const rows0 = runs[0].result.evaluation.rows;
  let lastGroup = null;
  const body = rows0.map((r, i) => {
    const grp = r.group !== lastGroup ? `<tr class="grp"><td colspan="${2 + runs.length}">${r.group}</td></tr>` : '';
    lastGroup = r.group;
    const tgt = r.targetValue == null ? '' : `${r.op === '>=' ? '≥' : '≤'} ${formatValue(r, r.targetValue)}${runs.some((x) => x.result.evaluation.rows[i].override) ? '*' : ''}`;
    return `${grp}<tr><td>${esc(r.label)} <span class="help" title="${esc(r.help)}">?</span><div style="color:var(--text-3);font-size:11px">${esc(r.unit)}</div></td>
      <td class="num mono" style="color:var(--text-3)">${tgt}</td>
      ${runs.map((run) => `<td class="num">${statusCell(run.result.evaluation.rows[i])}</td>`).join('')}</tr>`;
  }).join('');
  const verdicts = runs.map((run) => `<td class="num"><span class="st ${run.result.evaluation.verdict}">${run.result.evaluation.verdict.toUpperCase()}</span></td>`).join('');
  return `${matrix}<h2>${esc(SCENARIOS[state.terrain].label)} — metrics vs targets</h2>
    <table><tr><th>Metric</th><th class="num">Target</th>${runs.map((r) => `<th class="num"><i style="display:inline-block;width:9px;height:9px;border-radius:2px;background:${r.color}"></i> ${esc(r.spec.name)}</th>`).join('')}</tr>
    ${body}<tr class="grp"><td colspan="${2 + runs.length}">Verdict</td></tr><tr><td></td><td></td>${verdicts}</tr></table>
    <p style="font-size:12px">Targets come from <code>config/targets.json</code>; * = overridden by a walker. Hover <span class="help">?</span> for how each metric is computed.</p>`;
}

function renderDesign() {
  const run = state.runs[state.focus];
  const s = run.spec;
  const p = s.proposal;
  const list = (xs) => (xs?.length ? `<ul class="plain">${xs.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>` : '<p style="color:var(--text-3)">—</p>');
  const warn = s.warnings.map((w) => `<div class="note">${esc(w)}</div>`).join('');
  return `<h2>${esc(s.name)} <span class="mono" style="text-transform:none;letter-spacing:0">walkers/${esc(s.id)}.json</span></h2>
    <p>${esc(s.description)}</p>${s.author ? `<p style="font-size:12px">by ${esc(s.author)}</p>` : ''}${warn}
    <h2>Proposal</h2><p>${esc(p.summary || '—')}</p>
    <h2>Decisions</h2>${list(p.decisions)}
    <h2>Open questions</h2>${list(p.openQuestions)}
    <h2>Configuration</h2>
    <div class="kv mono">
      <div>hull</div><div>${s.body.size.join('×')} blocks, ride height ${s.body.rideHeight}, mass ≈ ${s.mass} blocks</div>
      <div>gait</div><div>${esc(s.gait.preset)} · duty ${s.gait.duty} · stride ${s.gait.stride} b · step height ${s.gait.stepHeight} b · swing ${esc(s.gait.swing)}</div>
      <div>timing</div><div>cycle ${s.gait.cycleTicks.toFixed(1)} ticks (${(s.gait.cycleTicks / 20).toFixed(2)} s) @ ${s.drive.rpm.toFixed(1)} RPM × ${s.drive.revsPerCycle} rev/cycle</div>
      <div>body</div><div>adapt ${esc(s.gait.bodyAdapt)} · smoothing ${s.gait.bodySmoothing} · sway ${s.gait.comShift}</div>
      <div>footholds</div><div>${s.gait.footholdSearch ? `search r=${s.gait.footholdSearch.radius}` : 'no search (raw placement)'}${s.gait.maxStepUp != null ? ` · maxStepUp ${s.gait.maxStepUp}` : ''}</div>
      <div>support</div><div>${esc(s.physics.support)}${s.physics.support === 'kinematic' ? ' (controller holds hull; tipping is advisory)' : ' (must stay statically stable)'}</div>
      <div>legs as</div><div>${esc(s.implementation.legRepresentation)}</div>
    </div>
    <h2>Legs</h2>
    <table class="mono"><tr><th>id</th><th>mount</th><th>foot</th><th>segments</th><th>knee</th><th class="num">phase</th></tr>
      ${s.legs.map((l) => `<tr><td>${esc(l.id)}</td><td>${l.mount.join(', ')}</td><td>${l.neutral.join(', ')}</td><td>${l.segments.join(' + ')}</td><td>${esc(l.knee)}</td><td class="num">${l.phase.toFixed(3)}</td></tr>`).join('')}</table>
    <h2>Bill of materials</h2>
    <table>${s.bom.map((b) => `<tr><td>${esc(b.name)}</td><td class="num mono">${b.count}</td></tr>`).join('')}</table>
    <p style="font-size:12px">Part names are placeholders; set them per walker with <code>"parts"</code>.</p>`;
}

const TWEAKS = [
  { path: 'drive.rpm', label: 'Drive RPM', min: 4, max: 256, step: 1, get: (s) => s.drive.rpm },
  { path: 'gait.stride', label: 'Stride (b)', min: 0.5, max: 8, step: 0.1, get: (s) => s.gait.stride },
  { path: 'gait.duty', label: 'Duty factor', min: 0.3, max: 0.95, step: 0.01, get: (s) => s.gait.duty },
  { path: 'gait.stepHeight', label: 'Step height (b)', min: 0.2, max: 3, step: 0.1, get: (s) => s.gait.stepHeight },
  { path: 'body.rideHeight', label: 'Ride height (b)', min: 0.5, max: 10, step: 0.1, get: (s) => s.body.rideHeight },
  { path: 'gait.comShift', label: 'Hull sway', min: 0, max: 1, step: 0.05, get: (s) => s.gait.comShift },
  { path: 'gait.bodySmoothing', label: 'Body smoothing', min: 0.05, max: 1, step: 0.05, get: (s) => s.gait.bodySmoothing },
  { path: 'gait.footholdSearch', label: 'Foothold search r', min: 0, max: 3, step: 0.25, get: (s) => s.gait.footholdSearch?.radius ?? 0 },
];
const TWEAK_SELECTS = [
  { path: 'gait.preset', label: 'Gait', options: Object.keys(GAIT_PRESETS), get: (s) => s.gait.preset },
  { path: 'gait.swing', label: 'Swing profile', options: ['arc', 'box'], get: (s) => s.gait.swing },
  { path: 'gait.bodyAdapt', label: 'Body adapt', options: ['level', 'pitch'], get: (s) => s.gait.bodyAdapt },
  { path: 'physics.support', label: 'Support model', options: ['static', 'kinematic'], get: (s) => s.physics.support },
  { path: 'implementation.legRepresentation', label: 'Legs as', options: LEG_REPRESENTATIONS, get: (s) => s.implementation.legRepresentation },
];

function renderTweak() {
  const run = state.runs[state.focus];
  const s = run.spec;
  const patch = state.tweaks[s.id] || {};
  const changed = (p) => (p in patch ? ' style="color:var(--accent)"' : '');
  return `<h2>Tweak ${esc(s.name)}</h2>
    <p style="font-size:12px">Live overrides on top of <code>walkers/${esc(s.id)}.json</code>. Nothing is written until you save or copy.</p>
    <div class="tweak">
      ${TWEAKS.map((t) => `<label${changed(t.path)} for="tw-${t.path}">${t.label}</label><input type="range" id="tw-${t.path}" data-tw="${t.path}" min="${t.min}" max="${t.max}" step="${t.step}" value="${t.get(s)}"><output class="mono">${(+t.get(s)).toFixed(t.step < 0.1 ? 2 : t.step < 1 ? 1 : 0)}</output>`).join('')}
      ${TWEAK_SELECTS.map((t) => `<label${changed(t.path)}>${t.label}</label><select data-tw="${t.path}" style="grid-column:span 2">${t.options.map((o) => `<option ${o === t.get(s) ? 'selected' : ''}>${o}</option>`).join('')}</select>`).join('')}
    </div>
    <p style="font-size:12px" class="mono">→ ${s.speed.toFixed(2)} b/s · cycle ${s.gait.cycleTicks.toFixed(1)} ticks · ${stress(s).toFixed(0)} SU</p>
    <div class="btns">
      <button id="twSave" ${state.injected[s.id] ? 'disabled' : ''}>Save to walkers/${esc(s.id)}.json</button>
      <button id="twCopy">Copy JSON</button>
      <button id="twReset">Reset tweaks</button>
    </div>
    <p id="twMsg" style="font-size:12px"></p>`;
}

let tweakTimer = null;
function bindTweak() {
  const s = state.runs[state.focus].spec;
  $('panel').querySelectorAll('[data-tw]').forEach((el) => {
    el.addEventListener('input', () => {
      const path = el.dataset.tw;
      const v = el.type === 'range' ? Number(el.value) : el.value;
      if (el.nextElementSibling?.tagName === 'OUTPUT') el.nextElementSibling.textContent = v.toFixed?.(el.step < 0.1 ? 2 : el.step < 1 ? 1 : 0) ?? v;
      state.tweaks[s.id] = { ...(state.tweaks[s.id] || {}), [path]: v };
      clearTimeout(tweakTimer);
      tweakTimer = setTimeout(() => rebuild(), 60);
    });
  });
  const json = () => JSON.stringify(applyTweaks(state.raw[s.id], state.tweaks[s.id]), null, 2) + '\n';
  $('twCopy').onclick = async () => {
    try { await navigator.clipboard.writeText(json()); $('twMsg').textContent = 'Copied.'; }
    catch { $('twMsg').textContent = 'Clipboard blocked; JSON logged to the console.'; console.log(json()); }
  };
  $('twReset').onclick = () => { delete state.tweaks[s.id]; rebuild(); };
  $('twSave').onclick = async () => {
    const r = await fetch(`api/walkers/${encodeURIComponent(s.id)}`, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: json() }).catch((e) => ({ ok: false, statusText: e.message }));
    if (r.ok) { delete state.tweaks[s.id]; $('twMsg').textContent = 'Saved.'; }
    else $('twMsg').textContent = `Save failed (${r.status ?? ''} ${r.statusText}). Is the dev server running?`;
  };
}

function renderEvents() {
  const all = state.runs.flatMap((run, ri) => run.result.sim.events.map((e) => ({ ...e, run, ri }))).sort((a, b) => a.t - b.t);
  if (!all.length) return '<h2>Events</h2><p>No collisions, slips or reach failures on this course.</p>';
  return `<h2>Events on ${esc(SCENARIOS[state.terrain].label)}</h2><p style="font-size:12px">Click to jump there (paused). Each event is reported once per leg per step.</p>
    <table><tr><th class="num">tick</th><th>walker</th><th>leg</th><th>what</th></tr>
    ${all.map((e) => `<tr class="ev" data-t="${e.t}" data-focus="${e.ri}"><td class="num mono">${e.t}</td><td><i style="display:inline-block;width:9px;height:9px;border-radius:2px;background:${e.run.color}"></i> ${esc(e.run.spec.name)}</td><td class="mono">${esc(e.leg)}</td><td><b>${esc(e.type)}</b><div style="color:var(--text-3);font-size:12px">${esc(e.detail)}</div></td></tr>`).join('')}</table>`;
}

function renderModel() {
  const cm = state.costModel;
  const run = state.runs[state.focus];
  const c = estimateCost(run.spec, run.result.sim, cm);
  const partColors = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#9085e9'];
  const parts = Object.entries(c.parts);
  const bar = `<div class="bar">${parts.map(([k, v], i) => `<span title="${k}: ${v.toFixed(1)} µs" style="flex:${v};background:${partColors[i]}"></span>`).join('')}</div>`;
  return `<h2>Server cost — ${esc(run.spec.name)}</h2>
    ${cm.calibrated ? '' : '<div class="note">The cost model is <b>uncalibrated</b>: the coefficients are placeholders. Profile one walker in the sandbox with spark, then edit <code>config/cost-model.json</code> and set <code>"calibrated": true</code>.</div>'}
    ${bar}
    <table>${parts.map(([k, v], i) => `<tr><td><i style="display:inline-block;width:9px;height:9px;border-radius:2px;background:${partColors[i]}"></i> ${k}</td><td class="num mono">${v.toFixed(1)} µs</td></tr>`).join('')}
      <tr><td><b>total per tick</b></td><td class="num mono"><b>${c.us.toFixed(0)} µs</b></td></tr>
      <tr><td>walker budget (${(cm.shareOfTickForWalkers * 100).toFixed(0)}% of ${cm.tickBudgetMs} ms)</td><td class="num mono">${c.walkersPerServer} walkers</td></tr>
      <tr><td>leg sync bandwidth</td><td class="num mono">${c.netKBps.toFixed(2)} KB/s/player</td></tr></table>
    <p style="font-size:12px">Leg representation <code>${esc(run.spec.implementation.legRepresentation)}</code>: ${esc(cm.legRepresentation[run.spec.implementation.legRepresentation]?._about ?? '')}</p>
    <h2>Shared targets</h2><p style="font-size:12px">${esc(state.targetsDoc._readme ?? '')}</p>
    <table class="mono">${Object.entries(state.targets).map(([k, v]) => `<tr><td>${k}</td><td class="num">${v ?? 'off'}</td></tr>`).join('')}</table>
    <h2>What this sandbox models</h2>
    <ul class="plain">
      <li>Kinematics only: the hull moves at constant speed; legs never push it. Tipping is measured (support margin), not simulated.</li>
      <li>Footholds are planned once per step at the column surface under the neutral foot, optionally moved by a local search (edges, void).</li>
      <li>Straight-line walking on +x. No turning, no strafing, no slopes steeper than block steps.</li>
      <li>No leg-vs-leg or leg-vs-hull collision, except a foot-clash check between neighbours.</li>
      <li>Stress and server cost are linear formulas with tunable coefficients. Calibrate them against the real mod.</li>
    </ul>
    <p style="font-size:12px">Details: <code>docs/model.md</code>.</p>`;
}

// ---------- events ----------
document.addEventListener('click', (e) => {
  const el = e.target.closest('[data-rm],[data-id],[data-cam],[data-follow],[data-opt],[data-tab],[data-metric],[data-scn],.ev');
  if (!el) return;
  if (el.dataset.rm) {
    e.stopPropagation();
    state.selected = state.selected.filter((x) => x !== el.dataset.rm);
    state.focus = 0;
    rebuild();
  } else if (el.dataset.id) {
    state.focus = state.selected.indexOf(el.dataset.id);
    scene.lastFocus = null;
    scene.setCamera(state.cam, state.focus);
    renderChrome(); renderPanel(); writeUrl();
  } else if (el.dataset.cam) {
    state.cam = el.dataset.cam;
    scene.setCamera(state.cam, state.focus);
    renderChrome(); writeUrl();
  } else if ('follow' in el.dataset) {
    scene.follow = !scene.follow; renderChrome();
  } else if (el.dataset.opt) {
    scene.options[el.dataset.opt] = !scene.options[el.dataset.opt]; renderChrome();
  } else if (el.dataset.tab) {
    state.tab = el.dataset.tab; renderChrome(); renderPanel(); writeUrl();
  } else if (el.dataset.metric) {
    state.metric = timeline.metric = el.dataset.metric; renderChrome(); writeUrl();
  } else if (el.dataset.scn) {
    state.terrain = el.dataset.scn;
    state.focus = Number(el.dataset.focus);
    rebuild({ keepTime: false });
    scene.lastFocus = null;
    scene.setCamera(state.cam, state.focus);
  } else if (el.classList.contains('ev')) {
    state.focus = Number(el.dataset.focus);
    state.t = Math.max(0, Number(el.dataset.t) - 3);
    state.playing = false;
    scene.lastFocus = null;
    scene.update(state.t, state.focus);
    scene.setCamera(state.cam, state.focus);
    renderChrome(); writeUrl();
  }
});
$('addWalker').onchange = async (e) => {
  const id = e.target.value;
  if (!id) return;
  state.selected.push(id);
  await loadRaw([id]);
  rebuild();
  scene.setCamera(state.cam, state.focus);
};
$('terrain').onchange = (e) => { state.terrain = e.target.value; rebuild({ keepTime: false }); scene.lastFocus = null; scene.setCamera(state.cam, state.focus); };
$('speed').onchange = (e) => { state.speed = Number(e.target.value); writeUrl(); };
$('play').onclick = () => { state.playing = !state.playing; if (state.t >= state.ticks) state.t = 0; syncTransport(); writeUrl(); };
$('restart').onclick = () => { state.t = 0; scene.lastFocus = null; scene.update(0, state.focus); scene.setCamera(state.cam, state.focus); writeUrl(); };
const step = (d) => { state.playing = false; state.t = Math.max(0, Math.min(state.ticks, Math.round(state.t) + d)); syncTransport(); writeUrl(); };
$('stepBack').onclick = () => step(-1);
$('stepFwd').onclick = () => step(1);
document.addEventListener('keydown', (e) => {
  if (e.target.closest('input,select,textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); $('play').click(); }
  else if (e.code === 'ArrowLeft') step(e.shiftKey ? -10 : -1);
  else if (e.code === 'ArrowRight') step(e.shiftKey ? 10 : 1);
  else if (e.code === 'Home') $('restart').click();
  else if (e.key === 'h') { state.hud = !state.hud; document.body.classList.toggle('nohud', !state.hud); writeUrl(); }
});

// ---------- loop ----------
let last = performance.now();
let hudTick = 0;
function frame(now) {
  const dt = Math.min(0.1, (now - last) / 1000);
  last = now;
  if (state.playing && state.ticks) {
    state.t += dt * 20 * state.speed;
    if (state.t > state.ticks) { state.t = 0; scene.lastFocus = null; scene.update(0, state.focus); scene.setCamera(state.cam, state.focus); }
    syncTransport();
  }
  if (state.runs.length) {
    scene.update(state.t, state.focus);
    drawGait($('gait'), state.runs[state.focus], state.t);
    timeline.draw(state.t);
    if (++hudTick % 4 === 0) renderHud();
  }
  requestAnimationFrame(frame);
}

// ---------- live reload ----------
function connectLiveReload() {
  if (!window.EventSource) return;
  const es = new EventSource('api/events');
  es.onmessage = async (msg) => {
    const { kind, files } = JSON.parse(msg.data);
    if (kind === 'code') { writeUrl(); location.reload(); return; }
    if (files.some((f) => f.startsWith('config/'))) await loadConfig();
    const changed = files.map((f) => f.match(/^walkers\/(.+)\.json$/)?.[1]).filter(Boolean);
    await loadIndex();
    await loadRaw(state.selected.filter((id) => changed.includes(id) || files.some((f) => f.startsWith('config/'))));
    rebuild();
  };
}

// ---------- agent / automation API ----------
let readyResolve;
const ready = new Promise((r) => (readyResolve = r));
window.walkerStudio = {
  ready,
  state,
  /** Show these walkers (ids from walkers/), up to 3 lanes. */
  async setWalkers(ids) { state.selected = ids.slice(0, MAX_COMPARE); state.focus = 0; await loadRaw(state.selected); rebuild(); scene.setCamera(state.cam, 0); },
  /** Load an in-memory spec without writing a file (e.g. generated by an agent). */
  async load(id, rawSpec, { compare = false } = {}) {
    state.injected[id] = rawSpec;
    state.selected = compare ? [...state.selected.filter((x) => x !== id), id].slice(-MAX_COMPARE) : [id];
    await loadRaw([id]); rebuild(); scene.setCamera(state.cam, state.focus);
  },
  setTerrain(name) { state.terrain = name; rebuild({ keepTime: false }); },
  seek(t) { state.t = Math.max(0, Math.min(state.ticks, t)); state.playing = false; scene.update(state.t, state.focus); syncTransport(); writeUrl(); },
  play() { state.playing = true; syncTransport(); writeUrl(); },
  pause() { state.playing = false; syncTransport(); writeUrl(); },
  setCamera(p) { state.cam = p; scene.setCamera(p, state.focus); scene.update(state.t, state.focus); renderChrome(); writeUrl(); },
  tweak(path, value, id = state.runs[state.focus]?.spec.id) { state.tweaks[id] = { ...(state.tweaks[id] || {}), [path]: value }; rebuild(); },
  /** Metrics for the current course: [{ id, name, verdict, metrics: { key: {value, target, status, unit} }, events }] */
  metrics() {
    return state.runs.map((r) => ({
      id: r.spec.id, name: r.spec.name, terrain: state.terrain, verdict: r.result.evaluation.verdict,
      metrics: Object.fromEntries(r.result.evaluation.rows.map((x) => [x.key, { value: x.value, target: x.targetValue, status: x.status, unit: x.unit }])),
      events: r.result.sim.events,
    }));
  },
  /** Verdict per walker per course. */
  matrix() {
    return Object.fromEntries(state.runs.map((r) => [r.spec.id, Object.fromEntries(state.matrices[r.spec.id].rows.map((m) => [m.scenario, { verdict: m.evaluation.verdict, fails: m.evaluation.rows.filter((x) => x.status === 'fail').map((x) => x.key) }]))]));
  },
  /** PNG data URL of the 3D view as currently posed. */
  screenshot() { scene.update(state.t, state.focus); return scene.screenshot(); },
};

// ---------- boot ----------
(async () => {
  try {
    await loadConfig();
    await loadIndex();
    if (!state.selected.length) state.selected = [state.index.find((w) => w.id === 'quad-strider')?.id ?? state.index[0]?.id].filter(Boolean);
    state.selected = state.selected.slice(0, MAX_COMPARE);
    if (!SCENARIOS[state.terrain]) state.terrain = 'flat';
    await loadRaw(state.selected);
    rebuild();
    connectLiveReload();
    requestAnimationFrame(frame);
    readyResolve(true);
  } catch (e) {
    $('errors').innerHTML = `<div class="err">${esc(e.stack || e.message)}</div>`;
    readyResolve(false);
  }
})();
