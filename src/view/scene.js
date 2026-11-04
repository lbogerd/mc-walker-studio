// three.js view: block terrain lanes, voxel hulls, legs, footprints, support polygon.
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { VOID } from '../sim/terrain.js';

const LANE_GAP = 6; // blocks of empty space between compared walkers

function blockTexture(seed = 1, border = 0.28) {
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const g = c.getContext('2d');
  let s = seed;
  const rnd = () => ((s = (s * 16807) % 2147483647) / 2147483647);
  for (let y = 0; y < 16; y++)
    for (let x = 0; x < 16; x++) {
      const edge = x === 0 || y === 0 || x === 15 || y === 15;
      const v = Math.round(255 * (edge ? 1 - border : 0.86 + rnd() * 0.14));
      g.fillStyle = `rgb(${v},${v},${v})`;
      g.fillRect(x, y, 1, 1);
    }
  const t = new THREE.CanvasTexture(c);
  t.magFilter = THREE.NearestFilter;
  t.minFilter = THREE.NearestMipmapLinearFilter;
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

const SURFACES = {
  grass: { top: '#6fa64a', side: '#7b5a3a' },
  stone: { top: '#9a9a96', side: '#7e7e7a' },
};

// three r170+ setFromPoints reuses the old buffer and iterates its length, so a changing point
// count must get a fresh geometry.
function setLinePoints(obj, pts) {
  const pos = obj.geometry.getAttribute('position');
  if (!pos || pos.count !== pts.length) {
    obj.geometry.dispose();
    obj.geometry = new THREE.BufferGeometry();
  }
  obj.geometry.setFromPoints(pts);
}

const lerpArr = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];

export class StudioScene {
  constructor(container) {
    this.container = container;
    this.renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
    this.renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    container.appendChild(this.renderer.domElement);

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color('#9cc3e6');
    this.scene.fog = new THREE.Fog('#9cc3e6', 60, 140);
    this.camera = new THREE.PerspectiveCamera(45, 1, 0.1, 500);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.12;

    this.scene.add(new THREE.HemisphereLight('#dfefff', '#5b4a35', 1.6));
    const sun = new THREE.DirectionalLight('#fff6e0', 2.2);
    sun.castShadow = true;
    sun.shadow.mapSize.set(2048, 2048);
    const sc = sun.shadow.camera;
    sc.left = -30; sc.right = 30; sc.top = 30; sc.bottom = -30; sc.near = 1; sc.far = 120;
    sun.shadow.bias = -0.0005;
    this.sun = sun;
    this.scene.add(sun, sun.target);

    this.tex = blockTexture(7);
    this.strutTex = blockTexture(11, 0.45);
    this.unitBox = new THREE.BoxGeometry(1, 1, 1);
    this.terrainGroup = new THREE.Group();
    this.scene.add(this.terrainGroup);
    this.lanes = [];
    this.follow = true;
    this.lastFocus = null;
    this.options = { support: true, footprints: true, targets: true };

    new ResizeObserver(() => this.resize()).observe(container);
    this.resize();
  }

  resize() {
    const w = this.container.clientWidth, h = this.container.clientHeight;
    if (!w || !h) return;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
  }

  laneZ(i) {
    let z = 0;
    for (let j = 0; j < i; j++) z += this.lanes[j].width / 2 + LANE_GAP + this.lanes[j + 1].width / 2;
    return Math.round(z);
  }

  /** Rebuild everything. runs: [{ spec, color, result:{terrain, sim} }] */
  setRuns(runs) {
    for (const lane of this.lanes) this.scene.remove(lane.group);
    this.lanes = runs.map((run) => ({
      run,
      width: Math.max(run.spec.body.size[2], ...run.spec.legs.map((l) => 2 * Math.abs(l.mount[2] + l.neutral[1]) + 2)) + 2,
    }));
    this.lanes.forEach((lane, i) => {
      lane.z = this.laneZ(i);
      this.buildLane(lane);
    });
    this.buildTerrain();
  }

