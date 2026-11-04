import { readFileSync, readdirSync } from 'node:fs';
import { join, basename, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
export const loadConfig = () => ({
  costModel: readJson(join(ROOT, 'config/cost-model.json')),
  targets: stripMeta(readJson(join(ROOT, 'config/targets.json'))),
});
export const stripMeta = (o) => Object.fromEntries(Object.entries(o).filter(([k]) => !k.startsWith('_')));

export function listWalkers() {
  return readdirSync(join(ROOT, 'walkers'))
    .filter((f) => f.endsWith('.json') && !f.startsWith('_') && f !== 'index.json')
    .map((f) => basename(f, '.json'))
    .sort();
}

export function resolveWalker(arg) {
  const p = arg.endsWith('.json') ? resolve(arg) : join(ROOT, 'walkers', `${arg}.json`);
  return { id: basename(p, '.json'), path: p };
}
