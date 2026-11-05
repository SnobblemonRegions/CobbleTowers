// Reads the mod's own data files (the single source) and returns what the pages need to know that the server does not send.
import fs from 'node:fs';
import path from 'node:path';

const pretty = (s) => s.split(/[_\s]+/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
const readJson = (p) => JSON.parse(fs.readFileSync(p, 'utf8'));
const listJson = (dir) => fs.existsSync(dir) ? fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort() : [];

export function genData(root) {
  const data = path.join(root, 'src/main/resources/data/cobbletowers/cobbletowers');
  const species = new Set();

  const rental = listJson(path.join(data, 'rental_sets')).map((f) => {
    const r = readJson(path.join(data, 'rental_sets', f));
    species.add(r.species);
    return {
      id: 'cobbletowers:' + f.replace(/\.json$/, ''),
      species: r.species, name: pretty(r.species), level: r.level, types: r.types, rarity: r.rarity,
      item: r.item ? pretty(r.item.split(':')[1]) : 'No item',
      // the loader's defaults for what a set leaves out: hardy, no EVs, perfect IVs
      nature: r.nature ?? 'hardy', evs: r.evs ?? [0, 0, 0, 0, 0, 0], ivs: r.ivs ?? [31, 31, 31, 31, 31, 31],
    };
  });

  for (const f of listJson(path.join(data, 'encounter_pools'))) {
    for (const e of readJson(path.join(data, 'encounter_pools', f)).entries ?? []) species.add(String(e.species).split(':').pop());
  }
  for (const f of listJson(path.join(data, 'regional_themes'))) {
    for (const j of readJson(path.join(data, 'regional_themes', f)).jersey_signatures ?? []) species.add(String(j.species).split(':').pop());
  }

  // Three foes to show on each tower's row (P51): the first entries of the pool its first floor draws from, if they have a sprite.
  const residents = {};
  for (const f of listJson(path.join(data, 'towers'))) {
    const id = f.replace(/\.json$/, '');
    const tower = readJson(path.join(data, 'towers', f));
    const floor = (tower.floors ?? [])[0];
    if (!floor) continue;
    const floorFile = path.join(data, 'floors', floor.split(':')[1] + '.json');
    if (!fs.existsSync(floorFile)) continue;
    const floorData = readJson(floorFile);
    const spriteDir = path.join(root, 'src/main/resources/assets/cobbletowers/textures/gui/partners');
    const hasSprite = (s) => fs.existsSync(path.join(spriteDir, s + '.png'));
    let names;
    const poolId = String(floorData.encounter_pool ?? '').split(':').pop();
    const poolFile = path.join(data, 'encounter_pools', poolId + '.json');
    if (poolId && fs.existsSync(poolFile)) {
      names = (readJson(poolFile).entries ?? []).map((e) => String(e.species).split(':').pop());
    } else {
      // Towers are boss-only: a floor names a boss pool, and the foes you may meet are its bosses (a definition id, with the event's skin prefix dropped).
      const bossPool = String(floorData.boss_pool ?? '').split(':').pop();
      const bossFile = path.join(data, 'boss_pools', bossPool + '.json');
      if (!bossPool || !fs.existsSync(bossFile)) continue;
      names = (readJson(bossFile).entries ?? []).map((e) => String(e.definition).split(':').pop().replace(/^halloween_/, ''));
    }
    residents[id] = [...new Set(names.filter(hasSprite))].slice(0, 3);
  }

  const version = JSON.parse(fs.readFileSync(path.join(root, 'src/main/resources/fabric.mod.json'), 'utf8')).version;
  const guide = readJson(path.join(root, 'src/main/resources/data/cobbletowers/guide.json'));
  return { rental, species: [...species].sort(), residents, version, guide };
}
