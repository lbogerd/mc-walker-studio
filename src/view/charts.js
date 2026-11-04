// 2D canvas charts: footfall (gait) diagram and run timeline.
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function setupCanvas(canvas) {
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = canvas.clientWidth, h = canvas.clientHeight;
  if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
  }
  const g = canvas.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, w, h);
  return { g, w, h };
}

/**
 * Footfall diagram for one walker: one row per leg, filled = planted, over a window of
 * ±1 full-speed gait cycle around the current tick. Vertical lines mark where the gait clock starts
 * a new cycle; they spread out when the walker slows down and stop while it stands.
 */
export function drawGait(canvas, run, t) {
  const { g, w, h } = setupCanvas(canvas);
  if (!run) return;
  const { spec, result } = run;
  const frames = result.sim.frames;
  const T = spec.gait.cycleTicks;
  const labelW = 64, top = 6, bottom = 18;
  const rows = spec.legs.length;
  const rowH = Math.min(22, (h - top - bottom) / rows);
  const x0 = labelW, x1 = w - 8;
  const t0 = t - T, t1 = t + T;
  const X = (tt) => x0 + ((tt - t0) / (t1 - t0)) * (x1 - x0);
  g.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace';
  g.textBaseline = 'middle';
  // cycle boundaries (from the gait clock, which runs slower when the walker is slow and stops at rest)
  g.strokeStyle = css('--grid');
  g.lineWidth = 1;
  for (let tt = Math.max(1, Math.ceil(t0)); tt <= Math.floor(t1) && tt < frames.length; tt++) {
    const a = frames[tt - 1].phase, b = frames[tt].phase;
    if (a === undefined || b === undefined || Math.floor(b + 1e-9) === Math.floor(a + 1e-9)) continue;
    const x = Math.round(X(tt)) + 0.5;
    g.beginPath(); g.moveTo(x, top); g.lineTo(x, top + rows * rowH); g.stroke();
  }
  spec.legs.forEach((leg, li) => {
    const y = top + li * rowH;
    g.fillStyle = css('--text-2');
    g.textAlign = 'left';
    g.fillText(leg.id, 6, y + rowH / 2);
    g.fillStyle = css('--text-3');
    g.fillText(`φ${leg.phase.toFixed(2)}`, 28, y + rowH / 2);
    g.fillStyle = css('--track');
    g.fillRect(x0, y + 3, x1 - x0, rowH - 6);
    g.fillStyle = run.color;
    let start = null;
    for (let tt = Math.floor(t0); tt <= Math.ceil(t1) + 1; tt++) {
      const fr = frames[tt];
      const st = fr ? fr.legs[li].stance : null;
      if (st && start === null) start = tt;
      if ((!st || tt === Math.ceil(t1) + 1) && start !== null) {
        const a = Math.max(x0, X(start)), b = Math.min(x1, X(tt));
        if (b > a) {
          g.beginPath();
          g.roundRect(a, y + 3, b - a - 1, rowH - 6, 3);
          g.fill();
        }
        start = null;
      }
    }
  });
  // cursor
  const cx = Math.round(X(t)) + 0.5;
  g.strokeStyle = css('--text-1');
  g.lineWidth = 1.5;
  g.beginPath(); g.moveTo(cx, top - 2); g.lineTo(cx, top + rows * rowH + 2); g.stroke();
  g.fillStyle = css('--text-3');
  g.textAlign = 'center';
  g.fillText(`±${T.toFixed(1)} ticks (1 cycle at full speed) · bars = foot planted`, (x0 + x1) / 2, h - bottom / 2);
}

export const TIMELINE_METRICS = {
  margin: { label: 'Stability margin', unit: 'b', key: 'margin', target: 'stabilityMarginMin', clampMin: -3 },
  bodyY: { label: 'Hull height', unit: 'y', key: 'bodyY' },
  reach: { label: 'Reach used', unit: '%', key: 'reach', scale: 100, target: 'reachMax' },
  footErr: { label: 'Foot slip', unit: 'b', key: 'footErr', target: 'footErrorMax' },
  stance: { label: 'Feet planted', unit: '', key: 'stance', target: 'stanceMin', step: true },
  clearance: { label: 'Hull clearance', unit: 'b', key: 'clearance' },
  speed: { label: 'Ground speed', unit: 'b/s', key: 'speed' },
  torque: { label: 'Righting torque', unit: 'mass·b', key: 'torque' },
  trackErr: { label: 'Hull tracking', unit: 'b', key: 'trackErr' },
};

/** Timeline of one metric for every walker over the whole run. Returns a hit-test helper. */
export class Timeline {
  constructor(canvas, tooltip) {
    this.canvas = canvas;
    this.tooltip = tooltip;
    this.metric = 'margin';
    this.hover = null;
    this.runs = [];
    canvas.addEventListener('pointermove', (e) => {
      this.hover = e.offsetX;
      if (e.buttons === 1) this.onSeek?.(this.tickAt(e.offsetX));
      this.draw(this.t);
    });
    canvas.addEventListener('pointerleave', () => { this.hover = null; this.tooltip.hidden = true; this.draw(this.t); });
    canvas.addEventListener('pointerdown', (e) => { canvas.setPointerCapture(e.pointerId); this.onSeek?.(this.tickAt(e.offsetX)); });
  }

  setRuns(runs, ticks) {
    this.runs = runs;
    this.ticks = ticks;
  }

  tickAt(px) {
    const { x0, x1 } = this.layout ?? { x0: 0, x1: 1 };
    return Math.max(0, Math.min(this.ticks, ((px - x0) / (x1 - x0)) * this.ticks));
  }