  buildTerrain() {
    this.terrainGroup.clear();
    if (!this.lanes.length) return;
    const minX = -14;
    let maxX = 20;
    for (const l of this.lanes) {
      const f = l.run.result.sim.frames;
      if (f.length) maxX = Math.max(maxX, f[f.length - 1].body.pos[0] + 16);
    }
    maxX = Math.ceil(maxX);
    const cells = [];
    for (const lane of this.lanes) {
      const terrain = lane.run.result.terrain;
      const hw = Math.ceil(lane.width / 2 + LANE_GAP / 2);
      for (let ix = minX; ix < maxX; ix++)
        for (let iz = -hw; iz < hw; iz++) {
          const h = terrain.column(ix, iz);
          if (h <= VOID) continue;
          for (let y = Math.min(h - 1, -2); y < h; y++) cells.push([ix, y, iz + Math.round(lane.z), y === h - 1, terrain.surface]);
        }
    }
    const mat = new THREE.MeshLambertMaterial({ map: this.tex });
    const mesh = new THREE.InstancedMesh(this.unitBox, mat, cells.length);
    const m = new THREE.Matrix4();
    const col = new THREE.Color();
    cells.forEach(([x, y, z, top, surf], i) => {
      m.makeTranslation(x + 0.5, y + 0.5, z + 0.5);
      mesh.setMatrixAt(i, m);
      const s = SURFACES[surf] ?? SURFACES.grass;
      col.set(top ? s.top : s.side);
      const j = 0.94 + (((x * 73856093) ^ (z * 19349663) ^ (y * 83492791)) & 255) / 255 * 0.08;
      col.multiplyScalar(j);
      mesh.setColorAt(i, col);
    });
    mesh.receiveShadow = true;
    this.terrainGroup.add(mesh);
    // void floor so trenches read as "deep"
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(maxX - minX, 400), new THREE.MeshBasicMaterial({ color: '#1d2430' }));
    floor.rotation.x = -Math.PI / 2;
    floor.position.set((minX + maxX) / 2, VOID, 0);
    this.terrainGroup.add(floor);
  }

  buildLane(lane) {
    const { spec, color } = lane.run;
    const group = new THREE.Group();
    group.position.z = lane.z;
    this.scene.add(group);
    lane.group = group;

    // hull: voxel shell
    const [sx, sy, sz] = spec.body.size.map((v) => Math.max(1, Math.round(v)));
    const hull = new THREE.Group();
    const vox = [];
    for (let i = 0; i < sx; i++)
      for (let j = 0; j < sy; j++)
        for (let k = 0; k < sz; k++) {
          const shell = i === 0 || j === 0 || k === 0 || i === sx - 1 || j === sy - 1 || k === sz - 1;
          if (shell) vox.push([i, j, k]);
        }
    const hullMesh = new THREE.InstancedMesh(this.unitBox, new THREE.MeshLambertMaterial({ map: this.tex }), vox.length);
    const m = new THREE.Matrix4();
    const c = new THREE.Color();
    vox.forEach(([i, j, k], n) => {
      m.makeTranslation(-sx / 2 + i + 0.5, j + 0.5, -sz / 2 + k + 0.5);
      hullMesh.setMatrixAt(n, m);
      const isTopEdge = j === sy - 1 && (k === 0 || k === sz - 1);
      const isNose = i === sx - 1 && j === sy - 1 && Math.abs(k - (sz - 1) / 2) < 1;
      c.set(isNose ? color : isTopEdge ? spec.body.accent : spec.body.color);
      hullMesh.setColorAt(n, c);
    });
    hullMesh.castShadow = true;
    hullMesh.receiveShadow = true;
    hull.add(hullMesh);
    const com = new THREE.Mesh(new THREE.SphereGeometry(0.22, 12, 8), new THREE.MeshBasicMaterial({ color: '#ffffff', depthTest: false }));
    com.renderOrder = 10;
    group.add(hull, com);
    lane.hull = hull;
    lane.comMarker = com;

    // legs
    const jointMat = new THREE.MeshLambertMaterial({ color: spec.body.accent });
    const footMat = new THREE.MeshLambertMaterial({ color });
    lane.legs = spec.legs.map((leg) => {
      const segMat = new THREE.MeshLambertMaterial({ map: this.strutTex, color: '#b9bec4' });
      const segs = leg.segments.map((L) => {
        const g = new THREE.BoxGeometry(0.5, L, 0.5);
        g.translate(0, L / 2, 0);
        const uv = g.attributes.uv;
        for (let i = 0; i < uv.count; i++) uv.setY(i, uv.getY(i) * Math.max(1, Math.round(L)));
        const tex = this.strutTex;
        tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
        const mesh = new THREE.Mesh(g, segMat);
        mesh.castShadow = true;
        group.add(mesh);
        return mesh;
      });
      const mk = (geo, mat) => { const x = new THREE.Mesh(geo, mat); x.castShadow = true; group.add(x); return x; };
      return {
        segs, segMat,
        hip: mk(new THREE.BoxGeometry(0.9, 0.9, 0.9), jointMat),
        joints: leg.segments.slice(1).map(() => mk(new THREE.BoxGeometry(0.7, 0.7, 0.7), jointMat)),
        foot: mk(new THREE.BoxGeometry(1, 0.25, 1), footMat),
        next: (() => {
          const r = new THREE.Mesh(new THREE.RingGeometry(0.32, 0.45, 4, 1), new THREE.MeshBasicMaterial({ color, side: THREE.DoubleSide, transparent: true, opacity: 0.9 }));
          r.rotation.x = -Math.PI / 2;
          r.rotation.z = Math.PI / 4;
          group.add(r);
          return r;
        })(),
      };
    });

    // footprints
    const fhs = lane.run.result.sim.footholds.flat().sort((a, b) => a.tStart - b.tStart);
    lane.footholds = fhs;
    const fp = new THREE.InstancedMesh(new THREE.BoxGeometry(0.8, 0.03, 0.8), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.45 }), Math.max(1, fhs.length));
    fhs.forEach((f, i) => {
      m.makeTranslation(f.x, f.y + 0.02, f.z);
      fp.setMatrixAt(i, m);
      c.set(f.void ? '#ff4d4d' : f.adjusted ? '#ffffff' : color);
      fp.setColorAt(i, c);
    });
    fp.count = 0;
    group.add(fp);
    lane.footprints = fp;

    // support polygon
    const line = new THREE.LineLoop(new THREE.BufferGeometry(), new THREE.LineBasicMaterial({ color: '#ffffff', depthTest: false, transparent: true }));
    line.renderOrder = 9;
    const drop = new THREE.Line(new THREE.BufferGeometry(), new THREE.LineDashedMaterial({ color: '#ffffff', dashSize: 0.2, gapSize: 0.15, depthTest: false }));
    drop.renderOrder = 9;
    group.add(line, drop);
    lane.supportLine = line;
    lane.comDrop = drop;
  }

  /** Pose every lane at fractional tick t. */
  update(t, focusIndex) {
    const up = new THREE.Vector3(0, 1, 0);
    const v = new THREE.Vector3();
    const q = new THREE.Quaternion();
    for (const lane of this.lanes) {
      const { spec, result } = lane.run;
      const frames = result.sim.frames;
      if (!frames.length) continue;
      const tc = Math.min(Math.max(t, 0), frames.length - 1);
      const i0 = Math.floor(tc), i1 = Math.min(frames.length - 1, i0 + 1), a = tc - i0;
      const f0 = frames[i0], f1 = frames[i1];
      lane.frame = a < 0.5 ? f0 : f1;
      const pos = lerpArr(f0.body.pos, f1.body.pos, a);
      lane.hull.position.set(...pos);
      lane.hull.rotation.set(f0.body.roll + (f1.body.roll - f0.body.roll) * a, 0, f0.body.pitch + (f1.body.pitch - f0.body.pitch) * a, 'ZYX');
      lane.bodyPos = pos;
      lane.comMarker.position.set(...lerpArr(f0.com, f1.com, a));

      spec.legs.forEach((leg, li) => {
        const L = lane.legs[li], s0 = f0.legs[li], s1 = f1.legs[li];
        const hip = lerpArr(s0.hip, s1.hip, a), base = lerpArr(s0.base, s1.base, a);
        const knee = lerpArr(s0.knee, s1.knee, a), foot = lerpArr(s0.foot, s1.foot, a);
        const chain = leg.segments.length === 3 ? [hip, base, knee, foot] : [hip, knee, foot];
        L.segs.forEach((mesh, si) => {
          const A = chain[si], B = chain[si + 1];
          v.set(B[0] - A[0], B[1] - A[1], B[2] - A[2]);
          if (v.lengthSq() < 1e-8) v.set(0, -1, 0);
          q.setFromUnitVectors(up, v.normalize());
          mesh.position.set(...A);
          mesh.quaternion.copy(q);
        });
        L.hip.position.set(...hip);
        L.joints.forEach((j, ji) => j.position.set(...chain[ji + 1]));
        L.foot.position.set(foot[0], foot[1] + 0.125, foot[2]);
        const bad = s0.err > 0.05;
        L.segMat.color.set(bad ? '#ff6b6b' : '#b9bec4');
        const nx = s0.next;
        L.next.visible = this.options.targets && !!nx && !s0.stance;
        if (nx) L.next.position.set(nx[0], nx[1] + 0.04, nx[2]);
      });

      // footprints: show every planted foothold whose stance has begun
      if (this.options.footprints) {
        let n = 0;
        while (n < lane.footholds.length && lane.footholds[n].tStart <= tc) n++;
        lane.footprints.count = n;
      } else lane.footprints.count = 0;

      const fr = lane.frame;
      const show = this.options.support && fr.hull.length >= 2;
      lane.supportLine.visible = show;
      lane.comDrop.visible = this.options.support;
      lane.comMarker.visible = this.options.support;
      if (show) {
        const stanceY = fr.legs.filter((l) => l.stance).map((l) => l.foot[1]);
        const y = (stanceY.length ? stanceY.reduce((p, c) => p + c, 0) / stanceY.length : 0) + 0.08;
        setLinePoints(lane.supportLine, fr.hull.map(([x, z]) => new THREE.Vector3(x, y, z)));
        const target = spec.targets.stabilityMarginMin ?? 0;
        lane.supportLine.material.color.set(fr.margin >= target ? '#4ade80' : fr.margin >= 0 ? '#facc15' : '#f87171');
        lane.comMarker.material.color.copy(lane.supportLine.material.color);
        const com = lane.comMarker.position;
        setLinePoints(lane.comDrop, [com.clone(), new THREE.Vector3(com.x, y, com.z)]);
        lane.comDrop.computeLineDistances();
      }
    }

    const focus = this.lanes[focusIndex] ?? this.lanes[0];
    if (focus?.bodyPos) {
      const p = new THREE.Vector3(focus.bodyPos[0], focus.bodyPos[1], focus.bodyPos[2] + focus.z);
      if (this.follow && this.lastFocus) {
        const d = p.clone().sub(this.lastFocus);
        if (d.length() < 20) {
          this.camera.position.add(d);
          this.controls.target.add(d);
        }
      }
      this.lastFocus = p;
      this.sun.position.set(p.x + 20, p.y + 40, p.z + 15);
      this.sun.target.position.copy(p);
    }
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }

  setCamera(preset, focusIndex = 0) {
    const lane = this.lanes[focusIndex] ?? this.lanes[0];
    if (!lane) return;
    const fr = lane.run.result.sim.frames[0];
    const bp = lane.bodyPos ?? fr.body.pos;
    const p = new THREE.Vector3(bp[0], bp[1], bp[2] + lane.z);
    this.lastFocus = p.clone();
    const s = Math.max(10, lane.run.spec.body.size[0] * 1.6 + lane.run.spec.body.rideHeight * 1.5);
    const spread = this.lanes.length > 1 ? (this.laneZ(this.lanes.length - 1)) : 0;
    const offsets = {
      iso: [s * 0.9, s * 0.65, s * 1.1 + spread * 0.6],
      side: [0, s * 0.15, s * 1.6 + spread * 0.8],
      front: [s * 1.6, s * 0.2, 0],
      top: [0.01, s * 2.2 + spread, 0],
      chase: [-s * 1.3, s * 0.55, s * 0.15],
    };
    const o = offsets[preset] ?? offsets.iso;
    const target = p.clone();
    if (spread && preset !== 'front' && preset !== 'chase') target.z = this.lanes[0].z + spread / 2 + 0;
    this.controls.target.copy(target);
    this.camera.position.set(target.x + o[0], target.y + o[1], target.z + o[2]);
    this.controls.update();
  }

  screenshot() {
    return this.renderer.domElement.toDataURL('image/png');
  }
}
