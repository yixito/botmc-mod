// RemoteBot browser client: WebSocket control + 3D voxel view.
const params = new URLSearchParams(location.search);
const token = params.get('t') || '';

const state = { hasBot: false, name: '', x: 0, y: 0, z: 0, yaw: 0, pitch: 0, hp: 0, mode: 'IDLE', stopped: false };
let lookYaw = 0, lookPitch = 0;   // camera = bot eyes, driven by mouse
let locked = false;               // pointer-lock (Minecraft-style) active
let lookDX = 0, lookDY = 0;       // accumulated mouse deltas, sent once per frame
const hist = [];                  // recent states, oldest first (interpolation buffer)

// ---------- three.js scene ----------
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setSize(innerWidth, innerHeight);
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x7ec8e3);
scene.fog = new THREE.Fog(0x7ec8e3, 26, 100);
const camera = new THREE.PerspectiveCamera(75, innerWidth / innerHeight, 0.1, 200);
camera.rotation.order = 'YXZ';
scene.add(new THREE.AmbientLight(0xffffff, 0.55));
const sun = new THREE.DirectionalLight(0xffffff, 0.75);
sun.position.set(40, 60, 25);
scene.add(sun);

let voxelMesh = null, pathLine = null, botMarker = null, entityMarkers = [];

// ---------- WebSocket ----------
let ws = null;
function connect() {
  ws = new WebSocket(`ws://${location.host}/ws`);
  ws.onopen = () => send({ type: 'hello', t: token });
  ws.onmessage = ev => handle(JSON.parse(ev.data));
  ws.onclose = () => {
    setStatus('disconnected — retrying…');
    clearKeys();
    setTimeout(connect, 2000);
  };
  ws.onerror = () => ws.close();
}
function send(o) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(o)); }

function handle(m) {
  switch (m.type) {
    case 'hello':
      setStatus(m.ok ? 'authenticated ✓' : 'auth failed');
      break;
    case 'state':
      state.hasBot = m.hasBot;
      if (m.hasBot) {
        state.name = m.name; state.x = m.pos[0]; state.y = m.pos[1]; state.z = m.pos[2];
        state.yaw = m.yaw; state.pitch = m.pitch; state.hp = m.hp;
        state.mode = m.mode; state.stopped = m.stopped;
        hist.push({ t: performance.now(), x: state.x, y: state.y, z: state.z, yaw: state.yaw, pitch: state.pitch });
        if (hist.length > 12) hist.shift();
      } else {
        hist.length = 0;
      }
      setStatus(state.hasBot
        ? `bot: ${state.name}  hp: ${state.hp}  mode: ${state.mode}${state.stopped ? ' (STOPPED)' : ''}`
        : 'connected — no bot spawned yet (run /remotebot spawn in game)');
      break;
    case 'blocks':
      buildVoxels(m);
      break;
    case 'entities':
      buildEntities(m.entities || []);
      break;
    case 'path':
      drawPath(m.points || []);
      break;
    case 'error':
      setStatus('⚠ ' + m.message);
      break;
  }
}