  draw(t) {
    this.t = t;
    const { g, w, h } = setupCanvas(this.canvas);
    const m = TIMELINE_METRICS[this.metric];
    const x0 = 44, x1 = w - 10, y0 = 8, y1 = h - 22;
    this.layout = { x0, x1 };
    if (!this.runs.length) return;
    const sc = m.scale ?? 1;
    const vals = (run) => run.result.sim.series[m.key].map((v) => (v === null ? null : Math.max(m.clampMin ?? -Infinity, v) * sc));
    let lo = Infinity, hi = -Infinity;
    const all = this.runs.map(vals);
    for (const arr of all) for (const v of arr) if (v !== null && Number.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
    const tgts = m.target ? this.runs.map((r) => r.spec.targets[m.target]).filter((v) => v != null) : [];
    for (const tv of tgts) { lo = Math.min(lo, tv * sc); hi = Math.max(hi, tv * sc); }
    if (!Number.isFinite(lo)) { lo = 0; hi = 1; }
    if (hi - lo < 1e-6) { hi += 0.5; lo -= 0.5; }
    const pad = (hi - lo) * 0.08;
    lo -= pad; hi += pad;
    const X = (tt) => x0 + (tt / this.ticks) * (x1 - x0);
    const Y = (v) => y1 - ((v - lo) / (hi - lo)) * (y1 - y0);

    g.font = '11px ui-monospace, SFMono-Regular, Menlo, monospace';
    g.textBaseline = 'middle';
    // grid + y ticks
    const step = niceStep((hi - lo) / 4);
    g.strokeStyle = css('--grid');
    g.fillStyle = css('--text-3');
    g.lineWidth = 1;
    g.textAlign = 'right';
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) {
      const y = Math.round(Y(v)) + 0.5;
      g.beginPath(); g.moveTo(x0, y); g.lineTo(x1, y); g.stroke();
      g.fillText(fmt(v), x0 - 6, y);
    }
    // x ticks in seconds
    g.textAlign = 'center';
    const secs = this.ticks / 20;
    const sstep = niceStep(secs / 8);
    for (let s = 0; s <= secs; s += sstep) g.fillText(`${fmt(s)}s`, X(s * 20), h - 10);
    // targets
    if (tgts.length) {
      g.setLineDash([4, 4]);
      g.strokeStyle = css('--text-2');
      for (const tv of new Set(tgts)) {
        const y = Math.round(Y(tv * sc)) + 0.5;
        g.beginPath(); g.moveTo(x0, y); g.lineTo(x1, y); g.stroke();
      }
      g.setLineDash([]);
      g.fillStyle = css('--text-2');
      g.textAlign = 'right';
      g.fillText('target', x1 - 2, Y(tgts[0] * sc) - 8);
    }
    // series
    this.runs.forEach((run, ri) => {
      const arr = all[ri];
      g.strokeStyle = run.color;
      g.lineWidth = 2;
      g.lineJoin = 'round';
      g.beginPath();
      let pen = false;
      arr.forEach((v, i) => {
        if (v === null || !Number.isFinite(v)) { pen = false; return; }
        const x = X(i), y = Y(v);
        if (!pen) { g.moveTo(x, y); pen = true; }
        else if (m.step) { g.lineTo(x, Y(arr[i - 1] ?? v)); g.lineTo(x, y); }
        else g.lineTo(x, y);
      });
      g.stroke();
      // event ticks
      g.fillStyle = run.color;
      for (const e of run.result.sim.events) {
        const x = X(e.t);
        g.beginPath(); g.moveTo(x, y1 + 1); g.lineTo(x - 4, y1 + 8); g.lineTo(x + 4, y1 + 8); g.closePath(); g.fill();
      }
    });
    // cursor
    const cx = Math.round(X(t)) + 0.5;
    g.strokeStyle = css('--text-1');
    g.lineWidth = 1.5;
    g.beginPath(); g.moveTo(cx, y0); g.lineTo(cx, y1); g.stroke();

    // hover crosshair + tooltip
    if (this.hover !== null && this.hover >= x0 && this.hover <= x1) {
      const tt = Math.round(this.tickAt(this.hover));
      const hx = Math.round(X(tt)) + 0.5;
      g.strokeStyle = css('--text-3');
      g.lineWidth = 1;
      g.beginPath(); g.moveTo(hx, y0); g.lineTo(hx, y1); g.stroke();
      const lines = this.runs.map((run, ri) => {
        const v = all[ri][tt];
        if (v !== null && v !== undefined && Number.isFinite(v)) {
          g.fillStyle = run.color;
          g.strokeStyle = css('--surface');
          g.lineWidth = 2;
          g.beginPath(); g.arc(X(tt), Y(v), 4, 0, Math.PI * 2); g.fill(); g.stroke();
        }
        return `<div><i style="background:${run.color}"></i>${esc(run.spec.name)} <b>${v == null ? '—' : fmt(v)}${m.unit === '%' ? '%' : ` ${m.unit}`}</b></div>`;
      });
      this.tooltip.innerHTML = `<div class="tt-h">tick ${tt} · ${(tt / 20).toFixed(2)}s</div>${lines.join('')}`;
      this.tooltip.hidden = false;
      const r = this.canvas.getBoundingClientRect();
      const tw = this.tooltip.offsetWidth;
      this.tooltip.style.left = `${Math.min(r.left + this.hover + 14, window.innerWidth - tw - 8)}px`;
      this.tooltip.style.top = `${r.top + 8}px`;
    }
  }
}

function niceStep(raw) {
  const p = 10 ** Math.floor(Math.log10(raw || 1));
  const n = raw / p;
  return (n < 1.5 ? 1 : n < 3.5 ? 2 : n < 7.5 ? 5 : 10) * p;
}
const fmt = (v) => (Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2).replace(/\.?0+$/, '') || '0');
export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
