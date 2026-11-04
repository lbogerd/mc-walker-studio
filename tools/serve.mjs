#!/usr/bin/env node
// Zero-dependency dev server: static files + walker listing + live reload over SSE.
//   node tools/serve.mjs [--port 5177] [--host 127.0.0.1]
import { createServer } from 'node:http';
import { readFile, stat, writeFile } from 'node:fs/promises';
import { watch } from 'node:fs';
import { join, extname, normalize, relative } from 'node:path';
import { ROOT, listWalkers, readJson } from './lib.mjs';

const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf(`--${n}`); return i >= 0 ? args[i + 1] : d; };
const PORT = Number(opt('port', process.env.PORT || 5177));
const HOST = opt('host', process.env.HOST || '127.0.0.1');

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.md': 'text/markdown' };
const clients = new Set();

function walkerIndex() {
  return listWalkers().map((id) => {
    try {
      const w = readJson(join(ROOT, 'walkers', `${id}.json`));
      return { id, name: w.name ?? id, description: w.description ?? '', color: w.color ?? null };
    } catch (e) {
      return { id, name: id, error: e.message };
    }
  });
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/api/walkers') {
    res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    return res.end(JSON.stringify(walkerIndex()));
  }
  const put = url.pathname.match(/^\/api\/walkers\/([A-Za-z0-9_-]+)$/);
  if (put && req.method === 'PUT') {
    let body = '';
    for await (const chunk of req) body += chunk;
    try {
      JSON.parse(body);
      await writeFile(join(ROOT, 'walkers', `${put[1]}.json`), body);
      res.writeHead(204);
      return res.end();
    } catch (e) {
      res.writeHead(400);
      return res.end(e.message);
    }
  }
  if (url.pathname === '/api/events') {
    res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive' });
    res.write('retry: 1000\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }
  let p = normalize(decodeURIComponent(url.pathname)).replace(/^([/\\])+/, '');
  if (p === '' || p.endsWith('/')) p += 'index.html';
  const file = join(ROOT, p);
  if (relative(ROOT, file).startsWith('..') || p.split(/[/\\]/).some((s) => s.startsWith('.')) || /^(mod|node_modules|reference)([/\\]|$)/.test(p)) {
    res.writeHead(403);
    return res.end('forbidden');
  }
  try {
    const st = await stat(file);
    if (!st.isFile()) throw new Error('not a file');
    res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(await readFile(file));
  } catch {
    res.writeHead(404);
    res.end('not found');
  }
});

let timer = null;
const pending = new Set();
for (const dir of ['walkers', 'config', 'src', '.']) {
  watch(join(ROOT, dir), { recursive: dir !== '.' }, (_, name) => {
    if (!name || (dir === '.' && name !== 'index.html')) return;
    pending.add(dir === '.' ? name : `${dir}/${name}`);
    clearTimeout(timer);
    timer = setTimeout(() => {
      const files = [...pending];
      pending.clear();
      const kind = files.every((f) => f.startsWith('walkers/') || f.startsWith('config/')) ? 'data' : 'code';
      const msg = `data: ${JSON.stringify({ kind, files })}\n\n`;
      for (const c of clients) c.write(msg);
      console.log(`↻ ${kind}: ${files.join(', ')}`);
    }, 80);
  });
}

server.listen(PORT, HOST, () => {
  console.log(`Walker Studio → http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}/`);
  console.log('Editing walkers/*.json or config/*.json re-simulates live; editing src/ reloads the page.');
});