// ---------- voxel rendering ----------
const BLOCK_COLORS = {
  grass_block: 0x5dba3d, dirt: 0x8a5a2b, stone: 0x8f8f8f, deepslate: 0x5a5a5f,
  cobblestone: 0x7c7c7c, sand: 0xe7dcae, gravel: 0x9a9a9a, bedrock: 0x3f3f3f,
  oak_log: 0x6e4a26, spruce_log: 0x4a3626, birch_log: 0xd8d0b8, oak_leaves: 0x3f8f2f,
  spruce_leaves: 0x2f6a2f, birch_leaves: 0x5f9f3f, water: 0x3a6fd8, lava: 0xe25822,
  glass: 0xcfe8f5, ice: 0xa8d8f0, snow: 0xf5f5f5, snow_block: 0xeef4f8,
  coal_ore: 0x5a5a5a, iron_ore: 0xb08d6a, gold_ore: 0xe8c84a, diamond_ore: 0x6ad8d8,
  oak_planks: 0xb0885a, spruce_planks: 0x8a6a3f, stone_bricks: 0x9a9a9a,
  brick: 0x9c4f3c, bricks: 0x9c4f3c, white_wool: 0xeaeaea, black_wool: 0x2a2a2a,
  red_wool: 0xcc3333, blue_wool: 0x3355cc, green_wool: 0x33aa33, yellow_wool: 0xe8e03f,
  clay: 0x9a9aa8, pumpkin: 0xe08a20, melon: 0x4f9f3f, cactus: 0x3f9f3f,
  tall_grass: 0x6fbf4f, grass: 0x6fbf4f, flower: 0xdd44dd, poppy: 0xdd2222,
  dandelion: 0xf5e03f, oak_sapling: 0x4f9f3f, torch: 0xffaa22, bookshelf: 0xa07850,
  crafting_table: 0x8a6a3f, furnace: 0x8a8a8a, chest: 0x9c6a32, dirt_path: 0x9a7a4f,
  farmland: 0x6a4f2f, wheat: 0xd8c84f, netherrack: 0x6f2f2f, obsidian: 0x1f1f2f,
  tnt: 0xe03030, moss_block: 0x5a8a3f, mycelium: 0x8a6a8a, podzol: 0x5f4a2f,
  stone_slab: 0x8f8f8f, oak_fence: 0xa07850, cobblestone_wall: 0x7c7c7c,
  iron_block: 0xd8d8d8, gold_block: 0xf5d040, diamond_block: 0x5fdcdc, emerald_block: 0x2fd86a,
  redstone_block: 0xd83a3a, lapis_block: 0x2a4acf, terracotta: 0xa05f3f,
  white_terracotta: 0xccb08f, light_blue_terracotta: 0x9ab8c8, end_stone: 0xd8d080
};
function blockColor(name) {
  if (BLOCK_COLORS[name] !== undefined) return new THREE.Color(BLOCK_COLORS[name]);
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return new THREE.Color().setHSL((h % 360) / 360, 0.45, 0.55);
}

// 6 faces of a unit box, wound counter-clockwise seen from outside (outward normals)
const FACES = [
  { n: [1, 0, 0], v: [[.5, -.5, -.5], [.5, .5, -.5], [.5, .5, .5], [.5, -.5, .5]] },
  { n: [-1, 0, 0], v: [[-.5, -.5, .5], [-.5, .5, .5], [-.5, .5, -.5], [-.5, -.5, -.5]] },
  { n: [0, 1, 0], v: [[-.5, .5, .5], [.5, .5, .5], [.5, .5, -.5], [-.5, .5, -.5]] },
  { n: [0, -1, 0], v: [[-.5, -.5, -.5], [.5, -.5, -.5], [.5, -.5, .5], [-.5, -.5, .5]] },
  { n: [0, 0, 1], v: [[.5, -.5, .5], [.5, .5, .5], [-.5, .5, .5], [-.5, -.5, .5]] },
  { n: [0, 0, -1], v: [[-.5, -.5, -.5], [-.5, .5, -.5], [.5, .5, -.5], [.5, -.5, -.5]] },
];
const FACE_LIGHT = { '1,0,0': .78, '-1,0,0': .78, '0,1,0': 1.0, '0,-1,0': .45, '0,0,1': .78, '0,0,-1': .78 };

// non-full blocks: render as two crossing planes (like Minecraft) and don't occlude
const CROSS_BLOCKS = new Set([
  'tall_grass', 'grass', 'fern', 'large_fern', 'dead_bush', 'short_grass',
  'flower', 'poppy', 'dandelion', 'orchid', 'allium', 'azure_bluet', 'tulip',
  'oxeye_daisy', 'cornflower', 'lily_of_the_valley', 'wither_rose', 'torch',
  'oak_sapling', 'spruce_sapling', 'birch_sapling', 'jungle_sapling', 'acacia_sapling', 'dark_oak_sapling',
  'wheat', 'carrots', 'potatoes', 'beetroots', 'sugar_cane', 'bamboo', 'lily_pad',
  'sunflower', 'lilac', 'rose_bush', 'peony', 'red_mushroom', 'brown_mushroom',
  'crimson_fungus', 'warped_fungus', 'crimson_roots', 'warped_roots', 'nether_sprouts',
  'twisting_vines', 'weeping_vines', 'vine'
]);
// two perpendicular diagonal planes forming an X
const CROSS_QUADS = [
  [[-.5, -.5, -.5], [-.5, .5, -.5], [.5, .5, .5], [.5, -.5, .5]],
  [[.5, -.5, -.5], [.5, .5, -.5], [-.5, .5, .5], [-.5, -.5, .5]],
];

