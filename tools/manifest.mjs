#!/usr/bin/env node
// Writes walkers/index.json so the page also works from a plain static host (no dev server).
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, listWalkers, readJson } from './lib.mjs';
const list = listWalkers().map((id) => {
  const w = readJson(join(ROOT, 'walkers', `${id}.json`));
  return { id, name: w.name ?? id, description: w.description ?? '', color: w.color ?? null };
});
writeFileSync(join(ROOT, 'walkers/index.json'), JSON.stringify(list, null, 2) + '\n');
console.log(`walkers/index.json: ${list.length} walkers`);
