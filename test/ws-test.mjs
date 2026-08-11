// End-to-end smoke test: auth -> state stream -> movement -> goto
const token = process.argv[2] || '';
const ws = new WebSocket('ws://localhost:8080/ws');
let states = 0, blocks = 0, helloOk = false, sawBot = false, moved = false;
let startPos = null, sentInput = false, sentGoto = false;
const t0 = Date.now();

const done = (code) => { console.log(code === 0 ? 'TEST PASS' : 'TEST FAIL'); process.exit(code); };
setTimeout(() => {
  console.log(`TIMEOUT: states=${states} blocks=${blocks} hello=${helloOk} sawBot=${sawBot} moved=${moved}`);
  done(states > 2 && sawBot ? 0 : 1);
}, 15000);

ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', t: token }));
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.type === 'hello' && m.ok) { helloOk = true; console.log('AUTH OK'); }
  else if (m.type === 'state') {
    states++;
    if (!m.hasBot) return;
    sawBot = true;
    const [x, y, z] = m.pos;
    if (!startPos) {
      startPos = [x, y, z];
      console.log(`bot at ${x.toFixed(1)},${y.toFixed(1)},${z.toFixed(1)} mode=${m.mode}`);
      sentInput = true;
      ws.send(JSON.stringify({ type: 'input', f: true, b: false, l: false, r: false, j: false }));
      console.log('> input forward');
    } else if (!sentGoto && Date.now() - t0 > 3500) {
      ws.send(JSON.stringify({ type: 'input', f: false }));
      sentGoto = true;
      ws.send(JSON.stringify({ type: 'goto', x: Math.floor(startPos[0]), y: Math.floor(startPos[1]), z: Math.floor(startPos[2]) + 8 }));
      console.log('> goto +8z');
    }
    if (Math.abs(x - startPos[0]) + Math.abs(z - startPos[2]) > 1.5) moved = true;
    if (states % 5 === 0) console.log(`state#${states} pos=${x.toFixed(1)},${z.toFixed(1)} mode=${m.mode} hp=${m.hp}`);
    if (moved && Date.now() - t0 > 9000) { console.log(`SUMMARY states=${states} blocks=${blocks} moved=${moved}`); done(0); }
  } else if (m.type === 'blocks') blocks++;
  else if (m.type === 'error') console.log('ERR:', m.message);
};
ws.onerror = (e) => { console.log('WS error', e.message || e); done(1); };