function buildVoxels(m) {
  const { x0, y0, z0, w, h, d, palette, data } = m;
  const bytes = Uint8Array.from(atob(data), c => c.charCodeAt(0));
  const at = (x, y, z) => (y * d + z) * w + x;
  const inGrid = (x, y, z) => x >= 0 && y >= 0 && z >= 0 && x < w && y < h && z < d;
  const isCross = (x, y, z) => inGrid(x, y, z) && bytes[at(x, y, z)] !== 0 && CROSS_BLOCKS.has(palette[bytes[at(x, y, z)]]);
  // a solid block occludes neighbours; cross plants don't
  const occludes = (x, y, z) => inGrid(x, y, z) && bytes[at(x, y, z)] !== 0 && !isCross(x, y, z);

  const positions = [], colors = [], indices = [];
  const quad = (verts, light) => { // verts are absolute world coordinates
    const base = positions.length / 3;
    for (const v of verts) {
      positions.push(v[0], v[1], v[2]);
      colors.push(light[0], light[1], light[2]);
    }
    indices.push(base, base + 1, base + 2, base, base + 2, base + 3);
  };

  for (let y = 0; y < h; y++) for (let z = 0; z < d; z++) for (let x = 0; x < w; x++) {
    const b = bytes[at(x, y, z)];
    if (!b) continue;
    const c = blockColor(palette[b]);
    if (isCross(x, y, z)) {
      for (const q of CROSS_QUADS) {
        quad(q.map(v => [x0 + x + 0.5 + v[0], y0 + y + 0.5 + v[1], z0 + z + 0.5 + v[2]]), [c.r * .9, c.g * .9, c.b * .9]);
      }
      continue;
    }
    for (const face of FACES) {
      if (occludes(x + face.n[0], y + face.n[1], z + face.n[2])) continue; // culled
      const k = face.n.join(',');
      const light = FACE_LIGHT[k];
      quad(face.v.map(v => [x0 + x + 0.5 + v[0], y0 + y + 0.5 + v[1], z0 + z + 0.5 + v[2]]), [c.r * light, c.g * light, c.b * light]);
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  geo.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
  geo.setIndex(indices);
  geo.computeVertexNormals();
  if (voxelMesh) { scene.remove(voxelMesh); voxelMesh.geometry.dispose(); }
  voxelMesh = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide }));
  scene.add(voxelMesh);
}

function entityColor(type, name) {
  if (type === 'player') return 0xff5533;
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return new THREE.Color().setHSL((h % 360) / 360, 0.6, 0.6);
}

function buildEntities(entities) {
  for (const e of entityMarkers) { scene.remove(e); e.geometry.dispose(); }
  entityMarkers = [];
  for (const e of entities) {
    const w = e.w || 0.6, h = e.h || 1.8;
    const geo = new THREE.BoxGeometry(w, h, w);
    const mat = new THREE.MeshLambertMaterial({ color: entityColor(e.t, e.n) });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(e.p[0], e.p[1] + h / 2, e.p[2]);
    scene.add(mesh);
    entityMarkers.push(mesh);
  }
}

function drawPath(points) {
  if (pathLine) { scene.remove(pathLine); pathLine.geometry.dispose(); pathLine = null; }
  if (!points.length) return;
  const geo = new THREE.BufferGeometry().setFromPoints(points.map(p => new THREE.Vector3(p[0] + .5, p[1] + .5, p[2] + .5)));
  pathLine = new THREE.Line(geo, new THREE.LineBasicMaterial({ color: 0xff3355, linewidth: 2 }));
  scene.add(pathLine);
}

// ---------- input (Minecraft-style) ----------
const keys = { f: false, b: false, l: false, r: false, j: false };
const KEYMAP = { KeyW: 'f', KeyS: 'b', KeyA: 'l', KeyD: 'r', Space: 'j' };

function clearKeys() {
  for (const k in keys) keys[k] = false;
  sendInput();
}
function sendInput() {
  send({ type: 'input', f: keys.f, b: keys.b, l: keys.l, r: keys.r, j: keys.j });
}

// click the view to capture the mouse, like the game
renderer.domElement.addEventListener('click', () => {
  if (!locked) renderer.domElement.requestPointerLock();
});
document.addEventListener('pointerlockchange', () => {
  locked = document.pointerLockElement === renderer.domElement;
  if (!locked) clearKeys();
});
document.addEventListener('mousemove', e => {
  if (!locked) return;
  lookDX += e.movementX;
  lookDY += e.movementY;
});
document.addEventListener('mousedown', e => {
  if (locked && e.button === 0) send({ type: 'attack' });
});

addEventListener('keydown', e => {
  if (e.code === 'KeyF') { send({ type: 'attack' }); return; }
  const k = KEYMAP[e.code];
  if (k && !e.repeat) { keys[k] = true; sendInput(); e.preventDefault(); }
});
addEventListener('keyup', e => {
  const k = KEYMAP[e.code];
  if (k) { keys[k] = false; sendInput(); }
});
addEventListener('blur', () => {
  clearKeys();
  if (document.pointerLockElement) document.exitPointerLock();
});

// ---------- buttons ----------
$('btn-follow').onclick = () => send({ type: 'mode', mode: 'FOLLOW' });
$('btn-idle').onclick = () => send({ type: 'mode', mode: 'IDLE' });
$('btn-stop').onclick = () => send({ type: 'stop' });
$('btn-resume').onclick = () => send({ type: 'resume' });
$('btn-attack').onclick = () => send({ type: 'attack' });

function $(id) { return document.getElementById(id); }
function setStatus(t) { $('status').textContent = t; }

const shortestAngle = d => ((d + 180) % 360 + 360) % 360 - 180;

// Interpolate the state stream, rendering slightly behind the newest frame
// (RENDER_DELAY) so network jitter turns into smooth motion instead of stutters.
const RENDER_DELAY = 50; // ms
function interpolated(now) {
  const target = now - RENDER_DELAY;
  while (hist.length >= 2 && hist[1].t <= target) hist.shift(); // drop frames we've passed
  if (hist.length < 2) return hist[0] || null;
  const a = hist[0], b = hist[1];
  const k = Math.min(1, (target - a.t) / Math.max(1, b.t - a.t));
  return {
    x: a.x + (b.x - a.x) * k,
    y: a.y + (b.y - a.y) * k,
    z: a.z + (b.z - a.z) * k,
    yaw: a.yaw + shortestAngle(b.yaw - a.yaw) * k,
    pitch: a.pitch + (b.pitch - a.pitch) * k,
  };
}

// ---------- main loop ----------
addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

function frame() {
  requestAnimationFrame(frame);

  // send accumulated look deltas once per frame
  if (locked && (lookDX !== 0 || lookDY !== 0)) {
    lookYaw += lookDX * 0.25;
    lookPitch += lookDY * 0.25;
    lookPitch = Math.max(-85, Math.min(85, lookPitch));
    lookDX = lookDY = 0;
    send({ type: 'look', yaw: lookYaw, pitch: lookPitch });
  }

  if (state.hasBot) {
    // interpolate between recent states for smooth motion under jitter
    const ip = interpolated(performance.now());
    const x = ip ? ip.x : state.x, y = ip ? ip.y : state.y, z = ip ? ip.z : state.z;
    const yaw = ip ? ip.yaw : state.yaw, pitch = ip ? ip.pitch : state.pitch;
    camera.position.set(x, y + 1.62, z);
    if (!locked) { lookYaw = yaw; lookPitch = pitch; } // follow bot's (smoothed) aim when not aiming
    camera.rotation.y = THREE.MathUtils.degToRad(180 - lookYaw);
    camera.rotation.x = THREE.MathUtils.degToRad(-lookPitch); // MC pitch: positive = down

    if (!botMarker) {
      botMarker = new THREE.Mesh(
        new THREE.BoxGeometry(0.6, 1.8, 0.6),
        new THREE.MeshLambertMaterial({ color: 0x33ff55 }));
      scene.add(botMarker);
    }
    botMarker.position.set(x, y + 0.9, z);
    botMarker.rotation.y = THREE.MathUtils.degToRad(180 - yaw);
  }
  renderer.render(scene, camera);
}

connect();
frame();
